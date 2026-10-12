package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.asmsolver.*;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaReflectionFacts;
import com.anatomist.model.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaReflectionFactsIT {
    @Test
    void conditionalBooleanWritesMergeSkippedAndEvaluatedStates(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Caller.java"),
                            """
                    class Caller {
                        void run(boolean flag) throws Exception {
                            String first = "unknown.Before";
                            boolean left = flag && (first = "unknown.After").isEmpty();
                            Class.forName(first);
                            String second = "unknown.Before";
                            boolean right = flag || (second = "unknown.After").isEmpty();
                            Class.forName(second);
                            String stable = "unknown.Stable";
                            boolean same = flag && (stable = "unknown.Stable").isEmpty();
                            Class.forName(stable);
                        }
                    }
                    """);
            var facts =
                    extract(
                            project,
                            file,
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release);
            assertEquals(1, facts.edges.size(), "JDK " + release);
            assertEquals("unknown.Stable", facts.edges.getFirst().externalTargetFqn);
        }
    }

    @Test
    void exactReflectionFactsUseOriginalCoordinatesAndDetachedSourceAndBinaryDeclarations(
            @TempDir Path directory) throws Exception {
        String source =
                """
                package p;
                import java.lang.reflect.*;
                class Parent { public String inherited(String value) { return value; } }
                class Target extends Parent {
                    public Target() {}
                    private Target(int value) {}
                    public String echo(String value) { return value; }
                    private void hidden() {}
                }
                class Outer { static class Inner {} }
                class Caller {
                    void run(boolean flag) throws Exception {
                        String name;
                        if (flag) name = "p." + "Target"; else name = "p.Target";
                        Class<?> type = Class.forName(name);
                        String methodName = "ec" + "ho";
                        Method method = type.getMethod(methodName, new Class<?>[]{String.class});
                        Method alias = method;
                        alias.invoke(new Target(), "value");
                        Constructor<Target> ctor = Target.class.getDeclaredConstructor(Integer.TYPE);
                        ctor.newInstance(1);
                        Target.class.getMethod("inherited", String.class).invoke(new Target(), "value");
                        Target.class.getDeclaredMethod("hidden");
                        Target.class.getConstructor();
                        Class.forName("p.Outer$Inner");
                        String.class.getMethod("substring", int.class).invoke("value", 1);
                    }
                }
                """;
        for (int release : List.of(8, 17, 25)) {
            Path home = Objects.requireNonNull(JavacBackendIT.home(release));
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = Files.writeString(project.resolve("Caller.java"), source);
            var facts = extract(project, file, home, release);
            var operations = new HashSet<String>();
            for (Edge edge : facts.edges) {
                assertEquals("p.Caller#run(boolean)", edge.sourceId);
                assertEquals(GraphConstants.Confidence.INFERRED, edge.confidence);
                assertTrue(edge.beginLine > 0 && edge.beginColumn > 0);
                var metadata = (Map<?, ?>) com.anatomist.json.Json.parseTree(edge.metadata);
                operations.add(metadata.get("operation") + " " + target(edge));
                if (target(edge).equals("p.Outer.Inner")) assertFalse(edge.isExternal);
                if (metadata.get("operation").equals("CLASS_FOR_NAME")
                        && target(edge).equals("p.Target"))
                    assertEquals("LOCAL_CONSTANT", metadata.get("value_source"));
                if (metadata.get("operation").equals("METHOD_INVOKE")
                        && target(edge).equals("p.Target#echo(java.lang.String)"))
                    assertEquals("LOCAL_HANDLE", metadata.get("value_source"));
            }
            assertEquals(
                    Set.of(
                            "CLASS_FOR_NAME p.Target", "CLASS_FOR_NAME p.Outer.Inner",
                            "METHOD_LOOKUP p.Target#echo(java.lang.String)",
                                    "METHOD_INVOKE p.Target#echo(java.lang.String)",
                            "CONSTRUCTOR_LOOKUP p.Target#Target(int)",
                                    "CONSTRUCTOR_NEW_INSTANCE p.Target#Target(int)",
                            "METHOD_LOOKUP p.Parent#inherited(java.lang.String)",
                                    "METHOD_INVOKE p.Parent#inherited(java.lang.String)",
                            "METHOD_LOOKUP p.Target#hidden()",
                                    "CONSTRUCTOR_LOOKUP p.Target#Target()",
                            "METHOD_LOOKUP java.lang.String#substring(int)",
                                    "METHOD_INVOKE java.lang.String#substring(int)"),
                    operations,
                    "JDK " + release);
            assertTrue(
                    facts.edges.stream()
                            .filter(edge -> "CALLS".equals(edge.relation))
                            .allMatch(
                                    edge ->
                                            GraphConstants.CallKind.REFLECTION.equals(
                                                    edge.callKind)));
        }
    }

    @Test
    void dynamicConflictingAndReassignedValuesAndLexicalLookalikesRemainUnknown(
            @TempDir Path project) throws Exception {
        Path file =
                Files.writeString(
                        project.resolve("Caller.java"),
                        """
                package p;
                class Target { public void present() {} }
                class Class { static java.lang.Class<?> forName(String name) { return Target.class; } }
                class Caller {
                    void run(boolean flag, String dynamic) throws Exception {
                        java.lang.Class.forName(dynamic);
                        String name;
                        if (flag) name = "p.Target"; else name = "p.Other";
                        java.lang.Class.forName(name);
                        Target.class.getMethod("missing");
                        Target.class.getMethod("present", Integer.class);
                        Class.forName("p.Target");
                        String loop = "p.Target";
                        while (flag) { loop = dynamic; }
                        java.lang.Class.forName(loop);
                        String conditional = flag ? "p.Target" : "p.Other";
                        java.lang.Class.forName(conditional);
                    }
                }
                """);
        assertTrue(
                extract(project, file, Path.of(System.getProperty("java.home")), 25)
                        .edges
                        .isEmpty());
    }

    @Test
    void configuredExternalClassesAndShadowedLocalsKeepSourceEvidence(@TempDir Path project)
            throws Exception {
        Path file =
                Files.writeString(
                        project.resolve("Caller.java"),
                        """
                class Caller {
                    void run(boolean flag) throws Exception {
                        String name = "external.Plugin";
                        if (flag) {
                            Class.forName(name);
                        }
                        Runnable callback = () -> { try { Class.forName("external.Callback"); } catch (Exception ignored) {} };
                        Class.forName(name);
                    }
                }
                """);
        var facts = extract(project, file, Path.of(System.getProperty("java.home")), 25);
        assertEquals(3, facts.edges.size());
        var branch =
                facts.edges.stream().filter(edge -> edge.beginLine == 5).findFirst().orElseThrow();
        assertEquals("if-then@L4", branch.context);
        assertEquals("external.Plugin", branch.externalTargetFqn);
        assertTrue(
                facts.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.sourceId.contains("$lambda@L7C")
                                                && "external.Callback"
                                                        .equals(edge.externalTargetFqn)));
    }

    @Test
    void controlFlowMergesConstantsAndInvalidatesLoopAndArrayMutation(@TempDir Path directory)
            throws Exception {
        String source =
                """
                package p;
                class Target { public void present(String value) {} }
                class Caller {
                    static void mutate(Class<?>[] values) { values[0] = Integer.class; }
                    void run(int choice, boolean flag) throws Exception {
                        String name = "external.Before";
                        switch (choice) {
                            case 0: name = "external.Switch"; break;
                            default: name = "external.Switch";
                        }
                        Class.forName(name);
                        String tried;
                        try { tried = "external.Try"; }
                        catch (Exception ignored) { tried = "external.Try"; }
                        finally { Class.forName("external.Finally"); }
                        Class.forName(tried);
                        String stable = flag ? "external.Stable" : "external.Stable";
                        for (int i = 0; i < choice; i++) { Class.forName(stable); }
                        String carried = "external.First";
                        do { Class.forName(carried); carried = "external.Next"; } while (flag);
                        String fallthrough = "external.Initial";
                        switch (choice) {
                            case 0: fallthrough = "external.Fallthrough";
                            case 1: Class.forName(fallthrough); break;
                            default: break;
                        }
                        Class<?>[] parameters = {String.class};
                        Class<?>[] alias = parameters;
                        alias[0] = Integer.class;
                        Target.class.getMethod("present", parameters);
                        Class<?>[] escaped = {String.class};
                        mutate(escaped);
                        Target.class.getMethod("present", escaped);
                        String compound = "external.Compound";
                        compound += "Changed";
                        Class.forName(compound);
                        synchronized (this) { Class.forName("external.Synchronized"); }
                    }
                }
                """;
        for (int release : List.of(8, 17, 25)) {
            Path home = Objects.requireNonNull(JavacBackendIT.home(release));
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = Files.writeString(project.resolve("Caller.java"), source);
            var facts = extract(project, file, home, release);
            assertEquals(
                    Set.of(
                            "external.Switch",
                            "external.Try",
                            "external.Finally",
                            "external.Stable",
                            "external.Synchronized"),
                    facts.edges.stream()
                            .map(JavaReflectionFactsIT::target)
                            .collect(java.util.stream.Collectors.toSet()),
                    "JDK " + release);
        }
    }

    private static ExtractionResult extract(Path project, Path file, Path home, int release) {
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
        try (var model = new JavaModel(contexts.getFirst());
                var binaries =
                        new JavaBinaryRepository(
                                new JdkClassFileSource(home, release), 8L * 1024 * 1024)) {
            var result = new ExtractionResult();
            new JavaReflectionFacts(model, project, binaries).extract(file, result);
            return result;
        }
    }

    private static String target(Edge edge) {
        return edge.isExternal ? edge.externalTargetFqn : edge.targetId;
    }
}
