package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import com.anatomist.extract.*;
import com.anatomist.model.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaRelationFactsIT {
    @Test
    void declarationIdentityIsIndependentOfIncompleteTypesAndAccessorShape(@TempDir Path project)
            throws Exception {
        Path file =
                Files.writeString(
                        project.resolve("Box.java"),
                        """
                class Box {
                    Missing missing;
                    Missing unknownReturn() { return null; }
                    void getVoid() {}
                    int get() { return 1; }
                    int getter() { return 1; }
                    boolean isReady() { return true; }
                    Boolean isBoxed() { return true; }
                    int isNumber() { return 1; }
                    int setChained(int value) { return value; }
                    void setValue(int value) {}
                    static class Nested { int member; }
                }
                record Pair(int x) {}
                """);
        var context = analyze(project, file, Path.of(System.getProperty("java.home")), 25);
        try (var model = new JavaModel(context)) {
            var facts = new ExtractionResult();
            new JavaDeclarationFacts(model, project, ".", "MAIN").extract(file, facts);
            assertTrue(
                    facts.declarations.stream()
                            .filter(
                                    declaration ->
                                            Set.of("Box", "Box#missing", "Box#unknownReturn()")
                                                    .contains(declaration.symbolId))
                            .allMatch(declaration -> declaration.bindingResolved));
            for (var node :
                    facts.nodes.stream()
                            .filter(
                                    node ->
                                            Set.of("Box", "Box#missing", "Box#unknownReturn()")
                                                    .contains(node.id))
                            .toList())
                assertTrue(node.metadata.contains("\"evidenceComplete\":false"), node.metadata);
            var accessors =
                    facts.nodes.stream()
                            .filter(
                                    node ->
                                            Boolean.TRUE.equals(
                                                    ((Map<?, ?>)
                                                                    com.anatomist.json.Json
                                                                            .parseTree(
                                                                                    node.metadata))
                                                            .get("isAccessor")))
                            .map(node -> node.id)
                            .collect(java.util.stream.Collectors.toSet());
            assertEquals(
                    Set.of(
                            "Box#getter()",
                            "Box#isReady()",
                            "Box#isBoxed()",
                            "Box#setValue(int)",
                            "Pair#x()"),
                    accessors);
        }
        var compact =
                new JavaAnalysisContext(
                        context.module(),
                        context.scope(),
                        context.units().stream().map(JavaDeclarationView::of).toList(),
                        context.publish(),
                        context.diagnostics(),
                        context.analysisFailure());
        try (var model = new JavaModel(compact)) {
            var owner = ((JavaModel.FoundType) model.type("Box")).declaration();
            var nested = ((JavaModel.FoundType) model.type("Box.Nested")).declaration();
            assertEquals(owner, model.parent(nested).orElseThrow());
            assertTrue(model.members(owner).contains(nested));
        }
    }

    @Test
    void overridesUseCompilerGenericSubstitutionAndJavaAccessRules(@TempDir Path directory)
            throws Exception {
        String source =
                """
                package p;
                interface Api<T> { T convert(T value); }
                class Base<T> implements Api<T> {
                    public T convert(T value) { return value; }
                    Number covariant() { return null; }
                    private void hidden() {}
                    static void staticMethod() {}
                }
                class Child extends Base<String> implements Runnable {
                    public String convert(String value) { return value; }
                    Integer covariant() { return null; }
                    private void hidden() {}
                    static void staticMethod() {}
                    public void run() {}
                    public String toString() { return "child"; }
                }
                """;
        for (int release : List.of(8, 17, 25)) {
            Path home = Objects.requireNonNull(JavacBackendIT.home(release));
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = Files.writeString(project.resolve("Child.java"), source);
            var context = analyze(project, file, home, release);
            for (var variant :
                    List.of(
                            context,
                            new JavaAnalysisContext(
                                    context.module(),
                                    context.scope(),
                                    context.units().stream().map(JavaDeclarationView::of).toList(),
                                    context.publish(),
                                    context.diagnostics(),
                                    context.analysisFailure()))) {
                try (var model = new JavaModel(variant)) {
                    var facts = new ExtractionResult();
                    assertTrue(
                            new JavaOverrideFacts(model, project).extract(file, facts).isEmpty());
                    var pairs =
                            facts.edges.stream()
                                    .map(
                                            edge ->
                                                    edge.sourceId
                                                            + " -> "
                                                            + (edge.isExternal
                                                                    ? edge.externalTargetFqn
                                                                    : edge.targetId))
                                    .collect(java.util.stream.Collectors.toSet());
                    assertEquals(
                            Set.of(
                                    "p.Base#convert(java.lang.Object) -> p.Api#convert(java.lang.Object)",
                                    "p.Child#convert(java.lang.String) -> p.Base#convert(java.lang.Object)",
                                    "p.Child#convert(java.lang.String) -> p.Api#convert(java.lang.Object)",
                                    "p.Child#covariant() -> p.Base#covariant()",
                                    "p.Child#run() -> java.lang.Runnable#run()",
                                    "p.Child#toString() -> java.lang.Object#toString()"),
                            pairs,
                            "JDK " + release);
                    assertTrue(
                            facts.edges.stream()
                                    .allMatch(
                                            edge ->
                                                    edge.relation.equals("OVERRIDES")
                                                            && edge.confidence.equals(
                                                                    GraphConstants.Confidence
                                                                            .EXTRACTED)));
                }
            }
        }
    }

    @Test
    void fieldWritesAndSignatureRelationsUseActualCompilerDeclarations(@TempDir Path directory)
            throws Exception {
        String source =
                """
                class Base { int value; Box ref; }
                class Box extends Base implements Runnable {
                    java.util.List<String> values;
                    int[] array;
                    int use(int value) {
                        Class<?> own = Box.class;
                        Class<?> external = String.class;
                        Class<?> primitive = int.class;
                        Class<?> arrays = Box[].class;
                        this.value = value;
                        if (value > 0) this.value += 2;
                        ref.value++;
                        array[0] = value;
                        return super.value + array.length + System.out.hashCode();
                    }
                    public void run() {}
                    Missing missing;
                }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = Files.writeString(project.resolve("Box.java"), source);
            var context = analyze(project, file, home, release);
            try (var model = new JavaModel(context)) {
                var result = new ExtractionResult();
                new JavaDeclarationFacts(model, project, ".", "MAIN").extract(file, result);
                var fieldFailures = new JavaFieldFacts(model, project).extract(file, result);
                assertTrue(fieldFailures.isEmpty(), fieldFailures.toString());
                var unresolved = new JavaTypeFacts(model, project).extract(file, result);
                assertEquals(1, unresolved.size(), unresolved.toString());
                assertEquals("Box#missing", unresolved.getFirst().owner());
                var accesses =
                        result.edges.stream()
                                .filter(edge -> Set.of("READS", "WRITES").contains(edge.relation))
                                .toList();
                assertEquals(
                        3,
                        accesses.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("WRITES")
                                                        && "Base#value".equals(edge.targetId))
                                .count(),
                        "JDK " + release);
                assertEquals(
                        2,
                        accesses.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("READS")
                                                        && "Base#value".equals(edge.targetId))
                                .count());
                assertTrue(
                        accesses.stream()
                                .noneMatch(
                                        edge ->
                                                edge.relation.equals("READS")
                                                        && "Base#value".equals(edge.targetId)
                                                        && "ref.value".equals(edge.syntaxTarget)),
                        "unary mutation retains the existing write-only access contract");
                assertEquals(
                        1,
                        accesses.stream().filter(edge -> "Base#ref".equals(edge.targetId)).count());
                assertEquals(
                        2,
                        accesses.stream()
                                .filter(
                                        edge ->
                                                "Box#array".equals(edge.targetId)
                                                        && edge.relation.equals("READS"))
                                .count());
                assertTrue(
                        accesses.stream()
                                .noneMatch(
                                        edge ->
                                                edge.relation.equals("WRITES")
                                                        && "Box#array".equals(edge.targetId)));
                assertTrue(
                        accesses.stream()
                                .noneMatch(
                                        edge ->
                                                edge.targetId != null
                                                        && edge.targetId.endsWith("#length")));
                assertTrue(
                        accesses.stream()
                                .anyMatch(
                                        edge ->
                                                "java.lang.System#out"
                                                        .equals(edge.externalTargetFqn)));
                assertTrue(
                        accesses.stream()
                                .anyMatch(
                                        edge ->
                                                "if-then@L11".equals(edge.context)
                                                        && edge.relation.equals("WRITES")));
                assertTrue(
                        accesses.stream()
                                .allMatch(
                                        edge ->
                                                "Box#use(int)".equals(edge.sourceId)
                                                        && edge.beginLine > 0));
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                "INHERITS".equals(edge.relation)
                                                        && "Box".equals(edge.sourceId)
                                                        && "Base".equals(edge.targetId)));
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                "IMPLEMENTS".equals(edge.relation)
                                                        && "java.lang.Runnable"
                                                                .equals(edge.externalTargetFqn)));
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                "field_type".equals(edge.context)
                                                        && "java.util.List"
                                                                .equals(edge.externalTargetFqn)));
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                "generic_arg".equals(edge.context)
                                                        && "java.lang.String"
                                                                .equals(edge.externalTargetFqn)));
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                "REFERENCES".equals(edge.relation)
                                                        && "Base#ref".equals(edge.sourceId)
                                                        && "Box".equals(edge.targetId)));
            }
            tested++;
        }
        assertTrue(tested > 0);
    }

    @Test
    void sealedHierarchyAndRecordBackingAccessUseExistingGraphIdentities(@TempDir Path project)
            throws Exception {
        Path file =
                Files.writeString(
                        project.resolve("Shape.java"),
                        "sealed interface Shape permits Point {} record Point(int x) implements Shape { int doubled() { return x + x; } }");
        var context = analyze(project, file, Path.of(System.getProperty("java.home")), 25);
        try (var model = new JavaModel(context)) {
            var result = new ExtractionResult();
            new JavaDeclarationFacts(model, project, ".", "MAIN").extract(file, result);
            var fieldFailures = new JavaFieldFacts(model, project).extract(file, result);
            assertTrue(fieldFailures.isEmpty(), fieldFailures.toString());
            assertTrue(new JavaTypeFacts(model, project).extract(file, result).isEmpty());
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            "Point".equals(edge.sourceId)
                                                    && "Shape".equals(edge.targetId)
                                                    && "PERMITS".equals(edge.relation)));
            assertEquals(
                    2,
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            "Point#doubled()".equals(edge.sourceId)
                                                    && "Point#x".equals(edge.targetId)
                                                    && "READS".equals(edge.relation))
                            .count());
        }
    }

    private static JavaAnalysisContext analyze(Path project, Path file, Path home, int release) {
        var config = new ProjectConfig();
        config.setJavaHome(home.toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), release, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
        return contexts.getFirst();
    }
}
