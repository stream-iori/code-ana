package dev.codeana.adapter;

import dev.codeana.common.Json;

import java.util.*;

/** Known context policy, decoded once; source descriptions remain shared lazy views. */
public record ContextOptions(
        long modelArchiveBytes,
        long modelRecordLimit,
        long modelDecodeBytes,
        String lombok,
        boolean lombokStrict,
        boolean spring,
        boolean springXml,
        boolean sofa,
        boolean externalPolicyConfigured,
        List<String> externalExcludePatterns,
        List<String> sourceRoots) {
    public ContextOptions {
        externalExcludePatterns = List.copyOf(externalExcludePatterns);
        sourceRoots = List.copyOf(sourceRoots);
    }

    static ContextOptions parse(Map<String, Object> row) {
        return new ContextOptions(
                number(row, "model_archive_bytes", 64L << 20),
                number(row, "model_record_limit", 100000),
                number(row, "model_decode_bytes", 512L << 20),
                Objects.toString(row.get("lombok"), "off"),
                flag(row, "lombok_strict"),
                flag(row, "spring"),
                flag(row, "spring_xml"),
                flag(row, "sofa"),
                flag(row, "external_policy_configured"),
                strings(row.get("external_exclude_patterns")),
                strings(row.get("source_roots")));
    }

    static boolean flag(Map<String, Object> row, String key) {
        if (row.containsKey(key) && !(row.get(key) instanceof Boolean))
            throw new IllegalArgumentException("BOOLEAN_REQUIRED: " + key);
        return Boolean.TRUE.equals(row.get(key));
    }

    static long number(Map<String, Object> row, String key, long fallback) {
        return row.containsKey(key) ? Json.number(row, key) : fallback;
    }

    static List<String> strings(Object value) {
        return CapturedProject.list(value).stream().map(String.class::cast).toList();
    }
}
