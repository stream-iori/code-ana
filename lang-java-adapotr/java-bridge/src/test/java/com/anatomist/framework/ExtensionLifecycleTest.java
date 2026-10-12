package com.anatomist.framework;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ExtensionLifecycleTest {

    @Test
    void rejectsDuplicateIdsAndFingerprintTracksVersion() {
        var first = analyzer("same", "1");
        var duplicate = analyzer("same", "2");
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaModelAnalyzerRegistry(List.of(), List.of(first, duplicate)));
        var v1 =
                new JavaModelAnalyzerRegistry(List.of(), List.of(analyzer("a", "1"))).fingerprint();
        var v2 =
                new JavaModelAnalyzerRegistry(List.of(), List.of(analyzer("a", "2"))).fingerprint();
        assertNotEquals(v1, v2);
        var callV1 =
                new JavaModelAnalyzerRegistry(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(callEvidence("call", "1")))
                        .fingerprint();
        var callV2 =
                new JavaModelAnalyzerRegistry(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(callEvidence("call", "2")))
                        .fingerprint();
        assertNotEquals(callV1, callV2);
    }

    @Test
    void modelAugmentationIsIdempotentAndVisibleToSourceCalls(@TempDir Path tmp) throws Exception {
        var original =
                JavaFactsTestSupport.analyze(
                        tmp,
                        "A.java",
                        "class A { int value; } class B { int use() { return new A().generated(); } }");
        var extension = generatedMethod("test-model", "generated");
        var extensions = new JavaModelExtensions(List.of(extension));
        var once = extensions.augment(original, null);
        var twice = extensions.augment(once, null);
        assertEquals(once, twice);
        assertEquals(
                1,
                twice.units().getFirst().declarationInventory().stream()
                        .filter(type -> type.canonicalName().equals("A"))
                        .flatMap(type -> type.members().stream())
                        .filter(member -> member.symbol().name().equals("generated"))
                        .count());
        var facts = new ExtractionResult();
        new JavaModelExtractor(tmp, null).extract(twice, unit -> facts.addFacts(unit.facts()));
        var call =
                facts.edges.stream()
                        .filter(
                                edge ->
                                        edge.relation.equals("CALLS")
                                                && edge.targetId.equals("A#generated()"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("INFERRED", call.confidence);
        assertEquals("source_fallback", call.resolution);
        assertTrue(facts.unresolvedCallSites.isEmpty());
    }

    @Test
    void everyMatchingProjectAnalyzerSharesResourceAndOwnsItsFacts() {
        ProjectResource resource = new ProjectResource(Path.of("app.xml"), "app.xml", "test");
        ProjectResourceAnalyzer a = projectAnalyzer("producer-a", "A");
        ProjectResourceAnalyzer b = projectAnalyzer("producer-b", "B");
        var prepared =
                new JavaModelAnalyzerRegistry(List.of(), List.of(), List.of(), List.of(a, b));
        ExtractionResult result = new ExtractionResult();
        JavaFrameworkContext context =
                new JavaFrameworkContext(
                        Path.of("."), List.of(), new com.anatomist.config.ProjectConfig(), true);

        new ProjectAnalysisRunner()
                .run(
                        prepared,
                        context,
                        List.of(resource),
                        new DefaultProjectFactView(Set.of("source", "target"), Map.of()),
                        result,
                        null);

        assertEquals(
                List.of("producer-a", "producer-b"),
                result.edges.stream().map(edge -> edge.producerId).toList());
        assertTrue(result.edges.stream().allMatch(edge -> "app.xml".equals(edge.sourceFile)));
    }

    @Test
    void modelFailureRollsBackThatExtensionAndContinues(@TempDir Path tmp) throws Exception {
        var original = JavaFactsTestSupport.analyze(tmp, "A.java", "class A { int value; }");
        JavaModelExtension broken =
                new JavaModelExtension() {
                    public String id() {
                        return "broken-model";
                    }

                    public JavaAnalysisContext augment(
                            JavaAnalysisContext context, JavaBinaryRepository binaries) {
                        generatedMethod(id(), "mustNotLeak").augment(context, binaries);
                        throw new IllegalStateException("boom");
                    }
                };
        var timings = new com.anatomist.core.IndexTimings();
        var augmented =
                new JavaModelExtensions(
                                List.of(broken, generatedMethod("healthy-model", "survives")))
                        .augment(original, null, timings);
        assertTrue(timings.millis().containsKey("extension_model_augment.broken-model"));
        assertTrue(timings.millis().containsKey("extension_model_augment.healthy-model"));
        var unit = augmented.units().getFirst();
        var members = unit.declarationInventory().getFirst().members();
        assertTrue(
                members.stream().noneMatch(member -> member.symbol().name().equals("mustNotLeak")));
        assertEquals(
                1,
                members.stream()
                        .filter(member -> member.symbol().name().equals("survives"))
                        .count());
        assertTrue(original.units().getFirst().extensions().generated().isEmpty());
        assertTrue(
                unit.extensions().diagnostics().stream()
                        .anyMatch(
                                value ->
                                        value.code().equals("JAVA_MODEL_EXTENSION_FAILED")
                                                && value.symbol().equals("broken-model")));
        assertTrue(
                unit.declarationInventory()
                        .getFirst()
                        .incompleteReasons()
                        .contains("model-extension-failed:broken-model"));
    }

    private static JavaModelExtension generatedMethod(String id, String name) {
        return new JavaModelExtension() {
            public String id() {
                return id;
            }

            public JavaAnalysisContext augment(
                    JavaAnalysisContext context, JavaBinaryRepository binaries) {
                var units = new ArrayList<JavaUnit>();
                for (var unit : context.units()) {
                    var entries = new ArrayList<>(unit.types().entries());
                    var inventory = new ArrayList<JavaDeclaration>();
                    var generated = new LinkedHashMap<>(unit.extensions().generated());
                    for (var type : unit.declarationInventory()) {
                        if (!type.canonicalName().equals("A")
                                || type.members().stream()
                                        .anyMatch(member -> member.symbol().name().equals(name))) {
                            inventory.add(type);
                            continue;
                        }
                        var field =
                                type.members().stream()
                                        .filter(member -> member.symbol().kind().equals("FIELD"))
                                        .findFirst()
                                        .orElseThrow();
                        int executable = entries.size();
                        entries.add(
                                new JavaType(
                                        JavaType.Kind.EXECUTABLE,
                                        "",
                                        "()int",
                                        -1,
                                        List.of(),
                                        List.of(),
                                        -1,
                                        -1,
                                        List.of(),
                                        field.symbol().declaredType(),
                                        List.of()));
                        var members = new ArrayList<>(type.members());
                        members.add(
                                new JavaDeclaration(
                                        new JavaSymbol(
                                                "METHOD",
                                                "A",
                                                name,
                                                "()I",
                                                executable,
                                                List.of("public"),
                                                false),
                                        "",
                                        "",
                                        "()I",
                                        field.span(),
                                        JavaDeclaration.Origin.MODEL,
                                        -1,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        null,
                                        null,
                                        List.of(),
                                        List.of()));
                        inventory.add(
                                new JavaDeclaration(
                                        type.symbol(),
                                        type.canonicalName(),
                                        type.binaryName(),
                                        type.signature(),
                                        type.span(),
                                        type.origin(),
                                        type.superclass(),
                                        type.interfaces(),
                                        type.permitted(),
                                        type.annotations(),
                                        type.constant(),
                                        type.annotationDefault(),
                                        members,
                                        type.incompleteReasons(),
                                        type.overrides(),
                                        type.deprecated()));
                        generated.put(
                                "A#" + name + "()",
                                new JavaGeneratedOrigin(
                                        id, "model", "test", "A#value", "INFERRED", false));
                    }
                    units.add(
                            new JavaUnit(
                                    unit.path(),
                                    unit.tree(),
                                    unit.processing(),
                                    unit.bindings(),
                                    new JavaTypes(entries),
                                    unit.declarations(),
                                    inventory,
                                    unit.sourceHash(),
                                    new JavaExtensionData(
                                            generated,
                                            unit.extensions().declarationMetadata(),
                                            unit.extensions().diagnostics(),
                                            unit.extensions().counters())));
                }
                return new JavaAnalysisContext(
                        context.module(),
                        context.scope(),
                        units,
                        context.publish(),
                        context.diagnostics(),
                        context.analysisFailure(),
                        context.sourceIdentities());
            }
        };
    }

    @Test
    void resourceProviderFailureIsIsolatedAndInventoryIsShared() {
        ProjectResourceProvider broken =
                new ProjectResourceProvider() {
                    @Override
                    public String id() {
                        return "broken-resource";
                    }

                    @Override
                    public boolean enabled(JavaFrameworkContext context) {
                        return true;
                    }

                    @Override
                    public Set<String> kinds() {
                        return Set.of("test");
                    }

                    @Override
                    public boolean mayContain(Path path) {
                        return true;
                    }

                    @Override
                    public List<ProjectResource> discover(
                            JavaFrameworkContext context,
                            com.anatomist.core.ProjectScanner scanner) {
                        throw new IllegalStateException("boom");
                    }
                };
        ProjectResourceProvider healthy =
                new ProjectResourceProvider() {
                    @Override
                    public String id() {
                        return "healthy-resource";
                    }

                    @Override
                    public boolean enabled(JavaFrameworkContext context) {
                        return true;
                    }

                    @Override
                    public Set<String> kinds() {
                        return Set.of("test");
                    }

                    @Override
                    public boolean mayContain(Path path) {
                        return true;
                    }

                    @Override
                    public List<ProjectResource> discover(
                            JavaFrameworkContext context,
                            com.anatomist.core.ProjectScanner scanner) {
                        return List.of(new ProjectResource(Path.of("app.xml"), "app.xml", "test"));
                    }
                };
        var prepared =
                new JavaModelAnalyzerRegistry(
                        List.of(), List.of(), List.of(broken, healthy), List.of());
        ExtensionReport report = new ExtensionReport();

        List<ProjectResource> resources =
                new ProjectResourceDiscovery()
                        .discover(
                                prepared,
                                new JavaFrameworkContext(
                                        Path.of("."),
                                        List.of(),
                                        new com.anatomist.config.ProjectConfig(),
                                        true),
                                new com.anatomist.core.ProjectScanner(),
                                report);

        assertEquals(
                List.of("app.xml"), resources.stream().map(ProjectResource::sourceFile).toList());
        assertTrue(
                report.diagnostics().stream()
                        .anyMatch(
                                value ->
                                        value.code().equals("EXTENSION_RESOURCE_DISCOVERY_FAILED")
                                                && value.symbol().equals("broken-resource")));
    }

    private static JavaModelAnalyzer analyzer(String id, String version) {
        return new JavaModelAnalyzer() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {}
        };
    }

    private static JavaCallSiteEvidenceProvider callEvidence(String id, String version) {
        return new JavaCallSiteEvidenceProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public java.util.Optional<Evidence> observe(
                    JavaUnitAnalysis unit,
                    com.anatomist.core.java.JavaNode call,
                    FallbackCallSite site) {
                return java.util.Optional.empty();
            }
        };
    }

    private static ProjectResourceAnalyzer projectAnalyzer(String id, String label) {
        return new ProjectResourceAnalyzer() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean enabled(JavaFrameworkContext context) {
                return true;
            }

            @Override
            public ResourceSelector selector() {
                return ResourceSelector.kind("test");
            }

            @Override
            public void analyze(
                    JavaFrameworkContext context,
                    List<ProjectResource> resources,
                    ProjectFactView facts,
                    ExtractionResult result) {
                Edge edge = Edge.call("source", "target", label, null);
                edge.sourceFile = resources.getFirst().sourceFile();
                result.edges.add(edge);
            }
        };
    }
}
