package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

class ContentHashTest {
    @Test
    void smallInputsKeepOrderAndDuplicates(@TempDir Path directory) throws Exception {
        Path first = Files.writeString(directory.resolve("first"), "alpha");
        Path second = Files.writeString(directory.resolve("second"), "beta");
        assertEquals(
                List.of(
                        ContentHash.sha256(second),
                        ContentHash.sha256(first),
                        ContentHash.sha256(second)),
                ContentHash.sha256Files(List.of(second, first, second)));
        assertEquals(List.of(), ContentHash.sha256Files(List.of()));
        assertThrows(
                IOException.class,
                () -> ContentHash.sha256Files(List.of(directory.resolve("missing"))));
    }

    @Test
    void largeInputsKeepExactHashesAndJoinReadersAfterCancellation(@TempDir Path directory)
            throws Exception {
        var inputs = new ArrayList<Path>();
        var expected = new ArrayList<String>();
        byte[] bytes = new byte[2 * 1024 * 1024];
        for (int index = 0; index < 17; index++) {
            bytes[0] = (byte) index;
            Path path = Files.write(directory.resolve("file" + index), bytes);
            inputs.add(path);
            expected.add(ContentHash.sha256(path));
        }
        Collections.reverse(inputs);
        Collections.reverse(expected);
        assertEquals(expected, ContentHash.sha256Files(inputs));
        assertNoReaders();
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> ContentHash.sha256Files(inputs));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNoReaders();
        // Identical size and timestamp still require hashing actual bytes.
        Path changed = inputs.getFirst();
        var time = Files.getLastModifiedTime(changed);
        bytes[0] = 99;
        Files.write(changed, bytes);
        Files.setLastModifiedTime(changed, time);
        assertNotEquals(expected, ContentHash.sha256Files(inputs));
        assertNoReaders();
    }

    private static void assertNoReaders() {
        assertTrue(
                Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(
                                thread ->
                                        thread.isAlive()
                                                && thread.getName()
                                                        .startsWith("anatomist-content-hash-")));
    }
}
