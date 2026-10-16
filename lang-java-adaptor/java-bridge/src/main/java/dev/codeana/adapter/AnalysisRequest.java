package dev.codeana.adapter;

import dev.codeana.common.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** JSON is interpreted here; analysis consumes immutable named fields. */
record AnalysisRequest(
        long version,
        Path scratch,
        Path diagnosticScratch,
        Path javaHome,
        Path cacheRoot,
        int parallelism,
        int compilerHeapMb,
        Duration compilerTimeout,
        boolean full,
        boolean signatureCache,
        boolean deferFactDedup,
        String dependencyMode,
        DependencyFailure dependencyFailure,
        List<ContextInput> contexts,
        AnalysisLimits limits,
        BaselineDescriptor.Input baseline) {
    record DependencyFailure(String module, String pom, String coordinate, String reason) {
        static DependencyFailure parse(Object raw) {
            if (!(raw instanceof Map<?, ?>) || Json.map(raw).isEmpty()) return null;
            var row = Json.map(raw);
            return new DependencyFailure(
                    Json.string(row, "module"),
                    Json.string(row, "pom"),
                    Json.string(row, "coordinate"),
                    Json.string(row, "reason"));
        }
    }

    record SourceInput(
            String path,
            String hash,
            String module,
            String world,
            String scope,
            Path input,
            boolean auxiliary,
            boolean publish) {
        static SourceInput parse(Object value, boolean resource) {
            var row = Json.map(value);
            var unit = resource ? Json.map(row.get("unit")) : row;
            return new SourceInput(
                    Json.string(unit, "path"),
                    Json.string(unit, "hash"),
                    Json.string(unit, "module"),
                    Json.string(unit, "world"),
                    Json.string(unit, "scope"),
                    Path.of(Json.string(row, "input")),
                    ContextOptions.flag(unit, "auxiliary"),
                    ContextOptions.flag(unit, "publish"));
        }
    }

    record ContextInput(
            String id,
            String module,
            String world,
            String scope,
            String digest,
            List<SourceInput> sources,
            List<SourceInput> resources,
            List<Path> classpath,
            Set<String> dependencies,
            ContextOptions options) {}

    private static List<SourceInput> sources(Object raw, boolean resource) {
        List<Object> values = CapturedProject.list(raw);
        return Collections.unmodifiableList(
                new AbstractList<SourceInput>() {
                    public int size() {
                        return values.size();
                    }

                    public SourceInput get(int i) {
                        return SourceInput.parse(values.get(i), resource);
                    }
                });
    }

    static AnalysisRequest capture(Map<String, Object> raw) throws IOException {
        var input = new LinkedHashMap<>(raw);
        input.putIfAbsent("version", 10);
        input.putIfAbsent("java_home", "");
        return parse(input);
    }

    static AnalysisRequest parse(Map<String, Object> raw) throws IOException {
        Path scratch = Path.of(Json.string(raw, "scratch"));
        var resolver = new ContextInputs(raw);
        List<ContextInput> contexts = new ArrayList<>();
        for (Object value : Json.list(raw.get("contexts"))) {
            var c = resolver.resolve(Json.map(value));
            contexts.add(
                    new ContextInput(
                            Json.string(c, "context"),
                            Json.string(c, "module"),
                            Json.string(c, "world"),
                            Json.string(c, "scope"),
                            Json.string(c, "digest"),
                            sources(c.get("sources"), false),
                            sources(c.get("resources"), true),
                            CapturedProject.list(c.get("classpath")).stream()
                                    .map(v -> Path.of((String) v).toAbsolutePath().normalize())
                                    .toList(),
                            Set.copyOf(ContextOptions.strings(c.get("dependency_modules"))),
                            ContextOptions.parse(c)));
        }
        Path diagnostic =
                Path.of(Objects.toString(raw.get("diagnostic_scratch"), scratch.toString()));
        long heap = ContextOptions.number(raw, "compiler_heap_bytes", 0);
        return new AnalysisRequest(
                Json.number(raw, "version"),
                scratch,
                diagnostic,
                Path.of(Json.string(raw, "java_home")),
                raw.get("cache_root") instanceof String p ? Path.of(p) : null,
                Math.max(1, Math.toIntExact(ContextOptions.number(raw, "parallelism", 1))),
                heap > 0 ? (int) (heap / (1 << 20)) : 512,
                Duration.ofSeconds(ContextOptions.number(raw, "compiler_timeout_seconds", 120)),
                !Boolean.FALSE.equals(raw.get("full")),
                !Boolean.FALSE.equals(raw.get("signature_cache")),
                ContextOptions.flag(raw, "defer_fact_dedup"),
                Objects.toString(raw.get("dependency_mode"), "resolved"),
                DependencyFailure.parse(raw.get("dependency_failure")),
                List.copyOf(contexts),
                new AnalysisLimits(raw),
                new BaselineDescriptor.Input(raw.get("baseline_summary"), diagnostic));
    }
}
