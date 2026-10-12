package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ContentHash;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaSourceModelIT {
    @Test
    void missingParameterTypesKeepSourceIdentitiesWithoutResolvedBindings(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("missing" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Example.java"),
                            """
                    package p;
                    import missing.api.Alpha;
                    import missing.api.Beta;
                    class Example extends absent.Base {
                      void run(Alpha input) {}
                      void run(Beta input) {}
                      void arrays(Alpha[] first, Beta... rest) {}
                      void qualified(absent.Other input) {}
                      void nested(Alpha.Inner input) {}
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
            backend.analyzeFiles(List.of(file), contexts::add);
            try (var model = new JavaModel(contexts.getFirst())) {
                var facts = new com.anatomist.model.ExtractionResult();
                new com.anatomist.extract.JavaDeclarationFacts(model, project, ".", "MAIN")
                        .extract(file, facts);
                Set<String> expected =
                        Set.of(
                                "p.Example#run(missing.api.Alpha)",
                                "p.Example#run(missing.api.Beta)",
                                "p.Example#arrays(missing.api.Alpha[],missing.api.Beta...)",
                                "p.Example#qualified(absent.Other)",
                                "p.Example#nested(missing.api.Alpha.Inner)");
                Set<String> actual = new HashSet<>();
                for (var declaration : facts.declarations) {
                    if (!expected.contains(declaration.symbolId)) continue;
                    actual.add(declaration.symbolId);
                    assertFalse(
                            declaration.bindingResolved,
                            "JDK " + release + ": " + declaration.symbolId);
                }
                assertEquals(expected, actual, "JDK " + release);
            }
        }
    }

    @Test
    void enumConstantsKeepCompilerModifiersImplicit(@TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("enum" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Choice.java"),
                            "enum Choice { ITEM { void run() {} }; abstract void run(); }");
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
                var facts = new com.anatomist.model.ExtractionResult();
                new com.anatomist.extract.JavaDeclarationFacts(model, project, ".", "MAIN")
                        .extract(file, facts);
                var constant =
                        facts.declarations.stream()
                                .filter(value -> value.symbolId.equals("Choice#ITEM"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(List.of(), constant.declaredModifiers, "JDK " + release);
                assertEquals(List.of("public", "static", "final"), constant.implicitModifiers);
                assertTrue(
                        facts.nodes.stream()
                                .anyMatch(
                                        value ->
                                                value.id.startsWith("Choice#ITEM$anon@")
                                                        && value.id.endsWith("#run()")));
            }
        }
    }

    @Test
    void independentModelResolvesSourceDeclarationsAndKeepsUtf16SnapshotPositions(
            @TempDir Path directory) throws Exception {
        String text =
                "package sample;\r\nclass Box<T extends Number & Comparable<T>> {\r\n\tString marker = \"中文😀\";\r\n\tT pick(T value) { return value; }\r\n\tstatic class Inner {}\r\n}\r\n";
        Path file = Files.writeString(directory.resolve("Box.java"), text);
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(directory),
                        config.javacOptions());
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
        JavaModel model = new JavaModel(contexts.getFirst());
        try (model) {
            JavaModel.DeclarationRef box =
                    ((JavaModel.FoundType) model.type("sample.Box")).declaration();
            assertEquals("sample.Box", model.declaration(box).binaryName());
            assertEquals(model.type("sample.Box.Inner"), model.type("sample.Box$Inner"));
            var method =
                    model.declaration(box).members().stream()
                            .filter(d -> d.symbol().name().equals("pick"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "sample.Box#pick(java.lang.Number)",
                    JavaTypeNames.callableId(model.unit(file).types(), method.symbol()));
            assertEquals(
                    "T pick(T value) { return value; }", model.source(file).slice(method.span()));
            assertEquals(
                    new JavaSource.Position(4, 2),
                    model.source(file).position(method.span().start()));
            JavaNode syntax =
                    model.syntax(file).nodes().stream()
                            .filter(
                                    n ->
                                            n.kind().equals("METHOD")
                                                    && n.text("getName").equals("pick"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("CLASS", model.syntax(file).parent(syntax).orElseThrow().kind());
            assertEquals(text, model.source(file).text());
            assertEquals(ContentHash.sha256(text), model.unit(file).sourceHash());
            Files.writeString(file, text + "// changed");
            assertEquals(text, model.source(file).text(), "owned source snapshot remains stable");
            assertThrows(IllegalArgumentException.class, () -> JavaSource.read(model.unit(file)));
        }
        assertThrows(IllegalStateException.class, () -> model.type("sample.Box"));
        assertThrows(IllegalStateException.class, () -> model.unit(file));
    }

    @Test
    void declarationsProduceGraphFactsWithoutCompatibilityAst(@TempDir Path directory)
            throws Exception {
        String text =
                """
                package sample;
                class Box<T extends Number> {
                  T pick(T value) { return value; }
                  Object make() {
                    class Local { int field; }
                    Runnable action = new Runnable() { public void run() {} };
                    return new Local();
                  }
                  static class Inner {}
                }
                record Pair<T>(T value) {}
                """;
        Path file = Files.writeString(directory.resolve("Box.java"), text);
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(directory),
                        config.javacOptions());
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
        var result = new com.anatomist.model.ExtractionResult();
        try (var model = new JavaModel(contexts.getFirst())) {
            new com.anatomist.extract.JavaDeclarationFacts(model, directory, ".", "MAIN")
                    .extract(file, result);
            Set<String> ids =
                    result.nodes.stream()
                            .map(node -> node.id)
                            .collect(java.util.stream.Collectors.toSet());
            assertTrue(
                    ids.containsAll(
                            List.of(
                                    "sample.Box",
                                    "sample.Box#Box()",
                                    "sample.Box#pick(java.lang.Number)",
                                    "sample.Box.Inner",
                                    "sample.Pair#Pair(java.lang.Object)",
                                    "sample.Pair#value",
                                    "sample.Pair#value()")),
                    ids.toString());
            String local = "sample.Box#make()$local@L5C5:Local";
            assertTrue(ids.contains(local), ids.toString());
            assertTrue(ids.contains(local + "#field"));
            JavaSource.Position position =
                    model.source(file).position(text.indexOf("new Runnable()"));
            String anonymous =
                    "sample.Box#make()$anon@L" + position.line() + "C" + position.column();
            assertTrue(ids.contains(anonymous), ids.toString());
            assertTrue(ids.contains(anonymous + "#run()"));
            assertEquals(
                    ids.size(),
                    result.nodes.size(),
                    "record backing fields must not duplicate component facts");
            assertTrue(
                    result.declarations.stream()
                            .allMatch(declaration -> declaration.bindingResolved));
            assertTrue(
                    result.nodes.stream()
                            .allMatch(
                                    node ->
                                            node.sourceLocation == null
                                                    || !node.sourceLocation.equals("L0")));
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals(local)
                                                    && edge.targetId.equals(local + "#field")
                                                    && edge.relation.equals("CONTAINS")));
        }
    }

    @Test
    void coordinatesCountUtf16AndSupportAllJavaLineTerminators() {
        String text = "😀x\r\ny\rz\n";
        var source = new JavaSource(Path.of("Example.java"), text, ContentHash.sha256(text));
        assertEquals(new JavaSource.Position(1, 3), source.position(2));
        assertEquals(new JavaSource.Position(2, 1), source.position(5));
        assertEquals(new JavaSource.Position(3, 1), source.position(7));
        assertEquals(new JavaSource.Position(4, 1), source.position(text.length()));
        assertThrows(IllegalArgumentException.class, () -> source.slice(SourceSpan.ABSENT));
        assertThrows(IllegalArgumentException.class, () -> source.position(-1));
        assertThrows(IllegalArgumentException.class, () -> source.position(text.length() + 1));
    }
}
