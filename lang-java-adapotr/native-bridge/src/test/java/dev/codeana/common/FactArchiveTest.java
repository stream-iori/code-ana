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
