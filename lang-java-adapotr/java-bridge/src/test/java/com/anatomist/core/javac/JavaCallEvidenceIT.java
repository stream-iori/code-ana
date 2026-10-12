package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.framework.lombok.*;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaCallEvidenceIT {
    @Test
    void unresolvedCallLinksItsCompilerDiagnostic(@TempDir Path project) throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Use.java"),
                        "package p; class Use { void run() { missing(); } }");
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(17)).toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 17, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
        var extractor = new JavaModelExtractor(project, null);
        assertTrue(
                backend.analyzeFiles(
                                List.of(source), context -> extractor.extract(context, facts::add))
                        .complete());
        assertTrue(
                facts.stream()
                        .flatMap(unit -> unit.diagnostics().stream())
                        .filter(
                                diagnostic ->
                                        diagnostic.code().equals("NAVIGATION_TARGET_UNRESOLVED"))
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.sampleDetails().contains("raw_javac")
                                                && diagnostic.sampleDetails().contains("missing")));
    }

    @Test
    void sourceObservedEvidenceSurvivesUnsupportedModelsWithoutInventingTargets(
            @TempDir Path directory) throws Exception {
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Getter @lombok.experimental.Accessors(fluent=true, prefix="m")
                    class Fluent {
                        private String mName;
                        @lombok.Getter(lombok.AccessLevel.NONE) private int mHidden;
                        private String mExplicit;
                        String explicit() { return "written"; }
                    }
                    @lombok.Getter class Simple { String name; }
                    @lombok.Builder class Generic<T> { T value; @lombok.Singular java.util.List<T> items; }
                    @lombok.experimental.SuperBuilder class Base { String base; @lombok.Singular java.util.List<String> items; }
                    @lombok.experimental.SuperBuilder class Child extends Base { int child; }
                    class Use {
                        void run(Fluent fluent, Simple simple) {
                            fluent.name(); fluent.hidden(); fluent.explicit(); simple.getName();
                            Generic.<String>builder().value("text").build();
                            Child.builder().base("text").child(1).build();
                        }
                    }
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var successful = new LombokJavaCallEvidence(project);
            JavaCallSiteEvidenceProvider failed =
                    new JavaCallSiteEvidenceProvider() {
                        public String id() {
                            return "failed-evidence";
                        }

                        public Optional<Evidence> observe(
                                JavaUnitAnalysis unit, JavaNode call, FallbackCallSite site) {
                            throw new IllegalArgumentException("test failure after observation");
                        }
                    };
            var registry =
                    new JavaModelAnalyzerRegistry(
                            List.of(new LombokJavaModelExtension(project, false)),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(successful, failed));
            var backend =
                    new JavacBackend(
                            project,
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(lombok),
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                            config.javacOptions());
            backend.setDeclarationOverlay(registry.overlays());
            var extracted = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor =
                    new JavaModelExtractor(
                            new JavaFrameworkContext(project, List.of(source), config, false),
                            null,
                            registry);
            assertTrue(
                    backend.analyzeFiles(
                                    List.of(source),
                                    context -> extractor.extract(context, extracted::add))
                            .complete());
            var facts = extracted.getFirst().facts();
            var accessor =
                    facts.unresolvedCallSites.stream()
                            .filter(site -> site.syntaxTarget().equals("fluent.name"))
                            .findFirst()
                            .orElseThrow();
            var usage = usage(accessor.metadata());
            assertEquals("usage-observed", usage.get("status"));
            assertEquals("p.Fluent#mName", usage.get("field_id"));
            assertEquals("mapped", usage.get("mapping_status"));
            assertTrue(
                    facts.unresolvedCallSites.stream()
                            .filter(site -> site.syntaxTarget().equals("fluent.hidden"))
                            .allMatch(site -> usage(site.metadata()) == null));
            assertTrue(
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.syntaxTarget.equals("fluent.explicit"))
                            .allMatch(edge -> usage(edge.metadata) == null));
            var getter =
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && "p.Simple#getName()".equals(edge.targetId))
                            .findFirst()
                            .orElseThrow();
            assertEquals("INFERRED", getter.confidence);
            assertEquals("p.Simple#name", usage(getter.metadata).get("field_id"));
            for (String owner : List.of("p.Generic", "p.Child")) {
                var root =
                        facts.unresolvedCallSites.stream()
                                .filter(
                                        site -> {
                                            var evidence = usage(site.metadata());
                                            return evidence != null
                                                    && owner.equals(evidence.get("owner"))
                                                    && evidence.containsKey("steps");
                                        })
                                .findFirst()
                                .orElseThrow();
                var evidence = usage(root.metadata());
                assertEquals("mapped", evidence.get("mapping_status"));
                assertNull(evidence.get("builder_type"));
                assertEquals(
                        owner.equals("p.Generic") ? "builder" : "super_builder",
                        evidence.get("capability"));
                assertEquals(
                        owner.equals("p.Generic") ? 2 : 3,
                        ((List<?>) evidence.get("steps")).size());
                assertTrue(
                        facts.nodes.stream()
                                .noneMatch(node -> node.id.equals(owner + "#builder()")));
                assertTrue(
                        facts.edges.stream()
                                .noneMatch(edge -> (owner + "#builder()").equals(edge.targetId)));
            }
            assertEquals(2L, facts.stats.get("lombok_usage_builder_chain_observed"));
            assertTrue(
                    extracted.getFirst().diagnostics().stream()
                            .anyMatch(
                                    diagnostic ->
                                            diagnostic
                                                    .code()
                                                    .equals("EXTENSION_CALL_EVIDENCE_FAILED")));
            assertFalse(Files.exists(project.resolve("target")));
        }
    }

    private static Map<?, ?> usage(String metadata) {
        return metadata != null && Json.parseTree(metadata) instanceof Map<?, ?> value
                ? (Map<?, ?>) value.get("lombok_usage")
                : null;
    }
}
