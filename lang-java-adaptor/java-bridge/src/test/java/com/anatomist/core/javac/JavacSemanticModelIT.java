package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.IndexTimings;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.java.JavaBinding;
import com.anatomist.core.java.JavaModel;
import com.anatomist.core.java.JavaNode;
import com.anatomist.core.java.JavaType;
import com.anatomist.core.java.JavaUnit;
import com.anatomist.extract.JavaCallFacts;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class JavacSemanticModelIT {
    @Test
    void compilerSelectedMethodReferencesBecomeCallFacts(@TempDir Path temporary) throws Exception {
        Path file = temporary.resolve("References.java");
        Files.writeString(
                file,
                """
                import java.util.Map;
                import java.util.function.*;
                class References {
                    static int pick(long n) { return 1; }
                    static int pick(Integer n) { return 2; }
                    LongUnaryOperator overloaded = References::pick;
                    Function<Map.Entry<String,Integer>,String> key = Map.Entry::getKey;
                    Predicate<String> empty = String::isEmpty;
                    Function<String,StringBuilder> constructor = StringBuilder::new;
                }
                """);
        ProjectConfig config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(temporary),
                        config.javacOptions());
        var result = new ExtractionResult();
        assertTrue(
                backend.analyzeFiles(
                                List.of(file),
                                analysis -> {
                                    try (var model = new JavaModel(analysis);
                                            var binaries = backend.openBinaryRepository(analysis)) {
                                        assertTrue(
                                                new JavaCallFacts(
                                                                model, temporary, ".", "MAIN",
                                                                binaries)
                                                        .extract(file, result)
                                                        .isEmpty());
                                    }
                                })
                        .complete());
        var calls =
                result.edges.stream()
                        .filter(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && e.sourceId.contains("$methodref"))
                        .toList();
        assertEquals(4, calls.size());
        assertTrue(calls.stream().anyMatch(e -> "References#pick(long)".equals(e.targetId)));
        assertTrue(calls.stream().allMatch(e -> "EXTRACTED".equals(e.confidence)));
        assertTrue(
                result.nodes.stream()
                        .filter(n -> "METHOD_REF".equals(n.kind))
                        .allMatch(n -> n.metadata.contains("\"bindingResolved\":true")));
    }

    @Test
    void dependencyDeclarationsKeepBindingsAndErrorsDoNotEraseEnclosingSymbols(
            @TempDir Path temporary) throws Exception {
        Path library = temporary.resolve("ZLibrary.java");
        Path usage = temporary.resolve("AUsage.java");
        Files.writeString(library, "class ZLibrary<T> { T item; T get() { return item; } }");
        Files.writeString(
                usage,
                "class AUsage { String run(ZLibrary<String> x) { return x.get(); } void broken() { absent(); } }");
        ProjectConfig config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(temporary),
                        config.javacOptions());
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        backend.analyzeFiles(List.of(usage), contexts::add);
        JavaAnalysisContext context = contexts.getFirst();
        assertEquals(2, context.units().size());
        assertTrue(context.diagnostics().stream().anyMatch(d -> d.kind().equals("ERROR")));
        JavaUnit dependency =
                context.units().stream()
                        .filter(u -> u.path().equals(library))
                        .findFirst()
                        .orElseThrow();
        assertTrue(dependency.declarations());
        List<JavaNode> declarations = new ArrayList<>();
        collect(dependency.tree(), declarations);
        JavaNode getter =
                declarations.stream()
                        .filter(n -> n.kind().equals("METHOD") && n.text("getName").equals("get"))
                        .findFirst()
                        .orElseThrow();
        JavaBinding declaration = dependency.bindings().get(getter.id());
        assertNotNull(declaration);
        assertEquals("ZLibrary", declaration.owner());
        assertEquals(-1, declaration.selectedType());
        JavaType method = dependency.types().get(declaration.declaredType());
        assertEquals(JavaType.Kind.TYPEVAR, dependency.types().get(method.returns()).kind());
        JavaUnit caller =
                context.units().stream()
                        .filter(u -> u.path().equals(usage))
                        .findFirst()
                        .orElseThrow();
        JavaBinding type =
                caller.bindings().values().stream()
                        .filter(b -> b.name().equals("AUsage"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(type.evidenceComplete());
        assertTrue(
                caller.bindings().values().stream()
                        .anyMatch(
                                b ->
                                        b.name().equals("get")
                                                && b.evidenceComplete()
                                                && b.type().equals("java.lang.String")));
    }

    @Test
    void exportsGenericGraphAndInstantiatedCallsWithoutCompatibilityAst(@TempDir Path temporary)
            throws Exception {
        String source =
                """
                package sample;
                import java.util.List;
                class Example<T extends Comparable<T>> {
                    List<? super Integer> values;
                    T[] array;
                    <R> R choose(R value) { return value; }
                    String run() { return choose("中文"); }
                    Number first(List<? extends Number> xs) { return xs.get(0); }
                    Runnable field = () -> System.out.println("x");
                    Runnable anonymous = new Runnable() { public void run() {} };
                }
                """;
        Path file = temporary.resolve("Example.java");
        Files.writeString(file, source);
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            ProjectConfig config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(temporary),
                            config.javacOptions());
            var timings = new IndexTimings();
            backend.setTimings(timings);
            List<JavaAnalysisContext> contexts = new ArrayList<>();
            assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
            JavaAnalysisContext context = contexts.getFirst();
            assertTrue(context.analysisFailure().isEmpty());
            assertTrue(
                    context.diagnostics().stream().noneMatch(d -> d.kind().equals("ERROR")),
                    context.diagnostics().toString());
            JavaUnit unit = context.units().getFirst();
            JavaBinding call =
                    unit.bindings().values().stream()
                            .filter(
                                    b ->
                                            b.name().equals("choose")
                                                    && b.selectedType() >= 0
                                                    && unit.types()
                                                            .get(b.expressionType())
                                                            .display()
                                                            .equals("java.lang.String"))
                            .findFirst()
                            .orElseThrow();
            JavaType selected = unit.types().get(call.selectedType());
            assertEquals(JavaType.Kind.EXECUTABLE, selected.kind());
            assertEquals(
                    "java.lang.String", unit.types().get(selected.parameters().getFirst()).name());
            assertEquals("java.lang.String", unit.types().get(selected.returns()).name());
            JavaType declaration = unit.types().get(call.declaredType());
            assertEquals(JavaType.Kind.TYPEVAR, unit.types().get(declaration.returns()).kind());
            assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", call.symbol().descriptor());
            JavaType recursive =
                    unit.types().entries().stream()
                            .filter(t -> t.kind() == JavaType.Kind.TYPEVAR && t.name().equals("T"))
                            .findFirst()
                            .orElseThrow();
            JavaType upper = unit.types().get(recursive.upperBounds().getFirst());
            assertEquals("java.lang.Comparable", upper.name());
            assertSame(recursive, unit.types().get(upper.arguments().getFirst()));
            assertTrue(
                    unit.types().entries().stream()
                            .anyMatch(
                                    t ->
                                            t.kind() == JavaType.Kind.WILDCARD
                                                    && t.lowerBound() >= 0
                                                    && unit.types()
                                                            .get(t.lowerBound())
                                                            .name()
                                                            .equals("java.lang.Integer")));
            List<JavaNode> nodes = new ArrayList<>();
            collect(unit.tree(), nodes);
            JavaNode lambda =
                    nodes.stream()
                            .filter(n -> n.kind().equals("LAMBDA_EXPRESSION"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(source.indexOf("() ->"), lambda.span().start());
            assertEquals(
                    "() -> System.out.println(\"x\")",
                    source.substring((int) lambda.start(), (int) lambda.end()));
            for (String phase :
                    List.of(
                            "javac_setup",
                            "javac_parse",
                            "javac_analyze",
                            "javac_binding",
                            "javac_syntax_encode",
                            "javac_emit")) assertTrue(timings.millis().containsKey(phase), phase);
            tested++;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(tested > 0, "no target JDK installed");
    }

    private static void collect(JavaNode node, List<JavaNode> out) {
        out.add(node);
        for (Object value : node.fields().values()) {
            if (value instanceof JavaNode child) collect(child, out);
            else if (value instanceof List<?> list)
                for (Object member : list)
                    if (member instanceof JavaNode child) collect(child, out);
        }
    }
}
