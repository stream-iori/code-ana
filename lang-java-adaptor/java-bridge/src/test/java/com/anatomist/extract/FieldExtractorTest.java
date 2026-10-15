package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.JavaExtensionData;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

class FieldExtractorTest {

    @Test
    void emitsFieldNodeAndContainsEdge(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                JavaFactsTestSupport.declarations(
                        directory,
                        "A.java",
                        "package pkg; public class A { private String name; }");
        fieldsOnly(r);

        assertEquals(1, r.nodes.size());
        Node n = r.nodes.get(0);
        assertEquals("pkg.A#name", n.id);
        assertEquals("FIELD", n.kind);
        assertTrue(n.metadata.contains("\"type\":\"java.lang.String\""), n.metadata);

        assertEquals(1, r.edges.size());
        Edge e = r.edges.get(0);
        assertEquals("pkg.A", e.sourceId);
        assertEquals("pkg.A#name", e.targetId);
        assertEquals("CONTAINS", e.relation);
    }

    @Test
    void emitsMultiVariableField(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                JavaFactsTestSupport.declarations(
                        directory, "A.java", "package pkg; public class A { int a, b, c; }");
        fieldsOnly(r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertEquals(Set.of("pkg.A#a", "pkg.A#b", "pkg.A#c"), ids);
    }

    @Test
    void fieldInitializerOwnerMatchesEachFieldNodeIncludingAnonymousClass(@TempDir Path directory)
            throws Exception {
        var result =
                JavaFactsTestSupport.extract(
                        directory,
                        "A.java",
                        """
                package pkg;
                class A {
                  static class Counter { Counter(int value) {} }
                  Counter normal = new Counter(3);
                  void init() {
                    Runnable task = new Runnable() {
                      Counter first = new Counter(1), second = new Counter(2);
                      public void run() {}
                    };
                  }
                }
                """);
        var fields =
                result.nodes.stream()
                        .filter(node -> "FIELD".equals(node.kind))
                        .map(node -> node.id)
                        .collect(Collectors.toSet());
        var calls =
                result.edges.stream()
                        .filter(
                                edge ->
                                        "CALLS".equals(edge.relation)
                                                && "pkg.A.Counter#Counter(int)"
                                                        .equals(edge.targetId))
                        .toList();
        assertEquals(3, calls.size());
        for (var call : calls) assertTrue(fields.contains(call.sourceId), call.sourceId);
        assertTrue(calls.stream().anyMatch(call -> call.sourceId.equals("pkg.A#normal")));
        assertTrue(calls.stream().anyMatch(call -> call.sourceId.endsWith("#first")));
        assertTrue(calls.stream().anyMatch(call -> call.sourceId.endsWith("#second")));
    }

    @Test
    void emitsExtensionMetadataOnEveryVariableInAnnotatedDeclaration(@TempDir Path directory)
            throws Exception {
        Map<String, Object> extensionMetadata = Map.of("lombok", Map.of("coverage", "complete"));
        var extensions =
                new JavaExtensionData(
                        Map.of(),
                        Map.of("pkg.A#first", extensionMetadata, "pkg.A#second", extensionMetadata),
                        List.of(),
                        Map.of());
        var result =
                JavaFactsTestSupport.declarations(
                        directory,
                        "A.java",
                        "package pkg; public class A { String first, second; }",
                        extensions);
        fieldsOnly(result);
        assertEquals(2, result.nodes.size());

        for (Node node : result.nodes) {
            Map<?, ?> metadata = (Map<?, ?>) Json.parseTree(node.metadata);
            assertEquals(Map.of("coverage", "complete"), metadata.get("lombok"));
            assertEquals("java.lang.String", metadata.get("type"));
        }
    }

    @Test
    void emitsEnumConstant(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                JavaFactsTestSupport.declarations(
                        directory, "E.java", "package pkg; public enum E { ALPHA, BETA }");
        fieldsOnly(r);

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.E#ALPHA"), "got " + ids);
        assertTrue(ids.contains("pkg.E#BETA"));
        long enumKindCount = r.nodes.stream().filter(n -> "ENUM_CONSTANT".equals(n.kind)).count();
        assertEquals(2, enumKindCount);
    }

    @Test
    void extract_recordComponents_emitsFieldNodes(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                JavaFactsTestSupport.declarations(
                        directory,
                        "Point.java",
                        "package pkg; public record Point(int x, int y) {}");

        Set<String> ids =
                r.nodes.stream()
                        .filter(n -> "FIELD".equals(n.kind))
                        .map(n -> n.id)
                        .collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.Point#x"), "got " + ids);
        assertTrue(ids.contains("pkg.Point#y"), "got " + ids);
        long containsX =
                r.edges.stream()
                        .filter(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && "pkg.Point".equals(e.sourceId)
                                                && "pkg.Point#x".equals(e.targetId))
                        .count();
        assertEquals(1, containsX, "got " + r.edges);
        Set<String> methods =
                r.nodes.stream()
                        .filter(n -> "METHOD".equals(n.kind))
                        .map(n -> n.id)
                        .collect(Collectors.toSet());
        assertTrue(methods.contains("pkg.Point#x()"), methods.toString());
        assertTrue(methods.contains("pkg.Point#y()"), methods.toString());
        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                n ->
                                        "CONSTRUCTOR".equals(n.kind)
                                                && "pkg.Point#Point(int,int)".equals(n.id)),
                r.nodes.toString());
    }

    @Test
    void explicitRecordAccessor_isNotSynthesizedTwice(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                JavaFactsTestSupport.declarations(
                        directory,
                        "Name.java",
                        "package pkg; public record Name(String value) {"
                                + " public String value() { return value.trim(); }"
                                + "}");

        assertEquals(1, r.nodes.stream().filter(n -> "pkg.Name#value()".equals(n.id)).count());
        assertTrue(
                r.declarations.stream()
                        .filter(d -> d.symbolId.equals("pkg.Name#value()"))
                        .allMatch(d -> !d.synthetic));
    }

    private static void fieldsOnly(ExtractionResult result) {
        Set<String> kinds = Set.of("FIELD", "ENUM_CONSTANT");
        result.nodes.removeIf(node -> !kinds.contains(node.kind));
        Set<String> ids = result.nodes.stream().map(node -> node.id).collect(Collectors.toSet());
        result.edges.removeIf(edge -> !ids.contains(edge.targetId));
    }
}
