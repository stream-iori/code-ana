package com.anatomist.core.asmsolver;

import com.anatomist.core.java.JavaBinaryType;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Real class-file inputs for the detached binary signature graph. */
final class BinarySignatureTestSupport {
    private BinarySignatureTestSupport() {}

    static JavaBinaryType owner(String signature) {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "test/Example",
                signature,
                "java/lang/Object",
                null);
        writer.visitEnd();
        return JavaBinaryReader.read(writer.toByteArray());
    }

    static JavaBinaryTypes.MethodShape method(String descriptor, String signature) {
        return method(null, descriptor, signature);
    }

    static JavaBinaryTypes.MethodShape method(
            String ownerSignature, String descriptor, String signature) {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "test/Example",
                ownerSignature,
                "java/lang/Object",
                null);
        writer.visitMethod(Opcodes.ACC_PUBLIC, "example", descriptor, signature, null).visitEnd();
        writer.visitEnd();
        var type = JavaBinaryReader.read(writer.toByteArray());
        return JavaBinaryTypes.method(type, type.methods().getFirst());
    }
}
