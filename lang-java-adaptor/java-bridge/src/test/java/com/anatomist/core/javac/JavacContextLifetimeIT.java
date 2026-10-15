package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;

class JavacContextLifetimeIT {
    @Test
    void contextsFinishInOrderAndCannotKeepPriorUnitsAlive(@TempDir Path root) throws Exception {
        var files = new ArrayList<Path>();
        var roots = new ArrayList<SourceRoot>();
        for (int i = 0; i < 4; i++) {
            String module = "m" + i;
            Path directory = Files.createDirectories(root.resolve(module + "/src/main/java"));
            Files.writeString(
                    root.resolve(module + "/pom.xml"),
                    "<project><groupId>g</groupId><artifactId>"
                            + module
                            + "</artifactId></project>");
            files.add(
                    Files.writeString(
                            directory.resolve("C" + i + ".java"),
                            "class C" + i + " { String call() { return String.valueOf(1); } }"));
            roots.add(new SourceRoot(directory, module, SourceScope.MAIN));
        }
        var backend = backend(root, roots);
        var events = new ArrayList<String>();
        var references = new ArrayList<WeakReference<JavaUnit>>();
        assertTrue(
                backend.analyzeFiles(
                                files,
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        for (var unit : context.units()) {
                                            if (!context.publish().contains(unit.path())) continue;
                                            events.add(unit.path().getFileName().toString());
                                            var call =
                                                    model.syntax(unit.path()).nodes().stream()
                                                            .filter(
                                                                    node ->
                                                                            node.kind()
                                                                                    .equals(
                                                                                            "METHOD_INVOCATION"))
                                                            .findFirst()
                                                            .orElseThrow();
                                            var binding = unit.bindings().get(call.id());
                                            assertEquals(
                                                    "java.lang.String",
                                                    JavaTypeNames.erased(
                                                            unit.types(),
                                                            binding.expressionType()));
                                            references.add(new WeakReference<>(unit));
                                        }
                                    }
                                    events.add("closed");
                                })
                        .complete());
        assertEquals(
                List.of(
                        "C0.java", "closed", "C1.java", "closed", "C2.java", "closed", "C3.java",
                        "closed"),
                events);
        for (int attempt = 0;
                attempt < 20 && references.stream().anyMatch(r -> r.get() != null);
                attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue(references.stream().allMatch(r -> r.get() == null));
    }

    @Test
    void targetWorkersReuseProcessesWithoutLeakingSameNamedDeclarations(@TempDir Path root)
            throws Exception {
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(root.resolve("java" + release));
            var files = new ArrayList<Path>();
            var roots = new ArrayList<SourceRoot>();
            for (int i = 0; i < 9; i++) {
                String module = "m" + i;
                Path directory =
                        Files.createDirectories(project.resolve(module + "/src/main/java"));
                Files.writeString(
                        project.resolve(module + "/pom.xml"),
                        "<project><groupId>g</groupId><artifactId>"
                                + module
                                + "</artifactId></project>");
                files.add(
                        Files.writeString(
                                directory.resolve("Shared.java"),
                                "class Shared { String value"
                                        + i
                                        + "() { return String.valueOf("
                                        + i
                                        + "); } }"));
                roots.add(new SourceRoot(directory, module, SourceScope.MAIN));
            }
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            project,
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            roots,
                            config.javacOptions());
            var modules = new HashSet<String>();
            assertTrue(
                    backend.analyzeFiles(
                                    files,
                                    context -> {
                                        assertTrue(modules.add(context.module()));
                                        assertTrue(context.analysisFailure().isEmpty());
                                        assertTrue(
                                                context.diagnostics().stream()
                                                        .noneMatch(d -> d.kind().equals("ERROR")),
                                                context.diagnostics().toString());
                                        assertEquals(1, context.units().size());
                                        var members =
                                                context.units()
                                                        .getFirst()
                                                        .declarationInventory()
                                                        .getFirst()
                                                        .members();
                                        assertEquals(
                                                List.of("value" + context.module().substring(1)),
                                                members.stream()
                                                        .filter(
                                                                d ->
                                                                        d.symbol()
                                                                                .kind()
                                                                                .equals("METHOD"))
                                                        .map(d -> d.symbol().name())
                                                        .toList());
                                    })
                            .complete());
            assertEquals(9, modules.size());
            assertEquals(2, backend.workerLaunches(), "JDK " + release);
            tested++;
        }
        assertTrue(tested > 0);
    }

    @Test
    void consumerFailureClosesOwnedModelAndAllowsNextAnalysis(@TempDir Path root) throws Exception {
        Path source =
                Files.writeString(root.resolve("C.java"), "class C { int f() { return 1; } }");
        var backend = backend(root, List.of(new SourceRoot(root, ".", SourceScope.MAIN)));
        RuntimeException primary = new RuntimeException("extract");
        var escaped = new ArrayList<JavaModel>();
        var actual =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                backend.analyzeFiles(
                                        List.of(source),
                                        context -> {
                                            try (var model = new JavaModel(context)) {
                                                escaped.add(model);
                                                throw primary;
                                            }
                                        }));
        assertSame(primary, actual);
        assertThrows(IllegalStateException.class, () -> escaped.getFirst().type("C"));
        assertTrue(backend.analyzeFiles(List.of(source), context -> {}).complete());
    }

    private static JavacBackend backend(Path root, List<SourceRoot> roots) throws Exception {
        Path home = JavacBackendIT.home(17);
        org.junit.jupiter.api.Assumptions.assumeTrue(home != null);
        var config = new ProjectConfig();
        config.setJavaHome(home.toString());
        return new JavacBackend(
                root,
                JavacToolchain.resolve(config.javacOptions(), 17, null),
                List.of(),
                roots,
                config.javacOptions());
    }
}
