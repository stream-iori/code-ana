package com.anatomist.model;

import java.util.List;

/** Static resolution result. Ambiguous results deliberately retain every candidate. */
public record SymbolResolution(String status, SymbolRef reference, List<String> candidates) {
    public static final String EXACT = "exact";
    public static final String AMBIGUOUS = "ambiguous";
    public static final String UNRESOLVED = "unresolved";

    public SymbolResolution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static SymbolResolution of(SymbolRef reference, List<String> candidates) {
        List<String> values =
                candidates == null ? List.of() : candidates.stream().distinct().sorted().toList();
        String status = values.isEmpty() ? UNRESOLVED : values.size() == 1 ? EXACT : AMBIGUOUS;
        return new SymbolResolution(status, reference, values);
    }
}
