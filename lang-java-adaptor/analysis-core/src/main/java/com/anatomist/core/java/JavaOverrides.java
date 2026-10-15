package com.anatomist.core.java;

import java.util.List;

/**
 * Compiler-confirmed overrides; missing hierarchy evidence is separate from header completeness.
 */
public record JavaOverrides(List<Target> targets, List<String> incompleteReasons) {
    public static final JavaOverrides EMPTY = new JavaOverrides(List.of(), List.of());

    public record Target(JavaSymbol symbol, String binaryOwner) {}

    public JavaOverrides {
        targets = List.copyOf(targets);
        incompleteReasons = List.copyOf(incompleteReasons);
    }
}
