package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.process.ProcessRunner;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.JavaModelExtensions;
import com.anatomist.framework.lombok.LombokJavaModelExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

class JavaModeledOverridesIT {
    private static final Path LOMBOK =
            Path.of(
                    System.getProperty("user.home"),
                    ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");

    @Test
    void generatedObjectAndGenericSourceAndBinaryOverridesMatchRealProcessors(
            @TempDir Path directory) throws Exception {
        assertTrue(Files.isRegularFile(LOMBOK));
        for (int release : List.of(8, 17, 25)) {
            Path home = Objects.requireNonNull(JavacBackendIT.home(release));
            Path library = Files.createDirectory(directory.resolve("library" + release));
            Path classes = Files.createDirectory(directory.resolve("classes" + release));
            Path parent =
                    Files.writeString(
                            library.resolve("Parent.java"),
                            """
                    package lib;
                    public class Parent<T> {
                        public T getValue() { return null; }
                        public void setValue(T value) {}
                    }
                    """);
            var compiled =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            home.resolve("bin/javac").toString(),
                                            "-proc:none",
                                            "-d",
                                            classes.toString(),
                                            parent.toString()),
                                    library,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(0, compiled.exitCode(), compiled.diagnostics().stderrTail());
            Path project = Files.createDirectory(directory.resolve("project" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Data class Entity { int count; }
                    @lombok.Data @lombok.EqualsAndHashCode(callSuper=true) class Extended extends Entity { String name; }
                    @lombok.Getter @lombok.Setter class SourceParent<T> { T item; }
                    @lombok.Getter @lombok.Setter class SourceChild extends SourceParent<String> { String item; }
                    @lombok.Getter @lombok.Setter class BinaryChild extends lib.Parent<String> { String value; }
                    class GenericParent { public void setNames(java.util.List<String> values) {} }
                    @lombok.Setter class RawChild extends GenericParent { java.util.List names; }
                    """);
            var classpath = List.of(LOMBOK, classes);
            var modeled = extract(project, source, release, classpath, false);
            var actual = extract(project, source, release, classpath, true);
            var expected = overrideRows(actual);
            assertTrue(
                    expected.contains(
                            "p.RawChild#setNames(java.util.List)->p.GenericParent#setNames(java.util.List)"));
            assertEquals(expected, overrideRows(modeled), "JDK " + release);
            assertTrue(
                    expected.contains(
                            "p.Entity#equals(java.lang.Object)->java.lang.Object#equals(java.lang.Object)"));
            assertTrue(expected.contains("p.Entity#hashCode()->java.lang.Object#hashCode()"));
            assertTrue(expected.contains("p.Entity#toString()->java.lang.Object#toString()"));
            assertTrue(
                    expected.contains(
                            "p.SourceChild#setItem(java.lang.String)->p.SourceParent#setItem(java.lang.Object)"));
            assertTrue(
                    expected.contains(
                            "p.BinaryChild#setValue(java.lang.String)->lib.Parent#setValue(java.lang.Object)"));
            assertTrue(
                    modeled.edges.stream()
                            .filter(edge -> edge.relation.equals("OVERRIDES"))
                            .allMatch(
                                    edge ->
                                            edge.confidence.equals("INFERRED")
                                                    && edge.resolution.equals("source_fallback")
                                                    && edge.metadata.contains(
                                                            "\"modeledInput\":true")
                                                    && edge.metadata.contains(
                                                            "\"bindingResolved\":false")));
        }
    }

    @Test
    void finalStaticPrimitiveAndGenericNameClashesDoNotManufactureOverrides(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("negative" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Invalid.java"),
                            """
                    class FinalParent { public final String toString() { return "parent"; } }
                    @lombok.ToString class FinalChild extends FinalParent { String text; }
                    class StaticParent { public static String getText() { return "parent"; } }
                    @lombok.Getter class StaticChild extends StaticParent { String text; }
                    class PrimitiveParent { public int getCount() { return 0; } }
                    @lombok.Getter class PrimitiveChild extends PrimitiveParent { long count; }
                    class GenericAncestor { public void setNames(java.util.List<String> values) {} }
                    class GenericParent extends GenericAncestor { public void setNames(java.util.List<Integer> values) {} }
                    @lombok.Setter class GenericChild extends GenericParent { java.util.List<String> names; }
                    """);
            var modeled = extract(project, source, release, List.of(LOMBOK), false);
            assertTrue(
                    modeled.edges.stream()
                            .filter(edge -> edge.relation.equals("OVERRIDES"))
                            .noneMatch(edge -> edge.sourceId.contains("Child#")),
                    "JDK " + release + ": " + overrideRows(modeled));
            Path output = Files.createDirectory(directory.resolve("negative-classes" + release));
            var compiled =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(JavacBackendIT.home(release))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-cp",
                                            LOMBOK.toString(),
                                            "-processorpath",
                                            LOMBOK.toString(),
                                            "-d",
                                            output.toString(),
                                            source.toString()),
                                    project,
                                    Duration.ofSeconds(45)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertNotEquals(0, compiled.exitCode());
            assertTrue(compiled.diagnostics().stderrTail().contains("FinalChild"));
            assertTrue(compiled.diagnostics().stderrTail().contains("StaticChild"));
            assertTrue(compiled.diagnostics().stderrTail().contains("PrimitiveChild"));
            assertTrue(compiled.diagnostics().stderrTail().contains("GenericChild"));
        }
    }

    private static Set<String> overrideRows(com.anatomist.model.ExtractionResult result) {
        var rows = new TreeSet<String>();
        result.edges.stream()
                .filter(edge -> edge.relation.equals("OVERRIDES"))
                .forEach(
                        edge ->
                                rows.add(
                                        edge.sourceId
                                                + "->"
                                                + (edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)));
        return rows;
    }

    private static com.anatomist.model.ExtractionResult extract(
            Path project, Path source, int release, List<Path> classpath, boolean compile)
            throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        config.setLombokMode("ast");
        config.setLombokMode(compile ? "compile" : "ast");
        var defaults = config.javacOptions();
        var options =
                new JavacOptions(
                        defaults.javaHome(),
                        true,
                        true,
                        compile,
                        false,
                        project.resolve(compile ? "compile-cache" : "model-cache"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(options, release, null),
                        classpath,
                        List.of(project),
                        options);
        backend.setDeclarationOverlay(
                new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, false))));
        var facts = new com.anatomist.model.ExtractionResult();
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                analysis -> {
                                    if (compile) {
                                        assertTrue(analysis.analysisFailure().isEmpty());
                                        assertTrue(
                                                analysis.units().stream()
                                                        .allMatch(
                                                                unit ->
                                                                        unit
                                                                                .hasProcessorEvidence()));
                                        assertTrue(
                                                analysis.diagnostics().stream()
                                                        .noneMatch(
                                                                diagnostic ->
                                                                        diagnostic
                                                                                .kind()
                                                                                .equals("ERROR")),
                                                analysis.diagnostics().toString());
                                    }
                                    try (var binaries = backend.openBinaryRepository(analysis)) {
                                        new JavaModelExtractor(project, binaries)
                                                .extract(
                                                        analysis,
                                                        unit -> facts.addFacts(unit.facts()));
                                    }
                                })
                        .complete());
        return facts;
    }
}
