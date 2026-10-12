package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class NavigationDiagnosticsTest {
    @Test
    void reasonTotalsSurviveRetentionAndKeepFileIdentity() {
        var diagnostics = new ArrayList<IndexDiagnostic>();
        for (int i = 0; i < 50_003; i++) diagnostics.add(gap("A.java", "m" + i));
        diagnostics.add(gap("B.java", "other"));
        assertEquals(50_004, diagnostics.stream().mapToLong(IndexDiagnostic::count).sum());
        var coverage = AnalysisCoverage.summarize(diagnostics);
        for (var expected : Map.of("A.java", 50_003L, "B.java", 1L).entrySet())
            assertEquals(
                    expected.getValue().longValue(),
                    coverage.stream()
                            .filter(
                                    r ->
                                            r.sourceFile().equals(expected.getKey())
                                                    && r.capability()
                                                            .startsWith("NAVIGATION_REASON:"))
                            .mapToLong(AnalysisCoverage.Row::occurrences)
                            .sum());
        var retained = IndexDiagnosticRetention.retain(diagnostics);
        assertEquals(IndexDiagnosticRetention.LIMIT, retained.size());
        assertEquals(50_004, retained.stream().mapToLong(IndexDiagnostic::count).sum());
        assertTrue(
                retained.stream().anyMatch(d -> d.code().equals("DIAGNOSTIC_STORAGE_TRUNCATED")));
        assertEquals(retained, IndexDiagnosticRetention.retain(retained));
    }

    private static IndexDiagnostic gap(String file, String symbol) {
        return new IndexDiagnostic(
                "warning",
                "NAVIGATION_TARGET_UNRESOLVED",
                "NAVIGATION_CALL_GRAPH",
                file,
                "module",
                "MAIN",
                symbol,
                1,
                "L1: missing()",
                "java",
                "java-core",
                null,
                "SYMBOL_UNRESOLVED",
                null);
    }
}
