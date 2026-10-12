package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaContractIT {
    @Test
    void descriptorFilesHaveContractsWithoutClassDeclarations(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            Path file = project.resolve("package-info.java");
            String annotated = fingerprint(backend, file, "@Deprecated package p;");
            assertEquals(
                    annotated, fingerprint(backend, file, "// moved\n@Deprecated\npackage p;\n"));
            assertNotEquals(annotated, fingerprint(backend, file, "package p;"));
            assertNotEquals(annotated, fingerprint(backend, file, "@Deprecated package q;"));
            if (release > 8) {
                Path module = project.resolve("module-info.java");
                String initial =
                        fingerprint(backend, module, "module sample { requires java.logging; }");
                assertEquals(
                        initial,
                        fingerprint(
                                backend,
                                module,
                                "// module header\nmodule sample {\n requires java.logging;\n }"));
                assertNotEquals(
                        initial,
                        fingerprint(backend, module, "module sample { requires java.sql; }"));
                assertNotEquals(
                        initial,
                        fingerprint(
                                backend,
                                module,
                                "module sample { requires transitive java.logging; }"));
            }
        }
    }

    @Test
    void compilerContractsIgnoreBodiesAndLayoutButTrackAbiConstantsAndAnnotations(
            @TempDir Path directory) throws Exception {
        String source =
                """
                import java.lang.annotation.*;
                @Target({ElementType.TYPE_USE, ElementType.TYPE, ElementType.METHOD})
                @Retention(RetentionPolicy.SOURCE)
                @interface Mark { int value() default 3; String name() default "x"; }
                @Mark(value=1, name="one")
                class Box<T extends Number & Comparable<T>> {
                    public static final int LIMIT = 4;
                    private int counter = 1;
                    @Mark(2) public final T pick(T value) throws Exception { return value; }
                }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = project.resolve("Box.java");
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            String baseline = fingerprint(backend, file, source);
            assertEquals(
                    baseline,
                    fingerprint(
                            backend,
                            file,
                            source.replace("return value;", "int noise = 123; return value;")
                                            .replace("counter = 1", "counter = 7")
                                            .replace("public final", "final public")
                                            .replace(
                                                    "value=1, name=\"one\"",
                                                    "name=\"one\", value=1")
                                    + "\n// layout and unrelated local syntax\n"),
                    "JDK " + release);
            for (String changed :
                    List.of(
                            source.replace("LIMIT = 4", "LIMIT = 5"),
                            source.replace("Number & Comparable<T>", "Number"),
                            source.replace("T pick(T value)", "T pick(T value, int extra)"),
                            source.replace("@Mark(2)", "@Mark(4)"),
                            source.replace("default 3", "default 4"),
                            source.replace(
                                    "@Mark(value=1, name=\"one\")",
                                    "/** @deprecated use another type */\n@Mark(value=1, name=\"one\")"),
                            source.replace("throws Exception", "throws java.io.IOException"))) {
                assertNotEquals(baseline, fingerprint(backend, file, changed), changed);
            }
            Files.writeString(file, source);
            assertEquals(baseline, fingerprint(backend, file, source));
            tested++;
        }
        assertTrue(tested > 0);
    }

    private static String fingerprint(JavacBackend backend, Path file, String source)
            throws Exception {
        Files.writeString(file, source);
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
        JavaUnit unit =
                contexts.getFirst().units().stream()
                        .filter(value -> value.path().equals(file))
                        .findFirst()
                        .orElseThrow();
        return JavaContract.fingerprint(unit);
    }
}
