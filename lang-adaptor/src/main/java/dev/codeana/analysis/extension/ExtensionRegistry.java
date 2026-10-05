package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Stable dependency order, even if the assembly's input list is reordered. */
public final class ExtensionRegistry {
    private final List<ExtensionPoint> ordered;
    public ExtensionRegistry(List<ExtensionPoint> extensions) {
        Map<String, ExtensionPoint> pending = new TreeMap<>();
        Map<String, Integer> schemas = new HashMap<>();
        Set<String> producers = new HashSet<>();
        for (ExtensionPoint e : extensions) {
            if (e.id() == null || !e.id().matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")
                    || e.version() == null || e.version().isBlank()
                    || e.producer() == null || !e.producer().matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")
                    || e.producer().equals("javac-java8-v1") || !producers.add(e.producer())
                    || pending.putIfAbsent(e.id(), e) != null)
                throw new IllegalArgumentException("EXTENSION_ID_OR_VERSION");
            for (var entry : e.facets().entrySet()) {
                Integer previous = schemas.putIfAbsent(entry.getKey(), entry.getValue());
                if (entry.getValue() < 1 || previous != null && !previous.equals(entry.getValue()))
                    throw new IllegalArgumentException("EXTENSION_FACET_CONFLICT");
            }
        }
        for (ExtensionPoint e : extensions) if (!pending.keySet().containsAll(e.requires()))
            throw new IllegalArgumentException("EXTENSION_DEPENDENCY_MISSING");
        List<ExtensionPoint> result = new ArrayList<>(); Set<String> done = new HashSet<>();
        while (!pending.isEmpty()) {
            ExtensionPoint ready = pending.values().stream().filter(e -> done.containsAll(e.requires()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("EXTENSION_DEPENDENCY_CYCLE"));
            result.add(ready); done.add(ready.id()); pending.remove(ready.id());
        }
        ordered = List.copyOf(result);
    }
    public List<ExtensionPoint> enabled(Map<String, Object> configuration) {
        Map<String, Object> frozen = ExtensionContext.freezeMap(configuration);
        List<ExtensionPoint> result = ordered.stream().filter(e -> e.enabled(frozen)).toList();
        Set<String> active = new HashSet<>(); for (ExtensionPoint e : result) active.add(e.id());
        for (ExtensionPoint e : result) if (!active.containsAll(e.requires()))
            throw new IllegalArgumentException("EXTENSION_DEPENDENCY_DISABLED: " + e.id());
        return result;
    }
    public List<Object> manifest(Map<String, Object> configuration) {
        List<Object> result = new ArrayList<>();
        for (ExtensionPoint e : enabled(configuration)) {
            List<String> points = new ArrayList<>();
            if (e instanceof JavaModelExtension) points.add("model");
            if (e instanceof JavaDeclarationModelExtension) points.add("model-declaration");
            if (e instanceof JavaModelAnalyzer) points.add("java");
            if (e instanceof JavaDeclarationAnalyzer) points.add("java-declaration");
            if (e instanceof JavaCallSiteEvidenceProvider) points.add("call");
            if (e instanceof ProjectResourceProvider) points.add("resource");
            if (e instanceof ProjectResourceAnalyzer) points.add("project");
            result.add(Json.object("id", e.id(), "version", e.version(), "producer", e.producer(),
                    "implementation", e.getClass().getName(), "requires", new TreeSet<>(e.requires()).stream().toList(),
                    "points", points, "facets", new TreeMap<>(e.facets()),
                    "configuration", ExtensionContext.freezeMap(e.configuration(ExtensionContext.freezeMap(configuration)))));
        }
        return result;
    }
    public String fingerprint(Map<String, Object> configuration) {
        return Json.sha(Json.write(List.of("staged-project-view-v11-lombok-ast-sharing", manifest(configuration))).getBytes(StandardCharsets.UTF_8));
    }
    public boolean supportsBodyOnlyReuse(Map<String, Object> configuration) {
        return enabled(configuration).stream().allMatch(ExtensionPoint::supportsBodyOnlyReuse);
    }
}
