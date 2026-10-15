package com.anatomist.core.java;

import java.util.List;

/** Compiler-selected declaration, separate from the instantiated type at a use site. */
public record JavaSymbol(
        String kind,
        String owner,
        String name,
        String descriptor,
        int declaredType,
        List<String> modifiers,
        boolean variadic) {
    public JavaSymbol {
        modifiers = List.copyOf(modifiers);
    }
}
