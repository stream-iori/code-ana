package com.anatomist.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExtractionResultTest {

    @Test
    void clearFactsReleasesEveryBufferedFact() {
        ExtractionResult result = new ExtractionResult();
        result.nodes.add(new Node());
        result.edges.add(new Edge());
        result.annotations.add(new Annotation());
        assertEquals(3, result.factCount());

        result.clearFacts();

        assertEquals(0, result.factCount());
    }
}
