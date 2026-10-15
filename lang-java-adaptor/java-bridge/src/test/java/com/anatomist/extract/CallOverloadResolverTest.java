package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class CallOverloadResolverTest {
    @TempDir Path directory;

    private ExtractionResult parse(String source) throws Exception {
        return JavaFactsTestSupport.extract(directory, "Case.java", source);
    }

    private static List<String> targets(ExtractionResult result) {
        return result.edges.stream()
                .filter(
                        edge ->
                                "CALLS".equals(edge.relation)
                                        && edge.sourceId.startsWith("Case#run("))
                .map(edge -> edge.targetId)
                .toList();
    }

    @Test
    void primitiveWideningPrefersNearestApplicableOverload() throws Exception {
        var unit =
                parse(
                        """
                class Case {
                  void choose(long value) {}
                  void choose(double value) {}
                  void run(int value) { choose(value); }
                }
                """);
        assertEquals(List.of("Case#choose(long)"), targets(unit));
    }

    @Test
    void fixedArityWinsBeforeVarargs() throws Exception {
        var unit =
                parse(
                        """
                class Case {
                  void choose(Object value) {}
                  void choose(String... value) {}
                  void run() { choose("x"); }
                }
                """);
        assertEquals(List.of("Case#choose(java.lang.Object)"), targets(unit));
    }

    @Test
    void expandedVarargsAcceptsSeveralArguments() throws Exception {
        var unit =
                parse(
                        """
                class Case {
                  void choose(String... values) {}
                  void run() { choose("a", "b"); }
                }
                """);
        assertEquals(List.of("Case#choose(java.lang.String...)"), targets(unit));
    }
}
