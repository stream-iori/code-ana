package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anatomist.model.Annotation;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;

import java.util.Set;

class GraphPostProcessorTest {

    @Test
    void fullIndex_rebindsExternalEdgesAndPrunesDanglingFacts() {
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(node("p.A", "A.java"));
        r.nodes.add(node("p.B#m()", "B.java"));

        Edge externalToInternal =
                Edge.externalCall("p.A", "p.B#m()", GraphConstants.CallKind.INSTANCE, "L1");
        Edge missingTarget =
                Edge.call("p.A", "p.Missing#m()", GraphConstants.CallKind.INSTANCE, "L2");
        Edge missingSource =
                Edge.call("p.Missing#caller()", "p.B#m()", GraphConstants.CallKind.INSTANCE, "L3");
        r.edges.add(externalToInternal);
        r.edges.add(missingTarget);
        r.edges.add(missingSource);

        Annotation badAnnotation = new Annotation();
        badAnnotation.nodeId = "p.Missing";
        badAnnotation.annotationFqn = "Deprecated";
        r.annotations.add(badAnnotation);

        GraphPostProcessor.Summary s = new GraphPostProcessor().process(r);

        assertEquals(1, s.reboundExternalTargets());
        assertEquals(3, s.droppedDanglingFacts());
        assertFalse(externalToInternal.isExternal);
        assertEquals("p.B#m()", externalToInternal.targetId);
        assertEquals(1, r.edges.size());
        assertTrue(r.annotations.isEmpty());
    }

    @Test
    void incrementalIndex_keepsEdgesPointingToSurvivingDbNodes() {
        ExtractionResult r = new ExtractionResult();
        r.nodes.add(node("p.A#m()", "A.java"));
        r.edges.add(Edge.call("p.A#m()", "p.B#m()", GraphConstants.CallKind.INSTANCE, "L1"));

        GraphPostProcessor.Summary s = new GraphPostProcessor().process(r, Set.of("p.B#m()"));

        assertEquals(0, s.droppedDanglingFacts());
        assertEquals("p.B#m()", r.edges.get(0).targetId);
    }

    private static Node node(String id, String sourceFile) {
        Node n = new Node();
        n.id = id;
        n.label = id.substring(id.lastIndexOf('.') + 1);
        n.kind = id.contains("#") ? GraphConstants.Kind.METHOD : GraphConstants.Kind.CLASS;
        n.qualifiedName = id;
        n.pkg = "p";
        n.sourceFile = sourceFile;
        n.sourceLocation = "L1";
        n.scope = GraphConstants.Scope.MAIN;
        return n;
    }
}
