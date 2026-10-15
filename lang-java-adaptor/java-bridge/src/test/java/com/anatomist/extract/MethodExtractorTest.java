package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class MethodExtractorTest {

    @Test
    void extract_emitsMethodNodeAndContainsEdge(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(directory, "package pkg; public class A { public void foo() {} }");

        assertEquals(2, r.nodes.size());
        Node n =
                r.nodes.stream()
                        .filter(node -> "METHOD".equals(node.kind))
                        .findFirst()
                        .orElseThrow();
        assertEquals("pkg.A#foo()", n.id);
        assertEquals("METHOD", n.kind);

        assertEquals(2, r.edges.size());
        assertTrue(
                r.declarations.stream()
                        .anyMatch(d -> d.synthetic && "pkg.A#A()".equals(d.symbolId)));
        Edge e =
                r.edges.stream()
                        .filter(edge -> "pkg.A#foo()".equals(edge.targetId))
                        .findFirst()
                        .orElseThrow();
        assertEquals("pkg.A", e.sourceId);
        assertEquals("pkg.A#foo()", e.targetId);
        assertEquals("CONTAINS", e.relation);
        assertFalse(e.isExternal);
        assertNull(e.externalTargetFqn);
    }

    @Test
    void extract_distinguishesOverloads(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A {"
                                + "  public void foo() {}"
                                + "  public void foo(String s) {}"
                                + "}");

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#foo()"));
        assertTrue(ids.contains("pkg.A#foo(java.lang.String)"));
    }

    @Test
    void extract_handlesConstructorAndGenericList(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; import java.util.List;"
                                + "public class A {"
                                + "  public A() {}"
                                + "  public void bar(List<Integer> xs) {}"
                                + "}");

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#A()"), "constructor id; got " + ids);
        assertTrue(ids.contains("pkg.A#bar(java.util.List)"), "erased generic; got " + ids);

        Node ctor =
                r.nodes.stream().filter(n -> n.id.equals("pkg.A#A()")).findFirst().orElseThrow();
        assertTrue(ctor.metadata.contains("\"isConstructor\":true"));
    }

    @Test
    void methodMetadata_marksGetterAsAccessor(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A {"
                                + "  private String name;"
                                + "  public String getName() { return name; }"
                                + "  public boolean isActive() { return true; }"
                                + "}");

        Node getName =
                r.nodes.stream()
                        .filter(n -> n.id.equals("pkg.A#getName()"))
                        .findFirst()
                        .orElseThrow();
        Node isActive =
                r.nodes.stream()
                        .filter(n -> n.id.equals("pkg.A#isActive()"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                getName.metadata.contains("\"isAccessor\":true"),
                "getName must be accessor; got " + getName.metadata);
        assertTrue(
                isActive.metadata.contains("\"isAccessor\":true"),
                "isActive (boolean) must be accessor; got " + isActive.metadata);
    }

    @Test
    void methodMetadata_marksSetterAsAccessor(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A {"
                                + "  private String name;"
                                + "  public void setName(String n) { this.name = n; }"
                                + "}");

        Node setName =
                r.nodes.stream()
                        .filter(n -> n.id.equals("pkg.A#setName(java.lang.String)"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                setName.metadata.contains("\"isAccessor\":true"),
                "setName must be accessor; got " + setName.metadata);
    }

    @Test
    void methodMetadata_marksNonAccessorAsFalse(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A {"
                                + "  public void process() {}"
                                + "  public String isReady() { return \"\"; }" // is + non-boolean
                                // return
                                + "  public void getSomething() {}" // get + void return
                                + "  public void setX() {}" // set + 0 params
                                + "  public int get() { return 0; }" // bare get
                                + "}");

        for (String id :
                List.of(
                        "pkg.A#process()",
                        "pkg.A#isReady()",
                        "pkg.A#getSomething()",
                        "pkg.A#setX()",
                        "pkg.A#get()")) {
            Node n =
                    r.nodes.stream()
                            .filter(x -> x.id.equals(id))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("missing " + id));
            assertTrue(
                    n.metadata.contains("\"isAccessor\":false"),
                    id + " must be non-accessor; got " + n.metadata);
        }
    }

    @Test
    void visit_lambdaExpr_emitsLambdaNodeAndContainsEdge(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; import java.util.List; import java.util.function.Predicate;"
                                + "public class A {"
                                + "  void run(List<String> xs) {"
                                + "    Predicate<String> p = s -> s.isEmpty();"
                                + "  }"
                                + "}");

        Node lambda =
                r.nodes.stream()
                        .filter(n -> "LAMBDA".equals(n.kind))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no LAMBDA node; got " + r.nodes));
        assertTrue(
                lambda.id.startsWith("pkg.A#run(java.util.List)$lambda@L"),
                "lambda id format; got " + lambda.id);
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && "pkg.A#run(java.util.List)".equals(e.sourceId)
                                                && lambda.id.equals(e.targetId)),
                "CONTAINS edge from parent method missing; got " + r.edges);
    }

    @Test
    void visit_anonymousClassMethod_emitsMethodNodeContainedByAnonClass(@TempDir Path directory)
            throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg;"
                                + "public class A {"
                                + "  void outer() {"
                                + "    new Runnable(){ public void run(){} };"
                                + "  }"
                                + "}");

        String anonMethod =
                r.nodes.stream()
                        .filter(n -> "METHOD".equals(n.kind))
                        .map(n -> n.id)
                        .filter(id -> id.startsWith("pkg.A#outer()$anon@L"))
                        .filter(id -> id.endsWith("#run()"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "anonymous method node missing; got " + r.nodes));
        String anonClass = anonMethod.substring(0, anonMethod.indexOf("#run()"));
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && anonClass.equals(e.sourceId)
                                                && anonMethod.equals(e.targetId)),
                "anonymous class must CONTAIN its method; got " + r.edges);
    }

    @Test
    void visit_unresolvedAnonymousClassMethod_emitsStableFallbackMethodNode(@TempDir Path directory)
            throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg;"
                                + "public class A {"
                                + "  void outer() {"
                                + "    new Callback(){ public void done(Missing m){} };"
                                + "  }"
                                + "}"
                                + "interface Callback { void done(Missing m); }");

        String anonMethod =
                r.nodes.stream()
                        .filter(n -> "METHOD".equals(n.kind))
                        .map(n -> n.id)
                        .filter(id -> id.startsWith("pkg.A#outer()$anon@L"))
                        .filter(id -> id.endsWith("#done(Missing)"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "fallback anonymous method node missing; got "
                                                        + r.nodes));
        String anonClass = anonMethod.substring(0, anonMethod.indexOf("#done("));
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && anonClass.equals(e.sourceId)
                                                && anonMethod.equals(e.targetId)),
                "fallback anonymous class must CONTAIN its method; got " + r.edges);
    }

    @Test
    void visit_nestedAnonymousMethodInsideUnresolvedAnonymousMethod_emitsStableFallbackMethodNode(
            @TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        """
                package pkg;
                public class A {
                  void outer() {
                    new MissingCallback() {
                      public void done() {
                        new InnerCallback() {
                          public void call() {}
                        };
                      }
                    };
                  }
                }
                """);

        String nestedMethod =
                r.nodes.stream()
                        .filter(n -> "METHOD".equals(n.kind))
                        .map(n -> n.id)
                        .filter(id -> id.startsWith("pkg.A#outer()$anon@L"))
                        .filter(id -> id.contains("#done()$anon@L"))
                        .filter(id -> id.endsWith("#call()"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "nested fallback anonymous method missing; got "
                                                        + r.nodes));
        String nestedClass = nestedMethod.substring(0, nestedMethod.indexOf("#call()"));
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && nestedClass.equals(e.sourceId)
                                                && nestedMethod.equals(e.targetId)),
                "nested fallback anonymous class must CONTAIN its method; got " + r.edges);
    }

    @Test
    void visit_nestedLambda_emitsDistinctIds(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; import java.util.function.Function;"
                                + "public class A {"
                                + "  Function<String, Function<String, String>> f = a -> b -> a + b;"
                                + "}");

        long lambdas = r.nodes.stream().filter(n -> "LAMBDA".equals(n.kind)).count();
        assertEquals(2, lambdas, "expected 2 LAMBDA nodes (outer + inner); got " + r.nodes);
        Set<String> ids =
                r.nodes.stream()
                        .filter(n -> "LAMBDA".equals(n.kind))
                        .map(n -> n.id)
                        .collect(Collectors.toSet());
        assertEquals(2, ids.size(), "nested lambda ids must be distinct; got " + ids);
    }

    @Test
    void unresolvedParameterTypes_keepOverloadsDistinct(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A {"
                                + " void convert(MissingOne value) {}"
                                + " void convert(MissingTwo value) {}"
                                + "}");

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.A#convert(MissingOne)"), ids.toString());
        assertTrue(ids.contains("pkg.A#convert(MissingTwo)"), ids.toString());
        assertEquals(2, ids.stream().filter(id -> id.contains("#convert(")).count());
    }

    @Test
    void anonymousMethod_isEmittedExactlyOnce(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A { void outer() {"
                                + " new Runnable() { @Override public void run() {} };"
                                + "} }");

        List<Node> methods =
                r.nodes.stream()
                        .filter(
                                n ->
                                        "METHOD".equals(n.kind)
                                                && n.id.contains("$anon@L")
                                                && n.id.endsWith("#run()"))
                        .toList();
        assertEquals(1, methods.size(), methods.toString());
        String owner = methods.get(0).id.substring(0, methods.get(0).id.lastIndexOf("#run()"));
        List<Edge> contains =
                r.edges.stream()
                        .filter(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && methods.get(0).id.equals(e.targetId))
                        .toList();
        assertEquals(1, contains.size());
        assertEquals(owner, contains.get(0).sourceId);
    }

    @Test
    void anonymousMethodInFieldInitializer_usesStableFieldOwner(@TempDir Path directory)
            throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; public class A {"
                                + " static Runnable task = new Runnable() { public void run() {} };"
                                + "}");

        List<String> ids =
                r.nodes.stream().map(n -> n.id).filter(id -> id.endsWith("#run()")).toList();
        assertEquals(1, ids.size(), ids.toString());
        assertTrue(ids.get(0).startsWith("pkg.A#task$anon@L"), ids.toString());
        assertFalse(ids.get(0).contains("Anonymous-"), ids.toString());
        String owner = ids.get(0).substring(0, ids.get(0).lastIndexOf("#run()"));
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && owner.equals(e.sourceId)
                                                && ids.get(0).equals(e.targetId)),
                r.edges.toString());
    }

    @Test
    void recordMethodsAndCompactConstructor_areEmitted(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "Request.java",
                        "package pkg; public record Request(String value) {"
                                + " public Request { if (value == null) throw new IllegalArgumentException(); }"
                                + " public String normalized() { return value.trim(); }"
                                + "}");

        Set<String> ids = r.nodes.stream().map(n -> n.id).collect(Collectors.toSet());
        assertTrue(ids.contains("pkg.Request#Request(java.lang.String)"), ids.toString());
        assertTrue(ids.contains("pkg.Request#normalized()"), ids.toString());
    }

    @Test
    void visit_methodReferenceExpr_emitsMethodRefNodeAndCallsEdge(@TempDir Path directory)
            throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; import java.util.List; import java.util.stream.Stream;"
                                + "public class A {"
                                + "  static String upper(String s) { return s.toUpperCase(); }"
                                + "  Stream<String> run(List<String> xs) { return xs.stream().map(A::upper); }"
                                + "}");

        Node methodRef =
                r.nodes.stream()
                        .filter(n -> "METHOD_REF".equals(n.kind))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no METHOD_REF; got " + r.nodes));
        assertTrue(methodRef.id.contains("$methodref@L"), "id format; got " + methodRef.id);
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CONTAINS".equals(e.relation)
                                                && methodRef.id.equals(e.targetId)),
                "CONTAINS edge for METHOD_REF missing; got " + r.edges);
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "CALLS".equals(e.relation)
                                                && methodRef.id.equals(e.sourceId)
                                                && !e.isExternal
                                                && "pkg.A#upper(java.lang.String)"
                                                        .equals(e.targetId)),
                "CALLS edge from METHOD_REF to A.upper missing; got " + r.edges);
    }

    @Test
    void visit_methodReferenceExpr_resolveFailure_emitsNodeWithoutCallsEdge(@TempDir Path directory)
            throws Exception {
        // Unresolved source syntax retains a METHOD_REF without inventing a call target.
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; import java.util.function.Supplier;"
                                + "public class A {"
                                + "  Supplier<Object> s = unknownVar::doesNotExist;"
                                + "}");

        Node methodRef =
                r.nodes.stream()
                        .filter(n -> "METHOD_REF".equals(n.kind))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no METHOD_REF; got " + r.nodes));
        assertTrue(
                methodRef.metadata.contains("\"bindingResolved\":false"),
                "bindingResolved=false expected; got " + methodRef.metadata);
        assertFalse(
                r.edges.stream()
                        .anyMatch(
                                e -> "CALLS".equals(e.relation) && methodRef.id.equals(e.sourceId)),
                "no CALLS edge for unresolved METHOD_REF; got " + r.edges);
    }

    @Test
    void visit_constructorReference_gracefulDegrade(@TempDir Path directory) throws Exception {
        ExtractionResult r =
                methods(
                        directory,
                        "package pkg; import java.util.function.Supplier;"
                                + "public class A {"
                                + "  Supplier<Object> make = Object::new;"
                                + "}");

        // METHOD_REF Node always emitted for graceful degrade.
        assertTrue(
                r.nodes.stream().anyMatch(n -> "METHOD_REF".equals(n.kind)),
                "constructor reference should still emit METHOD_REF Node; got " + r.nodes);
    }

    private static ExtractionResult methods(Path directory, String source) throws Exception {
        return methods(directory, "A.java", source);
    }

    private static ExtractionResult methods(Path directory, String name, String source)
            throws Exception {
        var result = JavaFactsTestSupport.extract(directory, name, source);
        Set<String> kinds = Set.of("METHOD", "CONSTRUCTOR", "LAMBDA", "METHOD_REF");
        result.nodes.removeIf(node -> !kinds.contains(node.kind));
        Set<String> ids = result.nodes.stream().map(node -> node.id).collect(Collectors.toSet());
        Set<String> references =
                result.nodes.stream()
                        .filter(node -> "METHOD_REF".equals(node.kind))
                        .map(node -> node.id)
                        .collect(Collectors.toSet());
        result.edges.removeIf(
                edge ->
                        !("CONTAINS".equals(edge.relation) && ids.contains(edge.targetId))
                                && !("CALLS".equals(edge.relation)
                                        && references.contains(edge.sourceId)));
        return result;
    }
}
