package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaTypeNames;

import org.junit.jupiter.api.Test;

import java.util.List;

class AsmDescriptorParserTest {

    @Test
    void primitives() {
        assertEquals("int", parse("I"));
        assertEquals("long", parse("J"));
        assertEquals("boolean", parse("Z"));
        assertEquals("double", parse("D"));
        assertEquals("float", parse("F"));
        assertEquals("byte", parse("B"));
        assertEquals("short", parse("S"));
        assertEquals("char", parse("C"));
    }

    @Test
    void voidType() {
        assertEquals("void", parse("V"));
    }

    @Test
    void referenceType() {
        assertEquals("java.lang.String", parse("Ljava/lang/String;"));
    }

    @Test
    void arrayPrimitive() {
        assertEquals("int[]", parse("[I"));
    }

    @Test
    void arrayReference() {
        assertEquals("java.lang.String[]", parse("[Ljava/lang/String;"));
    }

    @Test
    void array2D() {
        assertEquals("int[][]", parse("[[I"));
    }

    @Test
    void methodDescriptor_parameters() {
        var shape = BinarySignatureTestSupport.method("(II)Ljava/lang/String;", null);
        assertEquals(List.of("int", "int"), parameters(shape));
    }

    @Test
    void methodDescriptor_returnType() {
        var shape = BinarySignatureTestSupport.method("(II)Ljava/lang/String;", null);
        assertEquals("java.lang.String", returns(shape));
    }

    @Test
    void methodDescriptor_mixedParams() {
        var shape = BinarySignatureTestSupport.method("(ILjava/lang/String;[I)V", null);
        assertEquals(List.of("int", "java.lang.String", "int[]"), parameters(shape));
        assertEquals("void", returns(shape));
    }

    @Test
    void methodDescriptor_noParams() {
        var shape = BinarySignatureTestSupport.method("()I", null);
        assertTrue(parameters(shape).isEmpty());
        assertEquals("int", returns(shape));
    }

    private static String parse(String descriptor) {
        return returns(BinarySignatureTestSupport.method("()" + descriptor, null));
    }

    private static String returns(JavaBinaryTypes.MethodShape shape) {
        return JavaTypeNames.erased(shape.types(), shape.types().get(shape.executable()).returns());
    }

    private static List<String> parameters(JavaBinaryTypes.MethodShape shape) {
        return shape.types().get(shape.executable()).parameters().stream()
                .map(reference -> JavaTypeNames.erased(shape.types(), reference))
                .toList();
    }
}
