package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.Edge;
import com.anatomist.quality.ResolutionQuality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Small truth-set gate: both extra targets and missing targets fail the build. */
class JavaResolutionQualityGateTest {
    @TempDir Path directory;

    @Test
    void genericCallHasExactInternalTarget() throws Exception {
        assertPerfectInternal(
                """
                package quality;
                class GenericCase {
                  static <T> T identity(T value) { return value; }
                  String run() { return identity("x"); }
                }
                """,
                Set.of("quality.GenericCase#identity(java.lang.Object)"));
    }

    @Test
    void lambdaMethodReferenceHasNoExtraInternalTarget() throws Exception {
        assertPerfectInternal(
                """
                package quality;
                import java.util.List;
                class LambdaCase {
                  static String trim(String value) { return value.trim(); }
                  List<String> run(List<String> values) {
                    return values.stream().map(LambdaCase::trim).toList();
                  }
                }
                """,
                Set.of("quality.LambdaCase#trim(java.lang.String)"));
    }

    @Test
    void recordAccessorHasExactInternalTarget() throws Exception {
        assertPerfectInternal(
                """
                package quality;
                record RecordCase(String value) {
                  String run() { return value(); }
                }
                """,
                Set.of("quality.RecordCase#value()"));
    }

    @Test
    void jdkOverloadHasExactExternalSignature() throws Exception {
        Set<String> actual =
                calls(
                                """
                package quality;
                class JdkCase {
                  long run(long value) { return Math.abs(value); }
                }
                """)
                        .stream()
                        .filter(
                                edge ->
                                        edge.isExternal
                                                && edge.externalTargetFqn != null
                                                && edge.externalTargetFqn.startsWith(
                                                        "java.lang.Math#abs"))
                        .map(edge -> edge.externalTargetFqn)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertPerfect(Set.of("java.lang.Math#abs(long)"), actual);
    }

    private void assertPerfectInternal(String source, Set<String> expected) throws Exception {
        Set<String> actual =
                calls(source).stream()
                        .filter(edge -> !edge.isExternal && edge.targetId != null)
                        .map(edge -> edge.targetId)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertPerfect(expected, actual);
    }

    private List<Edge> calls(String source) throws Exception {
        var result = JavaFactsTestSupport.extract(directory, "Case.java", source);
        return result.edges.stream().filter(edge -> "CALLS".equals(edge.relation)).toList();
    }

    private static void assertPerfect(Set<String> expected, Set<String> actual) {
        ResolutionQuality.Metrics metrics = ResolutionQuality.evaluate(expected, actual);
        assertTrue(
                metrics.passes(1.0, 1.0),
                () ->
                        "expected="
                                + expected
                                + ", actual="
                                + actual
                                + ", precision="
                                + metrics.precision()
                                + ", recall="
                                + metrics.recall());
    }
}
