package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class NodeIdGeneratorTest {
    @TempDir Path directory;

    @Test
    void forTypePreservesCaseAndNestedOwnership() throws Exception {
        var facts =
                JavaFactsTestSupport.declarations(
                        directory,
                        "Order.java",
                        "package pkg; public class Order { public static class Sub {} }");
        assertTrue(facts.nodes.stream().anyMatch(node -> node.id.equals("pkg.Order")));
        assertTrue(facts.nodes.stream().anyMatch(node -> node.id.equals("pkg.Order.Sub")));
        assertFalse(facts.nodes.stream().anyMatch(node -> node.id.equals("pkg.order")));
    }

    @Test
    void forMethodUsesErasedSignature() throws Exception {
        var facts =
                JavaFactsTestSupport.declarations(
                        directory,
                        "A.java",
                        "package pkg; import java.util.List; public class A { public void foo(String s, List<Integer> xs) {} public void foo() {} }");
        assertEquals(2, facts.nodes.stream().filter(node -> node.label.equals("foo")).count());
        assertTrue(
                facts.nodes.stream()
                        .anyMatch(
                                node ->
                                        node.id.equals(
                                                "pkg.A#foo(java.lang.String,java.util.List)")));
        assertTrue(facts.nodes.stream().anyMatch(node -> node.id.equals("pkg.A#foo()")));
    }

    @Test
    void forLambdaConcatenatesParentWithOriginalLineAndColumn() throws Exception {
        var facts = callbacks();
        assertTrue(
                facts.nodes.stream().anyMatch(node -> node.id.equals("pkg.A#foo()$lambda@L3C19")));
    }

    @Test
    void forMethodRefConcatenatesParentWithOriginalLineAndColumn() throws Exception {
        var facts = callbacks();
        assertTrue(
                facts.nodes.stream()
                        .anyMatch(node -> node.id.equals("pkg.A#foo()$methodref@L4C20")));
    }

    @Test
    void callbackIdentitiesAreStableAcrossIndependentAnalyses() throws Exception {
        var first =
                callbacks().nodes.stream()
                        .filter(node -> node.id.contains("$lambda@"))
                        .map(node -> node.id)
                        .toList();
        var second =
                callbacks().nodes.stream()
                        .filter(node -> node.id.contains("$lambda@"))
                        .map(node -> node.id)
                        .toList();
        assertFalse(first.isEmpty());
        assertEquals(first, second);
    }

    private ExtractionResult callbacks() throws Exception {
        return JavaFactsTestSupport.extract(
                directory,
                "A.java",
                """
                package pkg;
                class A { void foo() {
                  Runnable task = () -> {};
                  Runnable other = this::foo;
                } }
                """);
    }
}
