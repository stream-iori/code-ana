package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaModelExtensionIT {
    @Test
    void overlaysReachDependentArchivesWithoutClaimingCompilerOrBodyEvidence(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            List<Path> files = fixture(project, release);
            var backend = backend(project, files, release);
            backend.setDeclarationOverlay(new JavaModelExtensions(List.of(new Overlay("one"))));
            String fingerprint = backend.environmentFingerprint();
            backend.setDeclarationOverlay(new JavaModelExtensions(List.of(new Overlay("two"))));
            assertNotEquals(fingerprint, backend.environmentFingerprint());
            backend.setDeclarationOverlay(new JavaModelExtensions(List.of(new Overlay("one"))));
            var units = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend.analyzeFiles(
                                    files,
                                    context -> {
                                        assertThrows(
                                                IllegalStateException.class,
                                                () ->
                                                        backend.setDeclarationOverlay(
                                                                JavaDeclarationOverlay.NONE));
                                        extractor.extract(context, units::add);
                                    })
                            .complete());
            assertEquals(1, backend.signatureStats().archivesUsed());
            assertEquals(2, backend.signatureStats().sourceAppearances());
            var library =
                    units.stream()
                            .filter(unit -> unit.source().equals(files.getFirst()))
                            .findFirst()
                            .orElseThrow();
            var method =
                    library.facts().nodes.stream()
                            .filter(node -> node.id.equals("dep.Library#virtual()"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("test-model", method.producerId);
            var metadata = Json.parseTree(method.metadata);
            assertInstanceOf(Map.class, metadata);
            assertEquals(false, ((Map<?, ?>) metadata).get("bodyAvailable"));
            assertEquals(false, ((Map<?, ?>) metadata).get("bindingResolved"));
            assertEquals("model", ((Map<?, ?>) metadata).get("generator"));
            assertEquals(1L, library.facts().stats.get("test_generated"));
            var usage =
                    units.stream()
                            .filter(unit -> unit.source().equals(files.getLast()))
                            .findFirst()
                            .orElseThrow();
            var call =
                    usage.facts().edges.stream()
                            .filter(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && "dep.Library#virtual()"
                                                            .equals(edge.targetId))
                            .findFirst()
                            .orElseThrow();
            assertEquals("INFERRED", call.confidence);
            assertEquals("source_fallback", call.resolution);
            assertEquals("java-core", call.producerId);
            assertTrue(usage.facts().unresolvedCallSites.isEmpty());
            assertFalse(Files.exists(project.resolve("lib/target")));
        }
    }

    @Test
    void failedExtensionsKeepOriginalDeclarationsAndPreventSignaturePublication(
            @TempDir Path project) throws Exception {
        var files = fixture(project, 25);
        var backend = backend(project, files, 25);
        backend.setDeclarationOverlay(
                new JavaModelExtensions(
                        List.of(
                                new Overlay("fail") {
                                    @Override
                                    public JavaAnalysisContext augment(
                                            JavaAnalysisContext context,
                                            JavaBinaryRepository binaries) {
                                        super.augment(context, binaries);
                                        throw new IllegalStateException("fixture failure");
                                    }
                                })));
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend.analyzeFiles(files, contexts::add).complete());
        assertEquals(0, backend.signatureStats().archivesUsed());
        assertEquals(1, backend.signatureStats().fallbackModules());
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.units().stream())
                        .flatMap(unit -> unit.declarationInventory().stream())
                        .noneMatch(
                                type ->
                                        type.members().stream()
                                                .anyMatch(
                                                        member ->
                                                                member.symbol()
                                                                        .name()
                                                                        .equals("virtual"))));
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.units().stream())
                        .allMatch(
                                unit ->
                                        unit.extensions().diagnostics().stream()
                                                .anyMatch(
                                                        diagnostic ->
                                                                diagnostic
                                                                        .code()
                                                                        .equals(
                                                                                "JAVA_MODEL_EXTENSION_FAILED"))));
    }

    @Test
    void extensionBoundaryRejectsOwnershipChangesAndSnapshotsNestedMetadata() {
        var nested = new ArrayList<Object>();
        nested.add("original");
        var input = new LinkedHashMap<String, Object>();
        input.put("nested", nested);
        input.put("nullable", null);
        var data = new JavaExtensionData(Map.of(), Map.of("type", input), List.of(), Map.of());
        nested.add("changed");
        input.put("added", true);
        assertEquals(List.of("original"), data.declarationMetadata().get("type").get("nested"));
        assertFalse(data.declarationMetadata().get("type").containsKey("added"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JavaExtensionData(
                                Map.of(),
                                Map.of("type", Map.of("parserObject", new Object())),
                                List.of(),
                                Map.of()));
        var cyclic = new ArrayList<Object>();
        cyclic.add(cyclic);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JavaExtensionData(
                                Map.of(),
                                Map.of("type", Map.of("cycle", cyclic)),
                                List.of(),
                                Map.of()));
        var original =
                new JavaAnalysisContext(".", SourceScope.MAIN, List.of(), Set.of(), List.of(), "");
        var extensions =
                new JavaModelExtensions(
                        List.of(
                                new Overlay("invalid") {
                                    @Override
                                    public JavaAnalysisContext augment(
                                            JavaAnalysisContext context,
                                            JavaBinaryRepository binaries) {
                                        return new JavaAnalysisContext(
                                                "different",
                                                context.scope(),
                                                context.units(),
                                                context.publish(),
                                                context.diagnostics(),
                                                context.analysisFailure());
                                    }
                                }));
        assertEquals(original, extensions.augment(original, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavaModelExtensions(List.of(new Overlay("one"), new Overlay("two"))));
    }

    private static class Overlay implements JavaModelExtension {
        private final String configuration;

        private Overlay(String configuration) {
            this.configuration = configuration;
        }

        @Override
        public String id() {
            return "test-model";
        }

        @Override
        public String fingerprintMaterial() {
            return configuration;
        }

        @Override
        public JavaAnalysisContext augment(
                JavaAnalysisContext context, JavaBinaryRepository binaries) {
            List<JavaUnit> units = new ArrayList<>();
            for (var unit : context.units()) {
                var entries = new ArrayList<>(unit.types().entries());
                var inventory = new ArrayList<JavaDeclaration>();
                for (var type : unit.declarationInventory()) {
                    if (!type.canonicalName().equals("dep.Library")
                            || type.members().stream()
                                    .anyMatch(member -> member.symbol().name().equals("virtual"))) {
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
                    var method =
                            new JavaDeclaration(
                                    new JavaSymbol(
                                            "METHOD",
                                            "dep.Library",
                                            "virtual",
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
                                    List.of());
                    var members = new ArrayList<>(type.members());
                    members.add(method);
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
                                    type.overrides()));
                }
                if (inventory.equals(unit.declarationInventory())) units.add(unit);
                else
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
                                            Map.of(
                                                    "dep.Library#virtual()",
                                                    new JavaGeneratedOrigin(
                                                            id(),
                                                            "model",
                                                            "ast",
                                                            "dep.Library#value",
                                                            "INFERRED",
                                                            false)),
                                            Map.of(
                                                    "dep.Library",
                                                    Map.of("model_coverage", "complete")),
                                            List.of(),
                                            Map.of("test_generated", 1L))));
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
    }

    private static List<Path> fixture(Path project, int release) throws Exception {
        Path library = Files.createDirectories(project.resolve("lib/src/main/java"));
        Path usage = Files.createDirectories(project.resolve("app/src/main/java"));
        Files.writeString(project.resolve("lib/pom.xml"), pom("lib", "", release));
        Files.writeString(
                project.resolve("app/pom.xml"),
                pom(
                        "app",
                        "<dependencies><dependency><groupId>test</groupId><artifactId>lib</artifactId><version>1</version></dependency></dependencies>",
                        release));
        return List.of(
                Files.writeString(
                        library.resolve("Library.java"),
                        "package dep; public class Library { private int value; }"),
                Files.writeString(
                        usage.resolve("Use.java"),
                        "class Use { int run(dep.Library library) { return library.virtual(); } }"));
    }

    private static String pom(String name, String dependencies, int release) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>"
                + name
                + "</artifactId><version>1</version><properties><maven.compiler.release>"
                + release
                + "</maven.compiler.release></properties>"
                + dependencies
                + "</project>";
    }

    private static JavacBackend backend(Path project, List<Path> files, int release)
            throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        var defaults = config.javacOptions();
        var options =
                new JavacOptions(
                        defaults.javaHome(),
                        defaults.toolchainExecution(),
                        defaults.vmClasspath(),
                        defaults.lombokCompile(),
                        defaults.lombokStrict(),
                        project.resolve("cache"));
        return new JavacBackend(
                project,
                JavacToolchain.resolve(config.javacOptions(), release, null),
                List.of(),
                List.of(
                        new SourceRoot(files.getFirst().getParent(), "lib", SourceScope.MAIN),
                        new SourceRoot(files.getLast().getParent(), "app", SourceScope.MAIN)),
                options);
    }
}
