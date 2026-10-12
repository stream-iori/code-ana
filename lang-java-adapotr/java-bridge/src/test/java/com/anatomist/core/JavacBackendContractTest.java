package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.JavacToolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parser lifecycle contracts expressed through compiler facts, without an AST adapter. */
class JavacBackendContractTest {
    @Test
    void consumerFailureRetainsOriginalMemoryErrorAndReleasesSession(@TempDir Path directory)
            throws Exception {
        var source = Files.writeString(directory.resolve("A.java"), "class A {}");
        var backend = JavaFactsTestSupport.backend(directory);
        var exhausted = new OutOfMemoryError("injected");
        assertSame(
                exhausted,
                assertThrows(
                        OutOfMemoryError.class,
                        () ->
                                backend.analyzeFiles(
                                        List.of(source),
                                        context -> {
                                            throw exhausted;
                                        })));
        assertEquals(0, exhausted.getSuppressed().length);
        assertTrue(backend.analyzeFiles(List.of(source), context -> {}).complete());
    }

    @Test
    void analyzesEveryRequestedSourceFile(@TempDir Path directory) throws Exception {
        var a = Files.writeString(directory.resolve("A.java"), "package p; class A {}");
        var b = Files.writeString(directory.resolve("B.java"), "package p; class B {}");
        var names = new ArrayList<String>();
        var inventory =
                JavaFactsTestSupport.backend(directory)
                        .analyzeFiles(
                                List.of(a, b),
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        for (var path : context.publish())
                                            for (var ref : model.declarations(path)) {
                                                var symbol = model.declaration(ref).symbol();
                                                if (symbol.kind().equals("CLASS"))
                                                    names.add(symbol.name());
                                            }
                                    }
                                });
        names.sort(String::compareTo);
        assertEquals(List.of("A", "B"), names);
        assertEquals(2, inventory.parsedFiles());
    }

    @Test
    void selectedSourceDeclarationsCarryCompilerBindings(@TempDir Path directory) throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "A.java",
                        "package p; class A { void foo() {} void run() { foo(); } }");
        var unit = published(context, directory.resolve("A.java"));
        try (var model = new JavaModel(context)) {
            var call =
                    model.syntax(unit.path()).nodes().stream()
                            .filter(node -> node.kind().equals("METHOD_INVOCATION"))
                            .findFirst()
                            .orElseThrow();
            var binding = unit.bindings().get(call.id());
            assertTrue(binding.declarationResolved());
            assertEquals("p.A", binding.owner());
            assertEquals("foo", binding.name());
            assertEquals("()V", binding.descriptor());
        }
    }

    @Test
    void crossFileSourceCanSelectPlatformMembers(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("Dep.java"),
                "package p; class Dep { java.util.Optional<String> value() { return java.util.Optional.empty(); } }");
        var source =
                Files.writeString(
                        directory.resolve("Use.java"),
                        "package p; class Use { Object run(Dep dep) { return dep.value(); } }");
        var contexts = new ArrayList<JavaAnalysisContext>();
        JavaFactsTestSupport.backend(directory)
                .analyzeFiles(List.of(source, directory.resolve("Dep.java")), contexts::add);
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.units().stream())
                        .flatMap(unit -> unit.bindings().values().stream())
                        .anyMatch(
                                binding ->
                                        binding.declarationResolved()
                                                && binding.owner().equals("java.util.Optional")
                                                && binding.name().equals("empty")));
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.units().stream())
                        .flatMap(unit -> unit.bindings().values().stream())
                        .anyMatch(
                                binding ->
                                        binding.declarationResolved()
                                                && binding.owner().equals("p.Dep")
                                                && binding.name().equals("value")));
    }

    @Test
    void classpathDirectorySuppliesFieldType(@TempDir Path directory) throws Exception {
        var classes = Files.createDirectories(directory.resolve("classes/com/dep"));
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "com/dep/External",
                null,
                "java/lang/Object",
                null);
        writer.visitEnd();
        Files.write(classes.resolve("External.class"), writer.toByteArray());
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Use.java",
                        "class Use { com.dep.External value; }",
                        List.of(directory.resolve("classes")));
        var unit = published(context, directory.resolve("Use.java"));
        try (var model = new JavaModel(context)) {
            var field =
                    model.declarations(unit.path()).stream()
                            .map(model::declaration)
                            .filter(declaration -> declaration.symbol().kind().equals("FIELD"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "com.dep.External",
                    JavaTypeNames.erased(unit.types(), field.symbol().declaredType()));
        }
    }

    @Test
    void invalidSyntaxRetainsRecoveryModelAndOriginalDiagnostics(@TempDir Path directory)
            throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Broken.java",
                        "package p; class Broken { void run() {} throws Exception }");
        assertFalse(context.units().isEmpty());
        assertTrue(
                context.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.kind().equals("ERROR")));
        assertTrue(
                context.diagnostics().stream()
                        .anyMatch(
                                diagnostic ->
                                        directory
                                                .resolve("Broken.java")
                                                .equals(diagnostic.path())));
    }

    @Test
    void inventoryAccountsForRecoveryAndMissingInput(@TempDir Path directory) throws Exception {
        var valid = Files.writeString(directory.resolve("Valid.java"), "class Valid {}");
        var invalid = Files.writeString(directory.resolve("Broken.java"), "class Broken {");
        var contexts = new ArrayList<JavaAnalysisContext>();
        var inventory =
                JavaFactsTestSupport.backend(directory)
                        .analyzeFiles(List.of(valid, invalid), contexts::add);
        assertEquals(2, inventory.scannedFiles());
        assertEquals(2, inventory.attemptedFiles());
        assertEquals(2, inventory.parsedFiles());
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.diagnostics().stream())
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.kind().equals("ERROR")
                                                && diagnostic.path().equals(invalid)));
    }

    @Test
    void unsupportedReleaseCannotUseAHostCompiler(@TempDir Path directory) {
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        for (int release : List.of(7, 16, 21, 26)) {
            var error =
                    assertThrows(
                            JavaVersionException.class,
                            () -> JavacToolchain.resolve(config.javacOptions(), release, null));
            assertTrue(error.getMessage().contains("JAVAC_VERSION_UNSUPPORTED"));
        }
    }

    @Test
    void selectedJava25SupportsRecordDeclarations(@TempDir Path directory) throws Exception {
        var context =
                JavaFactsTestSupport.analyze(directory, "A.java", "record A(String value) {}");
        try (var model = new JavaModel(context)) {
            assertTrue(
                    model.declarations(directory.resolve("A.java")).stream()
                            .map(model::declaration)
                            .anyMatch(declaration -> declaration.symbol().kind().equals("RECORD")));
        }
    }

    private static JavaUnit published(JavaAnalysisContext context, Path path) {
        return context.units().stream()
                .filter(unit -> unit.path().equals(path))
                .findFirst()
                .orElseThrow();
    }
}
