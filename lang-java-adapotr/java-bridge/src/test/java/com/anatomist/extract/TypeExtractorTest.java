package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.JavaExtensionData;
import com.anatomist.json.Json;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class TypeExtractorTest {

    @Test
    void extract_emitsClassNode(@TempDir Path directory) throws Exception {
        ExtractionResult result =
                types(directory, "Order.java", "package pkg; public class Order {}");

        assertEquals(1, result.nodes.size());
        Node n = result.nodes.get(0);
        assertEquals("pkg.Order", n.id);
        assertEquals("CLASS", n.kind);
        assertEquals("Order", n.label);
        assertEquals("pkg", n.pkg);
        assertTrue(n.metadata.contains("\"isAbstract\":false"));
        assertTrue(n.metadata.contains("\"isInterface\":false"));
    }

    @Test
    void extract_emitsInterfaceAndEnum(@TempDir Path directory) throws Exception {
        ExtractionResult result = types(directory, "I.java", "package pkg; public interface I {}");
        result.addFacts(types(directory, "E.java", "package pkg; public enum E { A, B }"));

        Node i = byId(result, "pkg.I");
        Node e = byId(result, "pkg.E");
        assertEquals("INTERFACE", i.kind);
        assertEquals("ENUM", e.kind);
        assertTrue(e.metadata.contains("\"constants\":[\"A\",\"B\"]"), "got: " + e.metadata);
    }

    @Test
    void extract_emitsNestedTypes(@TempDir Path directory) throws Exception {
        ExtractionResult result =
                types(
                        directory,
                        "A.java",
                        "package pkg; public class A { public static class B {} }");

        assertEquals(2, result.nodes.size());
        Optional<Node> outer = result.nodes.stream().filter(n -> n.id.equals("pkg.A")).findFirst();
        Optional<Node> inner =
                result.nodes.stream().filter(n -> n.id.equals("pkg.A.B")).findFirst();
        assertTrue(outer.isPresent());
        assertTrue(inner.isPresent());
    }

    @Test
    void extract_mergesNamespacedExtensionMetadata(@TempDir Path directory) throws Exception {
        var extensions =
                new JavaExtensionData(
                        Map.of(),
                        Map.of("pkg.A", Map.of("lombok", Map.of("coverage", "complete"))),
                        List.of(),
                        Map.of());
        ExtractionResult result =
                JavaFactsTestSupport.declarations(
                        directory, "A.java", "package pkg; public class A {}", extensions);

        Map<?, ?> metadata = (Map<?, ?>) Json.parseTree(byId(result, "pkg.A").metadata);
        assertEquals(Map.of("coverage", "complete"), metadata.get("lombok"));
        assertEquals(false, metadata.get("isAbstract"));
    }

    @Test
    void visit_recordDeclaration_emitsRecordNode(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                types(directory, "Point.java", "package pkg; public record Point(int x, int y) {}");

        Node rec =
                r.nodes.stream()
                        .filter(n -> "RECORD".equals(n.kind))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no RECORD; got " + r.nodes));
        assertEquals("pkg.Point", rec.id);
    }

    @Test
    void visit_anonymousClassInsideUnresolvedAnonymousMethod_doesNotAbort(@TempDir Path directory)
            throws Exception {
        String source =
                """
                package pkg;
                public class A {
                  void outer() {
                    new MissingCallback() {
                      public void done() {
                        new TypeReference<String>() {};
                      }
                    };
                  }
                }
                """;
        ExtractionResult r = assertDoesNotThrow(() -> types(directory, "A.java", source));

        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                n ->
                                        "ANONYMOUS_CLASS".equals(n.kind)
                                                && n.id.startsWith("pkg.A#outer()$anon@L")
                                                && n.id.contains("#done()$anon@L")),
                "nested anonymous class should use fallback method owner; got " + r.nodes);
    }

    private static ExtractionResult types(Path directory, String name, String source)
            throws Exception {
        var result = JavaFactsTestSupport.declarations(directory, name, source);
        Set<String> kinds =
                Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD", "ANONYMOUS_CLASS");
        result.nodes.removeIf(node -> !kinds.contains(node.kind));
        return result;
    }

    private static Node byId(ExtractionResult r, String id) {
        return r.nodes.stream()
                .filter(n -> n.id.equals(id))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("no node with id " + id + "; got " + r.nodes));
    }
}
