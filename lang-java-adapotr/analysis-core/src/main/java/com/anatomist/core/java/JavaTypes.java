package com.anatomist.core.java;

import java.util.List;

/** Immutable compiler type graph; -1 means that no type was supplied, not java.lang.Object. */
public record JavaTypes(List<JavaType> entries) {
    public static final JavaTypes EMPTY = new JavaTypes(List.of());

    public JavaTypes {
        entries = List.copyOf(entries);
        int size = entries.size();
        for (JavaType type : entries) {
            check(type.enclosing(), size);
            check(type.lowerBound(), size);
            check(type.component(), size);
            check(type.returns(), size);
            for (int ref : type.arguments()) checkRequired(ref, size);
            for (int ref : type.upperBounds()) checkRequired(ref, size);
            for (int ref : type.parameters()) checkRequired(ref, size);
            for (int ref : type.exceptions()) checkRequired(ref, size);
        }
    }

    public JavaType get(int reference) {
        checkRequired(reference, entries.size());
        return entries.get(reference);
    }

    public void validateReference(int reference) {
        check(reference, entries.size());
    }

    private static void check(int reference, int size) {
        if (reference != -1) checkRequired(reference, size);
    }

    private static void checkRequired(int reference, int size) {
        if (reference < 0 || reference >= size)
            throw new IllegalArgumentException("unknown compiler type reference: " + reference);
    }
}
