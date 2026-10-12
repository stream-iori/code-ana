package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

/** Original constraint scenarios verified against actual selected compiler facts. */
class LocalConstraintTest {
    @Test
    void explicitCastSuppliesNestedCallbackTypeWithoutFullBodyInference(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
            import java.util.*; import java.util.function.*;
            class A {
              static <T,R> List<R> convert(List<T> source, Function<? super T,? extends R> mapper) { return null; }
              static String map(String value,String language) { return value; }
              static Integer map(Integer value,String language) { return value; }
              Object f(List<Object> data,String language) {
                return convert(data, value -> convert((List<String>)value, item -> map(item,language)));
              }
            }
            """);
        targets(value, "A#map(java.lang.String,java.lang.String)");
    }

    @Test
    void repeatedChainsReuseTheMethodDirectory(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "class A { A next() { return this; } void run() { "
                                + "next().next().next();".repeat(100)
                                + " } }");
        assertEquals(300, value.nodes("METHOD_INVOCATION").size());
        allComplete(value);
        assertEquals(
                value.unit().bindings(), new java.util.LinkedHashMap<>(value.unit().bindings()));
    }

    @Test
    void selectedJdkMethodFamilyIsStableAcrossCompilationUnits(@TempDir Path directory)
            throws Exception {
        Path first =
                Files.writeString(
                        directory.resolve("A.java"),
                        "import java.util.*; class A { int f(List<String> v) { return v.size(); } }");
        Path second =
                Files.writeString(
                        directory.resolve("B.java"),
                        "import java.util.*; class B { int f(List<Integer> v) { return v.size(); } }");
        var backend = JavaFactsTestSupport.backend(directory);
        var facts = new ExtractionResult();
        backend.analyzeFiles(
                List.of(first, second),
                context -> {
                    assertTrue(
                            context.diagnostics().stream()
                                    .noneMatch(d -> d.kind().equals("ERROR")));
                    try (var binaries = backend.openBinaryRepository(context)) {
                        new JavaModelExtractor(directory, binaries)
                                .extract(context, value -> facts.addFacts(value.facts()));
                    }
                });
        assertEquals(
                2,
                facts.edges.stream()
                        .filter(
                                e ->
                                        e.relation.equals("CALLS")
                                                && "java.util.List#size()"
                                                        .equals(e.externalTargetFqn))
                        .count());
    }

    @Test
    void assignmentAndReturnTargetsPropagate(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.Function;
                class A { Function<String,Integer> f; void set() { f = String::length; }
                  Function<String,Integer> get() { return String::length; } }
                """);
        assertEquals(2, value.nodes("MEMBER_REFERENCE").size());
        targets(value, "java.lang.String#length()");
        allComplete(value);
    }

    @Test
    void targetEnvironmentIsRestoredBetweenOverloadQueries(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.concurrent.*;
                class A { void f(ExecutorService e) {
                    e.submit(() -> { return "x"; });
                    e.submit(() -> {});
                } }
                """);
        targets(
                value,
                "java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)",
                "java.util.concurrent.ExecutorService#submit(java.lang.Runnable)");
        allComplete(value);
    }

    @Test
    void lambdaReturnDisambiguatesOverload(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.concurrent.*;
                class A { void f(ExecutorService e) { e.submit(() -> { return "x"; }); } }
                """);
        targets(
                value,
                "java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)");
        allComplete(value);
    }

    @Test
    void inheritedSamAndMethodGenericResultAreGrounded(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.Function;
                interface Fn<T> extends Function<T,Integer> {}
                class A { Fn<String> f = String::length; static <T> T id(T x) { return x; }
                  String value() { return id("x"); } }
                """);
        targets(value, "java.lang.String#length()", "A#id(java.lang.Object)");
        assertEquals("java.lang.String", value.type("id"));
        allComplete(value);
    }

    @Test
    void arrayConstructorChecksParameterAndReturnConstraints(@TempDir Path directory)
            throws Exception {
        var valid =
                fixture(
                        directory,
                        "import java.util.function.IntFunction; class A { IntFunction<String[]> f = String[]::new; }");
        assertFalse(valid.errors());
        assertEquals(1, valid.nodes("MEMBER_REFERENCE").size());
        assertTrue(valid.facts().edges.stream().noneMatch(e -> e.relation.equals("CALLS")));
        var invalid =
                fixture(
                        directory,
                        "import java.util.function.Function; class A { Function<String,String[]> f = String[]::new; }");
        assertTrue(invalid.errors());
        assertFalse(invalid.complete(invalid.nodes("MEMBER_REFERENCE").getFirst()));
    }

    @Test
    void methodGenericReferenceAndConstructorReference(@TempDir Path directory) throws Exception {
        for (String source :
                new String[] {
                    "import java.util.function.Function; class A { static <T> T id(T x) { return x; } Function<String,String> f = A::id; }",
                    "import java.util.*; import java.util.function.Supplier; class A { Supplier<List<String>> f = ArrayList::new; }",
                    "import java.util.function.Function; class A { Function<String,String> f = String::trim; }"
                }) {
            var value = fixture(directory, source);
            allComplete(value);
            assertEquals(1, value.nodes("MEMBER_REFERENCE").size());
            assertTrue(value.facts().edges.stream().anyMatch(e -> e.relation.equals("CALLS")));
        }
    }

    @Test
    void explicitTargetsAreIsolatedAndNoSolutionIsReported(@TempDir Path directory)
            throws Exception {
        for (String type : List.of("Integer", "Thread", "Integer", "Thread")) {
            var value =
                    fixture(
                            directory,
                            "import java.util.function.Function; class A { Function<String,"
                                    + type
                                    + "> f=String::length; }");
            assertEquals(type.equals("Thread"), value.errors());
            assertEquals(
                    type.equals("Integer"),
                    value.complete(value.nodes("MEMBER_REFERENCE").getFirst()));
        }
    }

    @Test
    void candidatePrefilterAvoidsSpendingBudgetOnIncompatibleOverloads(@TempDir Path directory)
            throws Exception {
        var source = new StringBuilder("import java.util.function.Function; class A { ");
        for (int i = 0; i < 65; i++)
            source.append("static class P")
                    .append(i)
                    .append(" {} static int f(P")
                    .append(i)
                    .append(" p) { return 1; }");
        source.append("Function<P0,Integer> ref = A::f; }");
        var value = fixture(directory, source.toString());
        targets(value, "A#f(A.P0)");
        allComplete(value);
    }

    @Test
    void candidateBudgetStillStopsManyApplicableOverloads(@TempDir Path directory)
            throws Exception {
        var source = new StringBuilder("import java.util.function.Function; class A { ");
        for (int i = 0; i < 65; i++) source.append("interface I").append(i).append(" {} ");
        source.append("static class P implements ");
        for (int i = 0; i < 65; i++) source.append(i == 0 ? "" : ",").append("I").append(i);
        source.append(" {} ");
        for (int i = 0; i < 65; i++)
            source.append("static int f(I").append(i).append(" p) { return 1; }");
        source.append("Function<P,Integer> ref = A::f; }");
        var value = fixture(directory, source.toString());
        assertTrue(value.errors());
        assertFalse(value.complete(value.nodes("MEMBER_REFERENCE").getFirst()));
    }

    @Test
    void constructorOverloadsUseLambdaReturnConstraints(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.*;
                class Box { Box(Function<String,Integer> f) {} Box(Consumer<String> f) {} }
                class A { Box box = new Box(s -> { return s.length(); }); }
                """);
        targets(value, "Box#Box(java.util.function.Function)");
        allComplete(value);
    }

    @Test
    void enumConstructorArgumentsSupplyLambdaTargetTypes(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.*;
                class Context { String name() { return ""; } }
                enum Kind {
                  NAME(context -> { return context.name(); });
                  Kind(Function<Context,String> function) {}
                  Kind(Consumer<Context> consumer) {}
                }
                """);
        targets(value, "Context#name()");
        assertTrue(
                value.unit().bindings().values().stream()
                        .anyMatch(b -> b.type().contains("Function")));
        allComplete(value);
    }

    @Test
    void boundReferenceFallsBackOnlyToOneDeclarationWithMatchingArity(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.*;
                class Repo { void save(Object value) {} void remove(Object value) {} }
                class Use {
                  Function<String,Integer> recovered(Repo repo) { return repo::save; }
                  Function<String,Integer> ambiguous(Ambiguous repo) { return repo::save; }
                  BiFunction<String,String,Integer> wrongArity(Repo repo) { return repo::save; }
                }
                class Ambiguous { void save(String value) {} void save(Integer value) {} }
                """);
        assertTrue(value.errors());
        for (var node : value.nodes("MEMBER_REFERENCE")) assertFalse(value.complete(node));
    }

    @Test
    void fieldReceiverRepresentedAsTypeExpressionIsStillABoundReference(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.function.*;
                class Repo { void save(Object value) {} }
                class Use {
                  Repo repo;
                  Object transaction(Supplier<Object> callback) { return callback.get(); }
                  void run(List<String> values) {
                    transaction(() -> { values.forEach(repo::save); values.forEach(repo::remove); return null; });
                  }
                }
                """);
        targets(value, "Repo#save(java.lang.Object)");
        assertTrue(value.errors());
        assertFalse(value.hasTarget("Repo#remove(java.lang.Object)"));
    }

    @Test
    void referencesSupportDiscardedReturnsUnboundReceiversAndDuplicateDeclarations(
            @TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.function.*; import java.util.stream.*;
                interface Parent { String value(); }
                interface Child extends Parent { @Override String value(); }
                class Repo { int save(String value) { return 1; } }
                class Codec {
                  String encode() { return ""; }
                  static String encode(Object value) { return ""; }
                  static String encode(Object value, int... options) { return ""; }
                }
                class Use {
                  Consumer<String> discarded(Repo repo) { return repo::save; }
                  Function<List<String>,Stream<String>> stream() { return List::stream; }
                  Function<Child,String> inherited() { return Child::value; }
                  Function<Object,String> fixed() { return Codec::encode; }
                  <T> Function<T,String> genericStatic() { return Codec::encode; }
                }
                """);
        targets(
                value,
                "Repo#save(java.lang.String)",
                "java.util.Collection#stream()",
                "Child#value()",
                "Codec#encode(java.lang.Object)");
        allComplete(value);
    }

    @Test
    void inferredGenericCollectionKeepsAUniqueUnboundReferenceTarget(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.stream.*;
                class Item {}
                class Use {
                  Map<String,List<Item>> values;
                  Stream<Item> run() {
                    return new ArrayList<>(values.values()).stream().flatMap(List::stream);
                  }
                }
                """);
        targets(value, "java.util.Collection#stream()");
        allComplete(value);
    }

    @Test
    void projectStaticDeclarationFallbackRequiresOneCompatibleSourceMethod(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                class Missing { Object value() { return null; } }
                class GenericHolder { <T extends CharSequence> T value() { return null; } }
                class Tool {
                  static String read(Object value, String path) { return ""; }
                  static String generic(String value, String path) { return ""; }
                  static String overloaded(String value) { return ""; }
                  static String overloaded(Integer value) { return ""; }
                }
                class Use {
                  String run(Missing missing, String path) { return Tool.read(missing.unknown(), path); }
                  String generic(GenericHolder holder, String path) { return Tool.generic(holder.value(), path); }
                  String ambiguous(Missing missing) { return Tool.overloaded(missing.unknown()); }
                }
                """);
        targets(
                value,
                "Tool#read(java.lang.Object,java.lang.String)",
                "Tool#generic(java.lang.String,java.lang.String)");
        assertTrue(value.errors());
        assertFalse(value.completeNamed("overloaded"));
    }

    @Test
    void conditionalArgumentsRetainAConservativeInvocationType(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*;
                class A {
                  static void log(String prefix, Object... values) {}
                  void run(Map<String,String> values, boolean flag) {
                    log("map", values != null ? values.keySet() : "null");
                    log("flag", flag ? true : "false");
                  }
                }
                """);
        assertEquals(
                2,
                value.nodes("METHOD_INVOCATION").stream()
                        .filter(
                                n -> {
                                    var b = value.unit().bindings().get(n.id());
                                    return b != null && b.name().equals("log");
                                })
                        .count());
        targets(value, "A#log(java.lang.String,java.lang.Object...)");
        allComplete(value);
    }

    @Test
    void declarationOnlyCallRequiresUnknownInputAndOneSourceCandidate(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                class A {
                  static void exact(String value) {}
                  static void overloaded(String value) {}
                  static void overloaded(Integer value) {}
                  void run(Missing missing) {
                    exact(missing.text());
                    overloaded(missing.text());
                    exact(1);
                  }
                }
                """);
        assertTrue(value.errors());
        targets(value, "A#exact(java.lang.String)");
        assertFalse(value.completeNamed("overloaded"));
        assertEquals(
                1,
                value.facts().edges.stream()
                        .filter(
                                e ->
                                        e.relation.equals("CALLS")
                                                && "A#exact(java.lang.String)".equals(e.targetId))
                        .count());
    }

    @Test
    void invalidReferenceIsRejectedAndIncompleteLambdaKeepsOnlyDeclaration(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.Function;
                class A { static <T extends Number> T id(T n) { return n; }
                    Function<String,String> ref = A::id;
                    static void use(Function<String,String> f) {}
                    void run() { use(s -> { if (s.isEmpty()) return s; }); } }
                """);
        assertTrue(value.errors());
        assertFalse(value.complete(value.nodes("MEMBER_REFERENCE").getFirst()));
        assertFalse(value.completeNamed("use"));
    }

    @Test
    void valueExpressionLambdaSelectsCallableOverRunnable(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        "import java.util.concurrent.*; class A { void run(ExecutorService e) { e.submit(() -> System.getProperty(\"x\")); } }");
        targets(
                value,
                "java.util.concurrent.ExecutorService#submit(java.util.concurrent.Callable)");
        allComplete(value);
    }

    @Test
    void inferenceBoxesPrimitiveAndPreservesVarargsArray(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.function.*;
                class A { static <T> T id(T x) { return x; }
                  static <T> List<T> list(T... xs) { return null; }
                  IntFunction<Integer> f = A::id;
                  Function<String[],List<String>> g = A::list;
                  void run() { id(1); list(new String[]{"x"}); } }
                """);
        assertEquals("java.lang.Integer", value.type("id"));
        assertEquals("java.util.List<java.lang.String>", value.type("list"));
        allComplete(value);
    }

    @Test
    void referencesConstrainPipelineAndComparatorTypeVariables(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.stream.*;
                class A { void run(List<String> input) {
                    input.stream().map(String::length).filter(n -> n > 0);
                    input.sort(Comparator.comparing(String::length));
                } }
                """);
        targets(
                value,
                "java.lang.String#length()",
                "java.util.Comparator#comparing(java.util.function.Function)");
        allComplete(value);
    }

    @Test
    void referenceDoesNotViolateTargetVariableBound(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        "import java.util.function.Function; class A { <U extends Number> void run() { Function<String,U> f = String::trim; } }");
        assertTrue(value.errors());
        assertFalse(value.complete(value.nodes("MEMBER_REFERENCE").getFirst()));
    }

    @Test
    void boundMapReferenceAndNestedTargetType(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.stream.*;
                class A { void run(Map<String,String> map, String[] keys, List<Integer> values) {
                    Arrays.stream(keys).map(map::get).filter(s -> s != null);
                    values.stream().max(Comparator.comparing(v -> v)).orElse(0);
                } }
                """);
        targets(
                value,
                "java.util.Map#get(java.lang.Object)",
                "java.util.Comparator#comparing(java.util.function.Function)");
        allComplete(value);
    }

    @Test
    void localSwitchTryAndThrowLambdaReturns(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.function.Function;
                class A { static void use(Function<String,String> f) {}
                  void run() {
                    use(s -> { switch(s) { case "x": { try { return s; } catch(Exception e) { return ""; } } default: throw new IllegalArgumentException(); } });
                    use(s -> { throw new IllegalArgumentException(); });
                  } }
                """);
        targets(value, "A#use(java.util.function.Function)");
        allComplete(value);
    }

    @Test
    void unboundGenericReceiverAndCollectorReferences(@TempDir Path directory) throws Exception {
        var value =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.function.*;
                class Box<T> { T get() { return null; } }
                class A {
                  Function<Box<String>,String> f = Box::get;
                  Map<String,String> run(List<String> input) {
                    return input.stream().collect(HashMap::new, (m,v) -> m.put(v,v), Map::putAll);
                  }
                }
                """);
        targets(
                value,
                "Box#get()",
                "java.util.Map#putAll(java.util.Map)",
                "java.util.stream.Stream#collect(java.util.function.Supplier,java.util.function.BiConsumer,java.util.function.BiConsumer)");
        allComplete(value);
    }

    private record Fixture(JavaAnalysisContext context, JavaUnit unit, ExtractionResult facts) {
        List<JavaNode> nodes(String kind) {
            try (var model = new JavaModel(context)) {
                return model.syntax(unit.path()).nodes().stream()
                        .filter(n -> n.kind().equals(kind))
                        .toList();
            }
        }

        boolean errors() {
            return context.diagnostics().stream().anyMatch(d -> d.kind().equals("ERROR"));
        }

        boolean complete(JavaNode node) {
            var b = unit.bindings().get(node.id());
            return b != null && b.declarationResolved() && b.evidenceComplete();
        }

        boolean completeNamed(String name) {
            return nodes("METHOD_INVOCATION").stream()
                    .anyMatch(
                            n -> {
                                var b = unit.bindings().get(n.id());
                                return b != null && b.name().equals(name) && complete(n);
                            });
        }

        String type(String name) {
            var node =
                    nodes("METHOD_INVOCATION").stream()
                            .filter(
                                    n -> {
                                        var b = unit.bindings().get(n.id());
                                        return b != null && b.name().equals(name);
                                    })
                            .findFirst()
                            .orElseThrow();
            return unit.types().get(unit.bindings().get(node.id()).expressionType()).display();
        }

        boolean hasTarget(String target) {
            return facts.edges.stream()
                    .anyMatch(
                            e ->
                                    e.relation.equals("CALLS")
                                            && target.equals(
                                                    e.targetId == null
                                                            ? e.externalTargetFqn
                                                            : e.targetId));
        }
    }

    private static Fixture fixture(Path directory, String source) throws Exception {
        var context = JavaFactsTestSupport.analyze(directory, "Use.java", source);
        var unit =
                context.units().stream()
                        .filter(u -> u.path().equals(directory.resolve("Use.java")))
                        .findFirst()
                        .orElseThrow();
        var facts = new ExtractionResult();
        var backend = JavaFactsTestSupport.backend(directory);
        try (var binaries = backend.openBinaryRepository(context)) {
            new JavaModelExtractor(directory, binaries)
                    .extract(context, value -> facts.addFacts(value.facts()));
        }
        return new Fixture(context, unit, facts);
    }

    private static void targets(Fixture value, String... expected) {
        for (String target : expected)
            assertTrue(
                    value.hasTarget(target),
                    () ->
                            "missing "
                                    + target
                                    + " actual "
                                    + value.facts().edges.stream()
                                            .filter(e -> e.relation.equals("CALLS"))
                                            .map(
                                                    e ->
                                                            e.targetId == null
                                                                    ? e.externalTargetFqn
                                                                    : e.targetId)
                                            .toList());
    }

    private static void allComplete(Fixture value) {
        assertFalse(value.errors(), () -> value.context().diagnostics().toString());
        for (String kind : List.of("METHOD_INVOCATION", "MEMBER_REFERENCE", "NEW_CLASS"))
            for (var node : value.nodes(kind))
                assertTrue(
                        value.complete(node),
                        () -> kind + " " + value.unit().bindings().get(node.id()));
    }
}
