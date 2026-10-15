package com.anatomist.framework;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.IndexTimings;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;

import java.util.List;

class ExtensionExecutionTest {
    @Test
    void failedFactsAreDiscardedAndBothAttemptsAreTimed() {
        IndexTimings timings = new IndexTimings();
        ExtensionReport report = new ExtensionReport(timings);
        ExtractionResult destination = new ExtractionResult();
        ExtensionPoint extension = () -> "test-extension";
        var source = new ExtensionExecution.Source("A.java", "app", "MAIN");
        assertFalse(
                ExtensionExecution.facts(
                        extension,
                        ExtensionExecution.Stage.JAVA,
                        source,
                        report,
                        destination,
                        result -> {
                            result.edges.add(Edge.call("a", "b", "partial", null));
                            throw new IllegalStateException("broken");
                        }));
        assertTrue(destination.edges.isEmpty());
        assertTrue(
                ExtensionExecution.facts(
                        extension,
                        ExtensionExecution.Stage.JAVA,
                        source,
                        report,
                        destination,
                        result -> result.edges.add(Edge.call("a", "b", "complete", null))));
        assertEquals(1, destination.edges.size());
        assertEquals("test-extension", destination.edges.getFirst().producerId);
        assertEquals("A.java", destination.edges.getFirst().sourceFile);
        assertEquals("EXTENSION_JAVA_ANALYZER_FAILED", report.diagnostics().getFirst().code());
        assertEquals("A.java", report.diagnostics().getFirst().sourceFile());
        assertEquals(
                timings.millis().get("extension_fact_emit"),
                timings.millis().get("extension_fact_emit.test-extension"));
        assertTrue(timings.millis().containsKey("extension_fact_emit.test-extension"));
    }

    @Test
    void enabledAndSelectionFailuresDoNotAbortOtherProjectAnalyzers() {
        ProjectResourceAnalyzer broken =
                new ProjectResourceAnalyzer() {
                    public String id() {
                        return "broken";
                    }

                    public boolean enabled(JavaFrameworkContext context) {
                        throw new IllegalStateException("enabled");
                    }

                    public ResourceSelector selector() {
                        return ResourceSelector.kind("test");
                    }

                    public void analyze(
                            JavaFrameworkContext context,
                            List<ProjectResource> resources,
                            ProjectFactView facts,
                            ExtractionResult result) {
                        fail("not enabled");
                    }
                };
        ProjectResourceAnalyzer healthy =
                new ProjectResourceAnalyzer() {
                    public String id() {
                        return "healthy";
                    }

                    public boolean enabled(JavaFrameworkContext context) {
                        return true;
                    }

                    public ResourceSelector selector() {
                        return ResourceSelector.kind("test");
                    }

                    public void analyze(
                            JavaFrameworkContext context,
                            List<ProjectResource> resources,
                            ProjectFactView facts,
                            ExtractionResult result) {
                        var edge = Edge.call("a", "b", "complete", null);
                        edge.sourceFile = "owned.xml";
                        result.edges.add(edge);
                    }
                };
        var extensions =
                new JavaModelAnalyzerRegistry(
                        List.of(), List.of(), List.of(), List.of(broken, healthy));
        var result = new ExtractionResult();
        var timings = new IndexTimings();
        var report = new ExtensionReport(timings);
        var producers =
                new ProjectAnalysisRunner()
                        .run(
                                extensions,
                                null,
                                List.of(
                                        new ProjectResource(
                                                java.nio.file.Path.of("a.xml"), "a.xml", "test")),
                                null,
                                result,
                                report);
        assertEquals(java.util.Set.of("healthy"), producers);
        assertEquals("owned.xml", result.edges.getFirst().sourceFile);
        assertEquals("broken", report.diagnostics().getFirst().symbol());
        assertTrue(timings.millis().containsKey("extension_project_analyze.broken"));
        assertTrue(timings.millis().containsKey("extension_project_analyze.healthy"));
    }
}
