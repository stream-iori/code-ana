package com.anatomist.core;

import com.anatomist.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Lossless aggregate of analysis gaps, independent from bounded diagnostic samples. */
public final class AnalysisCoverage {
    private static final java.util.Set<String> DIAGNOSTIC_METADATA =
            java.util.Set.of(
                    "UNRESOLVED_SYMBOLS",
                    "DIAGNOSTIC_STORAGE_TRUNCATED",
                    "DIAGNOSTIC_LIMIT_REACHED",
                    "LOCAL_INFERENCE_SAMPLES_TRUNCATED");

    public record Row(
            String sourceFile,
            String module,
            String scope,
            String language,
            String providerId,
            String capability,
            String status,
            long occurrences,
            long groups,
            String codes,
            String codeCounts,
            boolean detailsTruncated) {}

    private static final List<String> CAPABILITIES =
            List.of(
                    "DECLARATION",
                    "CALL_OUTGOING",
                    "CALL_INCOMING",
                    "CALL_PATH",
                    "TYPE_OUTGOING",
                    "TYPE_INCOMING",
                    "REFERENCE_OUTGOING",
                    "REFERENCE_INCOMING",
                    "FIELD_ACCESS",
                    "WIRING",
                    "AGGREGATE",
                    "DIAGNOSTIC_DETAILS",
                    "LOCAL_ANALYSIS");

    private AnalysisCoverage() {}

    public static List<Row> summarize(List<IndexDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        Map<Key, Accumulator> grouped = new TreeMap<>();
        for (IndexDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) continue;
            var capabilities = new ArrayList<>(CAPABILITIES);
            if (!DIAGNOSTIC_METADATA.contains(diagnostic.code()))
                capabilities.add("DIAGNOSTIC_GROUP:" + diagnostic.code());
            if ("NAVIGATION_TARGET_UNRESOLVED".equals(diagnostic.code()))
                capabilities.add("NAVIGATION_SYMBOL:" + value(diagnostic.symbol()));
            if ("NAVIGATION_TARGET_UNRESOLVED".equals(diagnostic.code()))
                capabilities.add(
                        "NAVIGATION_REASON:"
                                + diagnostic.phase()
                                + ":"
                                + (diagnostic.reasonCode() == null
                                        ? "UNKNOWN"
                                        : diagnostic.reasonCode()));
            for (String capability : capabilities) {
                if (!relevant(diagnostic, capability)) continue;
                Key key =
                        new Key(
                                value(diagnostic.sourceFile()),
                                value(diagnostic.module()),
                                value(diagnostic.scope()),
                                value(diagnostic.language()),
                                value(diagnostic.providerId()),
                                capability);
                grouped.computeIfAbsent(key, ignored -> new Accumulator()).add(diagnostic);
            }
        }
        List<Row> rows = new ArrayList<>();
        grouped.forEach(
                (key, value) ->
                        rows.add(
                                new Row(
                                        key.sourceFile(),
                                        key.module(),
                                        key.scope(),
                                        key.language(),
                                        key.providerId(),
                                        key.capability(),
                                        value.onlyBoundaries ? "complete" : "partial",
                                        value.occurrences,
                                        value.groups,
                                        Json.writeCompact(value.codes),
                                        Json.writeCompact(value.codeCounts),
                                        value.detailsTruncated)));
        return rows;
    }

    private static boolean relevant(IndexDiagnostic diagnostic, String capability) {
        String code = upper(diagnostic.code());
        String phase = upper(diagnostic.phase());
        if (capability.startsWith("NAVIGATION_REASON:")
                || capability.startsWith("NAVIGATION_SYMBOL:")
                || capability.startsWith("DIAGNOSTIC_GROUP:")) return true;
        if ("LOCAL_ANALYSIS".equals(capability)) return "LOCAL_INFERENCE_SKIPPED".equals(code);
        if ("DIAGNOSTIC_DETAILS".equals(capability)) {
            return !DIAGNOSTIC_METADATA.contains(code);
        }
        if (ResolutionDiagnostics.isAnalysisBoundary(code)) return false;
        if ("UNRESOLVED_SYMBOLS".equals(code)) return false;
        if ("JAVA_PARSE_FAILED".equals(code) || "DANGLING_FACTS_DROPPED".equals(code)) {
            return true;
        }
        if ("DIAGNOSTIC_STORAGE_TRUNCATED".equals(code)) {
            return !"DECLARATION".equals(capability);
        }
        if (code.startsWith("CLASSPATH_")) return !"DECLARATION".equals(capability);
        return switch (capability) {
            case "DECLARATION" -> false;
            case "CALL_OUTGOING", "CALL_INCOMING", "CALL_PATH" ->
                    phase.contains("CALL_GRAPH") || phase.contains("METHOD");
            case "TYPE_OUTGOING", "TYPE_INCOMING" ->
                    phase.contains("HIERARCHY") || phase.contains("REFERENCE");
            case "REFERENCE_OUTGOING", "REFERENCE_INCOMING" ->
                    phase.contains("REFERENCE")
                            || phase.contains("METHOD")
                            || phase.contains("FIELD");
            case "FIELD_ACCESS" -> phase.contains("FIELD_ACCESS");
            case "WIRING" -> phase.contains("ANNOTATION") || phase.contains("REFERENCE");
            case "AGGREGATE" -> resolutionFailure(diagnostic);
            default -> false;
        };
    }

    private static boolean resolutionFailure(IndexDiagnostic diagnostic) {
        String code = upper(diagnostic.code());
        String phase = upper(diagnostic.phase());
        return "NAVIGATION_TARGET_UNRESOLVED".equals(code)
                || phase.contains("RESOLUTION")
                || phase.contains("CALL_GRAPH")
                || phase.contains("HIERARCHY")
                || phase.contains("REFERENCE")
                || phase.contains("METHOD")
                || phase.contains("FIELD")
                || code.endsWith("_NOT_FOUND")
                || code.endsWith("_RESOLUTION")
                || code.contains("INFERENCE")
                || code.contains("OVERLOAD")
                || code.contains("SYMBOL_MISSING")
                || code.startsWith("CLASSPATH_");
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "*" : value;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private record Key(
            String sourceFile,
            String module,
            String scope,
            String language,
            String providerId,
            String capability)
            implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int result = sourceFile.compareTo(other.sourceFile);
            if (result != 0) return result;
            result = module.compareTo(other.module);
            if (result != 0) return result;
            result = scope.compareTo(other.scope);
            if (result != 0) return result;
            result = language.compareTo(other.language);
            if (result != 0) return result;
            result = providerId.compareTo(other.providerId);
            if (result != 0) return result;
            return capability.compareTo(other.capability);
        }
    }

    private static final class Accumulator {
        private long occurrences;
        private long groups;
        private final TreeSet<String> codes = new TreeSet<>();
        private final Map<String, Long> codeCounts = new TreeMap<>();
        private boolean detailsTruncated;
        private boolean onlyBoundaries = true;

        void add(IndexDiagnostic diagnostic) {
            onlyBoundaries &= ResolutionDiagnostics.isAnalysisBoundary(diagnostic.code());
            long count = Math.max(0L, diagnostic.count());
            occurrences += count;
            groups++;
            String code = diagnostic.code() == null ? "UNKNOWN" : diagnostic.code();
            codes.add(code);
            codeCounts.merge(code, count, Long::sum);
            detailsTruncated |=
                    "DIAGNOSTIC_STORAGE_TRUNCATED".equals(code)
                            || "DIAGNOSTIC_LIMIT_REACHED".equals(code);
        }
    }
}
