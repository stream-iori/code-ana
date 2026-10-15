package com.anatomist.core.javac;

import com.anatomist.core.*;

import org.w3c.dom.Element;

import java.nio.file.*;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Keeps compiler source visibility separate from publication batches and source scopes. */
final class JavacContexts {
    record Context(
            String module,
            SourceScope scope,
            List<Path> sources,
            Set<Path> publish,
            Set<String> dependencyModules) {}

    private record Dependency(String coordinate, boolean test) {}

    private record Module(String coordinate, List<Dependency> dependencies) {}

    private JavacContexts() {}

    static String compilerModule(
            Path path, SourceIdentityResolver identities, JavacMavenInputs effective) {
        return effective == null
                ? identities.resolve(path.toString()).module()
                : effective.owningModule(path).name();
    }

    static List<Context> prepare(
            Path project, List<SourceRoot> roots, List<Path> visible, List<Path> targets) {
        return prepare(project, roots, visible, targets, null);
    }

    static List<Context> prepare(
            Path project,
            List<SourceRoot> roots,
            List<Path> visible,
            List<Path> targets,
            JavacMavenInputs effective) {
        SourceIdentityResolver identities = SourceIdentityResolver.fromRoots(project, roots);
        Map<String, List<Path>> main = new LinkedHashMap<>(), tests = new LinkedHashMap<>();
        Map<String, SourceScope> requestedScopes = new LinkedHashMap<>();
        for (Path file : visible) {
            (identities.compilationScope(file) == SourceScope.TEST ? tests : main)
                    .computeIfAbsent(
                            compilerModule(file, identities, effective),
                            unused -> new ArrayList<>())
                    .add(file);
        }
        Map<String, Module> modules = effective == null ? modules(project, roots) : Map.of();
        Map<String, String> coordinates = new LinkedHashMap<>();
        modules.forEach(
                (name, module) -> {
                    if (coordinates.putIfAbsent(module.coordinate(), name) != null)
                        throw new IllegalArgumentException(
                                "JAVAC_MODULE_AMBIGUOUS: " + module.coordinate());
                });
        Map<String, Set<Path>> publication = new LinkedHashMap<>();
        for (Path target : targets) {
            SourceScope scope = identities.compilationScope(target);
            String compilerModule = compilerModule(target, identities, effective);
            String key = compilerModule + "@" + scope;
            publication.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(target);
            requestedScopes.put(key, scope);
        }
        List<Context> out = new ArrayList<>();
        for (var entry : publication.entrySet()) {
            Path example = entry.getValue().iterator().next();
            String module = compilerModule(example, identities, effective);
            boolean test = requestedScopes.get(entry.getKey()) == SourceScope.TEST;
            Set<String> dependencies = new LinkedHashSet<>();
            if (effective == null)
                collect(module, test, modules, coordinates, dependencies, new HashSet<>());
            else
                for (var dependency :
                        effective
                                .module(module)
                                .dependencies(test ? SourceScope.TEST : SourceScope.MAIN))
                    if (dependency instanceof JavacMavenInputs.ReactorDependency reactor)
                        dependencies.add(reactor.module());
            LinkedHashSet<Path> sources = new LinkedHashSet<>(main.getOrDefault(module, List.of()));
            if (test) sources.addAll(tests.getOrDefault(module, List.of()));
            dependencies.forEach(name -> sources.addAll(main.getOrDefault(name, List.of())));
            sources.addAll(entry.getValue());
            long descriptors =
                    sources.stream()
                            .filter(p -> p.getFileName().toString().equals("module-info.java"))
                            .count();
            if (descriptors > 1)
                throw new IllegalArgumentException(
                        "JAVAC_JPMS_REACTOR_UNSUPPORTED: multiple named modules require separate compiled module-path artifacts");
            out.add(
                    new Context(
                            module,
                            test ? SourceScope.TEST : SourceScope.MAIN,
                            sources.stream().sorted().toList(),
                            Set.copyOf(entry.getValue()),
                            Set.copyOf(dependencies)));
        }
        return dependencyOrder(out);
    }

    private static List<Context> dependencyOrder(List<Context> contexts) {
        Map<String, Context> byKey = new TreeMap<>();
        for (Context context : contexts)
            byKey.put(context.module() + "@" + context.scope(), context);
        List<Context> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String key : byKey.keySet()) appendContext(key, byKey, visited, ordered);
        return List.copyOf(ordered);
    }

    private static void appendContext(
            String key, Map<String, Context> contexts, Set<String> visited, List<Context> ordered) {
        if (!visited.add(key)) return;
        Context context = contexts.get(key);
        if (context == null) return;
        for (String dependency : context.dependencyModules().stream().sorted().toList())
            appendContext(dependency + "@MAIN", contexts, visited, ordered);
        if (context.scope() == SourceScope.TEST)
            appendContext(context.module() + "@MAIN", contexts, visited, ordered);
        ordered.add(context);
    }

    private static void collect(
            String module,
            boolean test,
            Map<String, Module> modules,
            Map<String, String> coordinates,
            Set<String> out,
            Set<String> active) {
        if (!active.add(module))
            throw new IllegalArgumentException("JAVAC_MODULE_CYCLE: " + module);
        Module model = modules.get(module);
        if (model != null)
            for (Dependency dependency : model.dependencies()) {
                if (dependency.test() && !test) continue;
                String target = coordinates.get(dependency.coordinate());
                if (target != null && active.contains(target))
                    throw new IllegalArgumentException("JAVAC_MODULE_CYCLE: " + target);
                if (target != null && out.add(target))
                    collect(target, false, modules, coordinates, out, active);
            }
        active.remove(module);
    }

    private static Map<String, Module> modules(Path project, List<SourceRoot> roots) {
        Set<String> names = new LinkedHashSet<>();
        roots.forEach(root -> names.add(root.module()));
        Map<String, Module> result = new LinkedHashMap<>();
        Set<Integer> versions = new LinkedHashSet<>();
        for (String name : names) {
            Path directory = name.equals(".") ? project : project.resolve(name);
            Path pom = directory.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                if (names.size() > 1)
                    throw new IllegalArgumentException(
                            "JAVAC_MODULE_LAYOUT_UNSUPPORTED: explicit Maven dependency direction is required for "
                                    + name);
                continue;
            }
            try {
                Element root = document(pom);
                String group = text(root, "groupId");
                if (group.isEmpty()) group = text(child(root, "parent"), "groupId");
                String artifact = text(root, "artifactId");
                List<Dependency> dependencies = new ArrayList<>();
                Element declarations = child(root, "dependencies");
                if (declarations != null)
                    for (Element dependency : children(declarations, "dependency")) {
                        String scope = text(dependency, "scope");
                        if (scope.equals("runtime") || scope.equals("import")) continue;
                        dependencies.add(
                                new Dependency(
                                        text(dependency, "groupId")
                                                + ":"
                                                + text(dependency, "artifactId"),
                                        scope.equals("test")));
                    }
                result.put(name, new Module(group + ":" + artifact, List.copyOf(dependencies)));
                JavaVersionDetection version =
                        new ClasspathDetector().detectJavaVersionDetailed(directory);
                if (version.found()) versions.add(version.version());
            } catch (Exception failure) {
                throw new IllegalArgumentException(
                        "JAVAC_MODULE_INPUT_INVALID: " + pom + " (" + failure.getMessage() + ")",
                        failure);
            }
        }
        if (versions.size() > 1)
            throw new IllegalArgumentException(
                    "JAVAC_MIXED_RELEASE_UNSUPPORTED: module targets "
                            + versions
                            + " must be analyzed with separate matching toolchains");
        return result;
    }

    private static Element document(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = Files.newInputStream(pom)) {
            return factory.newDocumentBuilder().parse(input).getDocumentElement();
        }
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static List<Element> children(Element parent, String name) {
        if (parent == null) return List.of();
        List<Element> out = new ArrayList<>();
        for (var at = parent.getFirstChild(); at != null; at = at.getNextSibling())
            if (at instanceof Element element && element.getTagName().equals(name))
                out.add(element);
        return out;
    }
}
