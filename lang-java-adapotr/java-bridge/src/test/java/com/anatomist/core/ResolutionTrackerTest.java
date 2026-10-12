package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

/** Resolution evidence comes from actual compiler diagnostics and detached source facts. */
class ResolutionTrackerTest {
    @Test
    void missingImportedDependencyKeepsCompilerEvidence(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; import org.springframework.http.ResponseEntity; class A { ResponseEntity value; }");
        assertTrue(value.errors());
        assertTrue(value.diagnostics().stream().anyMatch(d -> d.code().startsWith("JAVAC_")));
        assertFalse(
                value.facts().nodes.stream()
                        .anyMatch(n -> n.id.equals("org.springframework.http.ResponseEntity")));
    }

    @Test
    void groupsPreserveFilePhaseAndScope(@TempDir Path directory) throws Exception {
        var value =
                fixture(directory, "package p; class A { void run() { missing(); missing(); } }");
        var gaps = value.gaps();
        assertEquals(2, gaps.stream().filter(d -> d.symbol().contains("missing")).count());
        assertTrue(
                gaps.stream()
                        .allMatch(
                                d ->
                                        "Use.java".equals(d.sourceFile())
                                                && "MAIN".equals(d.scope())
                                                && d.phase().startsWith("NAVIGATION_")));
    }

    @Test
    void sourceAndMissingSdkAreSeparateEvidence(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; import p.LocalType; import com.ipay.sdk.RemoteType; class A { LocalType local; RemoteType remote; } class LocalType {}");
        assertTrue(value.errors());
        assertTrue(value.facts().nodes.stream().anyMatch(n -> n.id.equals("p.LocalType")));
        assertTrue(value.facts().edges.stream().anyMatch(e -> "p.LocalType".equals(e.targetId)));
        assertFalse(
                value.facts().nodes.stream().anyMatch(n -> n.id.equals("com.ipay.sdk.RemoteType")));
    }

    @Test
    void unhandledExceptionDoesNotInventMissingJdkMethod(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; import java.nio.file.Files; class A { void run(java.nio.file.Path tmp) { Files.walk(tmp); } }");
        assertTrue(value.errors());
        var b = value.binding("walk");
        assertTrue(b.declarationResolved());
        assertEquals("java.nio.file.Files", b.owner());
        assertFalse(b.evidenceComplete());
        assertTrue(
                value.diagnostics().stream()
                        .anyMatch(d -> d.code().contains("unreported.exception")));
    }

    @Test
    void distinctSourceSitesKeepOriginalCoordinates(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; class A { void run() {\n missing();\n missing();\n } }");
        var gaps = value.gaps().stream().filter(d -> d.symbol().contains("missing")).toList();
        assertEquals(2, gaps.size());
        assertNotEquals(gaps.get(0).sampleDetails(), gaps.get(1).sampleDetails());
        assertTrue(
                gaps.stream()
                        .allMatch(
                                d ->
                                        d.sample().startsWith("L")
                                                && d.sampleDetails().contains("begin_column")));
    }

    @Test
    void nestedMemberFailureKeepsInnerSourceSite(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; class A { void run(Thread.State state) { java.util.Map.of(\"state\",state.missing()); } }");
        assertTrue(value.errors());
        assertTrue(
                value.gaps().stream()
                        .anyMatch(
                                d ->
                                        d.symbol().contains("missing")
                                                && d.sample().contains("state.missing()")));
    }

    @Test
    void validGenericConstructorUsesActualCompilerDeclaration(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; import java.util.List; class A { A(List<String> values) {} static A empty() { return new A(List.of()); } }");
        assertFalse(value.errors());
        assertTrue(value.hasTarget("p.A#A(java.util.List)"));
        assertTrue(value.gaps().isEmpty());
    }

    @Test
    void missingConstructorDoesNotInventGeneratedMember(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; class A { B create() { return new B(\"value\"); } } class B { String value; }");
        assertTrue(value.errors());
        assertFalse(value.hasTarget("p.B#B(java.lang.String)"));
        assertTrue(value.gaps().stream().anyMatch(d -> d.sample().contains("new B")));
    }

    @Test
    void missingImportedParentKeepsActualSymbol(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; import org.example.PluginAdapter; class LocalPlugin extends PluginAdapter {}");
        assertTrue(value.errors());
        assertFalse(
                value.facts().edges.stream()
                        .anyMatch(
                                e ->
                                        e.relation.equals("INHERITS")
                                                && "EXTRACTED"
                                                        .equals(String.valueOf(e.confidence))));
        assertTrue(value.gaps().stream().anyMatch(d -> d.symbol().contains("PluginAdapter")));
    }

    @Test
    void builderFactorySurvivesMissingMember(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; class A { void run() { Builder.builder().name(\"value\"); } } class Builder { static Builder builder() { return new Builder(); } }");
        assertTrue(value.errors());
        assertTrue(value.hasTarget("p.Builder#builder()"));
        assertTrue(value.gaps().stream().anyMatch(d -> d.symbol().contains("name")));
    }

    @Test
    void lambdaConstructorFailureKeepsTypeAndOriginalGap(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "package p; import java.util.List; class A { void run() { List.of(\"value\").forEach(value -> new B(value)); } } class B { String value; }");
        assertTrue(value.errors());
        assertTrue(value.facts().nodes.stream().anyMatch(n -> n.id.equals("p.B")));
        assertFalse(value.hasTarget("p.B#B(java.lang.String)"));
        assertTrue(value.gaps().stream().anyMatch(d -> d.sample().contains("new B(value)")));
    }

    @Test
    void missingTargetRetainsSourceOwnerAndBoundary(@TempDir Path directory) throws Exception {
        var value = fixture(directory, "package p; class A { void run() { missing(); } }");
        assertTrue(value.errors());
        var gap =
                value.gaps().stream()
                        .filter(d -> d.symbol().contains("missing"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("SYMBOL_UNRESOLVED", gap.reasonCode());
        assertTrue(gap.sampleDetails().contains("p.A#run()"));
        assertTrue(gap.sampleDetails().contains("begin_line"));
    }

    private record Fixture(
            JavaAnalysisContext context,
            ExtractionResult facts,
            List<IndexDiagnostic> diagnostics) {
        boolean errors() {
            return context.diagnostics().stream().anyMatch(d -> d.kind().equals("ERROR"));
        }

        List<IndexDiagnostic> gaps() {
            return diagnostics.stream()
                    .filter(d -> d.code().equals("NAVIGATION_TARGET_UNRESOLVED"))
                    .toList();
        }

        boolean hasTarget(String target) {
            return facts.edges.stream()
                    .anyMatch(
                            e ->
                                    e.relation.equals("CALLS")
                                            && target.equals(
                                                    e.targetId == null
                                                            ? e.externalTargetFqn
                                                            : e.targetId));
        }

        JavaBinding binding(String name) {
            var unit =
                    context.units().stream()
                            .filter(u -> context.publish().contains(u.path()))
                            .findFirst()
                            .orElseThrow();
            try (var model = new JavaModel(context)) {
                return model.syntax(unit.path()).nodes().stream()
                        .filter(n -> n.kind().equals("METHOD_INVOCATION"))
                        .map(n -> unit.bindings().get(n.id()))
                        .filter(Objects::nonNull)
                        .filter(b -> b.name().equals(name))
                        .findFirst()
                        .orElseThrow();
            }
        }
    }

    private static Fixture fixture(Path directory, String source) throws Exception {
        var context = JavaFactsTestSupport.analyze(directory, "Use.java", source);
        var facts = new ExtractionResult();
        var diagnostics = new ArrayList<IndexDiagnostic>();
        var backend = JavaFactsTestSupport.backend(directory);
        try (var binaries = backend.openBinaryRepository(context)) {
            new JavaModelExtractor(directory, binaries)
                    .extract(
                            context,
                            value -> {
                                facts.addFacts(value.facts());
                                diagnostics.addAll(value.diagnostics());
                            });
        }
        return new Fixture(context, facts, List.copyOf(diagnostics));
    }
}
