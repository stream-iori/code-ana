package com.anatomist.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class QueryEvidenceTest {
    @Test
    void boundedEmptyResultCannotBeReportedAsConfirmedAbsence() {
        QueryEvidence empty =
                new QueryEvidence(
                        "confirmed_empty", "complete", true, null, List.of(), Map.of(), null);
        assertTrue(empty.negativeConclusionSafe());
        assertSame(empty, empty.bounded(false, false, false));
        assertEquals("QUERY_PAGE_INCOMPLETE", empty.bounded(true, false, false).code());
        assertEquals("QUERY_DEPTH_TRUNCATED", empty.bounded(false, true, false).code());
        assertEquals("QUERY_LIMIT_TRUNCATED", empty.bounded(false, false, true).code());
        QueryEvidence bounded = empty.bounded(true, true, true);
        assertEquals("indeterminate", bounded.status());
        assertFalse(bounded.negativeConclusionSafe());
        assertEquals(
                List.of("query_depth", "query_limit", "query_page"), bounded.affectedDimensions());
        assertEquals(false, bounded.toMap().get("negative_conclusion_safe"));
    }

    @Test
    void positiveAndDiagnosticEvidenceRetainTheirMeaningWhenBounded() {
        QueryEvidence positive = QueryEvidence.positiveComplete().bounded(true, false, false);
        assertEquals("positive", positive.status());
        assertEquals("complete", positive.coverage());
        QueryEvidence diagnostic = QueryEvidence.indeterminate("MISSING", "unresolved");
        assertEquals("MISSING", diagnostic.bounded(false, true, false).code());
        assertEquals("unresolved", diagnostic.toMap().get("message"));
    }
}
