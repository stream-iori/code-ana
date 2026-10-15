package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anatomist.core.EdgeTargetBinder;
import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

class ReflectionExtractorTest {

    @TempDir Path directory;

    @Test
    void extractsExactClassMethodConstructorAndInvocationTargets() throws Exception {
        ExtractionResult result =
                extract(
                        """
                package p;
                import java.lang.reflect.Constructor;
                import java.lang.reflect.Method;

                class Parent {
                    public String inherited(String value) { return value; }
                }
                class Target extends Parent {
                    public Target() {}
                    private Target(int value) {}
                    public String echo(String value) { return value; }
                    private void hidden() {}
                }
                class Caller {
                    void run() throws Exception {
                        Class.forName("p.Target");
                        Class.forName("p.Target", false, Caller.class.getClassLoader());
                        Method method = Target.class.getMethod("echo", String.class);
                        method.invoke(new Target(), "value");
                        Constructor<Target> constructor =
                                Target.class.getDeclaredConstructor(int.class);
                        constructor.newInstance(1);
                        Target.class.getMethod("inherited", String.class)
                                .invoke(new Target(), "value");
                        Target.class.getDeclaredMethod("hidden");
                        Target.class.getConstructor();
                    }
                }
                """);

        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.REFERENCES,
                null,
                "p.Target",
                "CLASS_FOR_NAME");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.REFERENCES,
                null,
                "p.Target#echo(java.lang.String)",
                "METHOD_LOOKUP");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.CALLS,
                GraphConstants.CallKind.REFLECTION,
                "p.Target#echo(java.lang.String)",
                "METHOD_INVOKE");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.REFERENCES,
                null,
                "p.Target#Target(int)",
                "CONSTRUCTOR_LOOKUP");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.CALLS,
                GraphConstants.CallKind.REFLECTION,
                "p.Target#Target(int)",
                "CONSTRUCTOR_NEW_INSTANCE");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.CALLS,
                GraphConstants.CallKind.REFLECTION,
                "p.Parent#inherited(java.lang.String)",
                "METHOD_INVOKE");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.REFERENCES,
                null,
                "p.Target#hidden()",
                "METHOD_LOOKUP");
        assertEdge(
                result,
                "p.Caller#run()",
                GraphConstants.Relation.REFERENCES,
                null,
                "p.Target#Target()",
                "CONSTRUCTOR_LOOKUP");
    }

    @Test
    void propagatesLocalConstantsAliasesAndIdenticalBranches() throws Exception {
        ExtractionResult result =
                extract(
                        """
                package p;
                import java.lang.reflect.Method;
                class Target {
                    public String echo(String value) { return value; }
                }
                class Caller {
                    void run(boolean flag) throws Exception {
                        String prefix = "p.";
                        String className;
                        if (flag) className = prefix + "Target";
                        else className = "p.Target";
                        Class<?> type = Class.forName(className);
                        String methodName = "ec" + "ho";
                        Method method = type.getMethod(methodName,
                                new Class<?>[]{String.class});
                        Method alias = method;
                        alias.invoke(new Target(), "value");
                    }
                }
                """);

        Edge classLookup = edge(result, "p.Target", "CLASS_FOR_NAME");
        assertTrue(
                classLookup.metadata.contains("\"value_source\":\"LOCAL_CONSTANT\""),
                classLookup.metadata);
        Edge methodLookup = edge(result, "p.Target#echo(java.lang.String)", "METHOD_LOOKUP");
        assertTrue(
                methodLookup.metadata.contains("\"value_source\":\"LOCAL_CONSTANT\""),
                methodLookup.metadata);
        Edge invocation = edge(result, "p.Target#echo(java.lang.String)", "METHOD_INVOKE");
        assertEquals(GraphConstants.CallKind.REFLECTION, invocation.callKind);
        assertTrue(
                invocation.metadata.contains("\"value_source\":\"LOCAL_HANDLE\""),
                invocation.metadata);
    }

    @Test
    void rejectsDynamicConflictingMissingAndLexicalLookalikeTargets() throws Exception {
        ExtractionResult result =
                extract(
                        """
                package p;
                class Target {
                    public void present() {}
                }
                class Class {
                    static java.lang.Class<?> forName(String name) { return Target.class; }
                }
                class Caller {
                    void run(boolean flag, String dynamic) throws Exception {
                        java.lang.Class.forName(dynamic);
                        String conflicting;
                        if (flag) conflicting = "p.Target";
                        else conflicting = "p.Other";
                        java.lang.Class.forName(conflicting);
                        Target.class.getMethod("missing");
                        Target.class.getMethod("present", Integer.class);
                        Class.forName("p.Target");
                    }
                }
                """);

        assertFalse(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        isReflection(edge)
                                                && ("p.Target".equals(target(edge))
                                                        || "p.Other".equals(target(edge))
                                                        || "p.Target#missing()".equals(target(edge))
                                                        || "p.Target#present(java.lang.Integer)"
                                                                .equals(target(edge)))),
                describe(result));
    }

    @Test
    void reassignmentAndLoopWriteInvalidateConstantsAfterTheWrite() throws Exception {
        ExtractionResult result =
                extract(
                        """
                package p;
                class Target {}
                class Other {}
                class Caller {
                    void run(boolean flag) throws Exception {
                        String name = "p.Target";
                        name = flag ? "p.Target" : "p.Other";
                        Class.forName(name);
                        String loopName = "p.Target";
                        while (flag) { loopName = "p.Other"; }
                        Class.forName(loopName);
                    }
                }
                """);

        assertFalse(
                result.edges.stream().anyMatch(ReflectionExtractorTest::isReflection),
                describe(result));
    }

    @Test
    void recordsBranchContextAndKeepsExternalConfiguredTarget() throws Exception {
        ExtractionResult result =
                extract(
                        """
                package p;
                class Caller {
                    void run(boolean enabled) throws Exception {
                        if (enabled) {
                            Class.forName("external.Plugin");
                        }
                    }
                }
                """);

        Edge edge = edge(result, "external.Plugin", "CLASS_FOR_NAME");
        assertTrue(edge.isExternal);
        assertEquals("if-then@L4", edge.context);
        assertEquals(GraphConstants.Confidence.INFERRED, edge.confidence);
        assertEquals("L5", edge.sourceLocation);
    }

    @Test
    void normalizesBinaryNestedClassNameWhenDeclarationIsAvailable() throws Exception {
        ExtractionResult result =
                extract(
                        """
                package p;
                class Outer { static class Inner {} }
                class Caller {
                    void run() throws Exception {
                        Class.forName("p.Outer$Inner");
                    }
                }
                """);

        Edge edge = edge(result, "p.Outer.Inner", "CLASS_FOR_NAME");
        assertFalse(edge.isExternal);
        assertTrue(edge.metadata.contains("\"class_name\":\"p.Outer.Inner\""), edge.metadata);
    }

    @Test
    void largeNonReflectionMethodRemainsBounded() throws Exception {
        StringBuilder source = new StringBuilder("package p; class Large { void run() {");
        for (int i = 0; i < 2_000; i++) {
            source.append("String value").append(i).append(" = \"").append(i).append("\";");
        }
        source.append("} }");

        var context = JavaFactsTestSupport.analyze(directory, "Large.java", source.toString());
        try (var model = new com.anatomist.core.java.JavaModel(context)) {
            assertTimeout(
                    Duration.ofSeconds(5),
                    () -> {
                        var result = new ExtractionResult();
                        new JavaReflectionFacts(model, directory, null)
                                .extract(directory.resolve("Large.java"), result);
                        assertFalse(
                                result.edges.stream()
                                        .anyMatch(ReflectionExtractorTest::isReflection));
                    });
        }
    }

    private ExtractionResult extract(String source) throws Exception {
        ExtractionResult result = JavaFactsTestSupport.extract(directory, "Case.java", source);
        EdgeTargetBinder.bindExternalTargets(result);
        return result;
    }

    private static void assertEdge(
            ExtractionResult result,
            String source,
            String relation,
            String callKind,
            String target,
            String operation) {
        Edge edge = edge(result, target, operation);
        assertEquals(source, edge.sourceId);
        assertEquals(relation, edge.relation);
        assertEquals(callKind, edge.callKind);
        assertEquals(GraphConstants.Confidence.INFERRED, edge.confidence);
        assertTrue(edge.metadata.contains("\"via\":\"reflection\""), edge.metadata);
        assertTrue(edge.metadata.contains("\"resolution\":\"EXACT\""), edge.metadata);
    }

    private static Edge edge(ExtractionResult result, String target, String operation) {
        return result.edges.stream()
                .filter(ReflectionExtractorTest::isReflection)
                .filter(edge -> target.equals(target(edge)))
                .filter(edge -> edge.metadata.contains("\"operation\":\"" + operation + "\""))
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "missing "
                                                + operation
                                                + " -> "
                                                + target
                                                + "; got "
                                                + describe(result)));
    }

    private static boolean isReflection(Edge edge) {
        return edge.metadata != null && edge.metadata.contains("\"via\":\"reflection\"");
    }

    private static String target(Edge edge) {
        return edge.isExternal ? edge.externalTargetFqn : edge.targetId;
    }

    private static String describe(ExtractionResult result) {
        return result.edges.stream()
                .filter(ReflectionExtractorTest::isReflection)
                .map(
                        edge ->
                                edge.sourceId
                                        + " -"
                                        + edge.relation
                                        + "/"
                                        + edge.callKind
                                        + "-> "
                                        + target(edge)
                                        + " "
                                        + edge.metadata)
                .toList()
                .toString();
    }
}
