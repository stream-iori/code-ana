package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.framework.JavaCallSiteEvidenceProvider;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class CallGraphExtractorTest {

    @TempDir Path directory;

    @Test
    void instanceCallToProjectInternalMethod() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  void caller() { callee(); }\n"
                        + "  void callee() {}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        boolean internalCall =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && "INSTANCE".equals(e.callKind)
                                                && "pkg.A#caller()".equals(e.sourceId)
                                                && "pkg.A#callee()".equals(e.targetId)
                                                && !e.isExternal);
        assertTrue(internalCall, "internal INSTANCE call missing; got " + r.edges);
    }

    @Test
    void projectSourceOverloadUsesArgumentTypeDeterministically() throws Exception {
        String source =
                "package pkg;\n"
                        + "class A {\n"
                        + "  void caller(float[] value) { clone(value); }\n"
                        + "  void clone(double[] value) {}\n"
                        + "  void clone(float[] value) {}\n"
                        + "}\n";

        for (int run = 0; run < 12; run++) {
            ExtractionResult result = JavaFactsTestSupport.extract(directory, "A.java", source);
            List<Edge> calls =
                    result.edges.stream()
                            .filter(edge -> "CALLS".equals(edge.relation))
                            .filter(edge -> "pkg.A#caller(float[])".equals(edge.sourceId))
                            .toList();

            assertEquals(
                    List.of("pkg.A#clone(float[])"),
                    calls.stream().map(edge -> edge.targetId).toList(),
                    "run "
                            + run
                            + " must not select a same-arity sibling overload: "
                            + describe(calls));
        }
    }

    @Test
    void staticCallToExternal() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int abs() { return Math.abs(-1); }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Edge call =
                r.edges.stream()
                        .filter(e -> "CALLS".equals(e.relation) && e.isExternal)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("got " + r.edges));
        assertEquals("STATIC", call.callKind);
        assertEquals("java.lang.Math#abs(int)", call.externalTargetFqn);
    }

    @Test
    void sourceOnlyStaticCallsKeepFixedArityAndInvocationPhases() throws Exception {
        String source =
                """
                package pkg;
                import java.util.*;
                class Model { Boolean flag() { return null; } int count() { return 0; } }
                enum Flag { ON }
                class Api {
                  static String render(Object value) { return null; }
                  static String render(Object value, Object... options) { return null; }
                  static <T> T parse(String value, Class<T> type) { return null; }
                  static <T> T parse(String value, Class<T> type, Object... options) { return null; }
                  static void copy(Object source, Object target) {}
                  static void copy(Object source, Object target, String... ignored) {}
                  static String choose(String value) { return null; }
                  static String choose(Integer value) { return null; }
                }
                class Use {
                  Object run(List<Model> input) {
                    return input.stream().map(item -> Optional.ofNullable(item).map(value -> {
                      Object target = new Object();
                      Api.copy(value, target);
                      String boxed = String.valueOf(value.flag());
                      String primitive = String.valueOf(value.count());
                      Flag flag = Flag.valueOf(Api.render(value.flag()));
                      String unknown = Api.choose(null);
                      return Api.parse(Api.render(value.flag()), String.class);
                    }).orElse("")).toList();
                  }
                }
                """;
        ExtractionResult result = JavaFactsTestSupport.extract(directory, "A.java", source);

        List<String> targets =
                result.edges.stream()
                        .filter(edge -> "CALLS".equals(edge.relation))
                        .map(edge -> edge.isExternal ? edge.externalTargetFqn : edge.targetId)
                        .toList();
        for (String expected :
                List.of(
                        "pkg.Api#copy(java.lang.Object,java.lang.Object)",
                        "pkg.Api#render(java.lang.Object)",
                        "pkg.Api#parse(java.lang.String,java.lang.Class)",
                        "pkg.Flag#valueOf(java.lang.String)",
                        "java.lang.String#valueOf(java.lang.Object)",
                        "java.lang.String#valueOf(int)")) {
            assertTrue(
                    targets.contains(expected),
                    () -> expected + " missing; got " + describe(result.edges));
        }
        assertFalse(
                targets.stream()
                        .anyMatch(
                                target -> target != null && target.contains("java.lang.Object[]")),
                () ->
                        "expanded varargs target must not replace fixed arity: "
                                + describe(result.edges));
        assertFalse(
                targets.stream().anyMatch(target -> target != null && target.contains("#choose(")),
                () ->
                        "unrelated fixed overloads must remain unresolved: "
                                + describe(result.edges));
    }

    @Test
    void sourceOnlyRecoveryDoesNotDropNestedByteCalls() throws Exception {
        String source =
                """
                package pkg;
                import java.util.*;
                class Codec { static byte[] decode(String value) { return null; } }
                class Digest { static String md5(byte[] value) { return null; } }
                class Use {
                  Object run(List<String> input, int[] counters) {
                    return input.stream().map(value -> {
                      String digest = Digest.md5(Codec.decode(value));
                      return Arrays.stream(counters).boxed().toArray(Integer[]::new);
                    }).toList();
                  }
                }
                """;
        ExtractionResult result = JavaFactsTestSupport.extract(directory, "A.java", source);
        List<String> targets =
                result.edges.stream()
                        .filter(edge -> "CALLS".equals(edge.relation))
                        .map(edge -> edge.isExternal ? edge.externalTargetFqn : edge.targetId)
                        .toList();
        for (String expected :
                List.of("pkg.Codec#decode(java.lang.String)", "pkg.Digest#md5(byte[])"))
            assertTrue(
                    targets.contains(expected),
                    () -> expected + " missing; got " + describe(result.edges));
    }

    @Test
    void failingCallEvidenceProviderKeepsExistingFacts() throws Exception {
        String source =
                "package pkg; class A { void run() { Math.abs(-1); MissingApi.invoke(); } }";
        JavaCallSiteEvidenceProvider broken =
                new JavaCallSiteEvidenceProvider() {
                    @Override
                    public String id() {
                        return "broken-call-evidence";
                    }

                    @Override
                    public java.util.Optional<Evidence> observe(
                            com.anatomist.framework.JavaUnitAnalysis unit,
                            com.anatomist.core.java.JavaNode call,
                            FallbackCallSite site) {
                        throw new IllegalStateException("boom");
                    }
                };
        var diagnostics = new java.util.ArrayList<com.anatomist.core.IndexDiagnostic>();
        var registry =
                new com.anatomist.framework.JavaModelAnalyzerRegistry(
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(broken));
        var result =
                JavaFactsTestSupport.extract(
                        directory,
                        "A.java",
                        source,
                        new com.anatomist.config.ProjectConfig(),
                        registry,
                        diagnostics::add);

        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge -> "java.lang.Math#abs(int)".equals(edge.externalTargetFqn)));
        assertEquals(1, result.unresolvedCallSites.size());
        assertTrue(
                diagnostics.stream()
                        .anyMatch(
                                diagnostic ->
                                        "EXTENSION_CALL_EVIDENCE_FAILED".equals(diagnostic.code())
                                                && "broken-call-evidence"
                                                        .equals(diagnostic.symbol())));
    }

    @Test
    void constructorCall() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  Object make() { return new Object(); }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Edge ctor =
                r.edges.stream()
                        .filter(e -> "CALLS".equals(e.relation) && "CONSTRUCTOR".equals(e.callKind))
                        .findFirst()
                        .orElseThrow();
        assertTrue(ctor.isExternal);
        assertEquals("java.lang.Object#Object()", ctor.externalTargetFqn);
    }

    @Test
    void enclosingId_lambdaBodyCall_attributesToLambdaNode() throws Exception {
        String source =
                "package pkg; import java.util.function.Supplier;"
                        + "public class A {"
                        + "  String s;"
                        + "  void run() {"
                        + "    Supplier<Integer> sup = () -> Math.abs(-1);"
                        + "  }"
                        + "}";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Edge mathAbs =
                r.edges.stream()
                        .filter(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && e.isExternal
                                                && "java.lang.Math#abs(int)"
                                                        .equals(e.externalTargetFqn))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no Math.abs call; got " + r.edges));
        assertTrue(
                mathAbs.sourceId.startsWith("pkg.A#run()$lambda@L"),
                "call inside lambda must attribute to LAMBDA node; got source=" + mathAbs.sourceId);
    }

    @Test
    void context_recordedForCallInsideLoopAndBranch() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  void caller(int[] xs){\n"
                        + "    for (int i=0;i<xs.length;i++){\n"
                        + "      if (xs[i] > 0) { callee(); }\n"
                        + "    }\n"
                        + "  }\n"
                        + "  void callee(){}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Edge call =
                r.edges.stream()
                        .filter(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && "pkg.A#callee()".equals(e.targetId))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("got " + r.edges));
        assertEquals("for@L4>if-then@L5", call.context);
    }

    @Test
    void context_nullForUnconditionalCall() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  void caller(){ callee(); }\n"
                        + "  void callee(){}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Edge call =
                r.edges.stream()
                        .filter(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && "pkg.A#callee()".equals(e.targetId))
                        .findFirst()
                        .orElseThrow();
        assertNull(call.context);
    }

    @Test
    void sourceStaticTemplateCall_preservesActualDeclarationWithMissingParameterType()
            throws Exception {
        String source =
                "package pkg;\n"
                        + "class A {\n"
                        + "  MissingResult result;\n"
                        + "  void run(){\n"
                        + "    Template.execute(result, new Callback(){\n"
                        + "      public void done(){ Template.fillSuccessResult(result); }\n"
                        + "    });\n"
                        + "  }\n"
                        + "}\n"
                        + "interface Callback { void done(); }\n"
                        + "class Template {\n"
                        + "  static void execute(MissingResult result, Callback callback){}\n"
                        + "  static void fillSuccessResult(MissingResult result){}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && "STATIC".equals(e.callKind)
                                                && "INFERRED".equals(e.confidence)
                                                && "pkg.A#run()".equals(e.sourceId)
                                                && "pkg.Template#execute(MissingResult,pkg.Callback)"
                                                        .equals(e.targetId)),
                "static template execute fallback missing; got " + describe(r.edges));
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && "STATIC".equals(e.callKind)
                                                && "pkg.A#run()$anon@L5C30#done()"
                                                        .equals(e.sourceId)
                                                && "pkg.Template#fillSuccessResult(MissingResult)"
                                                        .equals(e.targetId)),
                "static template callback fallback missing; got " + describe(r.edges));
    }

    @Test
    void unresolvedLowercaseScope_doesNotEmitStaticTypeFallback() throws Exception {
        String source =
                "package pkg;\n"
                        + "class A {\n"
                        + "  MissingDep dep;\n"
                        + "  MissingArg arg;\n"
                        + "  void run(){ dep.init(arg); }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        assertFalse(
                r.edges.stream()
                        .anyMatch(e -> "pkg.dep#init(<unresolved>)".equals(e.externalTargetFqn)),
                "lowercase variable scope must not become a static type fallback; got "
                        + describe(r.edges));
    }

    @Test
    void unknownOwnerCannotProduceGuessedCall() throws Exception {
        String source =
                """
                package pkg;
                class A { void run(MissingArg value) { MissingApi.invoke(value); } }
                """;
        var result = JavaFactsTestSupport.extract(directory, "A.java", source);
        assertTrue(
                result.edges.stream().noneMatch(edge -> "CALLS".equals(edge.relation)),
                () ->
                        "an unknown owner must not produce a guessed call edge: "
                                + describe(result.edges));
    }

    @Test
    void unresolvedUnscopedLocalCall_fallsBackToUniqueSameClassMethod() throws Exception {
        String source =
                "package pkg;\n"
                        + "class A {\n"
                        + "  void run(Missing m){ helper(m); }\n"
                        + "  private void helper(Missing m){}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && "INSTANCE".equals(e.callKind)
                                                && "INFERRED".equals(e.confidence)
                                                && "pkg.A#run(Missing)".equals(e.sourceId)
                                                && "pkg.A#helper(Missing)".equals(e.targetId)
                                                && !e.isExternal),
                "unresolved unscoped local call fallback missing; got " + describe(r.edges));
    }

    @Test
    void unresolvedAnonymousMethodBody_usesStableAnonMethodSourceFallback() throws Exception {
        String source =
                "package pkg;\n"
                        + "class A {\n"
                        + "  void outer(){ new Callback(){ public void done(Missing m){ helper(m); } }; }\n"
                        + "  void helper(Missing m){}\n"
                        + "}\n"
                        + "interface Callback { void done(Missing m); }\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && e.sourceId.startsWith("pkg.A#outer()$anon@L")
                                                && e.sourceId.endsWith("#done(Missing)")
                                                && "pkg.A#helper(Missing)".equals(e.targetId)
                                                && !e.isExternal),
                "anonymous unresolved callback body call fallback missing; got "
                        + describe(r.edges));
    }

    private static String describe(List<Edge> edges) {
        return edges.stream()
                .map(
                        e ->
                                e.sourceId
                                        + " -"
                                        + e.relation
                                        + "/"
                                        + e.callKind
                                        + "/"
                                        + e.confidence
                                        + "-> "
                                        + (e.isExternal ? e.externalTargetFqn : e.targetId)
                                        + " @"
                                        + e.sourceLocation)
                .toList()
                .toString();
    }
}
