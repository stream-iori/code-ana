package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LocalTypeResolverTest {
    @TempDir Path directory;

    @Test
    void annotationValueHasDeclaredReturnType() throws Exception {
        var unit =
                analyze(
                        "@interface Setting { String[] value(); } class A { String[] read(Setting s) { return s.value(); } }");
        var binding = site(unit, "METHOD_INVOCATION");
        assertTrue(binding.declarationResolved());
        assertEquals(
                "java.lang.String[]", JavaTypeNames.erased(unit.types(), binding.expressionType()));
    }

    @Test
    void methodReferenceUsesSamParameterAndReturnTypes() throws Exception {
        var unit =
                analyze(
                        "import java.util.function.Function; class A { Function<String,Integer> f = String::length; }");
        var binding = site(unit, "MEMBER_REFERENCE");
        assertTrue(binding.declarationResolved());
        assertEquals("java.lang.String", binding.owner());
        assertEquals("length", binding.name());
    }

    @Test
    void incompatibleReferenceReturnKeepsOnlySelectedDeclarationEvidence() throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "A.java",
                        "import java.util.function.Function; class A { Function<String,Thread> f = String::length; }");
        var binding = site(context.units().getFirst(), "MEMBER_REFERENCE");
        assertNotNull(binding);
        assertTrue(binding.declarationResolved());
        assertFalse(binding.evidenceComplete());
        assertEquals("java.lang.String", binding.owner());
        assertEquals("length", binding.name());
        assertTrue(
                context.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.kind().equals("ERROR")));
        var facts = new com.anatomist.model.ExtractionResult();
        new com.anatomist.extract.JavaModelExtractor(directory, null)
                .extract(context, unit -> facts.addFacts(unit.facts()));
        var call =
                facts.edges.stream()
                        .filter(
                                edge ->
                                        edge.relation.equals("CALLS")
                                                && "java.lang.String#length()"
                                                        .equals(edge.externalTargetFqn))
                        .findFirst()
                        .orElseThrow();
        assertEquals("EXTRACTED", call.confidence);
        assertEquals(
                false,
                ((java.util.Map<?, ?>) com.anatomist.json.Json.parseTree(call.metadata))
                        .get("evidenceComplete"));
    }

    @Test
    void ambiguousReferenceIsNotGuessed() throws Exception {
        var unit =
                analyze(
                        "import java.util.function.Function; class A { static int f(java.io.Serializable s) { return 1; } static int f(CharSequence s) { return 2; } Function<String,Integer> value = A::f; }");
        assertUnbound(site(unit, "MEMBER_REFERENCE"));
    }

    @Test
    void repeatedQueriesRetainImmutableCompilerEvidence() throws Exception {
        var unit = analyze("class A { String run(String value) { return value.trim(); } }");
        var first = site(unit, "METHOD_INVOCATION");
        var second = site(unit, "METHOD_INVOCATION");
        assertSame(first, second);
        assertEquals(
                "java.lang.String", JavaTypeNames.erased(unit.types(), first.expressionType()));
        assertTrue(first.declarationResolved());
        assertThrows(UnsupportedOperationException.class, () -> unit.bindings().clear());
    }

    @Test
    void repeatedFailedMethodAndConstructorQueriesRemainUnresolved() throws Exception {
        var unit =
                analyze(
                        "class A { Object run(String value) { value.definitelyMissing(); return new String(1); } }");
        for (String kind : java.util.List.of("METHOD_INVOCATION", "NEW_CLASS")) {
            var first = site(unit, kind);
            assertUnbound(first);
            assertSame(first, site(unit, kind));
        }
    }

    @Test
    void ambiguousOverloadIsReevaluatedAfterSourceChanges() throws Exception {
        var ambiguous =
                analyze(
                        """
                class A {
                  void f(String value) {} void f(Object value) {} void f(Integer value) {}
                  void f(Long value) {} void f(Double value) {} void f(Float value) {}
                  void f(Short value) {} void f(Byte value) {} void f(Character value) {}
                  void f(Boolean value) {} void f(StringBuilder value) {} void f(StringBuffer value) {}
                  void f(Number value) {} void f(CharSequence value) {} void f(Comparable<?> value) {}
                  void f(java.io.Serializable value) {} void f(Cloneable value) {}
                  void run() { f(null); }
                }
                """);
        assertUnbound(site(ambiguous, "METHOD_INVOCATION"));
        var fixed = analyze("class A { void f(String value) {} void run() { f(null); } }");
        assertTrue(site(fixed, "METHOD_INVOCATION").declarationResolved());
        assertEquals(
                java.util.List.of("java.lang.String"),
                site(fixed, "METHOD_INVOCATION").parameters());
    }

    private static void assertUnbound(JavaBinding binding) {
        assertTrue(binding == null || !binding.declarationResolved());
    }

    private JavaUnit analyze(String source) throws Exception {
        return JavaFactsTestSupport.analyze(directory, "A.java", source).units().getFirst();
    }

    private static JavaBinding site(JavaUnit unit, String kind) {
        var syntax = new JavaSyntax(unit.tree());
        var node =
                syntax.nodes().stream()
                        .filter(value -> value.kind().equals(kind) && value.span().present())
                        .findFirst()
                        .orElseThrow();
        return unit.bindings().get(node.id());
    }
}
