package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaCallFacts;
import com.anatomist.extract.JavaDeclarationFacts;
import com.anatomist.extract.JavaReflectionFacts;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaCallFactsIT {
    @Test
    void explicitConstructorInvocationsKeepWholeStatementCoordinates(@TempDir Path directory)
            throws Exception {
        String source =
                """
                class Parent { Parent(int value) {} }
                class Child extends Parent {
                    Child() { this(1); }
                    Child(int value) { super(value); }
                    void run() { String.valueOf(1); }
                }
                """;
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("constructors" + release));
            Path file = Files.writeString(project.resolve("Child.java"), source);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                var result = new ExtractionResult();
                assertTrue(
                        new JavaCallFacts(model, project, ".", "MAIN")
                                .extract(file, result)
                                .isEmpty());
                var constructors =
                        result.edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.callKind.equals("CONSTRUCTOR"))
                                .toList();
                assertEquals(2, constructors.size(), "JDK " + release);
                var lines = source.lines().toList();
                for (var edge : constructors) {
                    assertEquals(
                            ';',
                            lines.get(edge.endLine - 1).charAt(edge.endColumn - 1),
                            "JDK " + release);
                    assertEquals("EXTRACTED", edge.confidence);
                }
                var ordinary =
                        result.edges.stream()
                                .filter(
                                        edge ->
                                                "java.lang.String#valueOf(int)"
                                                        .equals(edge.externalTargetFqn))
                                .findFirst()
                                .orElseThrow();
                assertEquals(')', lines.get(ordinary.endLine - 1).charAt(ordinary.endColumn - 1));
            }
        }
    }

    @Test
    void initializerRegionsOwnCallsAndCallbacksWithOriginalCoordinates(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("initializers" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Initializers.java"),
                            """
                    class Initializers {
                        static { String.valueOf(1); Runnable action = () -> String.valueOf(2); }
                        { String.valueOf(3); }
                        void run() { String.valueOf(4); }
                    }
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                var result = new ExtractionResult();
                new JavaDeclarationFacts(model, project, ".", "MAIN").extract(file, result);
                assertTrue(
                        new JavaCallFacts(model, project, ".", "MAIN")
                                .extract(file, result)
                                .isEmpty());
                assertEquals(
                        Set.of(
                                "Initializers#<clinit>@(line 2,col 5)",
                                "Initializers#<initializer>@(line 3,col 5)"),
                        result.nodes.stream()
                                .filter(node -> node.kind.equals("SOURCE_REGION"))
                                .map(node -> node.id)
                                .collect(java.util.stream.Collectors.toSet()));
                assertEquals(
                        4,
                        result.edges.stream()
                                .filter(edge -> edge.relation.equals("CALLS"))
                                .count());
                assertTrue(
                        result.edges.stream()
                                .filter(edge -> edge.relation.equals("CALLS"))
                                .allMatch(edge -> edge.sourceId.startsWith("Initializers#")));
                assertTrue(
                        result.nodes.stream()
                                .anyMatch(
                                        node ->
                                                node.kind.equals("LAMBDA")
                                                        && node.id.startsWith(
                                                                "Initializers#<clinit>@(line 2,col 5)$lambda@")));
                assertEquals(
                        2,
                        result.declarations.stream()
                                .filter(
                                        declaration ->
                                                declaration.declarationKind.equals("initializer")
                                                        && declaration.bindingResolved
                                                        && declaration.directMember
                                                        && declaration.beginColumn == 5)
                                .count());
            }
        }
    }

    @Test
    void sourceVarargsKeepPublicIdentityWhileExternalCallsKeepArrayDescriptors(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("varargs" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Variable.java"),
                            """
                    class Variable {
                        public Variable(String... values) {}
                        public void accept(String... values) {}
                        void run() throws Exception {
                            accept("a", "b");
                            accept(new String[]{"a"});
                            new Variable("a", "b");
                            java.util.Arrays.asList("a", "b");
                            Variable.class.getMethod("accept", String[].class);
                            Variable.class.getConstructor(String[].class);
                        }
                    }
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            {
                var contexts = new ArrayList<JavaAnalysisContext>();
                assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
                try (var model = new JavaModel(contexts.getFirst())) {
                    var result = new ExtractionResult();
                    new JavaDeclarationFacts(model, project, ".", "MAIN").extract(file, result);
                    assertTrue(
                            new JavaCallFacts(model, project, ".", "MAIN")
                                    .extract(file, result)
                                    .isEmpty());
                    assertTrue(
                            result.nodes.stream()
                                    .anyMatch(
                                            node ->
                                                    "Variable#accept(java.lang.String...)"
                                                            .equals(node.id)));
                    assertTrue(
                            result.nodes.stream()
                                    .anyMatch(
                                            node ->
                                                    "Variable#Variable(java.lang.String...)"
                                                            .equals(node.id)));
                    assertEquals(
                            2,
                            result.edges.stream()
                                    .filter(
                                            edge ->
                                                    "CALLS".equals(edge.relation)
                                                            && "Variable#accept(java.lang.String...)"
                                                                    .equals(edge.targetId))
                                    .count());
                    assertTrue(
                            result.edges.stream()
                                    .anyMatch(
                                            edge ->
                                                    "java.util.Arrays#asList(java.lang.Object[])"
                                                            .equals(edge.externalTargetFqn)));
                    var reflection = new ExtractionResult();
                    new JavaReflectionFacts(model, project, null).extract(file, reflection);
                    assertEquals(
                            Set.of(
                                    "Variable#accept(java.lang.String...)",
                                    "Variable#Variable(java.lang.String...)"),
                            reflection.edges.stream()
                                    .map(edge -> edge.targetId)
                                    .collect(java.util.stream.Collectors.toSet()));
                }
            }
        }
    }

    @Test
    void detachedCallsPreserveCompilerOverloadsLocalOwnersCallbacksAndUnresolvedSites(
            @TempDir Path directory) throws Exception {
        String source =
                """
                import java.util.Map;
                import java.util.function.*;
                class Example<T extends Number> {
                    T echo(T value) { return value; }
                    static long pick(long value) { return 1; }
                    static long pick(Integer value) { return 2; }
                    long run() {
                        class Local { long read() { return pick(1); } }
                        return new Local().read();
                    }
                    LongUnaryOperator overloaded = Example::pick;
                    Function<Map.Entry<String,Integer>,String> key = Map.Entry::getKey;
                    Predicate<String> empty = String::isEmpty;
                    Function<String,StringBuilder> create = StringBuilder::new;
                    IntFunction<String[]> array = String[]::new;
                    Runnable callback = () -> System.out.println("x");
                    void conditional(boolean flag) {
                        if (flag) { String.valueOf(1); } else { String.valueOf(2); }
                        absent();
                    }
                }
                enum Choice { ITEM }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(directory.resolve("java" + release));
            Path file = Files.writeString(project.resolve("Example.java"), source);
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
            var result = new ExtractionResult();
            try (var model = new JavaModel(contexts.getFirst())) {
                new JavaDeclarationFacts(model, project, ".", "MAIN").extract(file, result);
                var unresolved =
                        new JavaCallFacts(model, project, ".", "MAIN").extract(file, result);
                assertEquals(1, unresolved.size(), "JDK " + release + ": " + unresolved);
                assertEquals("absent()", model.source(file).slice(unresolved.getFirst().span()));
                assertEquals("Example#conditional(boolean)", unresolved.getFirst().owner());
                var calls =
                        result.edges.stream()
                                .filter(edge -> edge.relation.equals("CALLS"))
                                .toList();
                assertTrue(calls.stream().allMatch(edge -> edge.confidence.equals("EXTRACTED")));
                assertTrue(
                        calls.stream()
                                .anyMatch(edge -> "Example#pick(long)".equals(edge.targetId)));
                assertFalse(
                        calls.stream()
                                .anyMatch(
                                        edge ->
                                                "Example#pick(java.lang.Integer)"
                                                        .equals(edge.targetId)));
                assertEquals(
                        4,
                        calls.stream()
                                .filter(edge -> edge.sourceId.contains("$methodref"))
                                .count());
                assertTrue(
                        calls.stream()
                                .anyMatch(
                                        edge ->
                                                "java.util.Map$Entry#getKey()"
                                                        .equals(edge.externalTargetFqn)));
                assertTrue(
                        calls.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.contains("$lambda")
                                                        && "java.io.PrintStream#println(java.lang.String)"
                                                                .equals(edge.externalTargetFqn)));
                assertTrue(
                        calls.stream()
                                .anyMatch(
                                        edge ->
                                                edge.targetId != null
                                                        && edge.targetId.contains("$local@")
                                                        && edge.targetId.endsWith("#read()")));
                assertTrue(calls.stream().anyMatch(edge -> "if-then@L18".equals(edge.context)));
                assertTrue(calls.stream().anyMatch(edge -> "if-else@L18".equals(edge.context)));
                assertTrue(
                        result.nodes.stream()
                                .filter(
                                        node ->
                                                node.kind.equals("METHOD_REF")
                                                        && node.label.endsWith(":new"))
                                .anyMatch(node -> node.metadata.contains("array_constructor")));
                assertTrue(
                        calls.stream()
                                .allMatch(
                                        edge ->
                                                edge.beginLine != null
                                                        && edge.beginLine > 0
                                                        && edge.beginColumn > 0));
            }
            tested++;
        }
        assertTrue(tested > 0);
    }
}
