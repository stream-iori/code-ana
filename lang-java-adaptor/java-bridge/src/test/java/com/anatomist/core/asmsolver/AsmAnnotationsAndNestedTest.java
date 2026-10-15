package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaBinaryType;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

class AsmAnnotationsAndNestedTest {

    @Test
    void directAnnotation_recognised() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.MyAnno", annotationBytes("com.x.MyAnno"));
        classes.put("com.x.Annotated", classWithAnnotation("com.x.Annotated", "Lcom/x/MyAnno;"));
        JavaBinaryType d = solve(classes, "com.x.Annotated");
        assertTrue(
                d.annotations().stream()
                        .anyMatch(annotation -> annotation.descriptor().equals("Lcom/x/MyAnno;")),
                "@MyAnno should be detected as directly annotated");
        assertFalse(
                d.annotations().stream()
                        .anyMatch(annotation -> annotation.descriptor().equals("Lcom/x/Other;")));
    }

    @Test
    void noAnnotation_returnsFalse() {
        JavaBinaryType d = solve(Map.of("com.x.Foo", classOf("com.x.Foo")), "com.x.Foo");
        assertFalse(!d.annotations().isEmpty());
    }

    @Test
    void nestedTypes_internalTypesPopulated() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("com.x.Outer", classWithInnerRef("com.x.Outer", "com/x/Outer$Inner"));
        classes.put("com.x.Outer$Inner", classOf("com.x.Outer$Inner"));
        JavaBinaryType outer = solve(classes, "com.x.Outer");
        var nested = outer.innerTypes();
        assertEquals(1, nested.size(), "expected one internal type");
        assertEquals("com.x.Outer$Inner", nested.getFirst().name());
        assertTrue(nested.stream().anyMatch(type -> "Inner".equals(type.simpleName())));
        assertEquals(
                "com.x.Outer$Inner",
                nested.stream()
                        .filter(type -> "Inner".equals(type.simpleName()))
                        .findFirst()
                        .orElseThrow()
                        .name());
    }

    @Test
    void nestedTypes_emptyWhenNone() {
        JavaBinaryType d = solve(Map.of("com.x.Foo", classOf("com.x.Foo")), "com.x.Foo");
        assertTrue(d.innerTypes().isEmpty());
        assertFalse(d.innerTypes().stream().anyMatch(type -> "Anything".equals(type.simpleName())));
    }

    // ── helpers ──

    static JavaBinaryType solve(Map<String, byte[]> classes, String fqn) {
        try (var repository =
                new JavaBinaryRepository(new InMemoryClassFileSource(classes), 1024 * 1024)) {
            return repository.find(fqn).orElseThrow();
        }
    }

    static byte[] classOf(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                fqn.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] annotationBytes(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC
                        | Opcodes.ACC_INTERFACE
                        | Opcodes.ACC_ABSTRACT
                        | Opcodes.ACC_ANNOTATION,
                fqn.replace('.', '/'),
                null,
                "java/lang/Object",
                new String[] {"java/lang/annotation/Annotation"});
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithAnnotation(String fqn, String annoDescriptor) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                fqn.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        AnnotationVisitor av = cw.visitAnnotation(annoDescriptor, /* visible= */ true);
        if (av != null) av.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] classWithInnerRef(String fqn, String innerInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                fqn.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        cw.visitInnerClass(
                innerInternal,
                fqn.replace('.', '/'),
                "Inner",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
