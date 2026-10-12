package com.anatomist.core.java;

import com.anatomist.core.IndexDiagnostic;

import java.util.*;

/** Extension boundary: declaration IDs own generated provenance and serialized capability data. */
public record JavaExtensionData(
        Map<String, JavaGeneratedOrigin> generated,
        Map<String, Map<String, Object>> declarationMetadata,
        List<IndexDiagnostic> diagnostics,
        Map<String, Long> counters) {
    public static final JavaExtensionData EMPTY =
            new JavaExtensionData(Map.of(), Map.of(), List.of(), Map.of());

    public JavaExtensionData {
        generated = Collections.unmodifiableMap(new LinkedHashMap<>(generated));
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        var budget = new Budget();
        declarationMetadata.forEach((key, value) -> metadata.put(key, freezeMap(value, 0, budget)));
        declarationMetadata = Collections.unmodifiableMap(metadata);
        diagnostics = List.copyOf(diagnostics);
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }

    private static Map<String, Object> freezeMap(Map<?, ?> input, int depth, Budget budget) {
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach(
                (key, value) -> {
                    if (!(key instanceof String name))
                        throw new IllegalArgumentException("extension metadata key must be text");
                    copy.put(name, freeze(value, depth + 1, budget));
                });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth, Budget budget) {
        if (depth > 64 || ++budget.visits > 100_000)
            throw new IllegalArgumentException("extension metadata exceeds budget");
        if (value instanceof Map<?, ?> map) return freezeMap(map, depth, budget);
        if (value instanceof Collection<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object item : values) copy.add(freeze(item, depth + 1, budget));
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double) return value;
        throw new IllegalArgumentException("extension metadata value must be JSON data");
    }

    private static final class Budget {
        private int visits;
    }
}
