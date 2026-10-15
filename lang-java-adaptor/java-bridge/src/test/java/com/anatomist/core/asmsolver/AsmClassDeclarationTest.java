package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.JavaBinaryType;
import com.anatomist.core.java.JavaType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

class AsmClassDeclarationTest {
    @Test
    void namedMethodLookupPreservesOrderAcrossSignatureRoundTrip() {
        var writer = header("com.x.Api", Opcodes.ACC_PUBLIC, "java.lang.Object");
        writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "(I)V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "other", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/String;)V", null, null).visitEnd();
        var original = read(writer);
        var restored = JavaBinaryReader.read(JavaBinaryWriter.signature(original));
        assertEquals(original, restored);
        for (var type : List.of(original, restored)) {
            assertEquals(
                    List.of("(I)V", "(Ljava/lang/String;)V"),
                    type.methods().stream()
                            .filter(method -> method.name().equals("f"))
                            .map(JavaBinaryType.Method::descriptor)
                            .toList());
            assertTrue(
                    type.methods().stream().noneMatch(method -> method.name().equals("missing")));
            assertThrows(UnsupportedOperationException.class, () -> type.methods().clear());
            assertEquals(3, type.methods().size());
        }
    }

    @Test
    void identityTopLevel() {
        assertEquals(
                "com.x.Foo",
                read(header("com.x.Foo", Opcodes.ACC_PUBLIC, "java.lang.Object")).name());
    }

    @Test
    void identityNestedClassPreservesBinaryName() {
        assertEquals(
                "com.x.Outer$Inner",
                read(header("com.x.Outer$Inner", Opcodes.ACC_PUBLIC, "java.lang.Object")).name());
    }

    @Test
    void identityDefaultPackage() {
        assertEquals("Foo", read(header("Foo", Opcodes.ACC_PUBLIC, "java.lang.Object")).name());
    }

    @Test
    void kindRegularClass() {
        var type = read(header("com.x.Foo", Opcodes.ACC_PUBLIC, "java.lang.Object"));
        assertEquals(
                0,
                type.access()
                        & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION));
    }

    @Test
    void kindInterface() {
        var type =
                read(
                        header(
                                "com.x.I",
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                                "java.lang.Object"));
        assertNotEquals(0, type.access() & Opcodes.ACC_INTERFACE);
        assertEquals(0, type.access() & (Opcodes.ACC_ENUM | Opcodes.ACC_ANNOTATION));
    }

    @Test
    void kindEnumKeepsConstants() {
        var writer = header("com.x.E", Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM, "java.lang.Enum");
        writer.visitField(
                        Opcodes.ACC_PUBLIC
                                | Opcodes.ACC_STATIC
                                | Opcodes.ACC_FINAL
                                | Opcodes.ACC_ENUM,
                        "A",
                        "Lcom/x/E;",
                        null,
                        null)
                .visitEnd();
        var type = read(writer);
        assertNotEquals(0, type.access() & Opcodes.ACC_ENUM);
        assertEquals(0, type.access() & Opcodes.ACC_INTERFACE);
        assertEquals(
                List.of("A"),
                type.fields().stream()
                        .filter(field -> (field.access() & Opcodes.ACC_ENUM) != 0)
                        .map(JavaBinaryType.Field::name)
                        .toList());
    }

    @Test
    void varargsFlagAndLastArrayParameterSurvive() {
        var writer = header("com.x.Api", Opcodes.ACC_PUBLIC, "java.lang.Object");
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_VARARGS, "join", "(I[I)V", null, null)
                .visitEnd();
        var type = read(writer);
        var method = type.methods().getFirst();
        assertNotEquals(0, method.access() & Opcodes.ACC_VARARGS);
        var shape = JavaBinaryTypes.method(type, method);
        var parameters = shape.types().get(shape.executable()).parameters();
        assertEquals(JavaType.Kind.INT, shape.types().get(parameters.getFirst()).kind());
        assertEquals(JavaType.Kind.ARRAY, shape.types().get(parameters.getLast()).kind());
    }

    @Test
    void kindAnnotation() {
        var type =
                read(
                        header(
                                "com.x.Mark",
                                Opcodes.ACC_PUBLIC
                                        | Opcodes.ACC_INTERFACE
                                        | Opcodes.ACC_ABSTRACT
                                        | Opcodes.ACC_ANNOTATION,
                                "java.lang.Object",
                                "java.lang.annotation.Annotation"));
        assertNotEquals(0, type.access() & Opcodes.ACC_ANNOTATION);
        assertNotEquals(0, type.access() & Opcodes.ACC_INTERFACE);
    }

    @Test
    void hierarchySuperclassResolvesInRepository() {
        var parent = read(header("com.x.Parent", Opcodes.ACC_PUBLIC, "java.lang.Object"));
        var child = read(header("com.x.Child", Opcodes.ACC_PUBLIC, "com.x.Parent"));
        try (var repository = repository(parent, child)) {
            assertEquals(
                    parent,
                    repository
                            .find(repository.find("com.x.Child").orElseThrow().superclass())
                            .orElseThrow());
        }
    }

    @Test
    void hierarchyInterfacesPreserveNamesAndOrder() {
        var type =
                read(
                        header(
                                "com.x.Impl",
                                Opcodes.ACC_PUBLIC,
                                "java.lang.Object",
                                "com.x.I1",
                                "com.x.I2"));
        var shape = JavaBinaryTypes.hierarchy(type);
        assertEquals(
                List.of("com.x.I1", "com.x.I2"),
                shape.interfaces().stream()
                        .map(reference -> shape.types().get(reference).name())
                        .toList());
    }

    @Test
    void hierarchyRootHasNoSuperclass() {
        var type = read(header("RootLike", Opcodes.ACC_PUBLIC, null));
        assertNull(type.superclass());
        assertEquals(-1, JavaBinaryTypes.hierarchy(type).superclass());
    }

    @Test
    void hierarchyContainsSuperclassAndInterfaces() {
        var type = read(header("com.x.C", Opcodes.ACC_PUBLIC, "com.x.Parent", "com.x.I"));
        var shape = JavaBinaryTypes.hierarchy(type);
        assertEquals("com.x.Parent", shape.types().get(shape.superclass()).name());
        assertEquals("com.x.I", shape.types().get(shape.interfaces().getFirst()).name());
    }

    @Test
    void compilerAcceptsSelfAssignment(@TempDir Path directory) throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Foo.java",
                        "class Foo { void run(Foo value) { Foo copy = value; } }");
        assertTrue(
                context.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.kind().equals("ERROR")));
    }

    @Test
    void compilerAcceptsSubclassAssignmentAndRejectsReverse(@TempDir Path directory)
            throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Probe.java",
                        """
                class Parent {}
                class Child extends Parent {}
                class Probe {
                  void accepted(Child value) { Parent copy = value; }
                  void rejected(Parent value) { Child copy = value; }
                }
                """);
        var errors =
                context.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.kind().equals("ERROR"))
                        .toList();
        assertEquals(1, errors.size());
        assertEquals(5, errors.getFirst().line());
    }

    private static JavaBinaryRepository repository(JavaBinaryType... types) {
        var entries =
                java.util.Arrays.stream(types)
                        .collect(
                                Collectors.toMap(
                                        JavaBinaryType::name, JavaBinaryWriter::signature));
        return new JavaBinaryRepository(new InMemoryClassFileSource(entries), 1024 * 1024);
    }

    private static ClassWriter header(String name, int flags, String parent, String... interfaces) {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                flags,
                name.replace('.', '/'),
                null,
                parent == null ? null : parent.replace('.', '/'),
                java.util.Arrays.stream(interfaces)
                        .map(value -> value.replace('.', '/'))
                        .toArray(String[]::new));
        return writer;
    }

    private static JavaBinaryType read(ClassWriter writer) {
        writer.visitEnd();
        return JavaBinaryReader.read(writer.toByteArray());
    }
}
