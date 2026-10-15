package com.anatomist.core.java;

import java.util.List;

/**
 * Detached attribution. Type references are meaningful only in the owning unit's JavaTypes.
 * selectedType is the instantiated method-select type when exposed by javac; otherwise -1. A
 * declaration type must not stand in for an unavailable instantiated type. declarationResolved
 * records selected-member evidence independently of local diagnostic/type completeness. Ambiguous
 * or inaccessible recovery selections are not resolved declarations.
 */
public record JavaBinding(
        int id,
        String kind,
        String owner,
        String name,
        List<String> parameters,
        String returns,
        String type,
        boolean variadic,
        List<String> flags,
        List<String> exceptions,
        String descriptor,
        int expressionType,
        int declaredType,
        int selectedType,
        boolean evidenceComplete,
        String binaryOwner,
        String ownerKind,
        boolean declarationResolved) {
    public JavaBinding {
        parameters = List.copyOf(parameters);
        flags = List.copyOf(flags);
        exceptions = List.copyOf(exceptions);
    }

    public JavaSymbol symbol() {
        return new JavaSymbol(kind, owner, name, descriptor, declaredType, flags, variadic);
    }
}
