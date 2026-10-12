package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaBinaryType;
import com.anatomist.core.java.JavaBinaryType.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.tools.ToolProvider;

class JavaBinaryModelIT {
    @Test
    void preservesGenericConstantsAnnotationsDefaultsAndParameterEvidence(@TempDir Path directory)
            throws Exception {
        Path source = directory.resolve("Example.java");
        Files.writeString(
                source,
                """
                import java.lang.annotation.*;
                import java.util.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE_USE, ElementType.TYPE, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
                @interface Mark {
                    String text() default "fallback";
                    Class<?> type() default String[].class;
                    RetentionPolicy mode() default RetentionPolicy.CLASS;
                    int[] numbers() default {2, 3};
                    Nested nested() default @Nested(flag=true);
                    Nested[] children() default {@Nested(flag=false)};
                }
                @interface Nested { boolean flag(); }
                @Mark(text="type", numbers={5, 7})
                class Example<T extends Number & Comparable<T>> {
                    static final int LIMIT = 42;
                    List<@Mark T> items;
                    @Mark T get(@Mark T value) throws java.io.IOException { return value; }
                    class Inner {}
                    Object local() { class Local {} return new Local(); }
                }
                record Pair<T>(@Mark T value) {}
                sealed interface Root permits Leaf {}
                final class Leaf implements Root {}
                """);
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "-proc:none",
                                "-parameters",
                                "-d",
                                directory.toString(),
                                source.toString()));
        try (var classes = Files.list(directory)) {
            for (Path file : classes.filter(file -> file.toString().endsWith(".class")).toList()) {
                var original = JavaBinaryReader.read(Files.readAllBytes(file));
                byte[] signature = JavaBinaryWriter.signature(original);
                assertEquals(original, JavaBinaryReader.read(signature), file.toString());
                org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(signature);
                reader.accept(
                        new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public org.objectweb.asm.MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String generic,
                                    String[] exceptions) {
                                return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitCode() {
                                        fail(
                                                "signature archives cannot contain executable Code: "
                                                        + file);
                                    }
                                };
                            }
                        },
                        0);
            }
        }
        JavaBinaryType type = read(directory, "Example");
        assertTrue(type.signature().contains("Ljava/lang/Comparable<TT;>;"));
        assertEquals("java.lang.Object", type.superclass());
        assertEquals(
                new Scalar(42),
                type.fields().stream()
                        .filter(f -> f.name().equals("LIMIT"))
                        .findFirst()
                        .orElseThrow()
                        .constant());
        var items =
                type.fields().stream()
                        .filter(f -> f.name().equals("items"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Ljava/util/List<TT;>;", items.signature());
        assertEquals("0;", items.typeAnnotations().getFirst().path());
        assertEquals("LMark;", items.typeAnnotations().getFirst().annotation().descriptor());
        var get =
                type.methods().stream()
                        .filter(m -> m.name().equals("get"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("(TT;)TT;", get.signature());
        assertEquals(List.of("java.io.IOException"), get.exceptions());
        assertEquals("value", get.parameters().getFirst().name());
        assertEquals(1, get.visibleParameterCount());
        assertEquals(0, get.parameterAnnotations().getFirst().parameter());
        assertFalse(get.typeAnnotations().isEmpty());
        assertEquals(new Scalar("type"), type.annotations().getFirst().values().get("text"));
        assertTrue(type.annotations().getFirst().visible());
        assertEquals(
                new ArrayValue(List.of(new Scalar(5), new Scalar(7))),
                type.annotations().getFirst().values().get("numbers"));
        assertTrue(
                type.innerTypes().stream()
                        .anyMatch(
                                i ->
                                        "Example".equals(i.outerName())
                                                && "Inner".equals(i.simpleName())));
        assertTrue(type.nestMembers().contains("Example$Inner"));
        assertEquals("Example", read(directory, "Example$Inner").nestHost());
        assertEquals("local", read(directory, "Example$1Local").enclosingMethod().name());
        var defaults =
                read(directory, "Mark").methods().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        Method::name, Method::annotationDefault));
        assertEquals(new ClassLiteral("[Ljava/lang/String;"), defaults.get("type"));
        assertEquals(
                new EnumValue("Ljava/lang/annotation/RetentionPolicy;", "CLASS"),
                defaults.get("mode"));
        assertEquals(
                new ArrayValue(List.of(new Scalar(2), new Scalar(3))), defaults.get("numbers"));
        assertEquals(
                new Scalar(true),
                ((NestedAnnotation) defaults.get("nested")).annotation().values().get("flag"));
        assertEquals(1, ((ArrayValue) defaults.get("children")).values().size());
        var record = read(directory, "Pair").recordComponents().getFirst();
        assertEquals("TT;", record.signature());
        assertEquals("LMark;", record.annotations().getFirst().descriptor());
        assertFalse(record.typeAnnotations().isEmpty());
        assertEquals(List.of("Leaf"), read(directory, "Root").permittedSubclasses());
        assertEquals(List.of("Root"), read(directory, "Leaf").interfaces());
        assertThrows(UnsupportedOperationException.class, () -> type.fields().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> type.annotations().getFirst().values().clear());
        assertThrows(IllegalArgumentException.class, () -> new Scalar(new Object()));
    }

    @Test
    void repositoryBoundsCachePreservesExactNamesAndClosesSource() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var entries =
                Map.of(
                        "x.Outer$Inner",
                        minimal("x.Outer$Inner"),
                        "x.Outer.Inner",
                        minimal("x.Outer.Inner"),
                        "x.Second",
                        minimal("x.Second"));
        ClassFileSource source =
                new ClassFileSource() {
                    @Override
                    public Optional<byte[]> find(String name) {
                        reads.incrementAndGet();
                        return Optional.ofNullable(entries.get(name));
                    }

                    @Override
                    public Set<String> knownClasses() {
                        return entries.keySet();
                    }

                    @Override
                    public void close() {
                        closes.incrementAndGet();
                    }
                };
        var repository = new JavaBinaryRepository(source, 4096);
        var exact = repository.find("x.Outer.Inner").orElseThrow();
        assertEquals("x.Outer.Inner", exact.name());
        assertSame(exact, repository.find(exact.name()).orElseThrow());
        assertEquals(1, reads.get());
        repository.find("x.Second").orElseThrow();
        assertNotSame(exact, repository.find(exact.name()).orElseThrow());
        assertEquals(3, reads.get());
        assertTrue(repository.find("absent").isEmpty());
        assertTrue(repository.find(null).isEmpty());
        assertTrue(repository.find(" ").isEmpty());
        repository.close();
        repository.close();
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> repository.find(exact.name()));
        assertThrows(IllegalArgumentException.class, () -> new JavaBinaryRepository(source, -1));
        try (var nested =
                new JavaBinaryRepository(
                        new InMemoryClassFileSource(
                                Map.of("x.Outer$Inner", minimal("x.Outer$Inner"))),
                        0)) {
            assertEquals("x.Outer$Inner", nested.find("x.Outer.Inner").orElseThrow().name());
            assertNotSame(
                    nested.find("x.Outer.Inner").orElseThrow(),
                    nested.find("x.Outer.Inner").orElseThrow());
        }
        try (var mismatched =
                new JavaBinaryRepository(
                        new InMemoryClassFileSource(Map.of("Wrong", minimal("Actual"))), 0)) {
            assertThrows(IllegalArgumentException.class, () -> mismatched.find("Wrong"));
        }
    }

    @Test
    void targetJdkSourceDoesNotResolveFromHostAndRequiresMatchingRelease(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path home = directory.resolve("jdk" + release);
            Files.createDirectories(home);
            Files.writeString(
                    home.resolve("release"),
                    "JAVA_VERSION=\"" + (release == 8 ? "1.8.0_492" : release + ".0.1") + "\"\n");
            Path archive = home.resolve(release == 8 ? "jre/lib/rt.jar" : "jmods/java.base.jmod");
            Files.createDirectories(archive.getParent());
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
                String prefix = release == 8 ? "" : "classes/";
                zip.putNextEntry(new java.util.zip.ZipEntry(prefix + "test/Target.class"));
                zip.write(minimal("test.Target"));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry(prefix + "module-info.class"));
                zip.write(new byte[0]);
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("irrelevant.txt"));
                zip.closeEntry();
            }
            JdkClassFileSource source = new JdkClassFileSource(home, release);
            assertEquals(Set.of("test.Target"), source.knownClasses());
            assertTrue(source.find("java.lang.String").isEmpty());
            assertEquals(
                    "test.Target",
                    JavaBinaryReader.read(source.find("test.Target").orElseThrow()).name());
            source.close();
            assertThrows(IllegalStateException.class, () -> source.find("test.Target"));
            assertThrows(IllegalStateException.class, source::knownClasses);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JdkClassFileSource(home, release + 1));
            Files.delete(archive);
            assertThrows(
                    java.io.UncheckedIOException.class,
                    () -> new JdkClassFileSource(home, release));
        }
    }

    @Test
    void readsActualSelectedJdkWithoutReflectingClasses() {
        try (var repository =
                new JavaBinaryRepository(
                        new JdkClassFileSource(Path.of(System.getProperty("java.home")), 25),
                        1024 * 1024)) {
            var entry = repository.find("java.util.Map.Entry").orElseThrow();
            assertEquals("java.util.Map$Entry", entry.name());
            assertTrue(
                    entry.methods().stream()
                            .anyMatch(
                                    m ->
                                            m.name().equals("getKey")
                                                    && "()TK;".equals(m.signature())));
            assertEquals(
                    "java.lang.Object",
                    repository.find("java.lang.String").orElseThrow().superclass());
        }
    }

    private static JavaBinaryType read(Path directory, String name) throws Exception {
        return JavaBinaryReader.read(Files.readAllBytes(directory.resolve(name + ".class")));
    }

    private static byte[] minimal(String name) {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                name.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
