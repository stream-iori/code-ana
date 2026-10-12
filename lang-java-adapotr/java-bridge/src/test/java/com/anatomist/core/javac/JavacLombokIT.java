package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.application.JavacIndexRuntime;
import com.anatomist.config.ProjectConfig;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacLombokIT {
    @Test
    void generationErrorsCannotPublishSuccessfulCompilation(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("TooLarge.java"),
                        "class TooLarge { int run() { int value = 0; "
                                + "value++;".repeat(22_000)
                                + "return value; } }");
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.6/lombok-1.18.6.jar");
        var config = config(8, false);
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 8, null),
                        List.of(lombok),
                        List.of(project),
                        config.javacOptions());
        assertTrue(backend.analyzeFiles(List.of(source), context -> {}).complete());
        String diagnostics = backend.diagnosticEvidence().toString();
        assertTrue(diagnostics.contains("JAVAC_LOMBOK_COMPILE_FAILED"), diagnostics);
        assertTrue(diagnostics.contains("code too large"), diagnostics);
        try (var files = Files.walk(project)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".class")));
        }
    }

    @Test
    void failedCompilationRetainsOnlySafeProcessorDeclarations(@TempDir Path project)
            throws Exception {
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        Path person =
                Files.writeString(
                        project.resolve("Person.java"),
                        "package sample; import lombok.Getter; @Getter class Person { String name; }");
        Path broken =
                Files.writeString(
                        project.resolve("Broken.java"),
                        "package sample; class Broken { MissingType value; }");
        var config = config(17, false);
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 17, null),
                        List.of(lombok),
                        List.of(project),
                        config.javacOptions());
        var runtime =
                new JavacIndexRuntime(
                        backend,
                        new JavaFrameworkContext(project, List.of(person, broken), config, false));
        var units = new ArrayList<JavaModelExtractor.UnitFacts>();
        assertTrue(runtime.extract(List.of(person, broken), units::add).complete());
        assertTrue(
                backend.taskClasspathEvidence().stream()
                        .anyMatch(
                                task ->
                                        task.get("target_jdk").equals(17)
                                                && task.get("entries")
                                                        .toString()
                                                        .contains(lombok.toString())
                                                && task.get("entries")
                                                        .toString()
                                                        .contains("sha256")));
        var getter =
                units.stream()
                        .flatMap(unit -> unit.facts().nodes.stream())
                        .filter(node -> node.id.equals("sample.Person#getName()"))
                        .findFirst()
                        .orElseThrow();
        var metadata = (Map<?, ?>) Json.parseTree(getter.metadata);
        assertEquals("processor-partial", metadata.get("generatorMode"));
        assertEquals(false, metadata.get("bindingResolved"));
        assertTrue(
                units.stream()
                        .flatMap(unit -> unit.diagnostics().stream())
                        .anyMatch(diagnostic -> diagnostic.code().equals("LOMBOK_COMPILE_FAILED")));
    }

    @Test
    void failedCompilationDoesNotPromoteSourceWithCalls(@TempDir Path project) throws Exception {
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        Path person =
                Files.writeString(
                        project.resolve("Person.java"),
                        "package sample; import lombok.Getter; @Getter class Person { String name; String display() { return name.toString(); } }");
        Path broken =
                Files.writeString(
                        project.resolve("Broken.java"),
                        "package sample; class Broken { MissingType value; }");
        var config = config(17, false);
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 17, null),
                        List.of(lombok),
                        List.of(project),
                        config.javacOptions());
        var runtime =
                new JavacIndexRuntime(
                        backend,
                        new JavaFrameworkContext(project, List.of(person, broken), config, false));
        var units = new ArrayList<JavaModelExtractor.UnitFacts>();
        assertTrue(runtime.extract(List.of(person, broken), units::add).complete());
        assertTrue(
                units.stream()
                        .flatMap(unit -> unit.facts().nodes.stream())
                        .filter(node -> node.id.equals("sample.Person#getName()"))
                        .noneMatch(
                                node ->
                                        "processor-partial"
                                                .equals(
                                                        ((Map<?, ?>) Json.parseTree(node.metadata))
                                                                .get("generatorMode"))));
    }

    @Test
    void compiledMembersPreserveOriginsAndCrossFileBindings(@TempDir Path temporary)
            throws Exception {
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        assertTrue(Files.isRegularFile(lombok), "locked test processor must be available");
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(temporary.resolve("jdk" + release));
            Path person =
                    Files.writeString(
                            project.resolve("Person.java"),
                            "package sample; import lombok.Data; import lombok.Builder; @Data @Builder class Person { private String name; }");
            Path use =
                    Files.writeString(
                            project.resolve("Use.java"),
                            "package sample; class Use { String run() { return Person.builder().name(\"x\").build().getName(); } }");
            var config = config(release, false);
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(lombok),
                            List.of(project),
                            config.javacOptions());
            var runtime =
                    new JavacIndexRuntime(
                            backend,
                            new JavaFrameworkContext(project, List.of(person, use), config, false));
            var units = new ArrayList<JavaModelExtractor.UnitFacts>();
            assertTrue(runtime.extract(List.of(person, use), units::add).complete());
            try (var files = Files.walk(project)) {
                assertTrue(
                        files.noneMatch(path -> path.toString().endsWith(".class")),
                        "processor output must remain inside the owned compilation cache");
            }
            assertTrue(
                    units.stream()
                            .flatMap(unit -> unit.diagnostics().stream())
                            .noneMatch(
                                    diagnostic ->
                                            diagnostic.code().equals("LOMBOK_COMPILE_FAILED")));
            var getter =
                    units.stream()
                            .flatMap(unit -> unit.facts().nodes.stream())
                            .filter(node -> node.id.equals("sample.Person#getName()"))
                            .findFirst()
                            .orElseThrow();
            var metadata = (Map<?, ?>) Json.parseTree(getter.metadata);
            assertEquals("compile", metadata.get("generatorMode"));
            assertEquals(false, metadata.get("bodyAvailable"));
            var call =
                    units.stream()
                            .flatMap(unit -> unit.facts().edges.stream())
                            .filter(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && "sample.Person#getName()"
                                                            .equals(edge.targetId))
                            .findFirst()
                            .orElseThrow();
            assertEquals("EXTRACTED", call.confidence);
            units.clear();
            assertTrue(runtime.extract(List.of(person), units::add).complete());
            assertEquals(
                    1,
                    units.stream()
                            .flatMap(unit -> unit.facts().nodes.stream())
                            .filter(node -> node.id.equals("sample.Person#getName()"))
                            .count());
        }
    }

    @Test
    void missingProcessorFallsBackToCleanSourceModelAndDiagnostic(@TempDir Path project)
            throws Exception {
        Path person =
                Files.writeString(
                        project.resolve("Person.java"),
                        "import lombok.Getter; @Getter class Person { String name; }");
        var config = config(17, true);
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 17, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var runtime =
                new JavacIndexRuntime(
                        backend, new JavaFrameworkContext(project, List.of(person), config, false));
        var units = new ArrayList<JavaModelExtractor.UnitFacts>();
        assertTrue(runtime.extract(List.of(person), units::add).complete());
        assertTrue(
                units.getFirst().facts().nodes.stream()
                        .anyMatch(node -> node.id.equals("Person#name")));
        assertTrue(
                units.getFirst().facts().nodes.stream()
                        .noneMatch(node -> node.id.equals("Person#getName()")),
                "An unresolved annotation does not prove a generated getter");
        assertTrue(
                units.getFirst().diagnostics().stream()
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.code().equals("LOMBOK_COMPILE_FAILED")
                                                && diagnostic.severity().equals("error")));
    }

    private static ProjectConfig config(int release, boolean strict) throws Exception {
        var config = new ProjectConfig();
        config.setParserBackend("javac");
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        config.setLombokMode("ast");
        config.setLombokMode("compile");
        config.setLombokStrict(strict);
        return config;
    }
}
