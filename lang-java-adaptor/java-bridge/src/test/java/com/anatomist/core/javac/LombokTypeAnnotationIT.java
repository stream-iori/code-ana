package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.asmsolver.*;
import com.anatomist.core.java.*;
import com.anatomist.core.process.ProcessRunner;
import com.anatomist.framework.JavaModelExtensions;
import com.anatomist.framework.lombok.LombokJavaModelExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

class LombokTypeAnnotationIT {
    @Test
    void nonNullAndDeprecatedCopyPoliciesMatchActualProcessors(@TempDir Path directory)
            throws Exception {
        for (String selected : List.of("8:1.18.6", "8:1.18.38", "17:1.18.38", "25:1.18.38")) {
            int release = Integer.parseInt(selected.split(":")[0]);
            String version = selected.split(":")[1];
            Path lombok =
                    Path.of(
                            System.getProperty("user.home"),
                            ".m2/repository/org/projectlombok/lombok/"
                                    + version
                                    + "/lombok-"
                                    + version
                                    + ".jar");
            Path project =
                    Files.createDirectory(directory.resolve("copy" + release + "-" + version));
            Files.writeString(
                    project.resolve("lombok.config"),
                    "config.stopBubbling = true\nlombok.addLombokGeneratedAnnotation = false\n");
            Path source =
                    Files.writeString(
                            project.resolve("Copied.java"),
                            """
                    @lombok.Data class Copied {
                        @lombok.NonNull String name;
                        @Deprecated String old;
                    }
                    @lombok.Builder class Built {
                        @lombok.NonNull String name;
                        @Deprecated String old;
                    }
                    """);
            Path home = Objects.requireNonNull(JavacBackendIT.home(release));
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            project,
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(lombok),
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                            config.javacOptions());
            backend.setDeclarationOverlay(
                    new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, false))));
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
            var result = JavaSignatureTypes.project(contexts.getFirst().units(), release);
            Path output =
                    Files.createDirectory(
                            directory.resolve("copy-actual" + release + "-" + version));
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            home.resolve("bin/javac").toString(),
                                            "-cp",
                                            lombok.toString(),
                                            "-processorpath",
                                            lombok.toString(),
                                            "-d",
                                            output.toString(),
                                            source.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(
                    0,
                    compilation.exitCode(),
                    new String(compilation.requireValue(), StandardCharsets.UTF_8));
            var actualTypes = new ArrayList<JavaBinaryType>();
            for (String name : List.of("Copied", "Built", "Built$BuiltBuilder"))
                actualTypes.add(
                        JavaBinaryReader.read(Files.readAllBytes(output.resolve(name + ".class"))));
            var projection =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            result,
                            result + " actual=" + actualTypes);
            for (var actual : actualTypes) {
                var modeled =
                        projection.types().stream()
                                .filter(type -> type.name().equals(actual.name()))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        methodAnnotations(actual),
                        methodAnnotations(modeled),
                        actual.name() + " JDK " + release);
                assertEquals(
                        actual.fields(),
                        modeled.fields(),
                        actual.name() + " fields JDK " + release);
                assertEquals(
                        actual.methods().stream()
                                .map(
                                        method ->
                                                method.name()
                                                        + method.descriptor()
                                                        + "|"
                                                        + method.annotations()
                                                        + "|"
                                                        + method.access())
                                .sorted()
                                .toList(),
                        modeled.methods().stream()
                                .map(
                                        method ->
                                                method.name()
                                                        + method.descriptor()
                                                        + "|"
                                                        + method.annotations()
                                                        + "|"
                                                        + method.access())
                                .sorted()
                                .toList(),
                        actual.name() + " declarations JDK " + release);
            }
        }
    }

    @Test
    void generatedTypesCopyExplicitUseAnnotationsAndOmitNoncopyableModifierAnnotations(
            @TempDir Path directory) throws Exception {
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        assertTrue(Files.isRegularFile(lombok));
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Box.java"),
                            """
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
                    @interface Constraint {}
                    @lombok.Getter @lombok.Setter @lombok.AllArgsConstructor
                    class Box {
                        @Constraint private String plain;
                        private String @Constraint [] array;
                        @Constraint private String[] modifiersArray;
                        private java.util.List<@Constraint String> nested;
                    }
                    """);
            Path home = Objects.requireNonNull(JavacBackendIT.home(release));
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            project,
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(lombok),
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                            config.javacOptions());
            backend.setDeclarationOverlay(
                    new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, false))));
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
            var result = JavaSignatureTypes.project(contexts.getFirst().units(), release);
            Path output = Files.createDirectory(directory.resolve("actual" + release));
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            home.resolve("bin/javac").toString(),
                                            "-cp",
                                            lombok.toString(),
                                            "-processorpath",
                                            lombok.toString(),
                                            "-d",
                                            output.toString(),
                                            source.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(
                    0,
                    compilation.exitCode(),
                    new String(compilation.requireValue(), StandardCharsets.UTF_8));
            var actual = JavaBinaryReader.read(Files.readAllBytes(output.resolve("Box.class")));
            var projection =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            result,
                            result + " actual=" + methodAnnotations(actual));
            var modeled =
                    projection.types().stream()
                            .filter(type -> type.name().equals("Box"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(methodAnnotations(actual), methodAnnotations(modeled), "JDK " + release);
            assertEquals(actual.fields(), modeled.fields(), "field annotations JDK " + release);
        }
    }

    private static List<String> methodAnnotations(JavaBinaryType type) {
        return type.methods().stream()
                .filter(method -> !method.name().equals("<clinit>"))
                .map(
                        method ->
                                method.name()
                                        + method.descriptor()
                                        + "|"
                                        + method.typeAnnotations()
                                        + "|"
                                        + method.parameterAnnotations())
                .sorted()
                .toList();
    }
}
