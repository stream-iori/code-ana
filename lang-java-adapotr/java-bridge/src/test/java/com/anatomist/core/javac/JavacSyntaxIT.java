package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

class JavacSyntaxIT {
    private static void analyze(
            Path project, List<Path> files, int release, Consumer<JavaModel> check)
            throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), release, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        assertTrue(
                backend.analyzeFiles(
                                files,
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        check.accept(model);
                                    }
                                })
                        .complete());
    }

    private static List<JavaNode> nodes(JavaModel model, Path file, String kind) {
        return model.syntax(file).nodes().stream()
                .filter(node -> node.kind().equals(kind))
                .toList();
    }

    @Test
    void absentInvocationArgumentsDoNotBecomeExplicitEmptyArguments(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Sample.java"),
                        "class Generic { static <T> T identity(T value) { return value; } String run() { return identity(\"value\"); } String explicit() { return Generic.<String>identity(\"value\"); } }");
        analyze(
                project,
                List.of(source),
                17,
                model -> {
                    var calls = nodes(model, source, "METHOD_INVOCATION");
                    assertEquals(2, calls.size());
                    assertTrue(calls.getFirst().values("getTypeArguments").isEmpty());
                    assertEquals(
                            "String",
                            model.source(source)
                                    .slice(
                                            calls.getLast()
                                                    .trees("getTypeArguments")
                                                    .getFirst()
                                                    .span()));
                });
    }

    @Test
    void arrayConstructorAndParameterizedReferencesKeepTypeScopes(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Sample.java"),
                        "class Arrays { java.util.function.IntFunction<String[]> array = String[]::new; java.util.function.Supplier<java.util.ArrayList<String>> list = java.util.ArrayList<String>::new; }");
        analyze(
                project,
                List.of(source),
                8,
                model -> {
                    var references = nodes(model, source, "MEMBER_REFERENCE");
                    assertEquals(2, references.size());
                    assertTrue(
                            references.stream()
                                    .allMatch(node -> node.text("getMode").equals("NEW")));
                    assertEquals(
                            "String[]",
                            model.source(source)
                                    .slice(
                                            references
                                                    .getFirst()
                                                    .tree("getQualifierExpression")
                                                    .span()));
                    assertEquals(
                            "java.util.ArrayList<String>",
                            model.source(source)
                                    .slice(
                                            references
                                                    .getLast()
                                                    .tree("getQualifierExpression")
                                                    .span()));
                });
    }

    @Test
    void initializersKeepSourceRanges(@TempDir Path project) throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Sample.java"),
                        "class Init { static { System.out.println(1); } { System.out.println(2); } }");
        analyze(
                project,
                List.of(source),
                8,
                model -> {
                    var identities = new JavaIdentities(model);
                    var initializers =
                            nodes(model, source, "BLOCK").stream()
                                    .filter(node -> identities.isInitializer(source, node))
                                    .toList();
                    assertEquals(2, initializers.size());
                    assertTrue(initializers.stream().allMatch(node -> node.span().present()));
                    assertTrue(
                            initializers.stream()
                                    .anyMatch(node -> node.text("isStatic").equals("true")));
                });
    }

    @Test
    void javaSeventeenModuleVarPatternsAndTextBlocksArePreserved(@TempDir Path project)
            throws Exception {
        Path descriptor =
                Files.writeString(
                        project.resolve("module-info.java"),
                        "module sample.mod { requires transitive java.logging; exports p; opens p; uses java.lang.Runnable; }");
        Path source =
                Files.writeString(
                        project.resolve("Modern.java"),
                        "package p; public class Modern { String run(Object value) { var text = \"\"\"\nhello\n\"\"\"; java.util.function.Function<String,String> fn = (var x) -> x; if (value instanceof String pattern) return fn.apply(pattern); return switch(text.length()) { case 0 -> \"empty\"; default -> text; }; } }");
        analyze(
                project,
                List.of(descriptor, source),
                17,
                model -> {
                    assertEquals(1, nodes(model, descriptor, "MODULE").size());
                    assertEquals(1, nodes(model, descriptor, "REQUIRES").size());
                    assertEquals(1, nodes(model, source, "BINDING_PATTERN").size());
                    assertEquals(1, nodes(model, source, "SWITCH_EXPRESSION").size());
                    assertEquals(
                            1,
                            nodes(model, source, "STRING_LITERAL").stream()
                                    .filter(
                                            node ->
                                                    model.source(source)
                                                            .slice(node.span())
                                                            .startsWith("\"\"\""))
                                    .count());
                    var local =
                            nodes(model, source, "VARIABLE").stream()
                                    .filter(node -> node.text("getName").equals("text"))
                                    .findFirst()
                                    .orElseThrow();
                    var binding = model.unit(source).bindings().get(local.id());
                    assertEquals(
                            "java.lang.String",
                            JavaTypeNames.erased(
                                    model.unit(source).types(), binding.declaredType()));
                });
    }

    @Test
    void modernTreesKeepRecordPatternsAndFlexibleConstructorBodies(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Sample.java"),
                        """
                import module java.base;
                sealed interface Shape permits Point {}
                @SuppressWarnings({"unused"}) record Point(int x, int y) implements Shape {
                    @SuppressWarnings({"unused"}) Point { if (x < 0) throw new IllegalArgumentException(); }
                }
                class Parent { Parent(int value) {} }
                class Child extends Parent {
                    Child(int value) { int prepared = value + 1; super(prepared); }
                    String classify(Object value) {
                        return switch(value) {
                            case Point(int x, _) when x > 0 -> "positive";
                            case Point(int x, int y) -> "point";
                            case String s -> s;
                            case null, default -> "other";
                        };
                    }
                }
                """);
        analyze(
                project,
                List.of(source),
                25,
                model -> {
                    assertEquals(1, nodes(model, source, "RECORD").size());
                    assertEquals(2, nodes(model, source, "DECONSTRUCTION_PATTERN").size());
                    assertEquals(1, nodes(model, source, "ANY_PATTERN").size());
                    assertEquals(1, nodes(model, source, "SWITCH_EXPRESSION").size());
                    var child = ((JavaModel.FoundType) model.type("Child")).declaration();
                    var constructor =
                            model.members(child).stream()
                                    .filter(
                                            ref ->
                                                    model.declaration(ref)
                                                            .symbol()
                                                            .kind()
                                                            .equals("CONSTRUCTOR"))
                                    .findFirst()
                                    .orElseThrow();
                    var statements =
                            model.syntax(constructor)
                                    .orElseThrow()
                                    .tree("getBody")
                                    .trees("getStatements");
                    assertEquals("VARIABLE", statements.getFirst().kind());
                    assertEquals(
                            "super",
                            statements
                                    .get(1)
                                    .tree("getExpression")
                                    .tree("getMethodSelect")
                                    .text("getName"));
                    assertTrue(model.source(source).text().contains("import module java.base;"));
                });
    }

    @Test
    void compactSourceHasStableImplicitOwner(@TempDir Path project) throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Hello.java"),
                        "// class Hello is implicit here\nvoid main() { System.out.println(\"hello\"); }\n");
        analyze(
                project,
                List.of(source),
                25,
                model -> {
                    var hello = ((JavaModel.FoundType) model.type("Hello")).declaration();
                    assertEquals("Hello", new JavaIdentities(model).declaration(hello));
                    assertTrue(
                            model.members(hello).stream()
                                    .anyMatch(
                                            ref ->
                                                    model.declaration(ref)
                                                            .symbol()
                                                            .name()
                                                            .equals("main")));
                });
    }

    @Test
    void javaEightStatementsExpressionsAndCommentsAreConverted(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Sample.java"),
                        """
                import java.util.*;
                import java.io.*;
                @Deprecated class Basics<T extends Number & Comparable<T>> {
                    int[] data = new int[2];
                    static { System.out.println("init"); }
                    { data[0] = 1; }
                    enum Choice { A, B { int value() { return 2; } }; int value() { return 1; } }
                    @interface Flag { String value() default "default"; }
                    void single(@Flag("...") String value) {}
                    @Flag("test") void work() throws Exception {
                        int a = 1, b = 2;
                        List<? extends Number> values = new ArrayList<Integer>();
                        Runnable action = () -> System.out.println("lambda");
                        java.util.function.Supplier<String> supplier = String::new;
                        Object local = new Object() { int number() { return 1; } };
                        class Local { Local() { super(); } }
                        for (int i=0; i<2; i++) { a += data[i]; }
                        for (int value : data) a ^= value;
                        while (a > 0) a--;
                        do { a++; } while (a < 1);
                        outer: for (;;) { if (a == b) break outer; else continue; }
                        synchronized (this) { assert a != b : "different"; }
                        try (ByteArrayInputStream stream = new ByteArrayInputStream(new byte[]{1,2})) {
                            switch(a) { case 1: a = -b; break; default: a = ~b; }
                        } catch (IOException | IllegalArgumentException problem) { throw problem; }
                        finally { action.run(); }
                        String text = (String) (true ? "yes" : "no");
                        boolean check = local instanceof Local;
                        long number = 1L << 2;
                        double floating = 1.5;
                        char character = '\\n';
                        // String comment-like content must not be read as a comment.
                        String raw = "/* literal */";
                    }
                }
                """);
        analyze(
                project,
                List.of(source),
                8,
                model -> {
                    assertEquals(1, nodes(model, source, "LAMBDA_EXPRESSION").size());
                    assertEquals(1, nodes(model, source, "MEMBER_REFERENCE").size());
                    assertEquals(1, nodes(model, source, "TRY").size());
                    for (String kind :
                            List.of(
                                    "FOR_LOOP",
                                    "ENHANCED_FOR_LOOP",
                                    "WHILE_LOOP",
                                    "DO_WHILE_LOOP",
                                    "SYNCHRONIZED",
                                    "ASSERT",
                                    "SWITCH",
                                    "CATCH",
                                    "CONDITIONAL_EXPRESSION",
                                    "TYPE_CAST"))
                        assertFalse(nodes(model, source, kind).isEmpty(), kind);
                    var choice = ((JavaModel.FoundType) model.type("Basics.Choice")).declaration();
                    assertEquals(
                            2,
                            model.members(choice).stream()
                                    .filter(
                                            ref ->
                                                    model.declaration(ref)
                                                            .symbol()
                                                            .kind()
                                                            .equals("ENUM_CONSTANT"))
                                    .count());
                    var basics = ((JavaModel.FoundType) model.type("Basics")).declaration();
                    var single =
                            model.members(basics).stream()
                                    .filter(
                                            ref ->
                                                    model.declaration(ref)
                                                            .symbol()
                                                            .name()
                                                            .equals("single"))
                                    .findFirst()
                                    .orElseThrow();
                    assertFalse(model.declaration(single).symbol().variadic());
                    assertTrue(
                            model.source(source).text().contains("// String comment-like content"));
                    assertTrue(
                            nodes(model, source, "STRING_LITERAL").stream()
                                    .anyMatch(
                                            node ->
                                                    model.source(source)
                                                            .slice(node.span())
                                                            .equals("\"/* literal */\"")));
                });
    }
}
