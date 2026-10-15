package com.anatomist.framework;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.IndexTimings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable report for one full or incremental extension execution batch. */
public final class ExtensionReport {
    private final List<IndexDiagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final IndexTimings timings;

    public ExtensionReport() {
        this(null);
    }

    public ExtensionReport(IndexTimings timings) {
        this.timings = timings;
    }

    long start() {
        return timings == null ? 0L : timings.start();
    }

    void stop(String phase, String extensionId, long started) {
        if (timings == null) return;
        long elapsed = System.nanoTime() - started;
        timings.addNanos(phase, elapsed);
        timings.addNanos(phase + "." + extensionId, elapsed);
    }

    public void diagnostic(IndexDiagnostic diagnostic) {
        if (diagnostic != null) diagnostics.add(diagnostic);
    }

    public void increment(String key, long amount) {
        if (key != null && amount > 0) counters.merge(key, amount, Long::sum);
    }

    public List<IndexDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public Map<String, Long> counters() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }
}
