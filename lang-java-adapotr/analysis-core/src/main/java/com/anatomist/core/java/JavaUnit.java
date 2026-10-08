package com.anatomist.core.java;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A detached syntax/semantic unit. No compiler, resolver or parser objects may escape into it. */
public record JavaUnit(
        Path path,
        JavaNode tree,
        JavaProcessingState processing,
        Map<Integer, JavaBinding> bindings,
        JavaTypes types,
        boolean declarations,
        List<JavaDeclaration> declarationInventory,
        String sourceHash,
        JavaExtensionData extensions) {
    public boolean hasProcessorEvidence() {
        return processing == JavaProcessingState.LOMBOK;
    }

    public JavaUnit {
        java.util.Objects.requireNonNull(sourceHash);
        java.util.Objects.requireNonNull(processing);
        java.util.Objects.requireNonNull(extensions);
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        declarationInventory = List.copyOf(declarationInventory);
        declarationInventory.forEach(declaration -> declaration.validate(types));
        bindings.forEach(
                (id, binding) -> {
                    if (id != binding.id())
                        throw new IllegalArgumentException("binding identity mismatch");
                    types.validateReference(binding.expressionType());
                    types.validateReference(binding.declaredType());
                    types.validateReference(binding.selectedType());
                });
    }

    public JavaUnit(
            Path path,
            JavaNode tree,
            JavaProcessingState processing,
            Map<Integer, JavaBinding> bindings,
            JavaTypes types,
            boolean declarations,
            List<JavaDeclaration> declarationInventory,
            String sourceHash) {
        this(
                path,
                tree,
                processing,
                bindings,
                types,
                declarations,
                declarationInventory,
                sourceHash,
                JavaExtensionData.EMPTY);
    }

    public JavaUnit(
            Path path,
            JavaNode tree,
            JavaProcessingState processing,
            Map<Integer, JavaBinding> bindings,
            JavaTypes types,
            boolean declarations,
            List<JavaDeclaration> declarationInventory) {
        this(path, tree, processing, bindings, types, declarations, declarationInventory, "");
    }

    public JavaUnit(
            Path path,
            JavaNode tree,
            JavaProcessingState processing,
            Map<Integer, JavaBinding> bindings,
            JavaTypes types,
            boolean declarations) {
        this(path, tree, processing, bindings, types, declarations, List.of());
    }
}
