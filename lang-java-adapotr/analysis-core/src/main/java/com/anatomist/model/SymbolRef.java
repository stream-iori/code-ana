package com.anatomist.model;

import java.util.List;

/** Language-neutral request for resolving one configured symbol reference. */
public record SymbolRef(
        String runtime,
        String languageHint,
        String symbolKind,
        String owner,
        String memberName,
        Integer arity,
        List<String> parameterTypeHints,
        Boolean staticRequirement,
        String role,
        String mechanism,
        String sourceFile) {
    public SymbolRef {
        parameterTypeHints =
                parameterTypeHints == null ? List.of() : List.copyOf(parameterTypeHints);
    }
}
