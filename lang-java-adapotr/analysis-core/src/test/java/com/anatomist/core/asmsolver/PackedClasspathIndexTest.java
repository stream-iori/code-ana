package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

class PackedClasspathIndexTest {

    @Test
    void roundTripsSortedNamesAndOrigins(@TempDir Path tmp) throws Exception {
        var origins = new LinkedHashMap<String, Integer>();
        origins.put("z.Last", 7);
        origins.put("a.First", 2);
        PackedClasspathIndex index = PackedClasspathIndex.from(origins);
        Path file = tmp.resolve("types.origins");

        index.write(file, "fingerprint");
        PackedClasspathIndex restored = PackedClasspathIndex.read(file, "fingerprint");

        assertEquals(2, restored.findOrigin("a.First"));
        assertEquals(7, restored.findOrigin("z.Last"));
        assertEquals(-1, restored.findOrigin("m.Missing"));
    }

    @Test
    void rejectsFingerprintMismatchAndCrcCorruption(@TempDir Path tmp) throws Exception {
        PackedClasspathIndex index = PackedClasspathIndex.from(java.util.Map.of("a.First", 2));
        Path file = tmp.resolve("types.origins");
        index.write(file, "fingerprint");

        assertThrows(IOException.class, () -> PackedClasspathIndex.read(file, "different"));

        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> PackedClasspathIndex.read(file, "fingerprint"));
    }
}
