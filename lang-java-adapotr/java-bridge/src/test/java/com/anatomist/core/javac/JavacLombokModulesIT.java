package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.application.JavacIndexRuntime;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacLombokModulesIT {
    private record Run(
            List<String> facts,
            JavacBackend.SignatureStats stats,
            List<Map<String, Object>> tasks,
            List<IndexDiagnostic> diagnostics) {}

    @Test
    void compiledModulesPreserveFactsAcrossArchivesCacheAndSignatureChanges(@TempDir Path project)
            throws Exception {
        Path library =
                source(
                        project,
                        "lib",
                        "Person.java",
                        "package p; @lombok.Value @lombok.Builder public class Person { String name; }");
        Path delegate =
                source(
                        project,
                        "middle",
                        "Box.java",
                        "package p; public class Box { @lombok.experimental.Delegate private final Person person = Person.builder().name(\"x\").build(); }");
        Path usage =
                source(
                        project,
                        "app",
                        "Use.java",
                        "package p; public class Use { public String run(Box box) { return box.getName(); } }");
        pom(project, "lib", "");
        pom(project, "middle", "lib");
        pom(project, "app", "middle");
        List<Path> files = List.of(library, delegate, usage);
        Run baseline = run(project, files, false);
        Run optimized = run(project, files, true);
        assertEquals(baseline.facts(), optimized.facts());
        assertTrue(optimized.stats().archivesUsed() >= 2, optimized.stats().toString());
        assertTrue(optimized.stats().sourceAppearances() < baseline.stats().sourceAppearances());
        assertTrue(
                optimized.diagnostics().stream()
                        .noneMatch(
                                d ->
                                        d.code().equals("LOMBOK_COMPILE_FAILED")
                                                || d.code().equals("LOMBOK_FEATURE_UNSUPPORTED")),
                optimized.diagnostics().toString());
        assertTrue(optimized.facts().stream().anyMatch(f -> f.contains("p.Box#getName()")));
        assertEquals(optimized.facts(), run(project, files, true).facts());
        Files.writeString(
                library,
                "package p; @lombok.Value @lombok.Builder public class Person { CharSequence name; }");
        Files.writeString(
                usage,
                "package p; public class Use { public CharSequence run(Box box) { return box.getName(); } }");
        assertEquals(run(project, files, false).facts(), run(project, files, true).facts());
    }

    @Test
    void compiledSignaturesRemainReusableWithUnmodeledBodies(@TempDir Path project)
            throws Exception {
        Path library =
                source(
                        project,
                        "lib",
                        "Person.java",
                        "package p; @lombok.Getter public class Person { String name; "
                                + "@lombok.SneakyThrows public void read() { throw new java.io.IOException(); } }");
        Path middle = source(project, "middle", "Middle.java", "package p; public class Middle {}");
        Path usage =
                source(
                        project,
                        "app",
                        "Use.java",
                        "package p; public class Use { public String run(Person person) { person.read(); return person.getName(); } }");
        pom(project, "lib", "");
        pom(project, "middle", "lib");
        pom(project, "app", "middle");
        List<Path> files = List.of(library, middle, usage);
        Run baseline = run(project, files, false);
        Run optimized = run(project, files, true);
        assertEquals(baseline.facts(), optimized.facts());
        assertTrue(optimized.stats().archivesUsed() >= 2, optimized.stats().toString());
        assertTrue(optimized.facts().stream().anyMatch(f -> f.contains("sneaky_throws")));
        assertTrue(
                optimized.diagnostics().stream()
                        .noneMatch(d -> d.code().equals("LOMBOK_COMPILE_FAILED")));
    }

    @Test
    void plainModuleDoesNotRequireLombokProcessor(@TempDir Path project) throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Plain.java"), "class Plain { int value() { return 1; } }");
        var config = config();
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 8, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var runtime =
                new JavacIndexRuntime(
                        backend, new JavaFrameworkContext(project, List.of(source), config, false));
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        assertTrue(
                runtime.extract(List.of(source), unit -> diagnostics.addAll(unit.diagnostics()))
                        .complete());
        assertTrue(diagnostics.stream().noneMatch(d -> d.code().equals("LOMBOK_COMPILE_FAILED")));
    }

    private static Run run(Path project, List<Path> files, boolean signatures) throws Exception {
        var config = config();
        List<SourceRoot> roots = new ArrayList<>();
        for (String module : List.of("lib", "middle", "app"))
            roots.add(
                    new SourceRoot(
                            project.resolve(module + "/src/main/java"), module, SourceScope.MAIN));
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.6/lombok-1.18.6.jar");
        assertTrue(Files.isRegularFile(lombok));
        var backend =
                new JavacBackend(
                        project,
                        JavacToolchain.resolve(config.javacOptions(), 8, null),
                        List.of(lombok),
                        roots,
                        config.javacOptions());
        backend.setSourceSignatures(signatures);
        var runtime =
                new JavacIndexRuntime(
                        backend, new JavaFrameworkContext(project, files, config, false));
        List<String> facts = new ArrayList<>();
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        assertTrue(
                runtime.extract(
                                files,
                                unit -> {
                                    facts.add(unit.source() + "=" + unit.contractHash());
                                    unit.facts().nodes.forEach(node -> facts.add(fields(node)));
                                    unit.facts().edges.forEach(edge -> facts.add(fields(edge)));
                                    unit.facts()
                                            .annotations
                                            .forEach(annotation -> facts.add(fields(annotation)));
                                    diagnostics.addAll(unit.diagnostics());
                                })
                        .complete());
        Collections.sort(facts);
        return new Run(
                facts, backend.signatureStats(), backend.taskClasspathEvidence(), diagnostics);
    }

    private static String fields(Object value) {
        Map<String, Object> fields = new TreeMap<>();
        for (var field : value.getClass().getFields()) {
            try {
                fields.put(field.getName(), field.get(value));
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        return Json.writeCompact(fields);
    }

    private static ProjectConfig config() throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(8)).toString());
        config.setLombokMode("ast");
        config.setLombokMode("compile");
        config.setLombokStrict(true);
        return config;
    }

    private static Path source(Path project, String module, String name, String contents)
            throws Exception {
        return Files.writeString(
                Files.createDirectories(project.resolve(module + "/src/main/java")).resolve(name),
                contents);
    }

    private static void pom(Path project, String module, String dependency) throws Exception {
        Files.writeString(
                project.resolve(module + "/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"
                        + module
                        + "</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties>"
                        + (dependency.isEmpty()
                                ? ""
                                : "<dependencies><dependency><groupId>g</groupId><artifactId>"
                                        + dependency
                                        + "</artifactId><version>1</version></dependency></dependencies>")
                        + "</project>");
    }
}
