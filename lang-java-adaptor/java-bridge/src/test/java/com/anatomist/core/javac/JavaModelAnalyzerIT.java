package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.model.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaModelAnalyzerIT {
    @Test
    void callbacksUseDetachedEvidenceAndFailedCallbacksCannotLeakFacts(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Example.java"), "class Example { int value; }");
            var retained = new ArrayList<JavaUnitAnalysis>();
            JavaModelAnalyzer successful =
                    new JavaModelAnalyzer() {
                        public String id() {
                            return "test-framework";
                        }

                        public String producerId() {
                            return "framework-test";
                        }

                        public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {
                            retained.add(unit);
                            assertEquals(SourceScope.GENERATED, unit.identity().scope());
                            assertEquals("generated-module", unit.identity().module());
                            assertEquals("Example.java", unit.sourceFile());
                            var declaration = unit.model().declarations(unit.source()).getFirst();
                            assertEquals("Example", unit.identities().declaration(declaration));
                            Node node = new Node();
                            node.id = "framework:example";
                            node.kind = "bean";
                            node.module = unit.identity().module();
                            node.scope = unit.identity().scope().name();
                            result.nodes.add(node);
                            result.stats.put("test_framework", 1L);
                        }
                    };
            JavaModelAnalyzer failed =
                    new JavaModelAnalyzer() {
                        public String id() {
                            return "failed-framework";
                        }

                        public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {
                            Node node = new Node();
                            node.id = "must-not-leak";
                            result.nodes.add(node);
                            throw new IllegalArgumentException("unsupported-test-feature");
                        }
                    };
            var registry = new JavaModelAnalyzerRegistry(List.of(), List.of(successful, failed));
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            project,
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(
                                    new SourceRoot(
                                            project, "generated-module", SourceScope.GENERATED)),
                            config.javacOptions());
            backend.setDeclarationOverlay(registry.overlays());
            var extractor =
                    new JavaModelExtractor(
                            new JavaFrameworkContext(project, List.of(source), config, false),
                            null,
                            registry);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            assertTrue(
                    backend.analyzeFiles(
                                    List.of(source),
                                    context -> extractor.extract(context, facts::add))
                            .complete());
            var result = facts.getFirst();
            var node =
                    result.facts().nodes.stream()
                            .filter(value -> value.id.equals("framework:example"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("Example.java", node.sourceFile);
            assertEquals("framework-test", node.producerId);
            assertEquals("GENERATED", node.scope);
            assertEquals(1L, result.facts().stats.get("test_framework"));
            assertTrue(
                    result.facts().nodes.stream()
                            .noneMatch(value -> value.id.equals("must-not-leak")));
            assertTrue(
                    result.diagnostics().stream()
                            .anyMatch(
                                    value ->
                                            value.code().equals("EXTENSION_JAVA_ANALYZER_FAILED")
                                                    && value.module().equals("generated-module")
                                                    && value.scope().equals("GENERATED")));
            assertThrows(
                    IllegalStateException.class,
                    () -> retained.getFirst().model().declarations(source));
        }
    }

    @Test
    void registrySnapshotsInputsRejectsDuplicateIdsAndIncludesAnalyzerChangesInParserIdentity() {
        JavaModelAnalyzer one = analyzer("one", "1"), two = analyzer("two", "2");
        var list = new ArrayList<JavaModelAnalyzer>(List.of(one));
        var registry = new JavaModelAnalyzerRegistry(List.of(), list);
        list.add(two);
        assertEquals(1, registry.analyzers().size());
        assertThrows(UnsupportedOperationException.class, () -> registry.analyzers().add(two));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaModelAnalyzerRegistry(List.of(), List.of(one, one)));
        assertNotEquals(
                registry.overlays().fingerprint(),
                new JavaModelAnalyzerRegistry(List.of(), List.of(analyzer("one", "2")))
                        .overlays()
                        .fingerprint());
        assertTrue(registry.overlays().isEmpty());
    }

    private static JavaModelAnalyzer analyzer(String id, String version) {
        return new JavaModelAnalyzer() {
            public String id() {
                return id;
            }

            public String version() {
                return version;
            }

            public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {}
        };
    }
}
