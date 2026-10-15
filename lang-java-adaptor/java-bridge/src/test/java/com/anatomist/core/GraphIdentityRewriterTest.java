package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

class GraphIdentityRewriterTest {

    @Test
    void duplicateCandidatesWithSameStorageKeyRemainBindable(@TempDir Path project) {
        Path sourceRoot = project.resolve("app/src/main/java");
        String sourceFile = "app/src/main/java/p/A.java";
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(node("p.A", sourceFile));
        result.nodes.add(node("p.A", sourceFile));
        result.nodes.add(node("p.A#run()", sourceFile));
        result.nodes.add(node("p.A#run()", sourceFile));
        Edge contains = new Edge();
        contains.sourceId = "p.A";
        contains.targetId = "p.A#run()";
        contains.relation = GraphConstants.Relation.CONTAINS;
        contains.confidence = GraphConstants.Confidence.EXTRACTED;
        contains.isExternal = false;
        result.edges.add(contains);

        SourceIdentityResolver resolver =
                SourceIdentityResolver.fromRoots(
                        project, List.of(new SourceRoot(sourceRoot, "app", SourceScope.MAIN)));
        GraphIdentityRewriter.rewrite(result, resolver, Set.of());

        assertEquals("java-core::app::MAIN::p.A", contains.sourceId);
        assertEquals("java-core::app::MAIN::p.A#run()", contains.targetId);
        assertEquals(sourceFile, contains.sourceFile);
    }

    @Test
    void sameLogicalSymbolFromDifferentProvidersDoesNotCrossBind(@TempDir Path project) {
        Path sourceRoot = project.resolve("app/src/main/java");
        String sourceFile = "app/src/main/java/p/Case.java";
        ExtractionResult result = new ExtractionResult();
        Node javaTarget = node("p.Shared", sourceFile);
        javaTarget.providerId = "java-core";
        javaTarget.language = "java";
        Node pythonTarget = node("p.Shared", sourceFile);
        pythonTarget.providerId = "python3-ast";
        pythonTarget.language = "python";
        Node pythonCaller = node("p.Caller#run()", sourceFile);
        pythonCaller.providerId = "python3-ast";
        pythonCaller.language = "python";
        result.nodes.addAll(List.of(javaTarget, pythonTarget, pythonCaller));
        Edge call = new Edge();
        call.sourceId = "p.Caller#run()";
        call.targetId = "p.Shared";
        call.relation = GraphConstants.Relation.CALLS;
        call.providerId = "python3-ast";
        call.language = "python";
        result.edges.add(call);

        GraphIdentityRewriter.rewrite(
                result,
                SourceIdentityResolver.fromRoots(
                        project, List.of(new SourceRoot(sourceRoot, "app", SourceScope.MAIN))),
                Set.of());

        assertEquals("python3-ast::app::MAIN::p.Caller#run()", call.sourceId);
        assertEquals("python3-ast::app::MAIN::p.Shared", call.targetId);
    }

    private static Node node(String id, String sourceFile) {
        Node node = new Node();
        node.id = id;
        node.label = id;
        node.kind = id.contains("#") ? GraphConstants.Kind.METHOD : GraphConstants.Kind.CLASS;
        node.qualifiedName = id;
        node.sourceFile = sourceFile;
        return node;
    }
}
