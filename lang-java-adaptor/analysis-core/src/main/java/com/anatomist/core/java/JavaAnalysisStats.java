package com.anatomist.core.java;

import java.util.*;

/** Completed analysis accounting; repeated source appearances differ from published files. */
public record JavaAnalysisStats(
        int archivesUsed,
        int fallbackModules,
        long sourceAppearances,
        int workerLaunches,
        Map<String, List<String>> fallbackReasons,
        int catalogueEvictions,
        int catalogueReloads,
        int restoredModules) {
    public static final JavaAnalysisStats EMPTY = new JavaAnalysisStats(0, 0, 0, 0, Map.of());

    public JavaAnalysisStats(
            int archivesUsed,
            int fallbackModules,
            long sourceAppearances,
            int workerLaunches,
            Map<String, List<String>> fallbackReasons) {
        this(
                archivesUsed,
                fallbackModules,
                sourceAppearances,
                workerLaunches,
                fallbackReasons,
                0,
                0,
                0);
    }

    public JavaAnalysisStats {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        fallbackReasons.forEach((module, reasons) -> snapshot.put(module, List.copyOf(reasons)));
        fallbackReasons = Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Object> metadata() {
        return Map.of(
                "archives_used",
                archivesUsed,
                "fallback_modules",
                fallbackModules,
                "source_appearances",
                sourceAppearances,
                "worker_launches",
                workerLaunches,
                "fallback_reasons",
                fallbackReasons,
                "catalogue_evictions",
                catalogueEvictions,
                "catalogue_reloads",
                catalogueReloads,
                "restored_modules",
                restoredModules);
    }
}
