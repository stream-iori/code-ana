package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class AsmMembersTest {
    @Test
    void methodVariablesShadowClassVariablesAndKeepBounds() {
        var identity =
                BinarySignatureTestSupport.method(
                        "<T:Ljava/lang/Object;>Ljava/lang/Object;",
                        "(Ljava/lang/Number;)Ljava/lang/Number;",
                        "<T:Ljava/lang/Number;>(TT;)TT;");
        var executable = identity.types().get(identity.executable());
        var methodVariable = identity.methodParameterTypes().getFirst();
        assertEquals(methodVariable, executable.returns());
        assertEquals(methodVariable, executable.parameters().getFirst());
        assertEquals("java.lang.Number", JavaTypeNames.erased(identity.types(), methodVariable));
        var classVariable =
                identity.types().entries().stream()
                        .filter(type -> type.kind() == JavaType.Kind.TYPEVAR)
                        .findFirst()
                        .orElseThrow();
        assertNotEquals(classVariable, identity.types().get(methodVariable));
        var get =
                BinarySignatureTestSupport.method(
                        "<T:Ljava/lang/Object;>Ljava/lang/Object;",
                        "()Ljava/lang/Object;",
                        "()TT;");
        assertTrue(get.methodParameters().isEmpty());
        assertEquals(
                "java.lang.Object",
                JavaTypeNames.erased(get.types(), get.types().get(get.executable()).returns()));
    }

    @Test
    void inheritedObjectMethodUsesSelectedCompilerJdk(@TempDir Path directory) throws Exception {
        write(directory, "p/Child", header("p/Child", null, "java/lang/Object"));
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Probe.java",
                        "class Probe { String run(p.Child child) { return child.toString(); } }",
                        List.of(directory));
        var binding =
                context.units().getFirst().bindings().values().stream()
                        .filter(
                                value ->
                                        value.kind().equals("METHOD")
                                                && value.name().equals("toString"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(binding.declarationResolved());
        assertEquals("java.lang.Object", binding.owner());
    }

    @Test
    void grandparentGenericMethodsRetainBindingAndVisibility(@TempDir Path directory)
            throws Exception {
        var base = header("p/Base", "<T:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object");
        base.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "value",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        "(TT;)TT;",
                        null)
                .visitEnd();
        base.visitMethod(Opcodes.ACC_PRIVATE, "secret", "()V", null, null).visitEnd();
        write(directory, "p/Base", base);
        write(
                directory,
                "p/Middle",
                header("p/Middle", "<U:Ljava/lang/Object;>Lp/Base<TU;>;", "p/Base"));
        write(
                directory,
                "p/Child",
                header("p/Child", "Lp/Middle<Ljava/lang/String;>;", "p/Middle"));
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Probe.java",
                        "class Probe { String run(p.Child child) { return child.value(\"ok\"); } void bad(p.Child child) { child.secret(); } }",
                        List.of(directory));
        var unit = context.units().getFirst();
        var value =
                unit.bindings().values().stream()
                        .filter(
                                binding ->
                                        binding.kind().equals("METHOD")
                                                && binding.name().equals("value")
                                                && binding.expressionType() >= 0)
                        .findFirst()
                        .orElseThrow();
        assertTrue(value.declarationResolved());
        assertEquals("p.Base", value.owner());
        assertEquals(
                "java.lang.String", JavaTypeNames.erased(unit.types(), value.expressionType()));
        assertTrue(
                context.diagnostics().stream()
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.kind().equals("ERROR")
                                                && diagnostic.message().contains("secret")));
        assertTrue(
                unit.bindings().values().stream()
                        .filter(binding -> binding.name().equals("secret"))
                        .noneMatch(JavaBinding::declarationResolved));
    }

    @Test
    void cyclicInterfaceHeadersRemainFiniteAndPreserveKind() {
        var a = new ClassWriter(0);
        a.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "p/A",
                null,
                "java/lang/Object",
                new String[] {"p/B"});
        var b = new ClassWriter(0);
        b.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "p/B",
                null,
                "java/lang/Object",
                new String[] {"p/A"});
        a.visitEnd();
        b.visitEnd();
        try (var repository =
                new JavaBinaryRepository(
                        new InMemoryClassFileSource(
                                Map.of("p.A", a.toByteArray(), "p.B", b.toByteArray())),
                        1024 * 1024)) {
            var type = repository.find("p.A").orElseThrow();
            assertTrue((type.access() & Opcodes.ACC_INTERFACE) != 0);
            assertEquals(List.of("p.B"), type.interfaces());
            assertEquals(List.of("p.A"), repository.find("p.B").orElseThrow().interfaces());
            assertTrue(type.methods().isEmpty());
            var hierarchy = JavaBinaryTypes.hierarchy(type);
            assertEquals("p.B", hierarchy.types().get(hierarchy.interfaces().getFirst()).name());
        }
    }

    @Test
    void declaredFieldsKeepTypesAndFlags() {
        var writer = header("com/x/Foo", null, "java/lang/Object");
        writer.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "age", "I", null, null)
                .visitEnd();
        writer.visitEnd();
        var fields = JavaBinaryReader.read(writer.toByteArray()).fields();
        assertEquals(2, fields.size());
        var name =
                fields.stream()
                        .filter(field -> field.name().equals("name"))
                        .findFirst()
                        .orElseThrow();
        var age =
                fields.stream()
                        .filter(field -> field.name().equals("age"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Ljava/lang/String;", name.descriptor());
        assertEquals("I", age.descriptor());
        assertEquals(0, name.access() & Opcodes.ACC_STATIC);
        assertNotEquals(0, age.access() & Opcodes.ACC_STATIC);
    }

    @Test
    void declaredMethodsDistinguishOverloads() {
        var writer = header("com/x/Foo", null, "java/lang/Object");
        writer.visitMethod(Opcodes.ACC_PUBLIC, "substring", "(I)Ljava/lang/String;", null, null)
                .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "substring", "(II)Ljava/lang/String;", null, null)
                .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "length", "()I", null, null).visitEnd();
        writer.visitEnd();
        var owner = JavaBinaryReader.read(writer.toByteArray());
        assertEquals(3, owner.methods().size());
        assertEquals(
                2,
                owner.methods().stream()
                        .filter(method -> method.name().equals("substring"))
                        .count());
        var length =
                owner.methods().stream()
                        .filter(method -> method.name().equals("length"))
                        .findFirst()
                        .orElseThrow();
        var shape = JavaBinaryTypes.method(owner, length);
        var executable = shape.types().get(shape.executable());
        assertTrue(executable.parameters().isEmpty());
        assertEquals(JavaType.Kind.INT, shape.types().get(executable.returns()).kind());
    }

    @Test
    void methodParameterTypesResolveFromSelectedBinaryClasspath(@TempDir Path directory)
            throws Exception {
        write(directory, "com/x/Bar", header("com/x/Bar", null, "java/lang/Object"));
        var foo = header("com/x/Foo", null, "java/lang/Object");
        foo.visitMethod(Opcodes.ACC_PUBLIC, "acceptBar", "(Lcom/x/Bar;)V", null, null).visitEnd();
        write(directory, "com/x/Foo", foo);
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "Probe.java",
                        "class Probe { void run(com.x.Foo foo, com.x.Bar bar) { foo.acceptBar(bar); } }",
                        List.of(directory));
        var binding =
                context.units().getFirst().bindings().values().stream()
                        .filter(
                                value ->
                                        value.name().equals("acceptBar")
                                                && value.kind().equals("METHOD"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(binding.declarationResolved());
        assertEquals(List.of("com.x.Bar"), binding.parameters());
        assertEquals("void", binding.returns());
        assertEquals("com.x.Foo", binding.owner());
    }

    @Test
    void constructorsRemainSeparateFromMethods() {
        var writer = header("com/x/Foo", null, "java/lang/Object");
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null).visitEnd();
        writer.visitEnd();
        var methods = JavaBinaryReader.read(writer.toByteArray()).methods();
        assertEquals(2, methods.stream().filter(method -> method.name().equals("<init>")).count());
        assertEquals(
                List.of("foo"),
                methods.stream()
                        .filter(method -> !method.name().equals("<init>"))
                        .map(JavaBinaryType.Method::name)
                        .toList());
    }

    @Test
    void abstractAndStaticFlagsSurvive() {
        var writer = header("com/x/Foo", null, "java/lang/Object");
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "statique", "()V", null, null)
                .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "abstrait", "()V", null, null)
                .visitEnd();
        writer.visitEnd();
        var methods = JavaBinaryReader.read(writer.toByteArray()).methods();
        var statique = methods.getFirst();
        var abstrait = methods.getLast();
        assertNotEquals(0, statique.access() & Opcodes.ACC_STATIC);
        assertEquals(0, statique.access() & Opcodes.ACC_ABSTRACT);
        assertNotEquals(0, abstrait.access() & Opcodes.ACC_ABSTRACT);
        assertEquals(0, abstrait.access() & Opcodes.ACC_STATIC);
    }

    private static ClassWriter header(String name, String signature, String parent) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, signature, parent, null);
        return writer;
    }

    private static void write(Path directory, String name, ClassWriter writer) throws Exception {
        writer.visitEnd();
        var file = directory.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }
}
