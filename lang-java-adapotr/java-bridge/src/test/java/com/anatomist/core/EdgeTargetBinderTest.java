package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;

import java.util.Set;

class EdgeTargetBinderTest {

    @Test
    void bindsExternalEdgeWhenTargetFqnMatchesNodeId() {
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(node("com.example.Service"));

        Edge edge = new Edge();
        edge.sourceId = "com.example.ServiceImpl";
        edge.relation = "IMPLEMENTS";
        edge.externalTargetFqn = "com.example.Service";
        edge.isExternal = true;
        result.edges.add(edge);

        int rebound = EdgeTargetBinder.bindExternalTargets(result);

        assertEquals(1, rebound);
        assertFalse(edge.isExternal);
        assertEquals("com.example.Service", edge.targetId);
        assertNull(edge.externalTargetFqn);
    }

    @Test
    void includesExistingDbNodeIdsForIncrementalBatches() {
        ExtractionResult result = new ExtractionResult();

        Edge edge = new Edge();
        edge.sourceId = "com.example.Caller#run()";
        edge.relation = "CALLS";
        edge.externalTargetFqn = "com.example.Repository#save()";
        edge.isExternal = true;
        result.edges.add(edge);

        int rebound =
                EdgeTargetBinder.bindExternalTargets(
                        result, Set.of("com.example.Repository#save()"));

        assertEquals(1, rebound);
        assertFalse(edge.isExternal);
        assertEquals("com.example.Repository#save()", edge.targetId);
        assertNull(edge.externalTargetFqn);
    }

    @Test
    void leavesTrueExternalEdgesUntouched() {
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(node("com.example.Service"));

        Edge edge = new Edge();
        edge.sourceId = "com.example.Service#run()";
        edge.relation = "CALLS";
        edge.externalTargetFqn = "java.lang.Math#abs(int)";
        edge.isExternal = true;
        result.edges.add(edge);

        int rebound = EdgeTargetBinder.bindExternalTargets(result);

        assertEquals(0, rebound);
        assertTrue(edge.isExternal);
        assertNull(edge.targetId);
        assertEquals("java.lang.Math#abs(int)", edge.externalTargetFqn);
    }

    @Test
    void bindsExternalMethodWhenOwnerNameAndArityAreUnique() {
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(
                node("com.example.Template#execute(com.example.Base,com.example.Callback)"));

        Edge edge = new Edge();
        edge.sourceId = "com.example.Service#run()";
        edge.relation = "CALLS";
        edge.externalTargetFqn =
                "com.example.Template#execute(com.example.Child,com.example.Callback)";
        edge.isExternal = true;
        result.edges.add(edge);

        int rebound = EdgeTargetBinder.bindExternalTargets(result);

        assertEquals(1, rebound);
        assertFalse(edge.isExternal);
        assertEquals(
                "com.example.Template#execute(com.example.Base,com.example.Callback)",
                edge.targetId);
        assertNull(edge.externalTargetFqn);
    }

    @Test
    void doesNotBindAmbiguousMethodArity() {
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(
                node("com.example.Template#execute(com.example.Base,com.example.Callback)"));
        result.nodes.add(
                node("com.example.Template#execute(com.example.Other,com.example.Callback)"));

        Edge edge = new Edge();
        edge.sourceId = "com.example.Service#run()";
        edge.relation = "CALLS";
        edge.externalTargetFqn =
                "com.example.Template#execute(com.example.Child,com.example.Callback)";
        edge.isExternal = true;
        result.edges.add(edge);

        int rebound = EdgeTargetBinder.bindExternalTargets(result);

        assertEquals(0, rebound);
        assertTrue(edge.isExternal);
        assertNull(edge.targetId);
        assertEquals(
                "com.example.Template#execute(com.example.Child,com.example.Callback)",
                edge.externalTargetFqn);
    }

    @Test
    void bindsReflectionBinaryNestedNameToUniqueCanonicalNode() {
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(node("com.example.Outer.Inner"));

        Edge edge = new Edge();
        edge.sourceId = "com.example.Caller#run()";
        edge.relation = "REFERENCES";
        edge.externalTargetFqn = "com.example.Outer$Inner";
        edge.isExternal = true;
        edge.metadata = "{\"via\":\"reflection\"}";
        result.edges.add(edge);

        int rebound = EdgeTargetBinder.bindExternalTargets(result);

        assertEquals(1, rebound);
        assertFalse(edge.isExternal);
        assertEquals("com.example.Outer.Inner", edge.targetId);
        assertNull(edge.externalTargetFqn);
    }

    private static Node node(String id) {
        Node n = new Node();
        n.id = id;
        n.qualifiedName = id;
        n.kind = id.contains("#") ? "METHOD" : "INTERFACE";
        return n;
    }
}
