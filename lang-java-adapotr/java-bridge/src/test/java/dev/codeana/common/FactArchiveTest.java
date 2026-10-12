package dev.codeana.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FactArchiveTest {
    @TempDir Path temporary;

    @Test
    void bufferedFramesAndLargeRecordsRemainByteExactAcrossReadSealAndClose() throws Exception {
        byte[] small = Json.write(Json.object("value", "中文😀".repeat(700))).getBytes(StandardCharsets.UTF_8);
        byte[] large = Json.write(Json.object("value", "a".repeat(70000))).getBytes(StandardCharsets.UTF_8);
        var expected = new java.io.ByteArrayOutputStream();
        var frames = new java.io.DataOutputStream(expected);
        frames.writeInt(0x43414631);
        var rows = List.of(small, small, large, small, large, small);
        Path sealed = temporary.resolve("buffered-sealed.caf");
        try (var archive = new FactArchive(temporary.resolve("buffered.caf"), 1 << 20, rows.size())) {
            for (byte[] row : rows) {
                frames.writeInt(row.length);
                frames.write(row);
                archive.addEncoded(row);
            }
            assertArrayEquals(small, archive.encoded(1));
            assertArrayEquals(large, archive.encoded(4));
            archive.export(sealed);
        }
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(sealed));
        Path closed = temporary.resolve("closed.caf");
        try (var archive = new FactArchive(closed, 1 << 20, rows.size(), false)) {
            for (byte[] row : rows) archive.addEncoded(row);
        }
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(closed));
        try (var archive = FactArchive.open(sealed, 1 << 20, rows.size(), Json.sha(sealed))) {
            for (int index = 0; index < rows.size(); index++) assertArrayEquals(rows.get(index), archive.encoded(index));
        }
    }

    @Test
    void encodedRowsKeepBytesLimitsAndExportLifetime() throws Exception {
        byte[] encoded = Json.write(Json.object("name", "😀中文")).getBytes(StandardCharsets.UTF_8);
        Path destination = temporary.resolve("sealed.caf");
        Map<String, Object> descriptor;
        try (var archive = new FactArchive(temporary.resolve("input.caf"), 1024, 1)) {
            archive.addEncoded(encoded);
            assertArrayEquals(encoded, archive.encoded(0));
            assertThrows(IllegalStateException.class, () -> archive.addEncoded(encoded));
            descriptor = archive.export(destination);
            assertThrows(IllegalStateException.class, () -> archive.addEncoded(encoded));
        }
        try (var archive =
                FactArchive.open(destination, 1024, 1, (String) descriptor.get("sha256"))) {
            assertArrayEquals(encoded, archive.encoded(0));
        }
        try (var archive =
                new FactArchive(temporary.resolve("bounded.caf"), 4 + encoded.length, 1, false)) {
            assertThrows(IllegalStateException.class, () -> archive.addEncoded(encoded));
        }
    }
}
