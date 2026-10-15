package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.*;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacParallelAnalysisIT {
    @Test
    void conflictRetriesItsLaneWhileSiblingStaysEncoded(@TempDir Path root) throws Exception {
        List<SourceRoot> roots = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        List<JavacMavenInputs.Module> modules = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            Path source =
                    Files.writeString(
                            Files.createDirectories(root.resolve(name + "/src"))
                                    .resolve("Type.java"),
                            "package "
                                    + (name.equals("b") ? "a" : name)
                                    + "; public class Type { public int value() { return 1; } }");
            files.add(source);
            roots.add(new SourceRoot(source.getParent(), name, SourceScope.MAIN));
            List<JavacMavenInputs.Dependency> dependencies =
                    name.equals("a")
                            ? List.of()
                            : List.of(new JavacMavenInputs.ReactorDependency("p:a:1", "a"));
            modules.add(
                    new JavacMavenInputs.Module(
                            name,
                            root.resolve(name),
                            "p:" + name + ":1",
                            25,
                            List.of(source.getParent()),
                            List.of(),
                            dependencies,
                            dependencies,
                            name));
        }
        List<JavaAnalysisContext> baseline = null;
        for (int threads : List.of(1, 2, 4, 8)) {
            var options =
                    new JavacOptions(
                            JavacBackendIT.home(25),
                            true,
                            true,
                            false,
                            false,
                            root.resolve("cache"),
                            threads,
                            1024);
            var backend =
                    new JavacBackend(
                            root,
                            JavacToolchain.resolve(options, 25, null),
                            List.of(),
                            roots,
                            options);
            backend.setMavenInputs(new JavacMavenInputs(modules, Map.of(), "conflict", true));
            List<JavaAnalysisContext> contexts = new ArrayList<>();
            assertTrue(backend.analyzeFiles(files, contexts::add).complete());
            assertEquals(
                    List.of("a", "b", "c"),
                    contexts.stream().map(JavaAnalysisContext::module).toList());
            assertEquals(4, backend.executionEvidence().size());
            assertEquals(
                    1,
                    backend.executionEvidence().stream()
                            .filter(row -> row.get("attempt_id").equals(1))
                            .count());
            assertTrue(
                    backend.executionEvidence().stream()
                            .filter(row -> row.get("attempt_id").equals(1))
                            .allMatch(row -> row.get("task_id").equals(2L)));
            assertTrue(
                    contexts.get(1).diagnostics().stream()
                            .anyMatch(d -> d.code().contains("duplicate.class")));
            if (baseline == null) baseline = contexts;
            else assertEquals(baseline, contexts);
            try (var paths = Files.list(options.cacheDirectory())) {
                assertTrue(
                        paths.noneMatch(
                                path -> path.getFileName().toString().startsWith(".worker-")));
            }
        }
    }

    @Test
    void readyBatchesPreserveModelsClasspathAndThreadIndependentIdentity(@TempDir Path root)
            throws Exception {
        List<SourceRoot> roots = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        List<JavacMavenInputs.Module> modules = new ArrayList<>();
        for (String name : List.of("a", "b", "c")) {
            Path source =
                    Files.writeString(
                            Files.createDirectories(root.resolve(name + "/src"))
                                    .resolve("Type.java"),
                            "package "
                                    + name
                                    + "; public class Type { public String value() { return "
                                    + (name.equals("b")
                                            ? "new a.Type().value()"
                                            : "String.valueOf(1)")
                                    + "; } }");
            roots.add(new SourceRoot(source.getParent(), name, SourceScope.MAIN));
            files.add(source);
            List<JavacMavenInputs.Dependency> dependencies =
                    name.equals("b")
                            ? List.of(new JavacMavenInputs.ReactorDependency("p:a:1", "a"))
                            : List.of();
            modules.add(
                    new JavacMavenInputs.Module(
                            name,
                            root.resolve(name),
                            "p:" + name + ":1",
                            25,
                            List.of(source.getParent()),
                            List.of(),
                            dependencies,
                            dependencies,
                            name));
        }
        var inputs = new JavacMavenInputs(modules, Map.of(), "parallel", true);
        List<JavaAnalysisContext> baseline = null;
        String identity = null;
        List<Map<String, Object>> evidence = null;
        for (int threads : List.of(1, 2, 4, 8)) {
            var options =
                    new JavacOptions(
                            JavacBackendIT.home(25),
                            true,
                            true,
                            false,
                            false,
                            root.resolve("cache"),
                            threads,
                            1024);
            var backend =
                    new JavacBackend(
                            root,
                            JavacToolchain.resolve(options, 25, null),
                            List.of(),
                            roots,
                            options);
            backend.setMavenInputs(inputs);
            List<JavaAnalysisContext> contexts = new ArrayList<>();
            assertTrue(backend.analyzeFiles(files, contexts::add).complete());
            assertEquals(
                    List.of("a", "c", "b"),
                    contexts.stream().map(JavaAnalysisContext::module).toList());
            assertEquals(
                    List.of("a", "b", "c"),
                    backend.taskClasspathEvidence().stream()
                            .map(item -> item.get("module"))
                            .toList());
            assertEquals(
                    Math.min(threads, 2),
                    backend.executionEvidence().stream()
                            .map(row -> row.get("pid"))
                            .distinct()
                            .count());
            if (baseline == null) {
                baseline = contexts;
                identity = backend.environmentFingerprint();
                evidence = backend.taskClasspathEvidence();
            } else {
                assertEquals(baseline, contexts);
                assertEquals(identity, backend.environmentFingerprint());
                assertEquals(evidence, backend.taskClasspathEvidence());
            }
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    backend.analyzeFiles(
                                            files,
                                            context -> {
                                                throw new IllegalStateException("consumer failed");
                                            }));
            assertEquals("consumer failed", failure.getMessage());
            try (var paths = Files.list(options.cacheDirectory())) {
                assertTrue(
                        paths.noneMatch(
                                path -> path.getFileName().toString().startsWith(".worker-")));
            }
            for (var execution : backend.executionEvidence())
                assertFalse(
                        ProcessHandle.of((long) execution.get("pid"))
                                .map(ProcessHandle::isAlive)
                                .orElse(false));
            assertTrue(backend.analyzeFiles(files, context -> {}).complete());
        }
    }
}
