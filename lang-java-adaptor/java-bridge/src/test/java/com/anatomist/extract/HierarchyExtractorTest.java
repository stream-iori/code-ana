package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class HierarchyExtractorTest {
    @TempDir Path directory;

    @Test
    void enumInterfacesDoNotRequireClassConversion() throws Exception {
        var result =
                JavaFactsTestSupport.extract(
                        directory,
                        "E.java",
                        "package p; enum E implements java.io.Serializable { A }");
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "IMPLEMENTS".equals(edge.relation)
                                                && "java.io.Serializable"
                                                        .equals(edge.externalTargetFqn)));
    }

    @Test
    void emitsInheritsAndImplements_external() throws Exception {
        String source =
                "package pkg;\n"
                        + "import java.util.AbstractList;\n"
                        + "import java.io.Serializable;\n"
                        + "public class A extends AbstractList<String> implements Serializable {\n"
                        + "  public String get(int i) { return null; }\n"
                        + "  public int size() { return 0; }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        boolean inheritsAbstractList =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "INHERITS".equals(e.relation)
                                                && e.isExternal
                                                && "java.util.AbstractList"
                                                        .equals(e.externalTargetFqn));
        boolean implementsSerializable =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "IMPLEMENTS".equals(e.relation)
                                                && e.isExternal
                                                && "java.io.Serializable"
                                                        .equals(e.externalTargetFqn));
        assertTrue(inheritsAbstractList, "INHERITS edge missing; got " + r.edges);
        assertTrue(implementsSerializable, "IMPLEMENTS edge missing; got " + r.edges);
    }

    @Test
    void emitsOverridesEdge() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  @Override public String toString() { return \"a\"; }\n"
                        + "  @Override public int hashCode() { return 42; }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        long overrides =
                r.edges.stream()
                        .filter(e -> "OVERRIDES".equals(e.relation))
                        .filter(e -> e.isExternal)
                        .filter(
                                e ->
                                        e.externalTargetFqn != null
                                                && e.externalTargetFqn.startsWith(
                                                        "java.lang.Object#"))
                        .count();
        assertTrue(overrides >= 2, "expected ≥2 OVERRIDES to Object; got " + r.edges);
    }

    @Test
    void interfaceExtendsInterface_emitsInherits() throws Exception {
        String source =
                "package pkg;\n"
                        + "import java.io.Closeable;\n"
                        + "public interface I extends Closeable {}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "I.java", source);

        boolean inheritsCloseable =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "INHERITS".equals(e.relation)
                                                && e.isExternal
                                                && "java.io.Closeable".equals(e.externalTargetFqn));
        assertTrue(inheritsCloseable, "interface→interface INHERITS missing; got " + r.edges);
    }

    @Test
    void incompleteHierarchyRetainsDeclarationsWithoutClaimingExactOverride() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A implements I, MissingBase {\n"
                        + "  public void run(Missing m) {}\n"
                        + "}\n"
                        + "interface I { void run(Missing m); }\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        assertTrue(r.nodes.stream().anyMatch(node -> "pkg.A#run(Missing)".equals(node.id)));
        assertTrue(r.nodes.stream().anyMatch(node -> "pkg.I#run(Missing)".equals(node.id)));
        assertFalse(
                r.edges.stream()
                        .anyMatch(
                                edge ->
                                        "OVERRIDES".equals(edge.relation)
                                                && "EXTRACTED".equals(edge.confidence)
                                                && "pkg.A#run(Missing)".equals(edge.sourceId)
                                                && "pkg.I#run(Missing)".equals(edge.targetId)),
                r.edges.toString());
    }
}
