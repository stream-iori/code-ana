package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.*;

/** Timings are telemetry only; elapsed time is excluded from facts and fingerprints. */
public final class ExtensionReport {
    private final Map<String, Long> nanos = new TreeMap<>(), calls = new TreeMap<>();
    private final List<Object> failures = new ArrayList<>();
    private final Set<String> failureFacts = new HashSet<>();
    private long failureCount;
    void timing(ExtensionPoint e, String stage, long elapsed) {
        String key = stage + "." + e.id();
        nanos.merge(key, elapsed, Long::sum); calls.merge(key, 1L, Long::sum);
    }
    void failure(ExtensionPoint e, String stage, Exception failure) {
        failureCount++;
        if (failures.size() < 1000) failures.add(Json.object("extension", e.id(), "stage", stage, "code", "EXTENSION_CALLBACK_FAILED",
                "exception", failure.getClass().getName()));
    }
    boolean newFailureFact(String id) {
        if (failureFacts.size() >= 100000) throw new IllegalArgumentException("EXTENSION_DIAGNOSTIC_BUDGET");
        return failureFacts.add(id);
    }
    public Map<String, Object> summary() { return Json.object("nanos", nanos, "calls", calls, "failures", failures,
            "failure_count", failureCount, "failures_truncated", failureCount > failures.size()); }
}
