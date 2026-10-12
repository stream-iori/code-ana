package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.json.Json;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class DeclarationMetadataContractIT {
    @Test
    void originalHierarchyAndAnnotationMetadataRemainVisibleAcrossJdks(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            var facts =
                    JavaFactsTestSupport.extract(
                            project,
                            "Use.java",
                            """
 package p;
 class Parent {}
 interface First {}
 interface Second extends First {}
 class Child extends Parent implements Second { void run() {} }
 @interface Mark { String name(); }
 class Use { Object callback(){return new Runnable(){ public void run(){} };} Object create(){ return new Base("x") {}; } }
 class Base { Base(String value) {} }
 """,
                            release);
            assertEquals("Parent", metadata(facts, "p.Child").get("superClass"));
            assertEquals(List.of("Second"), metadata(facts, "p.Child").get("interfaces"));
            assertEquals(List.of("First"), metadata(facts, "p.Second").get("interfaces"));
            assertFalse(metadata(facts, "p.Parent").containsKey("superClass"));
            assertEquals(true, metadata(facts, "p.Mark#name()").get("isAnnotationMember"));
            var anonymous =
                    facts.nodes.stream()
                            .filter(n -> n.kind.equals("ANONYMOUS_CLASS"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "java.lang.Runnable",
                    ((Map<?, ?>) Json.parseTree(anonymous.metadata)).get("baseType"));
            var creation =
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && "p.Use#create()".equals(edge.sourceId))
                            .findFirst()
                            .orElseThrow();
            assertTrue(creation.targetId.contains("$anon@"), creation.targetId);
            assertTrue(creation.targetId.endsWith("#$anon(java.lang.String)"), creation.targetId);
            assertEquals("EXTRACTED", creation.confidence);
            assertTrue(
                    facts.declarations.stream()
                            .filter(d -> d.declarationKind.equals("type"))
                            .noneMatch(d -> d.directMember));
            assertTrue(
                    facts.declarations.stream()
                            .anyMatch(d -> d.symbolId.equals("p.Child#run()") && d.directMember));
        }
    }

    @Test
    void writtenMissingFieldTypeRetainsDeclarationButIncompleteEvidence(@TempDir Path directory)
            throws Exception {
        var facts =
                JavaFactsTestSupport.extract(directory, "Use.java", "class Use { Missing value; }");
        var metadata = metadata(facts, "Use#value");
        assertEquals("Missing", metadata.get("type"));
        var declaration =
                facts.declarations.stream()
                        .filter(d -> d.symbolId.equals("Use#value"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(declaration.bindingResolved);
        assertEquals(false, metadata.get("evidenceComplete"));
    }

    @Test
    void enumConstantClassBodyIsAnonymousAndKeepsItsActualBase(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            var facts =
                    JavaFactsTestSupport.extract(
                            Files.createDirectory(directory.resolve("jdk" + release)),
                            "Choice.java",
                            "package p; enum Choice { FIRST { @Override int value(){return 1;} }; abstract int value(); }",
                            release);
            var anonymous =
                    facts.nodes.stream()
                            .filter(
                                    node ->
                                            node.id.contains("$anon@")
                                                    && node.kind.equals("ANONYMOUS_CLASS"))
                            .findFirst()
                            .orElseThrow();
            var details = (Map<?, ?>) Json.parseTree(anonymous.metadata);
            assertEquals("p.Choice", details.get("baseType"));
            assertFalse(details.containsKey("constants"));
            assertEquals(1, facts.nodes.stream().filter(node -> node.kind.equals("ENUM")).count());
            assertTrue(
                    facts.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("OVERRIDES")
                                                    && edge.targetId.equals("p.Choice#value()")));
        }
    }

    @Test
    void sourceWrittenPlatformTypesStayPositionedDespiteAggregateExclusion(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            var facts =
                    JavaFactsTestSupport.extract(
                            Files.createDirectory(directory.resolve("jdk" + release)),
                            "Use.java",
                            "package p; class Use { String name; java.lang.String copy(String input){return input;} }",
                            release);
            var references =
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("REFERENCES")
                                                    && "java.lang.String"
                                                            .equals(edge.externalTargetFqn))
                            .toList();
            assertTrue(
                    references.stream().anyMatch(edge -> "p.Use#name".equals(edge.sourceId)),
                    references.toString());
            assertTrue(
                    references.stream()
                            .anyMatch(edge -> "p.Use#copy(java.lang.String)".equals(edge.sourceId)),
                    references.toString());
            assertTrue(
                    references.stream()
                            .allMatch(edge -> edge.beginLine != null && edge.beginColumn != null),
                    references.toString());
            assertTrue(
                    references.stream()
                            .allMatch(
                                    edge ->
                                            com.anatomist.model.ProducerIds.JAVA_CORE.equals(
                                                    edge.producerId)),
                    references.toString());
        }
    }

    private static Map<?, ?> metadata(ExtractionResult facts, String id) {
        return (Map<?, ?>)
                Json.parseTree(
                        facts.nodes.stream()
                                .filter(n -> n.id.equals(id))
                                .findFirst()
                                .orElseThrow()
                                .metadata);
    }
}
