package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

/** Binary metadata round-trips through the neutral signature representation. */
class PackedTypeMetadataCacheTest {
    @Test
    void roundTripsMetadataAndRejectsCorruption() {
        var writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "com/x/Foo",
                null,
                "java/lang/Object",
                new String[] {"java/io/Serializable"});
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd();
        writer.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "run",
                        "()V",
                        null,
                        new String[] {"java/io/IOException"})
                .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).visitEnd();
        writer.visitAnnotation("Ljava/lang/Deprecated;", true).visitEnd();
        writer.visitInnerClass("com/x/Foo$Inner", "com/x/Foo", "Inner", Opcodes.ACC_PUBLIC);
        writer.visitEnd();
        var metadata = JavaBinaryReader.read(writer.toByteArray());
        var restored = JavaBinaryReader.read(JavaBinaryWriter.signature(metadata));
        assertEquals(metadata, restored);
        byte[] corrupt = JavaBinaryWriter.signature(metadata);
        corrupt[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> JavaBinaryReader.read(corrupt));
    }
}
