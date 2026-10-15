package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalMethodCatalogTest {
    @TempDir Path directory;

    @Test
    void sourceMembersDoNotLeakIntoNextAnalysisWithSameTypeName() throws Exception {
        var first =
                JavaFactsTestSupport.analyze(
                                directory,
                                "A.java",
                                "class A { int value() { return 1; } int run() { return value(); } }")
                        .units()
                        .getFirst();
        var second =
                JavaFactsTestSupport.analyze(
                                directory,
                                "A.java",
                                "class A { String value() { return null; } String run() { return value(); } }")
                        .units()
                        .getFirst();
        var firstCall = call(first, "value");
        var secondCall = call(second, "value");
        assertTrue(firstCall.declarationResolved());
        assertTrue(secondCall.declarationResolved());
        assertEquals("int", JavaTypeNames.erased(first.types(), firstCall.expressionType()));
        assertEquals(
                "java.lang.String",
                JavaTypeNames.erased(second.types(), secondCall.expressionType()));
        assertEquals("int", JavaTypeNames.erased(first.types(), firstCall.expressionType()));
        assertNotEquals(first.sourceHash(), second.sourceHash());
    }

    @Test
    void libraryDeclarationsRemainStableAcrossIndependentCompilationUnits() throws Exception {
        var first =
                JavaFactsTestSupport.analyze(
                                directory,
                                "A.java",
                                "class A { String run(String s) { return s.trim(); } }")
                        .units()
                        .getFirst();
        var second =
                JavaFactsTestSupport.analyze(
                                directory,
                                "B.java",
                                "class B { String run(String s) { return s.trim(); } }")
                        .units()
                        .stream()
                        .filter(unit -> unit.path().getFileName().toString().equals("B.java"))
                        .findFirst()
                        .orElseThrow();
        var original = call(first, "trim");
        var next = call(second, "trim");
        assertTrue(original.declarationResolved());
        assertTrue(next.declarationResolved());
        assertEquals("java.lang.String", original.owner());
        assertEquals(original.owner(), next.owner());
        assertEquals(original.descriptor(), next.descriptor());
        assertEquals(
                "java.lang.String", JavaTypeNames.erased(second.types(), next.expressionType()));
        assertSame(original, call(first, "trim"));
    }

    private static JavaBinding call(JavaUnit unit, String name) {
        return new JavaSyntax(unit.tree())
                .nodes().stream()
                        .filter(
                                node ->
                                        node.kind().equals("METHOD_INVOCATION")
                                                && node.span().present())
                        .map(node -> unit.bindings().get(node.id()))
                        .filter(binding -> binding != null && binding.name().equals(name))
                        .findFirst()
                        .orElseThrow();
    }
}
