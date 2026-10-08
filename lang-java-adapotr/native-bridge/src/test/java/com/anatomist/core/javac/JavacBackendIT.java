package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacBackendIT {
    @Test
    void targetWorkersPreserveDeclarationsAndConfirmedOverloads(@TempDir Path temporary)
            throws Exception {
        Path target =
                Files.writeString(
                        temporary.resolve("Example.java"),
                        """
                package sample;
                /** Original documentation. */
                class Example {
                    static String pick(long value) { return "primitive"; }
                    static String pick(Integer value) { return "boxed"; }
                    String run() { return pick(1); }
                }
                """);
        for (int release : List.of(8, 17, 25)) {
            var backend = backend(temporary, release);
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(target), contexts::add).complete());
            assertEquals(1, contexts.size());
            try (var model = new JavaModel(contexts.getFirst())) {
                var call =
                        model.syntax(target).nodes().stream()
                                .filter(node -> node.kind().equals("METHOD_INVOCATION"))
                                .findFirst()
                                .orElseThrow();
                var binding = model.unit(target).bindings().get(call.id());
                assertTrue(binding.declarationResolved());
                assertEquals(
                        "sample.Example#pick(long)",
                        JavaTypeNames.callableId(model.unit(target).types(), binding.symbol()));
                assertEquals(
                        "java.lang.String",
                        JavaTypeNames.erased(model.unit(target).types(), binding.expressionType()));
                assertEquals(6, model.source(target).position(call.start()).line());
                var declaration =
                        model.declaration(
                                ((JavaModel.FoundType) model.type("sample.Example")).declaration());
                assertTrue(model.source(target).text().contains("/** Original documentation. */"));
                assertEquals(
                        "class Example",
                        model.source(target)
                                .slice(
                                        new SourceSpan(
                                                declaration.span().start(),
                                                declaration.span().start() + 13)));
            }
        }
    }

    private static JavacBackend backend(Path project, int release) throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(
                Objects.requireNonNull(home(release), "target JDK " + release).toString());
        return new JavacBackend(
                JavacToolchain.resolve(config.javacOptions(), release, null),
                List.of(),
                List.of(project),
                config.javacOptions());
    }

    @Test
    void partialAnalysisKeepsValidUnitsAndRejectsErrorBindings(@TempDir Path temporary)
            throws Exception {
        Path good =
                Files.writeString(
                        temporary.resolve("Good.java"),
                        "class Good { String text() { return String.valueOf(1); } }");
        Path missing =
                Files.writeString(
                        temporary.resolve("Missing.java"),
                        "class Missing { void run() { Unknown.invoke(); } }");
        Path broken =
                Files.writeString(
                        temporary.resolve("Broken.java"),
                        "class Broken { void run() { int value = ; } }");
        var backend = backend(temporary, 17);
        var contexts = new ArrayList<JavaAnalysisContext>();
        var inventory = backend.analyzeFiles(List.of(good, missing, broken), contexts::add);
        var accepted = new HashSet<Path>();
        for (var context : contexts)
            for (var unit : context.units()) {
                accepted.add(unit.path());
                if (unit.path().equals(broken)) continue;
                try (var model = new JavaModel(context)) {
                    var call =
                            model.syntax(unit.path()).nodes().stream()
                                    .filter(node -> node.kind().equals("METHOD_INVOCATION"))
                                    .findFirst()
                                    .orElseThrow();
                    var binding = unit.bindings().get(call.id());
                    if (unit.path().equals(good)) assertTrue(binding.declarationResolved());
                    else assertTrue(binding == null || !binding.declarationResolved());
                }
            }
        assertEquals(Set.of(good, missing, broken), accepted);
        assertTrue(inventory.complete());
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.diagnostics().stream())
                        .anyMatch(
                                diagnostic ->
                                        broken.equals(diagnostic.path())
                                                && diagnostic.kind().equals("ERROR")));
        RuntimeException primary = new RuntimeException("consumer failed");
        assertSame(
                primary,
                assertThrows(
                        RuntimeException.class,
                        () ->
                                backend.analyzeFiles(
                                        List.of(good),
                                        context -> {
                                            throw primary;
                                        })));
        assertTrue(backend.analyzeFiles(List.of(good), context -> {}).complete());
    }

    @Test
    void genericDeclarationsAndNestedBinaryDescriptorsKeepTheirContracts(@TempDir Path temporary)
            throws Exception {
        Path source =
                Files.writeString(
                        temporary.resolve("Generic.java"),
                        "package p; class Generic<T extends Number> { T echo(T value) { return value; } static class Nested {} static Nested nested(Nested value) { return value; } }");
        Path use =
                Files.writeString(
                        temporary.resolve("Use.java"),
                        "package p; class Use { Integer run(Generic<Integer> value) { return value.echo(1); } Generic.Nested runNested(Generic.Nested value) { return Generic.nested(value); } }");
        var backend = backend(temporary, 17);
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend.analyzeFiles(List.of(source, use), contexts::add).complete());
        try (var model = new JavaModel(contexts.getFirst())) {
            var generic = ((JavaModel.FoundType) model.type("p.Generic")).declaration();
            var echo =
                    model.members(generic).stream()
                            .filter(ref -> model.declaration(ref).symbol().name().equals("echo"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "p.Generic#echo(java.lang.Number)",
                    new JavaIdentities(model).declaration(echo));
            for (var call : model.syntax(use).nodes()) {
                if (!call.kind().equals("METHOD_INVOCATION")) continue;
                var binding = model.unit(use).bindings().get(call.id());
                assertTrue(binding.declarationResolved());
                if (binding.name().equals("echo"))
                    assertEquals(
                            "java.lang.Integer",
                            JavaTypeNames.erased(
                                    model.unit(use).types(), binding.expressionType()));
                else assertEquals("(Lp/Generic$Nested;)Lp/Generic$Nested;", binding.descriptor());
            }
        }
    }

    @Test
    void validatesToolchainAndConfigWithoutEnvironmentFallback(@TempDir Path temporary)
            throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.setParserBackend("javac");
        assertTrue(
                assertThrows(
                                com.anatomist.core.JavaVersionException.class,
                                () -> JavacToolchain.resolve(config.javacOptions(), 8, null))
                        .getMessage()
                        .contains("JAVAC_JDK_HOME_REQUIRED"));
        config.setJavacExecution("native");
        assertTrue(
                assertThrows(
                                com.anatomist.core.JavaVersionException.class,
                                () -> JavacToolchain.resolve(config.javacOptions(), 8, null))
                        .getMessage()
                        .contains("JAVAC_NATIVE_UNSUPPORTED"));
        config.setJavacExecution("toolchain");
        config.setVmClasspath(false);
        assertTrue(
                assertThrows(
                                com.anatomist.core.JavaVersionException.class,
                                () -> JavacToolchain.resolve(config.javacOptions(), 8, null))
                        .getMessage()
                        .contains("JAVAC_PLATFORM_REQUIRED"));
        assertThrows(IllegalArgumentException.class, () -> config.setJavaHome("~/jdk"));
    }

    static Path home(int release) throws Exception {
        return com.anatomist.core.JavaFactsTestSupport.targetHome(release);
    }
}
