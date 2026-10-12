package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaBindingEvidenceIT {
    @Test
    void errorArgumentsCannotConfirmAnArbitraryOverload(@TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("overloads" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    class Parent {}
                    class First extends Parent {}
                    class Second extends Parent {}
                    class Use {
                        void pick(First value) {}
                        void pick(Second value) {}
                        void single(Parent value) {}
                        void run() { pick(absent()); single(absent()); }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(project, release)
                            .analyzeFiles(
                                    List.of(source),
                                    analysis -> extractor.extract(analysis, facts::add))
                            .complete());
            var result = facts.getFirst().facts();
            assertEquals(
                    List.of("Use#single(Parent)"),
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals("Use#run()"))
                            .map(edge -> edge.targetId)
                            .toList(),
                    "JDK " + release);
            assertEquals(3, result.unresolvedCallSites.size());
        }
    }

    @Test
    void externalNestedOwnersParametersAndConstructorsKeepBinaryIds(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("binary" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    class Use {
                        Object entry(java.util.Map.Entry<String,String> value) { return value.getKey(); }
                        Object decode(java.util.Base64.Decoder value) { return value.decode("a"); }
                        Object constructed() { return new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy(); }
                        void nestedParameter() { java.text.Normalizer.normalize("a", java.text.Normalizer.Form.NFC); }
                    }
                    abstract class Entry implements java.util.Map.Entry<String,String> {
                        public String getKey() { return "key"; }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(project, release)
                            .analyzeFiles(
                                    List.of(source),
                                    analysis -> extractor.extract(analysis, facts::add))
                            .complete());
            var targets =
                    facts.getFirst().facts().edges.stream()
                            .filter(edge -> edge.relation.equals("CALLS") && edge.isExternal)
                            .map(edge -> edge.externalTargetFqn)
                            .toList();
            for (String target :
                    List.of(
                            "java.util.Map$Entry#getKey()",
                            "java.util.Base64$Decoder#decode(java.lang.String)",
                            "java.util.concurrent.ThreadPoolExecutor$CallerRunsPolicy#CallerRunsPolicy()",
                            "java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)"))
                assertTrue(targets.contains(target), "JDK " + release + ": " + targets);
            assertTrue(
                    facts.getFirst().facts().edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("OVERRIDES")
                                                    && edge.isExternal
                                                    && "java.util.Map$Entry#getKey()"
                                                            .equals(edge.externalTargetFqn)),
                    "nested external override JDK " + release);
        }
    }

    @Test
    void diagnosticBudgetFailureDoesNotPublishAndAllowsAFreshWorker(@TempDir Path project)
            throws Exception {
        String missing =
                java.util.stream.IntStream.range(0, 16385)
                        .mapToObj(index -> "missing" + index + "();")
                        .collect(java.util.stream.Collectors.joining("\n"));
        Path file =
                Files.writeString(
                        project.resolve("Use.java"),
                        "class Use { void run() {\n" + missing + "\n} }");
        var backend = backend(project, 25);
        var delivered = new ArrayList<JavaAnalysisContext>();
        assertThrows(
                IllegalStateException.class,
                () -> backend.analyzeFiles(List.of(file), delivered::add));
        assertTrue(delivered.isEmpty());
        Files.writeString(file, "class Use { void run() {} }");
        assertTrue(backend.analyzeFiles(List.of(file), delivered::add).complete());
        assertEquals(1, delivered.size());
        assertEquals("", delivered.getFirst().analysisFailure());
    }

    @Test
    void selectionsAfterHundredsOfErrorsRemainUnresolved(@TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            String missing =
                    java.util.stream.IntStream.range(0, 150)
                            .mapToObj(index -> "missing" + index + "();")
                            .collect(java.util.stream.Collectors.joining("\n"));
            Path file =
                    Files.writeString(
                            project.resolve("Use.java"),
                            "class Use { void pick(String x) {} void pick(Integer x) {} void run() {\n"
                                    + missing
                                    + "\npick(null); } }");
            var contexts = new ArrayList<JavaAnalysisContext>();
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(project, release)
                            .analyzeFiles(
                                    List.of(file),
                                    context -> {
                                        contexts.add(context);
                                        extractor.extract(context, facts::add);
                                    })
                            .complete());
            assertTrue(
                    contexts.getFirst().diagnostics().stream()
                            .anyMatch(diagnostic -> diagnostic.code().contains("ambiguous")),
                    "JDK " + release);
            assertEquals(151, facts.getFirst().facts().unresolvedCallSites.size());
            assertTrue(
                    facts.getFirst().facts().edges.stream()
                            .noneMatch(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && "Use#run()".equals(edge.sourceId)));
        }
    }

    @Test
    void selectedDeclarationsSurviveMissingResultTypesAndNestedArgumentErrors(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Box.java"),
                            """
                    class Box {
                        Missing missing;
                        Missing unknownReturn() { return null; }
                        void accept(Object value) {}
                        int known(int value) { return value; }
                        void run() {
                            unknownReturn();
                            accept(absent());
                            known(absent());
                            Object value = missing;
                        }
                    }
                    """);
            var contexts = new ArrayList<JavaAnalysisContext>();
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(project, release)
                            .analyzeFiles(
                                    List.of(file),
                                    context -> {
                                        contexts.add(context);
                                        extractor.extract(context, facts::add);
                                    })
                            .complete());
            var unit = contexts.getFirst().units().getFirst();
            var result = facts.getFirst().facts();
            var selected =
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.sourceId.equals("Box#run()"))
                            .map(edge -> edge.targetId)
                            .collect(java.util.stream.Collectors.toSet());
            assertEquals(
                    Set.of("Box#unknownReturn()", "Box#accept(java.lang.Object)", "Box#known(int)"),
                    selected,
                    "JDK " + release + ": " + contexts.getFirst().diagnostics());
            assertTrue(
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.sourceId.equals("Box#run()"))
                            .allMatch(
                                    edge ->
                                            "EXTRACTED".equals(edge.confidence)
                                                    && edge.metadata.contains(
                                                            "\"evidenceComplete\":false")));
            assertEquals(2, result.unresolvedCallSites.size());
            assertTrue(
                    result.unresolvedCallSites.stream()
                            .allMatch(site -> site.syntaxTarget().equals("absent")));
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            "READS".equals(edge.relation)
                                                    && "Box#missing".equals(edge.targetId)));
            assertTrue(
                    unit.bindings().values().stream()
                            .anyMatch(
                                    binding ->
                                            binding.kind().equals("METHOD")
                                                    && binding.name().equals("unknownReturn")
                                                    && binding.declarationResolved()
                                                    && !binding.evidenceComplete()));
        }
    }

    @Test
    void ambiguousInaccessibleAndInapplicableSelectionsNeverBecomeConfirmedCalls(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    class Target {
                        private static int hiddenField;
                        private static void hidden() {}
                        static void choose(String value) {}
                        static void choose(Integer value) {}
                        static void only(int value) {}
                        void instance() {}
                    }
                    class Use {
                        void run() {
                            Target.hidden();
                            Target.choose(null);
                            Target.only("wrong");
                            Target.instance();
                            int value = Target.hiddenField;
                        }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(project, release)
                            .analyzeFiles(
                                    List.of(file),
                                    context -> extractor.extract(context, facts::add))
                            .complete());
            var result = facts.getFirst().facts();
            assertEquals(4, result.unresolvedCallSites.size(), "JDK " + release);
            assertTrue(
                    result.edges.stream()
                            .noneMatch(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.sourceId.equals("Use#run()")),
                    "JDK " + release);
            assertTrue(
                    result.edges.stream()
                            .noneMatch(
                                    edge ->
                                            "READS".equals(edge.relation)
                                                    && "Target#hiddenField".equals(edge.targetId)));
        }
    }

    @Test
    void unresolvedSitesKeepReceiverAndControlEvidence(@TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Example.java"),
                            """
                    class Example {
                        void run(boolean flag, Example receiver) {
                            if (flag) { receiver.absent(); }
                        }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            backend(project, release)
                    .analyzeFiles(List.of(file), context -> extractor.extract(context, facts::add));
            var site = facts.getFirst().facts().unresolvedCallSites.getFirst();
            assertEquals("Example", site.receiverStaticType());
            assertEquals("if-then@L3", site.context());
            assertEquals("receiver.absent", site.syntaxTarget());
        }
    }

    @Test
    void missingParametersRecoverOnlyUniqueCompatibleSourceDeclarations(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("partial" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    class Use {
                        Missing field;
                        void helper(Missing value) {}
                        void different(Other value) {}
                        void choose(Missing value) {}
                        void choose(Other value) {}
                        void known(Missing value, int number) {}
                        void run(Missing value) {
                            helper(value);
                            different(value);
                            choose(value);
                            choose(null);
                            helper(null);
                            known(value, "wrong");
                            Template.accept(field, new Callback() {
                                public void done(Missing value) { helper(value); }
                            });
                        }
                    }
                    interface Callback { void done(Missing value); }
                    class Template { static void accept(Missing value, Callback callback) {} }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            backend(project, release)
                    .analyzeFiles(
                            List.of(source), context -> extractor.extract(context, facts::add));
            var calls =
                    facts.getFirst().facts().edges.stream()
                            .filter(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.targetId != null
                                                    && (edge.targetId.equals("Use#helper(Missing)")
                                                            || edge.targetId.startsWith(
                                                                    "Template#accept(")))
                            .toList();
            assertEquals(3, calls.size(), "JDK " + release);
            for (var call : calls) {
                assertEquals("INFERRED", call.confidence);
                assertEquals("source_fallback", call.resolution);
                assertTrue(call.metadata.contains("\"bindingResolved\":false"));
            }
            assertTrue(
                    facts.getFirst().facts().edges.stream()
                            .noneMatch(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.targetId != null
                                                    && (edge.targetId.startsWith("Use#different(")
                                                            || edge.targetId.startsWith(
                                                                    "Use#choose(")
                                                            || edge.targetId.startsWith(
                                                                    "Use#known("))),
                    "JDK " + release);
        }
    }

    @Test
    void missingImportsWithTheSameShortNameCannotMatchAcrossSourceUnits(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("imports" + release));
            Path caller =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    package p;
                    import missing.first.Alpha;
                    class Use { void run(Alpha value) { Target.same(value); Target.different(value); } }
                    """);
            Path same =
                    Files.writeString(
                            project.resolve("Target.java"),
                            """
                    package p;
                    class Target {
                        static void same(missing.first.Alpha value) {}
                        static void different(missing.second.Alpha value) {}
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            backend(project, release)
                    .analyzeFiles(
                            List.of(caller, same),
                            context -> extractor.extract(context, facts::add));
            var calls =
                    facts.stream()
                            .flatMap(unit -> unit.facts().edges.stream())
                            .filter(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.sourceId.startsWith("p.Use#run("))
                            .map(edge -> edge.targetId)
                            .toList();
            assertEquals(List.of("p.Target#same(missing.first.Alpha)"), calls, "JDK " + release);
        }
    }

    @Test
    void missingParentCannotHideAnOverloadDuringIncompleteParameterRecovery(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("parents" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    class Use extends MissingBase {
                        void helper(Missing value) {}
                        void run(Missing value) { helper(value); }
                    }
                    interface Api extends MissingParent { void helper(Missing value); }
                    class Caller { void run(Api receiver, Missing value) { receiver.helper(value); } }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            backend(project, release)
                    .analyzeFiles(
                            List.of(source), context -> extractor.extract(context, facts::add));
            assertTrue(
                    facts.getFirst().facts().edges.stream()
                            .noneMatch(
                                    edge ->
                                            "CALLS".equals(edge.relation)
                                                    && edge.targetId != null
                                                    && (edge.targetId.startsWith("Use#helper(")
                                                            || edge.targetId.startsWith(
                                                                    "Api#helper("))),
                    "JDK " + release);
            assertEquals(
                    2,
                    facts.getFirst().facts().unresolvedCallSites.stream()
                            .filter(site -> site.syntaxTarget().endsWith("helper"))
                            .count());
        }
    }

    private static JavacBackend backend(Path project, int release) throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        return new JavacBackend(
                project,
                JavacToolchain.resolve(config.javacOptions(), release, null),
                List.of(),
                List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                config.javacOptions());
    }
}
