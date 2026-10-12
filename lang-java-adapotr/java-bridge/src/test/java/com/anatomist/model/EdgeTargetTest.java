package com.anatomist.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EdgeTargetTest {

    @Test
    void internalAndExternalTargetsAreExclusive() {
        Edge internal = Edge.call("source", "target", "VIRTUAL", "L1");
        assertEquals(
                "target", assertInstanceOf(EdgeTarget.Internal.class, internal.target()).nodeId());

        Edge external = Edge.externalCall("source", "java.util.List#size()", "VIRTUAL", "L2");
        EdgeTarget.External target = assertInstanceOf(EdgeTarget.External.class, external.target());
        assertEquals("java.util.List#size()", target.fqn());
        assertEquals(GraphConstants.Resolution.CLASSPATH, target.resolution());
    }

    @Test
    void contradictoryDraftCannotReachPersistenceBoundary() {
        Edge edge = Edge.call("source", "target", "VIRTUAL", "L1");
        edge.isExternal = true;
        edge.externalTargetFqn = "external.Target";

        assertThrows(IllegalStateException.class, edge::target);
    }
}
