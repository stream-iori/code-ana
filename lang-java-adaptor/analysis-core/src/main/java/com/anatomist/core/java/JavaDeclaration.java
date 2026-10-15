package com.anatomist.core.java;

import java.util.List;
import java.util.Objects;

/** Compiler declaration inventory, independent of which declarations have source syntax. */
public record JavaDeclaration(
        JavaSymbol symbol,
        String canonicalName,
        String binaryName,
        String signature,
        SourceSpan span,
        Origin origin,
        int superclass,
        List<Integer> interfaces,
        List<Integer> permitted,
        List<JavaAnnotation> annotations,
        JavaBinaryType.Value constant,
        JavaBinaryType.Value annotationDefault,
        List<JavaDeclaration> members,
        List<String> incompleteReasons,
        JavaOverrides overrides,
        boolean deprecated) {
    public enum Origin {
        SOURCE,
        COMPILER_SYNTHETIC,
        PROCESSOR,
        MODEL,
        BINARY
    }

    public JavaDeclaration {
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(canonicalName);
        Objects.requireNonNull(binaryName);
        Objects.requireNonNull(signature);
        Objects.requireNonNull(span);
        Objects.requireNonNull(origin);
        interfaces = List.copyOf(interfaces);
        permitted = List.copyOf(permitted);
        annotations = List.copyOf(annotations);
        members = List.copyOf(members);
        incompleteReasons = List.copyOf(incompleteReasons);
        Objects.requireNonNull(overrides);
    }

    public JavaDeclaration(
            JavaSymbol symbol,
            String canonicalName,
            String binaryName,
            String signature,
            SourceSpan span,
            Origin origin,
            int superclass,
            List<Integer> interfaces,
            List<Integer> permitted,
            List<JavaAnnotation> annotations,
            JavaBinaryType.Value constant,
            JavaBinaryType.Value annotationDefault,
            List<JavaDeclaration> members,
            List<String> incompleteReasons,
            JavaOverrides overrides) {
        this(
                symbol,
                canonicalName,
                binaryName,
                signature,
                span,
                origin,
                superclass,
                interfaces,
                permitted,
                annotations,
                constant,
                annotationDefault,
                members,
                incompleteReasons,
                overrides,
                annotations.stream()
                        .anyMatch(
                                annotation ->
                                        annotation.descriptor().equals("Ljava/lang/Deprecated;")));
    }

    public JavaDeclaration(
            JavaSymbol symbol,
            String canonicalName,
            String binaryName,
            String signature,
            SourceSpan span,
            Origin origin,
            int superclass,
            List<Integer> interfaces,
            List<Integer> permitted,
            List<JavaAnnotation> annotations,
            JavaBinaryType.Value constant,
            JavaBinaryType.Value annotationDefault,
            List<JavaDeclaration> members,
            List<String> incompleteReasons) {
        this(
                symbol,
                canonicalName,
                binaryName,
                signature,
                span,
                origin,
                superclass,
                interfaces,
                permitted,
                annotations,
                constant,
                annotationDefault,
                members,
                incompleteReasons,
                JavaOverrides.EMPTY);
    }

    public boolean complete() {
        return incompleteReasons.isEmpty() && members.stream().allMatch(JavaDeclaration::complete);
    }

    public void validate(JavaTypes types) {
        types.validateReference(symbol.declaredType());
        types.validateReference(superclass);
        for (int reference : interfaces) types.get(reference);
        for (int reference : permitted) types.get(reference);
        for (JavaDeclaration member : members) member.validate(types);
        for (var target : overrides.targets()) types.get(target.symbol().declaredType());
    }
}
