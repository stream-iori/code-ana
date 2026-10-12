package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;

class JavacCatalogueCodecIT {
    @Test
    void persistsExactDeclarationsHeadersAndExtensionEvidenceAcrossJdks(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Declarations.java"),
                            """
                    package sample;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME) @interface Mark {
                        byte b() default 1; short s() default 2; char c() default 'x';
                        float f() default 3.0f; double d() default 4.0; long l() default 5L;
                        boolean flag() default true; String text() default "hello";
                        Class<?> type() default String[].class;
                        RetentionPolicy retention() default RetentionPolicy.CLASS;
                        Child child() default @Child(value=7);
                        Child[] children() default {@Child(value=8)};
                    }
                    @interface Child { int value(); }
                    /** @deprecated old type */
                    @Mark class Box<T extends Number & Comparable<T>> {
                        static final float NAN = 0.0f / 0.0f;
                        /** @deprecated old field */ T value;
                        <X extends CharSequence> X echo(X value) { return value; }
                    }
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
            var raw = contexts.getFirst().units().getFirst();
            var compact = JavaDeclarationView.of(raw);
            var deprecated =
                    compact.declarationInventory().stream()
                            .filter(declaration -> declaration.canonicalName().equals("sample.Box"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(deprecated.deprecated());
            assertTrue(
                    deprecated.members().stream()
                            .filter(member -> member.symbol().name().equals("value"))
                            .allMatch(JavaDeclaration::deprecated));
            var extensions =
                    new JavaExtensionData(
                            Map.of(
                                    "sample.Box#modeled()",
                                    new JavaGeneratedOrigin(
                                            "test", "model", "ast", "@Mark", "INFERRED", false)),
                            Map.of(
                                    "sample.Box",
                                    Map.of(
                                            "nested",
                                            Arrays.asList(
                                                    "text", null, true, 4, 5L, 6.0f, 7.0d, (byte) 8,
                                                    (short) 9),
                                            "map",
                                            Map.of("enabled", false))),
                            List.of(
                                    new IndexDiagnostic(
                                            "warning",
                                            "TEST",
                                            "OVERLAY",
                                            source.toString(),
                                            ".",
                                            "MAIN",
                                            "sample.Box",
                                            1,
                                            "sample",
                                            "java",
                                            "test",
                                            null,
                                            "TEST",
                                            "{}")),
                            Map.of("test", 2L));
            var evidence =
                    new JavaUnit(
                            compact.path(),
                            compact.tree(),
                            com.anatomist.core.java.JavaProcessingState.NONE,
                            Map.of(),
                            compact.types(),
                            true,
                            compact.declarationInventory(),
                            compact.sourceHash(),
                            extensions);
            Path catalogue = project.resolve("declarations.bin");
            JavacCatalogueCodec.write(catalogue, List.of(evidence));
            var restored = JavacCatalogueCodec.read(catalogue);
            assertEquals(List.of(evidence), restored);
            assertEquals(
                    JavaContract.fingerprint(raw), JavaContract.fingerprint(restored.getFirst()));
            assertTrue(restored.getFirst().bindings().isEmpty());
            assertNull(restored.getFirst().tree().fields().get("getTypeDecls"));
            assertThrows(
                    IOException.class,
                    () -> JavacCatalogueCodec.write(project.resolve("raw.bin"), List.of(raw)));
            Files.write(catalogue, new byte[] {1}, StandardOpenOption.APPEND);
            assertThrows(IOException.class, () -> JavacCatalogueCodec.read(catalogue));
        }
    }

    @Test
    void rejectsCorruptCountsTruncationAndOversizedFilesBeforeDecoding(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("invalid.bin");
        Files.write(file, new byte[] {0, 0, 0, 0});
        assertThrows(IOException.class, () -> JavacCatalogueCodec.read(file));
        try (var output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(0x41434431); // Previous catalogue format has no deprecated flag.
            output.writeInt(0);
        }
        assertThrows(IOException.class, () -> JavacCatalogueCodec.read(file));
        try (var output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(JavacCatalogueCodec.MAGIC);
            output.writeInt(Integer.MAX_VALUE);
        }
        assertThrows(IOException.class, () -> JavacCatalogueCodec.read(file));
        try (var output = new DataOutputStream(Files.newOutputStream(file))) {
            output.writeInt(JavacCatalogueCodec.MAGIC);
            output.writeInt(1);
            output.writeInt(100);
        }
        assertThrows(IOException.class, () -> JavacCatalogueCodec.read(file));
        try (var output = new RandomAccessFile(file.toFile(), "rw")) {
            output.setLength(JavacCatalogueCodec.MAX_BYTES + 1);
        }
        assertThrows(IOException.class, () -> JavacCatalogueCodec.read(file));
    }
}
