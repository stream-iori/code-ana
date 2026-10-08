package com.anatomist.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Monotonic, additive phase timings for one index run. */
public final class IndexTimings {
    private final Map<String, Long> nanos = new LinkedHashMap<>();

    private final Map<String, Long> counts = new LinkedHashMap<>();

    public void increment(String name) {
        counts.merge(name, 1L, Long::sum);
    }

    public Map<String, Long> counts() {
        return Map.copyOf(counts);
    }

    public void finishTotal(long startedNanos) {
        nanos.put("total", System.nanoTime() - startedNanos);
    }

    public long start() {
        return System.nanoTime();
    }

    public void stop(String phase, long startedNanos) {
        addNanos(phase, System.nanoTime() - startedNanos);
    }

    public void addNanos(String phase, long elapsedNanos) {
        if (phase == null || elapsedNanos < 0) return;
        nanos.merge(phase, elapsedNanos, Long::sum);
    }

    public Map<String, Long> millis() {
        Map<String, Long> out = new LinkedHashMap<>();
        nanos.forEach((key, value) -> out.put(key, value / 1_000_000L));
        return Collections.unmodifiableMap(out);
    }
}
