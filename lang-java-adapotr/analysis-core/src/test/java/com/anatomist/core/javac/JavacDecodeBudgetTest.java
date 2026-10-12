package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

class JavacDecodeBudgetTest {
    @Test
    void tinyPayloadCannotRequestUnboundedNestedCollections() throws Exception {
        ByteArrayOutputStream treeBytes = new ByteArrayOutputStream();
        DataOutputStream tree = new DataOutputStream(treeBytes);
        for (int depth = 0; depth < 12; depth++) {
            tree.writeByte(2);
            tree.writeInt(1_000_000);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(JavacProtocol.MAGIC);
        string(out, "25");
        out.writeInt(1);
        string(out, "/source/Example.java");
        string(out, "a".repeat(64));
        out.writeBoolean(false);
        out.writeInt(treeBytes.size() + 8);
        out.writeInt(treeBytes.size());
        out.write(treeBytes.toByteArray());
        out.writeInt(0); // empty syntax dictionary
        IOException failure =
                assertThrows(
                        IOException.class,
                        () ->
                                JavacProtocol.response(
                                        new ByteArrayInputStream(bytes.toByteArray()),
                                        unit -> fail("invalid payload must not reach consumer")));
        assertTrue(failure.getMessage().startsWith("JAVAC_DECODE_BUDGET_EXCEEDED:"));
        assertTrue(failure.getMessage().contains("file=/source/Example.java"));
        assertTrue(failure.getMessage().contains("section=SOURCE_TREE"));
    }

    @Test
    void framesShareTheTaskAllocationBudget() throws Exception {
        var parent =
                new JavacDecodeBudget.Input(
                        InputStream.nullInputStream(), new JavacDecodeBudget(100));
        var first = JavacDecodeBudget.frame(InputStream.nullInputStream(), parent);
        var second = JavacDecodeBudget.frame(InputStream.nullInputStream(), parent);
        JavacDecodeBudget.take(first, 70);
        assertThrows(IOException.class, () -> JavacDecodeBudget.take(second, 31));
        JavacDecodeBudget.take(second, 30);
        assertThrows(IOException.class, () -> JavacDecodeBudget.take(parent, -1));
    }

    @Test
    void twoGiBBudgetUsesLongAndRemainsBounded() throws Exception {
        var input = new JavacDecodeBudget.Input(InputStream.nullInputStream(), new JavacDecodeBudget(2L << 30));
        JavacDecodeBudget.take(input, (512L << 20) + 1);
        JavacDecodeBudget.take(input, (2L << 30) - (512L << 20) - 1);
        assertThrows(IOException.class, () -> JavacDecodeBudget.take(input, 1));
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
