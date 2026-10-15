package com.anatomist.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded persisted samples; coverage and health totals are computed before retention. */
public final class IndexDiagnosticRetention {

    public static final int LIMIT = 5_000;

    private IndexDiagnosticRetention() {}

    public static List<IndexDiagnostic> retain(List<IndexDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        var boundaries =
                diagnostics.stream()
                        .filter(d -> ResolutionDiagnostics.isAnalysisBoundary(d.code()))
                        .toList();
        var failures =
                diagnostics.stream()
                        .filter(d -> !ResolutionDiagnostics.isAnalysisBoundary(d.code()))
                        .toList();
        // Bound source-only samples separately so they cannot consume failure evidence
        // or manufacture a resolution-storage gap. Counts remain lossless in coverage.
        var local = retain(boundaries, 129, "LOCAL_INFERENCE_SAMPLES_TRUNCATED", "LOCAL_INFERENCE");
        var result =
                new ArrayList<>(
                        retain(
                                failures,
                                LIMIT - local.size(),
                                "DIAGNOSTIC_STORAGE_TRUNCATED",
                                "RESOLUTION"));
        result.addAll(local);
        return List.copyOf(result);
    }

    private static List<IndexDiagnostic> retain(
            List<IndexDiagnostic> diagnostics, int limit, String truncatedCode, String phase) {
        long previouslyOmitted =
                diagnostics.stream()
                        .filter(d -> truncatedCode.equals(d.code()))
                        .mapToLong(IndexDiagnostic::count)
                        .sum();
        List<IndexDiagnostic> ordered =
                new ArrayList<>(
                        diagnostics.stream().filter(d -> !truncatedCode.equals(d.code())).toList());
        if (previouslyOmitted == 0 && ordered.size() <= limit) return List.copyOf(ordered);
        ordered.sort(
                Comparator.comparingInt(IndexDiagnosticRetention::priority)
                        .thenComparing(Comparator.comparingLong(IndexDiagnostic::count).reversed())
                        .thenComparing(
                                IndexDiagnostic::code, Comparator.nullsLast(String::compareTo))
                        .thenComparing(
                                IndexDiagnostic::sourceFile,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(
                                IndexDiagnostic::phase, Comparator.nullsLast(String::compareTo))
                        .thenComparing(
                                IndexDiagnostic::symbol, Comparator.nullsLast(String::compareTo)));
        int capacity = limit - 1;
        List<IndexDiagnostic> retained = new ArrayList<>(capacity);
        Set<IndexDiagnostic> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, IndexDiagnostic> representatives = new LinkedHashMap<>();
        ordered.forEach(d -> representatives.putIfAbsent(d.code(), d));
        for (IndexDiagnostic d : representatives.values()) {
            if (retained.size() == capacity) break;
            retained.add(d);
            selected.add(d);
        }
        // Round-robin module/reason buckets within each severity. Repeated notices
        // must not starve method-binding evidence from other modules.
        Map<String, java.util.ArrayDeque<IndexDiagnostic>> buckets = new java.util.TreeMap<>();
        for (IndexDiagnostic d : ordered) {
            if (!selected.contains(d))
                buckets.computeIfAbsent(
                                priority(d) + ":" + d.code() + ":" + d.module(),
                                ignored -> new java.util.ArrayDeque<>())
                        .add(d);
        }
        for (int priority = 0; priority <= 3 && retained.size() < capacity; priority++) {
            final String prefix = priority + ":";
            var active =
                    buckets.entrySet().stream()
                            .filter(e -> e.getKey().startsWith(prefix))
                            .map(Map.Entry::getValue)
                            .toList();
            boolean more = true;
            while (more && retained.size() < capacity) {
                more = false;
                for (var bucket : active) {
                    if (retained.size() == capacity) break;
                    if (!bucket.isEmpty()) {
                        retained.add(bucket.removeFirst());
                        more = true;
                    }
                }
            }
        }
        long omitted = previouslyOmitted + ordered.size() - retained.size();
        if (omitted > 0)
            retained.add(
                    new IndexDiagnostic(
                            "info",
                            truncatedCode,
                            phase,
                            null,
                            null,
                            null,
                            null,
                            omitted,
                            "Detailed diagnostic samples were omitted; consult lossless file coverage before negative conclusions."));
        return List.copyOf(retained);
    }

    private static int priority(IndexDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.severity() == null) return 3;
        if ("LOMBOK_BODY_SEMANTICS_OMITTED".equals(diagnostic.code())) return 3;
        return switch (diagnostic.severity().toLowerCase(Locale.ROOT)) {
            case "error" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }
}
