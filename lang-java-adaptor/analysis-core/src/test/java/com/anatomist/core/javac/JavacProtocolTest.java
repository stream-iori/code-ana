package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;

class JavacProtocolTest {
    @Test
    void sharedBindingStringsRejectUnknownReferencesAndDuplicateTreeIds() throws Exception {
        var units = new java.util.ArrayList<JavaUnit>();
        JavacProtocol.response(new ByteArrayInputStream(bindingFrame(0, false)), units::add);
        var parsed = units.getFirst();
        assertEquals(2, parsed.bindings().size());
        assertSame(parsed.bindings().get(1).type(), parsed.bindings().get(2).type());
        assertEquals("int", parsed.bindings().get(1).type());
        assertThrows(
                IOException.class,
                () ->
                        JavacProtocol.response(
                                new ByteArrayInputStream(bindingFrame(1, false)), unit -> {}));
        assertThrows(
                IOException.class,
                () ->
                        JavacProtocol.response(
                                new ByteArrayInputStream(bindingFrame(-1, false)), unit -> {}));
        assertThrows(
                IOException.class,
                () ->
                        JavacProtocol.response(
                                new ByteArrayInputStream(bindingFrame(0, true)), unit -> {}));
    }

    private static byte[] bindingFrame(int reference, boolean duplicate) throws IOException {
        return bindingFrame(reference, duplicate, -1);
    }

    @Test
    void rejectsDanglingTypeReferencesAndDoesNotWrapConsumerFailures() throws Exception {
        for (int invalid : new int[] {-2, 0}) {
            var received = new java.util.ArrayList<JavaUnit>();
            assertThrows(
                    IOException.class,
                    () ->
                            JavacProtocol.response(
                                    new ByteArrayInputStream(bindingFrame(0, false, invalid)),
                                    received::add));
            assertTrue(received.isEmpty());
        }
        var primary = new IllegalArgumentException("consumer failure");
        assertSame(
                primary,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                JavacProtocol.response(
                                        new ByteArrayInputStream(bindingFrame(0, false)),
                                        unit -> {
                                            throw primary;
                                        })));
    }

    @Test
    void syntaxDictionaryRejectsUnknownReferencesAndOffsets() throws Exception {
        for (int reference : new int[] {-1, 1})
            assertThrows(
                    IOException.class,
                    () ->
                            JavacProtocol.response(
                                    new ByteArrayInputStream(bindingFrame(0, false, -1, reference)),
                                    unit -> fail("invalid dictionary")));
        byte[] frame = bindingFrame(0, false);
        // Skip the response header and unit identity to reach the self-contained AST frame.
        try (var input = new DataInputStream(new ByteArrayInputStream(frame))) {
            input.readInt();
            input.skipNBytes(input.readInt());
            input.readInt();
            input.skipNBytes(input.readInt());
            input.skipNBytes(input.readInt());
            input.readBoolean();
            input.readInt();
            int offset = frame.length - input.available();
            java.nio.ByteBuffer.wrap(frame).putInt(offset, Integer.MAX_VALUE);
        }
        assertThrows(
                IOException.class,
                () ->
                        JavacProtocol.response(
                                new ByteArrayInputStream(frame), unit -> fail("invalid offset")));
    }

    private static byte[] bindingFrame(int reference, boolean duplicate, int typeReference)
            throws IOException {
        return bindingFrame(reference, duplicate, typeReference, 0);
    }

    private static byte[] bindingFrame(
            int reference, boolean duplicate, int typeReference, int syntaxReference)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(JavacProtocol.MAGIC);
            out.writeInt(0); // version
            out.writeInt(1); // units
            out.writeInt(6);
            out.writeBytes("A.java");
            out.writeInt(64);
            out.writeBytes("0".repeat(64));
            out.writeBoolean(false);
            ByteArrayOutputStream tree = new ByteArrayOutputStream();
            try (DataOutputStream node = new DataOutputStream(tree)) {
                node.writeByte(1);
                node.writeInt(1);
                node.writeInt(syntaxReference); // syntax dictionary reference
                node.writeLong(0);
                node.writeLong(1);
                node.writeInt(0);
            }
            out.writeInt(4 + tree.size() + 4 + 4 + 16);
            out.writeInt(tree.size());
            out.write(tree.toByteArray());
            out.writeInt(1); // syntax dictionary
            out.writeInt(16);
            out.writeBytes("COMPILATION_UNIT");
            out.writeByte(0); // processor not run
            out.writeInt(1); // dictionary
            out.writeInt(3);
            out.writeBytes("int");
            out.writeInt(0); // types
            out.writeInt(2); // bindings
            for (int id = 1; id <= 2; id++) {
                out.writeInt(duplicate ? 1 : id);
                out.writeInt(reference); // kind
                out.writeInt(0); // owner
                out.writeInt(0); // name
                out.writeInt(0); // parameters
                out.writeInt(0); // returns
                out.writeInt(0); // type
                out.writeBoolean(false);
                out.writeInt(0); // flags
                out.writeInt(0); // exceptions
                out.writeInt(0); // descriptor
                out.writeInt(typeReference); // expression type
                out.writeInt(-1); // declared type
                out.writeInt(-1); // selected type
                out.writeBoolean(true);
                out.writeInt(0); // binary owner
                out.writeInt(0); // owner kind
                out.writeBoolean(true); // declaration resolved
            }
            out.writeInt(4); // declaration frame length
            out.writeInt(0); // declaration inventory
            out.writeBoolean(true);
            out.writeInt(0); // failure
            out.writeInt(0); // diagnostics
            out.writeInt(0); // timings
            out.writeInt(0x414A4544); // terminal result
        }
        return bytes.toByteArray();
    }

    @Test
    void rejectsMalformedFramesBeforeAllocatingOrPublishing() throws Exception {
        assertThrows(
                IOException.class,
                () -> JavacProtocol.response(new ByteArrayInputStream(new byte[0]), unit -> {}));
        assertThrows(
                IOException.class,
                () -> JavacProtocol.response(new ByteArrayInputStream(new byte[4]), unit -> {}));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(JavacProtocol.MAGIC);
            out.writeInt(JavacProtocol.MAX_BYTES + 1);
        }
        assertThrows(
                IOException.class,
                () ->
                        JavacProtocol.response(
                                new ByteArrayInputStream(bytes.toByteArray()), unit -> {}));
    }

    @Test
    void corruptOrEmptyCompilationManifestCannotBeReused(@TempDir Path temporary) throws Exception {
        Path pending = Files.createDirectory(temporary.resolve("pending"));
        Files.writeString(pending.resolve("analysis.bin"), "compiler result");
        Files.writeString(pending.resolve("manifest.txt"), "");
        assertFalse(JavacCompilationCache.valid(pending));
        Files.delete(pending.resolve("manifest.txt"));
        Path published = temporary.resolve("published");
        JavacCompilationCache.publish(pending, published);
        assertTrue(JavacCompilationCache.valid(published));
        Files.writeString(published.resolve("analysis.bin"), "truncated result");
        assertFalse(JavacCompilationCache.valid(published));
        JavacCompilationCache.remove(published);
        assertFalse(Files.exists(published));
    }
}
