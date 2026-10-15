package com.anatomist.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Dependency-free static Maven/Gradle Java language-level detector. */
final class BuildJavaVersionDetector {

    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern TOOLCHAIN =
            Pattern.compile("JavaLanguageVersion\\.of\\s*\\(\\s*([^\\)]+)\\s*\\)");
    private static final Pattern COMPATIBILITY =
            Pattern.compile(
                    "(?m)^[ \\t]*(sourceCompatibility|targetCompatibility)[ \\t]*=[ \\t]*([^\\r\\n]+)");
    private static final Pattern RELEASE_SET =
            Pattern.compile("(?:options\\.)?release(?:\\.set)?\\s*\\(\\s*([^\\)]+)\\s*\\)");
    private static final Pattern RELEASE_ASSIGN =
            Pattern.compile("(?m)^[ \\t]*(?:options\\.)?release[ \\t]*=[ \\t]*([^\\r\\n]+)");
    private static final Pattern GRADLE_PROPERTY =
            Pattern.compile(
                    "(?:findProperty|property)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)?"
                            + "|providers\\.gradleProperty\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)?");
    private static final Pattern JAVA_VERSION_CONSTANT =
            Pattern.compile("JavaVersion\\.VERSION_(?:1_)?(\\d+)");
    private static final Pattern NUMERIC_VERSION = Pattern.compile("^(\\d+)(?:\\.\\d+)?$");

    private BuildJavaVersionDetector() {}

    static JavaVersionDetection detect(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return JavaVersionDetection.unknown(List.of());
        }
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        detectMaven(projectRoot, candidates, diagnostics);
        detectGradle(projectRoot, candidates, diagnostics);
        return candidates.stream()
                .max(Comparator.comparingInt(Candidate::version))
                .map(
                        candidate ->
                                new JavaVersionDetection(
                                        candidate.version(),
                                        candidate.source(),
                                        candidate.file(),
                                        candidate.expression(),
                                        diagnostics))
                .orElseGet(() -> JavaVersionDetection.unknown(diagnostics));
    }

    private static void detectMaven(
            Path root, List<Candidate> candidates, List<IndexDiagnostic> diagnostics) {
        List<Path> poms = findFiles(root, "pom.xml");
        if (poms.isEmpty()) return;
        Map<Path, PomModel> models = new LinkedHashMap<>();
        for (Path pom : poms) {
            PomModel model = readPom(pom, diagnostics);
            if (model != null) models.put(pom.toAbsolutePath().normalize(), model);
        }
        Map<Path, Map<String, String>> effectiveCache = new HashMap<>();
        for (PomModel model : models.values()) {
            Map<String, String> properties =
                    effectiveProperties(model, models, effectiveCache, new java.util.HashSet<>());
            RawCandidate selected = selectMavenCandidate(model, properties, models, effectiveCache);
            if (selected == null) continue;
            String resolved = resolve(selected.expression(), properties, 0);
            int version = parseVersion(resolved);
            if (version > 0) {
                candidates.add(
                        new Candidate(
                                version,
                                JavaVersionDetection.Source.MAVEN,
                                model.file(),
                                selected.label() + "=" + selected.expression()));
            } else {
                diagnostics.add(
                        ambiguous(
                                "MAVEN",
                                model.file(),
                                selected.label() + "=" + selected.expression()));
            }
        }
    }

    private static RawCandidate selectMavenCandidate(
            PomModel model,
            Map<String, String> properties,
            Map<Path, PomModel> models,
            Map<Path, Map<String, String>> effectiveCache) {
        List<RawCandidate> ordered =
                List.of(
                        new RawCandidate("maven-compiler-plugin.release", model.pluginRelease()),
                        new RawCandidate(
                                "maven.compiler.release", properties.get("maven.compiler.release")),
                        new RawCandidate("maven-compiler-plugin.source", model.pluginSource()),
                        new RawCandidate(
                                "maven.compiler.source", properties.get("maven.compiler.source")),
                        new RawCandidate("java.version", properties.get("java.version")));
        for (RawCandidate candidate : ordered) {
            if (candidate.expression() != null && !candidate.expression().isBlank())
                return candidate;
        }
        Path parent = parentPath(model);
        PomModel parentModel = parent == null ? null : models.get(parent);
        if (parentModel == null) return null;
        return selectMavenCandidate(parentModel, properties, models, effectiveCache);
    }

    private static Map<String, String> effectiveProperties(
            PomModel model,
            Map<Path, PomModel> models,
            Map<Path, Map<String, String>> cache,
            java.util.Set<Path> visiting) {
        Map<String, String> cached = cache.get(model.file());
        if (cached != null) return cached;
        if (!visiting.add(model.file())) return model.properties();
        Map<String, String> out = new LinkedHashMap<>();
        Path parent = parentPath(model);
        PomModel parentModel = parent == null ? null : models.get(parent);
        if (parentModel != null) {
            out.putAll(effectiveProperties(parentModel, models, cache, visiting));
        }
        out.putAll(model.properties());
        Map<String, String> resolved = new LinkedHashMap<>();
        out.forEach((key, value) -> resolved.put(key, resolve(value, out, 0)));
        Map<String, String> immutable = Map.copyOf(resolved);
        cache.put(model.file(), immutable);
        visiting.remove(model.file());
        return immutable;
    }

    private static Path parentPath(PomModel model) {
        if (!model.hasParent()) return null;
        String relative = model.parentRelativePath();
        if (relative == null) relative = "../pom.xml";
        if (relative.isBlank()) return null;
        return model.file().getParent().resolve(relative).toAbsolutePath().normalize();
    }

    private static PomModel readPom(Path file, List<IndexDiagnostic> diagnostics) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            Element project = document.getDocumentElement();
            Map<String, String> properties = new LinkedHashMap<>();
            Element propertiesElement = directChild(project, "properties");
            if (propertiesElement != null) {
                for (Element child : childElements(propertiesElement)) {
                    properties.put(child.getTagName(), child.getTextContent().trim());
                }
            }
            Element parent = directChild(project, "parent");
            String relative = parent == null ? null : childText(parent, "relativePath");
            String pluginRelease = null;
            String pluginSource = null;
            NodeList plugins = project.getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                if (!(plugins.item(i) instanceof Element plugin)) continue;
                if (!"maven-compiler-plugin".equals(childText(plugin, "artifactId"))) continue;
                Element configuration = directChild(plugin, "configuration");
                if (configuration == null) continue;
                String release = childText(configuration, "release");
                String source = childText(configuration, "source");
                if (release != null && !release.isBlank()) pluginRelease = release;
                if (source != null && !source.isBlank()) pluginSource = source;
            }
            return new PomModel(
                    file.toAbsolutePath().normalize(),
                    properties,
                    parent != null,
                    relative,
                    pluginRelease,
                    pluginSource);
        } catch (Exception e) {
            diagnostics.add(
                    new IndexDiagnostic(
                            "warning",
                            "JAVA_VERSION_BUILD_FILE_INVALID",
                            "JAVA_VERSION",
                            file.toString(),
                            null,
                            null,
                            null,
                            1,
                            e.getMessage()));
            return null;
        }
    }

    private static void detectGradle(
            Path root, List<Candidate> candidates, List<IndexDiagnostic> diagnostics) {
        List<Path> builds = new ArrayList<>();
        builds.addAll(findFiles(root, "build.gradle"));
        builds.addAll(findFiles(root, "build.gradle.kts"));
        for (Path build : builds.stream().distinct().sorted().toList()) {
            String text;
            try {
                text = Files.readString(build, StandardCharsets.UTF_8);
            } catch (IOException e) {
                diagnostics.add(
                        new IndexDiagnostic(
                                "warning",
                                "JAVA_VERSION_BUILD_FILE_INVALID",
                                "JAVA_VERSION",
                                build.toString(),
                                null,
                                null,
                                null,
                                1,
                                e.getMessage()));
                continue;
            }
            Map<String, String> properties = gradleProperties(root, build.getParent());
            List<RawCandidate> raw = new ArrayList<>();
            collect(raw, TOOLCHAIN, text, "gradle.toolchain", 1);
            collect(raw, RELEASE_SET, text, "gradle.options.release", 1);
            collect(raw, RELEASE_ASSIGN, text, "gradle.options.release", 1);
            Matcher compatibility = COMPATIBILITY.matcher(text);
            while (compatibility.find()) {
                raw.add(
                        new RawCandidate(
                                "gradle." + compatibility.group(1),
                                trimExpression(compatibility.group(2))));
            }
            Candidate best = null;
            for (RawCandidate candidate : raw) {
                String resolved = resolveGradle(candidate.expression(), properties);
                int version = parseVersion(resolved);
                if (version > 0) {
                    Candidate parsed =
                            new Candidate(
                                    version,
                                    JavaVersionDetection.Source.GRADLE,
                                    build,
                                    candidate.label() + "=" + candidate.expression());
                    if (best == null || parsed.version() > best.version()) best = parsed;
                } else {
                    diagnostics.add(
                            ambiguous(
                                    "GRADLE",
                                    build,
                                    candidate.label() + "=" + candidate.expression()));
                }
            }
            if (best != null) candidates.add(best);
        }
    }

    private static void collect(
            List<RawCandidate> out, Pattern pattern, String text, String label, int valueGroup) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            out.add(new RawCandidate(label, trimExpression(matcher.group(valueGroup))));
        }
    }

    private static String trimExpression(String value) {
        if (value == null) return null;
        int comment = value.indexOf("//");
        if (comment >= 0) value = value.substring(0, comment);
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            char last = trimmed.charAt(trimmed.length() - 1);
            if (last == ';' || last == '}') {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private static Map<String, String> gradleProperties(Path root, Path directory) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Path> chain = new ArrayList<>();
        Path current = directory.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        while (current.startsWith(normalizedRoot)) {
            chain.add(current);
            if (current.equals(normalizedRoot)) break;
            current = current.getParent();
            if (current == null) break;
        }
        java.util.Collections.reverse(chain);
        for (Path dir : chain) {
            Path file = dir.resolve("gradle.properties");
            if (!Files.isRegularFile(file)) continue;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String stripped = line.strip();
                    if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!"))
                        continue;
                    int split = stripped.indexOf('=');
                    if (split < 0) split = stripped.indexOf(':');
                    if (split > 0) {
                        out.put(
                                stripped.substring(0, split).trim(),
                                stripped.substring(split + 1).trim());
                    }
                }
            } catch (IOException ignore) {
                // Build file diagnostic is enough; unreadable optional properties remain ambiguous.
            }
        }
        return out;
    }

    private static String resolveGradle(String expression, Map<String, String> properties) {
        if (expression == null) return null;
        String value = expression.trim();
        Matcher ref = GRADLE_PROPERTY.matcher(value);
        if (ref.find()) {
            String name = ref.group(1) != null ? ref.group(1) : ref.group(2);
            return properties.get(name);
        }
        if (properties.containsKey(value)) return properties.get(value);
        value = value.replace("JavaVersion.VERSION_1_8", "8");
        Matcher javaVersion = JAVA_VERSION_CONSTANT.matcher(value);
        if (javaVersion.find()) return javaVersion.group(1);
        return unquote(value);
    }

    private static String resolve(String expression, Map<String, String> properties, int depth) {
        if (expression == null || depth >= 10) return expression;
        Matcher matcher = PROPERTY_REF.matcher(expression);
        StringBuffer out = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String replacement = properties.get(matcher.group(1));
            if (replacement == null) continue;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        matcher.appendTail(out);
        return changed ? resolve(out.toString(), properties, depth + 1) : expression;
    }

    static int parseVersion(String raw) {
        if (raw == null) return -1;
        String value = unquote(raw.trim());
        if (value.startsWith("1.")) value = value.substring(2);
        Matcher numeric = NUMERIC_VERSION.matcher(value);
        if (!numeric.matches()) return -1;
        try {
            return Integer.parseInt(numeric.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String unquote(String value) {
        if (value != null
                && value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static List<Path> findFiles(Path root, String name) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(
                            path ->
                                    path.getFileName() != null
                                            && name.equals(path.getFileName().toString()))
                    .filter(
                            path -> {
                                Path relative =
                                        root.toAbsolutePath()
                                                .normalize()
                                                .relativize(path.toAbsolutePath().normalize());
                                for (Path part : relative) {
                                    String text = part.toString();
                                    if ("target".equals(text)
                                            || "build".equals(text)
                                            || ".gradle".equals(text)
                                            || ".git".equals(text)
                                            || "node_modules".equals(text)) return false;
                                }
                                return true;
                            })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) return null;
        for (Element child : childElements(parent)) {
            if (name.equals(child.getTagName())) return child;
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element) out.add(element);
        }
        return out;
    }

    private static IndexDiagnostic ambiguous(String source, Path file, String expression) {
        return new IndexDiagnostic(
                "warning",
                "JAVA_VERSION_AMBIGUOUS",
                "JAVA_VERSION",
                file.toString(),
                null,
                null,
                null,
                1,
                source + " expression could not be resolved statically: " + expression);
    }

    private record Candidate(
            int version, JavaVersionDetection.Source source, Path file, String expression) {}

    private record RawCandidate(String label, String expression) {}

    private record PomModel(
            Path file,
            Map<String, String> properties,
            boolean hasParent,
            String parentRelativePath,
            String pluginRelease,
            String pluginSource) {}
}
