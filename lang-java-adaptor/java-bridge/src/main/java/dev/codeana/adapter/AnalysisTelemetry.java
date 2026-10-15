package dev.codeana.adapter;

import com.anatomist.core.IndexTimings;
import dev.codeana.common.Json;
import dev.codeana.common.PerformanceTrace;
import java.nio.file.Path;
import java.util.*;

/** Small task-local counters. No model dumps or protocol events. */
public final class AnalysisTelemetry implements AnalysisObserver {
    private final PerformanceTrace trace = System.getenv("CODEANA_PERFORMANCE_TIMINGS_DIR") == null ? null : new PerformanceTrace();
    private final List<Object> runtimeSamples = new ArrayList<>();
    private final Map<String, Long> calls = new LinkedHashMap<>();
    public boolean detailed() { return trace != null; }
    private final IndexTimings timings = new IndexTimings();
    private final Map<String, Long> started = new HashMap<>(), durations = new LinkedHashMap<>();
    private final long began = System.nanoTime();
    private Map<String, Object> compiler = Map.of();
    private Map<String, Object> projection = Map.of();
    public void projectionStatistics(Map<String, Object> statistics) { projection = statistics; }

    public IndexTimings timings() {
        return timings;
    }

    public void phase(String phase, String context, boolean beginning) {
        String key = phase + "/" + context;
        if (trace != null && runtimeSamples.size() < 512 && Set.of("inventory", "extraction_total", "resources", "archive_seal").contains(phase))
            runtimeSamples.add(Json.object("phase", phase, "context", context, "beginning", beginning,
                    "elapsed_ns", System.nanoTime() - began, "runtime", PerformanceTrace.runtime()));
        if (beginning) started.put(key, System.nanoTime());
        else {
            calls.merge(phase, 1L, Long::sum);
            Long before = started.remove(key);
            if (before != null) durations.merge(phase, System.nanoTime() - before, Long::sum);
        }
    }

    public void compilerStatistics(Map<String, Object> statistics) {
        compiler = statistics;
    }

    public void save(Path path, int state, String failure) {
        Map<String, Object> millis = new LinkedHashMap<>();
        durations.forEach((key, value) -> millis.put(key, value / 1_000_000));
        try {
            Json.writeTo(
                    path,
                    Json.object(
                            "elapsed_ms",
                            (System.nanoTime() - began) / 1_000_000,
                            "state",
                            state,
                            "failure",
                            failure,
                            "phase_ms",
                            millis,
                            "phase_calls", calls,
                            "phase_runtime_samples", runtimeSamples,
                            "unfinished_phases", started.keySet().stream().sorted().toList(),
                            "diagnostics", trace == null ? null : trace.snapshot(),
                            "core_ms",
                            timings.millis(),
                            "compiler_statistics",
                            compiler,
                            "projection_statistics", projection),
                    8L << 20);
        } catch (Exception failureToWrite) {
            System.err.println("JAVA_TELEMETRY_WRITE_FAILED: " + failureToWrite);
        } finally {
            if (trace != null) trace.close();
        }
    }
}
