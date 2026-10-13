package dev.codeana.adapter;

import com.anatomist.core.*;
import com.anatomist.core.javac.*;
import dev.codeana.common.Json;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** The compiler can see only immutable inputs explicitly captured by Rust. */
public final class CapturedProject {
    public record Source(
            String path,
            String hash,
            String module,
            String world,
            String scope,
            Path file,
            boolean resource,
            boolean validEncoding,
            boolean auxiliary) {}

    public record Context(
            String id,
            String module,
            String world,
            SourceScope scope,
            String digest,
            List<Source> sources,
            Set<Path> publish,
            List<Source> resources,
            List<Path> classpath,
            Set<String> dependencies,
            Map<String, Object> options) {}

    public final Path root;
    public final Path diagnosticRoot;
    public final boolean deferFactDedup;
    public final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<Path, Source> physicalSources = new HashMap<>();
    public final List<Context> contexts;
    private final List<SourceRoot> sourceRoots;

    public CapturedProject(Map<String, Object> request) throws IOException {
        root =
                Files.createDirectory(
                        Path.of(Json.string(request, "scratch")).resolve("anatomist-inputs"));
        diagnosticRoot =
                Path.of(
                        Objects.toString(
                                request.get("diagnostic_scratch"),
                                Json.string(request, "scratch")));
        deferFactDedup = Boolean.TRUE.equals(request.get("defer_fact_dedup"));
        ContextInputs resolver = new ContextInputs(request);
        List<Context> contexts = new ArrayList<>();
        Set<String> worlds = new HashSet<>(), ids = new HashSet<>();
        for (Object raw : Json.list(request.get("contexts"))) {
            Map<String, Object> c = resolver.resolve(Json.map(raw));
            String world = Json.string(c, "world"), id = Json.string(c, "context");
            if (!worlds.add(world) || !ids.add(id))
                throw new IllegalArgumentException("DUPLICATE_CONTEXT");
            List<Source> visible = new ArrayList<>(), resources = new ArrayList<>();
            Set<Path> publish = new LinkedHashSet<>();
            for (Object value : Json.list(c.get("sources"))) {
                Map<String, Object> row = Json.map(value);
                Source s = capture(row, false);
                visible.add(s);
                if (Boolean.TRUE.equals(row.get("publish"))) {
                    if (s.auxiliary() || !s.world().equals(world))
                        throw new IllegalArgumentException("JAVA_PUBLICATION_IDENTITY");
                    publish.add(s.file());
                }
            }
            for (Object value : list(c.get("resources"))) {
                Map<String, Object> row = Json.map(value),
                        unit = new LinkedHashMap<>(Json.map(row.get("unit")));
                unit.put("input", row.get("input"));
                Source source = capture(unit, true);
                if (source.auxiliary())
                    throw new IllegalArgumentException("RESOURCE_PUBLICATION_IDENTITY");
                resources.add(source);
            }
            List<Path> cp =
                    list(c.get("classpath")).stream()
                            .map(v -> Path.of((String) v).toAbsolutePath().normalize())
                            .toList();
            Set<String> dependencies = new LinkedHashSet<>();
            for (Object d : list(c.get("dependency_modules"))) dependencies.add((String) d);
            dependencies.remove(Json.string(c, "module"));
            contexts.add(
                    new Context(
                            id,
                            Json.string(c, "module"),
                            world,
                            SourceScope.valueOf(Json.string(c, "scope")),
                            Json.string(c, "digest"),
                            List.copyOf(visible),
                            Set.copyOf(publish),
                            List.copyOf(resources),
                            cp,
                            Set.copyOf(dependencies),
                            Map.copyOf(c)));
        }
        this.contexts = List.copyOf(contexts);
        Set<SourceRoot> roots = new LinkedHashSet<>();
        Set<String> described = new HashSet<>();
        for (var context : contexts) {
            List<Object> declared = list(context.options().get("source_roots"));
            if (declared.isEmpty()) continue;
            described.add(context.module() + "\n" + context.scope());
            for (Object value : declared) {
                Path relative = Path.of((String) value);
                if (relative.isAbsolute() || relative.normalize().startsWith(".."))
                    throw new IllegalArgumentException("CAPTURE_SOURCE_ROOT");
                roots.add(
                        new SourceRoot(
                                root.resolve(relative).normalize(),
                                context.module(),
                                context.scope()));
            }
        }
        for (var source : sources.values()) {
            if (source.resource()) continue;
            var scope = SourceScope.valueOf(source.scope());
            if (!described.contains(source.module() + "\n" + scope))
                roots.add(new SourceRoot(source.file(), source.module(), scope));
            else if (roots.stream()
                    .noneMatch(
                            r ->
                                    r.module().equals(source.module())
                                            && r.scope() == scope
                                            && source.file().startsWith(r.path())))
                throw new IllegalArgumentException("CAPTURE_SOURCE_OUTSIDE_ROOT");
        }
        sourceRoots = List.copyOf(roots);
    }

    private Source capture(Map<String, Object> row, boolean resource) throws IOException {
        String name = Json.string(row, "path");
        Path relative = Path.of(name);
        if (relative.isAbsolute()
                || name.isBlank()
                || !relative.normalize().equals(relative)
                || relative.startsWith("..")) throw new IllegalArgumentException("CAPTURE_PATH");
        String hash = Json.string(row, "hash");
        Source old = sources.get(name);
        if (old != null) {
            if (!old.hash().equals(hash)
                    || !old.world().equals(Json.string(row, "world"))
                    || old.resource() != resource)
                throw new IllegalArgumentException("CAPTURE_IDENTITY");
            return old;
        }
        byte[] bytes = Json.read(Path.of(Json.string(row, "input")));
        if (!Json.sha(bytes).equals(hash)) throw new IOException("CAPTURE_HASH_MISMATCH");
        boolean valid = true;
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException failure) {
            valid = false;
        }
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        Source s =
                new Source(
                        name,
                        hash,
                        Json.string(row, "module"),
                        Json.string(row, "world"),
                        Json.string(row, "scope"),
                        file,
                        resource,
                        valid,
                        Boolean.TRUE.equals(row.get("auxiliary")));
        sources.put(name, s);
        physicalSources.put(s.file(), s);
        return s;
    }

    public Source source(Path file) {
        return Objects.requireNonNull(
                physicalSources.get(file),
                "uncaptured source");
    }

    public List<SourceRoot> roots() {
        return sourceRoots;
    }

    private Set<String> publishFilter;
    public void publishedSources(Set<String> sources) { publishFilter=Set.copyOf(sources); }
    public boolean publishes(Path path) { return publishFilter==null || publishFilter.contains(source(path).path()); }

    public JavaSourcePlan sourcePlan(Set<String> dirty) {
        List<Path> publish =
                contexts.stream()
                        .filter(c -> dirty.contains(c.world()))
                        .flatMap(c -> c.publish().stream())
                        .filter(p -> source(p).validEncoding() && publishes(p))
                        .toList();
        List<Path> visible =
                sources.values().stream()
                        .filter(s -> !s.resource() && s.validEncoding())
                        .map(Source::file)
                        .toList();
        Map<Path, SourceIdentity> identities = new HashMap<>();
        sources.values().stream()
                .filter(s -> !s.resource())
                .forEach(
                        s ->
                                identities.put(
                                        s.file(),
                                        new SourceIdentity(
                                                s.module(), SourceScope.valueOf(s.scope()))));
        return new JavaSourcePlan(publish, visible, roots(), identities);
    }

    public List<JavacBackend.CapturedContext> compilerContexts(Set<String> dirty) {
        return contexts.stream()
                // An empty TEST scope can still see MAIN sources for XML/type
                // queries. That visibility does not require another javac task.
                // MAIN prerequisites with their own declarations remain tasks.
                .filter(
                        c ->
                                c.sources().stream()
                                        .anyMatch(
                                                s ->
                                                        s.validEncoding()
                                                                && s.world().equals(c.world())
                                                                && s.scope()
                                                                        .equals(c.scope().name())))
                .map(
                        c ->
                                new JavacBackend.CapturedContext(
                                        c.module(),
                                        c.scope(),
                                        c.sources().stream()
                                                .filter(Source::validEncoding)
                                                .map(Source::file)
                                                .toList(),
                                        dirty.contains(c.world())
                                                ? c.publish().stream()
                                                        .filter(p -> source(p).validEncoding() && publishes(p))
                                                        .collect(
                                                                java.util.stream.Collectors.toSet())
                                                : Set.of(),
                                        c.dependencies()))
                .toList();
    }

    public JavacMavenInputs moduleInputs() {
        Map<String, List<Context>> modules = new LinkedHashMap<>();
        contexts.forEach(
                c -> modules.computeIfAbsent(c.module(), ignored -> new ArrayList<>()).add(c));
        sources.values().stream()
                .filter(s -> !s.resource())
                .forEach(s -> modules.computeIfAbsent(s.module(), ignored -> new ArrayList<>()));
        List<JavacMavenInputs.Module> out = new ArrayList<>();
        for (var entry : modules.entrySet()) {
            String module = entry.getKey();
            List<Path> main = new ArrayList<>(), test = new ArrayList<>();
            roots().stream()
                    .filter(r -> r.module().equals(module))
                    .forEach(r -> (r.scope() == SourceScope.TEST ? test : main).add(r.path()));
            Context m =
                    entry.getValue().stream()
                            .filter(c -> c.scope() != SourceScope.TEST)
                            .findFirst()
                            .orElse(null);
            Context t =
                    entry.getValue().stream()
                            .filter(c -> c.scope() == SourceScope.TEST)
                            .findFirst()
                            .orElse(null);
            out.add(
                    new JavacMavenInputs.Module(
                            module,
                            root.resolve(module).normalize(),
                            module,
                            8,
                            main,
                            test,
                            dependencies(m),
                            dependencies(t),
                            m == null ? "" : m.digest()));
        }
        return new JavacMavenInputs(
                out,
                Map.of(),
                Json.key(
                        "captured-modules",
                        contexts.stream().map(Context::digest).toArray(String[]::new)),
                true);
    }

    private List<JavacMavenInputs.Dependency> dependencies(Context c) {
        if (c == null) return List.of();
        List<JavacMavenInputs.Dependency> out = new ArrayList<>();
        c.dependencies().stream()
                .sorted()
                .forEach(m -> out.add(new JavacMavenInputs.ReactorDependency(m, m)));
        for (Path jar : c.classpath())
            out.add(new JavacMavenInputs.ExternalDependency("", "", "jar", jar));
        return out;
    }

    static List<Object> list(Object v) {
        return v instanceof List<?> ? Json.list(v) : List.of();
    }

    public void verify() throws IOException {
        for (Source s : sources.values())
            if (!Json.sha(s.file()).equals(s.hash())) throw new IOException("CAPTURE_CHANGED");
    }
}
