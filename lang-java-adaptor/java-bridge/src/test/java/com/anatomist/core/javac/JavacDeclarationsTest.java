package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;

class JavacDeclarationsTest {
    @Test
    void rejectsTruncatedOversizedAndTrailingDeclarationFrames() throws Exception {
        for (byte[] invalid :
                new byte[][] {
                    frame(-1, new byte[0]), frame(JavacProtocol.MAX_BYTES + 1, new byte[0]),
                    frame(4, new byte[3]), frame(5, new byte[5]),
                    frame(4, new byte[] {0, 0, 0, 1})
                }) {
            assertThrows(
                    IOException.class,
                    () ->
                            JavacDeclarations.readFrame(
                                    new DataInputStream(new ByteArrayInputStream(invalid))));
        }
    }

    @Test
    void rejectsUnknownRetentionNullAttributesAndNullArrayElements() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(1);
                text(out, "Lsample/Mark;");
                text(out, variant == 0 ? "UNKNOWN" : "CLASS");
                out.writeInt(1);
                text(out, "value");
                if (variant == 2) {
                    out.writeByte(13);
                    out.writeInt(1);
                }
                out.writeByte(0);
            }
            assertThrows(
                    IOException.class,
                    () ->
                            JavacDeclarations.annotations(
                                    new DataInputStream(
                                            new ByteArrayInputStream(bytes.toByteArray()))));
        }
    }

    private static byte[] frame(int length, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(length);
            out.write(body);
        }
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
