package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaType;
import com.anatomist.core.java.JavaTypes;
import com.anatomist.core.java.SourceSpan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class JavaModelTest {
    @Test
    void recursiveTypeReferencesAreValidButDanglingReferencesAreRejected() {
        JavaType variable = type(JavaType.Kind.TYPEVAR, "T", List.of(), List.of(1));
        JavaType bound = type(JavaType.Kind.DECLARED, "Comparable", List.of(0), List.of());
        var original = new ArrayList<>(List.of(variable, bound));
        JavaTypes graph = new JavaTypes(original);
        original.clear();
        assertEquals(2, graph.entries().size());
        assertSame(variable, graph.get(graph.get(1).arguments().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> new JavaTypes(List.of(variable)));
        assertThrows(IllegalArgumentException.class, () -> graph.validateReference(-2));
        assertThrows(IllegalArgumentException.class, () -> graph.get(-1));
        assertDoesNotThrow(() -> graph.validateReference(-1));
    }

    @Test
    void absentSourceIsDistinctFromTheBeginningOfARealFile() {
        assertFalse(SourceSpan.ABSENT.present());
        assertTrue(new SourceSpan(0, 0).present());
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(5, 4));
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(-1, 4));
    }

    private static JavaType type(
            JavaType.Kind kind, String name, List<Integer> arguments, List<Integer> bounds) {
        return new JavaType(
                kind, name, name, -1, arguments, bounds, -1, -1, List.of(), -1, List.of());
    }
}
