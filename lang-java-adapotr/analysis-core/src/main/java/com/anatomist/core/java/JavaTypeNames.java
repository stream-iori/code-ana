package com.anatomist.core.java;

import java.util.HashSet;
import java.util.Set;

/** Stable names for IDs, separated from compiler display text and instantiated signatures. */
public final class JavaTypeNames {
    private JavaTypeNames() {}

    public static String erased(JavaTypes types, int reference) {
        return erased(types, reference, new HashSet<>(), 0, false);
    }

    public static String binaryErased(JavaTypes types, int reference) {
        return erased(types, reference, new HashSet<>(), 0, true);
    }

    private static String erased(
            JavaTypes types, int reference, Set<Integer> active, int depth, boolean binary) {
        if (reference < 0 || depth > 128 || !active.add(reference)) return "<unresolved>";
        JavaType type = types.get(reference);
        try {
            return switch (type.kind()) {
                case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID ->
                        type.kind().name().toLowerCase(java.util.Locale.ROOT);
                case DECLARED ->
                        binary && !type.binaryName().isEmpty() || type.name().isEmpty()
                                ? type.binaryName()
                                : type.name();
                case ARRAY -> erased(types, type.component(), active, depth + 1, binary) + "[]";
                case TYPEVAR, INTERSECTION ->
                        type.upperBounds().isEmpty()
                                ? "<unresolved>"
                                : erased(
                                        types,
                                        type.upperBounds().getFirst(),
                                        active,
                                        depth + 1,
                                        binary);
                case WILDCARD ->
                        type.upperBounds().isEmpty()
                                ? "java.lang.Object"
                                : erased(
                                        types,
                                        type.upperBounds().getFirst(),
                                        active,
                                        depth + 1,
                                        binary);
                default -> "<unresolved>";
            };
        } finally {
            active.remove(reference);
        }
    }

    public static String callableId(JavaTypes types, JavaSymbol symbol) {
        return callableId(types, symbol, symbol.owner(), false);
    }

    /** External physical declarations retain binary nested names and array parameter spelling. */
    public static String binaryCallableId(JavaTypes types, JavaSymbol symbol, String binaryOwner) {
        return callableId(
                types, symbol, binaryOwner.isEmpty() ? symbol.owner() : binaryOwner, true);
    }

    private static String callableId(
            JavaTypes types, JavaSymbol symbol, String owner, boolean binary) {
        if (!(symbol.kind().equals("METHOD") || symbol.kind().equals("CONSTRUCTOR")))
            throw new IllegalArgumentException("not a callable symbol");
        if (owner.isEmpty()) throw new IllegalArgumentException("callable owner is unavailable");
        JavaType method = types.get(symbol.declaredType());
        if (method.kind() != JavaType.Kind.EXECUTABLE)
            throw new IllegalArgumentException("callable type is unavailable");
        String name =
                symbol.kind().equals("CONSTRUCTOR")
                        ? symbol.owner().substring(symbol.owner().lastIndexOf('.') + 1)
                        : symbol.name();
        return owner
                + "#"
                + name
                + "("
                + String.join(
                        ",",
                        method.parameters().stream()
                                .map(
                                        parameter ->
                                                binary
                                                        ? binaryErased(types, parameter)
                                                        : erased(types, parameter))
                                .toList())
                + ")";
    }
}
