package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.extract.*;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacSignaturePlanIT {
    @Test
    void sourceChangingBetweenFingerprintAndCompilerOutputRejectsTheSession(@TempDir Path project)
            throws Exception {
        Path source =
                source(
                        project,
                        "lib",
                        "main",
                        "Library.java",
                        "public class Library { int value; }");
        pom(project, "lib", "", 25);
        var roots = List.of(new SourceRoot(source.getParent(), "lib", SourceScope.MAIN));
        try (var plan =
                new JavacSignaturePlan(
                        project,
                        roots,
                        project.resolve("cache"),
                        "source-before-analysis",
                        25,
                        List.of(),
                        true,
                        null)) {
            var context = plan.contexts(List.of(source), List.of(source)).getFirst();
            var input = plan.input(context);
            var timestamp = Files.getLastModifiedTime(source);
            Files.writeString(source, "public class Library { long value; }");
            Files.setLastModifiedTime(source, timestamp);
            var changed =
                    run(
                            project,
                            roots,
                            List.of(source),
                            Path.of(System.getProperty("java.home")),
                            25,
                            false);
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    plan.completed(
                                            context,
                                            input,
                                            changed.contexts().getFirst().units(),
                                            ""));
            assertTrue(failure.getMessage().contains("source changed"));
            assertThrows(IllegalArgumentException.class, plan::verifyInputs);
        }
    }

    private record Run(
            List<JavaModelExtractor.UnitFacts> facts,
            JavacBackend.SignatureStats stats,
            List<JavaAnalysisContext> contexts) {}

    @Test
    void moduleArchivesPreserveSourceIdentitiesAndFactsWithoutRepeatedBodies(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path home =
                    Objects.requireNonNull(JavacBackendIT.home(release), "required JDK " + release);
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path library =
                    source(
                            project,
                            "lib",
                            "main",
                            "Library.java",
                            """
                    package dep;
                    public class Library<T extends Number & Comparable<T>> {
                        public static final int LIMIT = 42;
                        public T value;
                        public T echo(T input) { return input; }
                        public static int pick(long input) { return 1; }
                        public static int pick(Integer input) { return 2; }
                        public class Inner { public Inner(T input) {} }
                    }
                    """);
            Path usage =
                    source(
                            project,
                            "app",
                            "main",
                            "Use.java",
                            """
                    package use;
                    public class Use {
                        java.util.function.LongUnaryOperator select = dep.Library::pick;
                        public Integer run(dep.Library<Integer> library) { library.value = 2; return library.echo(library.value); }
                        public dep.Library<Integer>.Inner make() { return new dep.Library<Integer>().new Inner(1); }
                    }
                    """);
            Path test =
                    source(
                            project,
                            "app",
                            "test",
                            "UseTest.java",
                            "package use; class UseTest { Integer test(Use value, dep.Library<Integer> library) { return value.run(library); } }");
            pom(project, "lib", "", release);
            pom(project, "app", "lib", release);
            var roots =
                    List.of(
                            new SourceRoot(library.getParent(), "lib", SourceScope.MAIN),
                            new SourceRoot(usage.getParent(), "app", SourceScope.MAIN),
                            new SourceRoot(test.getParent(), "app", SourceScope.TEST));
            var targets = List.of(library, usage, test);
            Run full = run(project, roots, targets, home, release, false);
            Run archived = run(project, roots, targets, home, release, true);
            assertEquals(canonical(full), canonical(archived), "JDK " + release);
            assertEquals(3, archived.stats().archivesUsed());
            assertEquals(0, archived.stats().fallbackModules());
            assertEquals(3, archived.stats().sourceAppearances());
            assertEquals(6, full.stats().sourceAppearances());
            var app =
                    archived.contexts().stream()
                            .filter(
                                    context ->
                                            context.module().equals("app")
                                                    && context.scope() == SourceScope.MAIN)
                            .findFirst()
                            .orElseThrow();
            JavaUnit catalogue =
                    app.units().stream()
                            .filter(unit -> unit.path().equals(library))
                            .findFirst()
                            .orElseThrow();
            assertTrue(catalogue.bindings().isEmpty());
            assertNull(catalogue.tree().fields().get("getTypeDecls"));
            assertEquals(
                    JavaContract.fingerprint(full.contexts().getFirst().units().getFirst()),
                    JavaContract.fingerprint(catalogue));
            assertTrue(
                    catalogue.types().entries().size()
                            < full.contexts()
                                    .getFirst()
                                    .units()
                                    .getFirst()
                                    .types()
                                    .entries()
                                    .size());
            Run incremental = run(project, roots, List.of(usage), home, release, true);
            assertEquals(canonical(full).get(usage), canonical(incremental).get(usage));
            assertEquals(
                    List.of(usage),
                    incremental.facts().stream()
                            .map(JavaModelExtractor.UnitFacts::source)
                            .toList());
            assertEquals(1, incremental.stats().archivesUsed());
            assertEquals(1, incremental.stats().restoredModules());
            assertEquals(1, incremental.stats().sourceAppearances());
            try (var files = Files.walk(project)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().equals("target")));
            }
        }
    }

    @Test
    void incompleteHeadersFallBackToOriginalSourceInsteadOfInventingCompleteArchives(
            @TempDir Path project) throws Exception {
        Path library =
                source(
                        project,
                        "lib",
                        "main",
                        "Library.java",
                        "package dep; public class Library { public Missing missing; public int known() { return 1; } }");
        Path usage =
                source(
                        project,
                        "app",
                        "main",
                        "Use.java",
                        "class Use { int run(dep.Library library) { return library.known(); } }");
        pom(project, "lib", "", 25);
        pom(project, "app", "lib", 25);
        var roots =
                List.of(
                        new SourceRoot(library.getParent(), "lib", SourceScope.MAIN),
                        new SourceRoot(usage.getParent(), "app", SourceScope.MAIN));
        Run full =
                run(
                        project,
                        roots,
                        List.of(usage),
                        Path.of(System.getProperty("java.home")),
                        25,
                        false);
        Run fallback =
                run(
                        project,
                        roots,
                        List.of(usage),
                        Path.of(System.getProperty("java.home")),
                        25,
                        true);
        assertEquals(canonical(full), canonical(fallback));
        assertEquals(0, fallback.stats().archivesUsed());
        assertEquals(1, fallback.stats().fallbackModules());
        assertTrue(
                fallback.contexts().getFirst().diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.kind().equals("ERROR")));
    }

    @Test
    void archivesNeverHideDuplicateReactorDeclarations(@TempDir Path project) throws Exception {
        Path library =
                source(
                        project,
                        "lib",
                        "main",
                        "Same.java",
                        "package dep; public class Same { public int value() { return 1; } }");
        Path usage =
                source(
                        project,
                        "app",
                        "main",
                        "Same.java",
                        "package dep; public class Same { public int value() { return 2; } }");
        pom(project, "lib", "", 25);
        pom(project, "app", "lib", 25);
        var roots =
                List.of(
                        new SourceRoot(library.getParent(), "lib", SourceScope.MAIN),
                        new SourceRoot(usage.getParent(), "app", SourceScope.MAIN));
        Run full =
                run(
                        project,
                        roots,
                        List.of(usage),
                        Path.of(System.getProperty("java.home")),
                        25,
                        false);
        Run archived =
                run(
                        project,
                        roots,
                        List.of(usage),
                        Path.of(System.getProperty("java.home")),
                        25,
                        true);
        assertEquals(canonical(full), canonical(archived));
        assertTrue(
                archived.contexts().getFirst().diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code().contains("duplicate.class")));
        assertEquals(0, archived.stats().archivesUsed());
        assertEquals(1, archived.stats().fallbackModules());
        assertTrue(
                archived.stats().fallbackReasons().get("app").getFirst().contains("conflicting"));
        assertEquals(4, archived.stats().sourceAppearances());
    }

    @Test
    void bindingKeepsSelectedCataloguesWithinTheSharedBudget(@TempDir Path project)
            throws Exception {
        Path a =
                source(
                        project,
                        "a",
                        "main",
                        "A.java",
                        "package dep; public class A { public int value() { return 1; } }");
        Path b =
                source(
                        project,
                        "b",
                        "main",
                        "B.java",
                        "package dep; public class B { public int value() { return new A().value(); } }");
        Path app =
                source(
                        project,
                        "app",
                        "main",
                        "Use.java",
                        "package use; public class Use { int run() { return new dep.B().value(); } }");
        pom(project, "a", "", 25);
        pom(project, "b", "a", 25);
        pom(project, "app", "b", 25);
        var roots =
                List.of(
                        new SourceRoot(a.getParent(), "a", SourceScope.MAIN),
                        new SourceRoot(b.getParent(), "b", SourceScope.MAIN),
                        new SourceRoot(app.getParent(), "app", SourceScope.MAIN));
        var sources = List.of(a, b, app);
        var full =
                run(project, roots, sources, Path.of(System.getProperty("java.home")), 25, false);
        Map<String, JavaAnalysisContext> contexts = new HashMap<>();
        full.contexts().forEach(context -> contexts.put(context.module(), context));
        long budget = 0;
        for (String module : List.of("a", "b"))
            budget +=
                    JavacSignaturePlan.catalogueWeight(
                            contexts.get(module).units().stream()
                                    .filter(unit -> unit.path().equals(module.equals("a") ? a : b))
                                    .map(JavaDeclarationView::of)
                                    .toList());
        try (var plan =
                new JavacSignaturePlan(
                        project,
                        roots,
                        project.resolve("cache"),
                        "eviction-test",
                        25,
                        List.of(),
                        true,
                        null,
                        budget - 1)) {
            for (var context : plan.contexts(sources, sources)) {
                if (context.module().equals("app")) assertFalse(plan.canAdmit(context));
                else assertTrue(plan.canAdmit(context));
                var input = plan.input(context);
                if (context.module().equals("app")) {
                    assertEquals(2, input.context().sources().size());
                    assertEquals(1, input.catalogues().size());
                    assertEquals(1, input.archivedModules());
                    assertTrue(
                            input.catalogues().stream()
                                    .allMatch(unit -> unit.bindings().isEmpty()));
                }
                plan.completed(context, input, contexts.get(context.module()).units(), "");
            }
            assertTrue(plan.catalogueEvictions() > 0);
            assertTrue(plan.catalogueReloads() > 0);
            assertTrue(plan.fallbackModules() > 0);
            assertEquals(4, plan.sourceAppearances());
        }
        try (var plan =
                new JavacSignaturePlan(
                        project,
                        roots,
                        project.resolve("pinned-cache"),
                        "pinned-test",
                        25,
                        List.of(),
                        true,
                        null,
                        budget - 1)) {
            for (var context : plan.contexts(sources, sources)) {
                var input = plan.input(context);
                try (var ignored = plan.pin(input)) {
                    plan.completed(context, input, contexts.get(context.module()).units(), "");
                    if (context.module().equals("b")) {
                        assertEquals(0, plan.catalogueEvictions());
                        assertTrue(
                                plan.fallbackReasons().get("b").getFirst().contains("in-flight"));
                        assertEquals(1, input.catalogues().size());
                    }
                }
            }
        }
    }

    @Test
    void signatureKeysCoverClasspathContentOrderModuleScopeAndEnvironment(@TempDir Path directory)
            throws Exception {
        Path source = Files.writeString(directory.resolve("Type.java"), "class Type {}");
        Path first = Files.writeString(directory.resolve("first.jar"), "first dependency");
        Path second = Files.writeString(directory.resolve("second.jar"), "second dependency");
        Path pom = Files.writeString(directory.resolve("pom.xml"), "module input");
        var context =
                new JavacContexts.Context(
                        "module",
                        SourceScope.MAIN,
                        List.of(source),
                        Set.of(source),
                        Set.of("dependency"));
        var model = JavacSignatureInputs.files(List.of(pom));
        var cp = JavacSignatureInputs.files(List.of(first, second));
        String key = JavacSignatureInputs.fingerprint(context, "environment", 25, cp, model);
        assertNotEquals(
                key,
                JavacSignatureInputs.fingerprint(
                        context,
                        "environment",
                        25,
                        JavacSignatureInputs.files(List.of(second, first)),
                        model));
        Files.writeString(first, "replacement dependency");
        assertNotEquals(
                key,
                JavacSignatureInputs.fingerprint(
                        context,
                        "environment",
                        25,
                        JavacSignatureInputs.files(List.of(first, second)),
                        model));
        assertNotEquals(
                key, JavacSignatureInputs.fingerprint(context, "new worker", 25, cp, model));
        assertNotEquals(
                key, JavacSignatureInputs.fingerprint(context, "environment", 17, cp, model));
        assertNotEquals(
                key,
                JavacSignatureInputs.fingerprint(
                        new JavacContexts.Context(
                                "module",
                                SourceScope.TEST,
                                List.of(source),
                                Set.of(source),
                                Set.of("dependency")),
                        "environment",
                        25,
                        cp,
                        model));
        Files.writeString(pom, "new module input");
        assertNotEquals(
                key,
                JavacSignatureInputs.fingerprint(
                        context, "environment", 25, cp, JavacSignatureInputs.files(List.of(pom))));
    }

    @Test
    void inputsChangingDuringConsumptionAbortTheSession(@TempDir Path project) throws Exception {
        Path source = source(project, "lib", "main", "Library.java", "public class Library {}");
        Path usage = source(project, "app", "main", "Use.java", "class Use extends Library {}");
        pom(project, "lib", "", 25);
        pom(project, "app", "lib", 25);
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        project,
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(
                                new SourceRoot(source.getParent(), "lib", SourceScope.MAIN),
                                new SourceRoot(usage.getParent(), "app", SourceScope.MAIN)),
                        config.javacOptions());
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                backend.analyzeFiles(
                                        List.of(usage),
                                        context -> {
                                            try {
                                                Files.writeString(
                                                        source,
                                                        "public class Library { int changed; }");
                                            } catch (java.io.IOException io) {
                                                throw new java.io.UncheckedIOException(io);
                                            }
                                        }));
        assertTrue(failure.getMessage().contains("source changed"), failure.getMessage());
    }

    private static Run run(
            Path project,
            List<SourceRoot> roots,
            List<Path> targets,
            Path home,
            int release,
            boolean signatures) {
        var config = new ProjectConfig();
        config.setJavaHome(home.toString());
        var backend =
                new JavacBackend(
                        project,
                        JavacToolchain.resolve(config.javacOptions(), release, null),
                        List.of(),
                        roots,
                        config.javacOptions());
        backend.setSourceSignatures(signatures);
        List<JavaModelExtractor.UnitFacts> facts = new ArrayList<>();
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        var extractor = new JavaModelExtractor(project, null);
        assertTrue(
                backend.analyzeFiles(
                                targets,
                                context -> {
                                    contexts.add(context);
                                    extractor.extract(context, facts::add);
                                })
                        .complete());
        return new Run(facts, backend.signatureStats(), contexts);
    }

    private static Map<Path, String> canonical(Run run) {
        Map<Path, String> values = new TreeMap<>();
        for (var unit : run.facts()) {
            List<String> facts = new ArrayList<>();
            unit.facts().nodes.forEach(node -> facts.add(fields(node)));
            unit.facts().edges.forEach(edge -> facts.add(fields(edge)));
            unit.facts().annotations.forEach(annotation -> facts.add(fields(annotation)));
            unit.facts().unresolvedCallSites.forEach(site -> facts.add(site.toString()));
            Collections.sort(facts);
            values.put(unit.source(), unit.contractHash() + facts);
        }
        return values;
    }

    // Compare every draft field, including newly added fields, at this test-only boundary.
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

    private static Path source(Path project, String module, String scope, String name, String text)
            throws Exception {
        return Files.writeString(
                Files.createDirectories(project.resolve(module + "/src/" + scope + "/java"))
                        .resolve(name),
                text);
    }

    private static void pom(Path project, String module, String dependency, int release)
            throws Exception {
        Files.writeString(
                project.resolve(module + "/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"
                        + module
                        + "</artifactId><properties><maven.compiler.release>"
                        + release
                        + "</maven.compiler.release></properties>"
                        + (dependency.isEmpty()
                                ? ""
                                : "<dependencies><dependency><groupId>g</groupId><artifactId>"
                                        + dependency
                                        + "</artifactId></dependency></dependencies>")
                        + "</project>");
    }
}
