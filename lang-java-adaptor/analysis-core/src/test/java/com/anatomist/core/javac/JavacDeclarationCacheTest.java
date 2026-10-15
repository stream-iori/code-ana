package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

class JavacDeclarationCacheTest {
    @Test
    void referencesArePinnedBoundedAndInvalidatedBySourceAndEnvironment(@TempDir Path root)
            throws Exception {
        Path a = Files.writeString(root.resolve("A.java"), "class A {}"),
                b = Files.writeString(root.resolve("B.java"), "class B {}");
        var cache = new JavacDeclarationCache(4096);
        byte[] encoded = new byte[1024];
        try (var lease = cache.lease(List.of(a), Set.of(), true, "jdk-one")) {
            assertTrue(lease.declarations(a));
            lease.remember(a, encoded);
            lease.commit();
        }
        try (var lease = cache.lease(List.of(a, b), Set.of(), true, "jdk-one")) {
            String token = lease.token(a);
            assertFalse(token.isEmpty());
            lease.remember(b, new byte[3500]);
            assertSame(encoded, lease.reference(a, token));
            assertTrue(cache.bytes() <= 4096);
            assertThrows(IOException.class, () -> lease.reference(a, "wrong"));
            assertThrows(IllegalStateException.class, cache::clear);
        }
        assertEquals(1, cache.hits());
        try (var lease = cache.lease(List.of(a), Set.of(), true, "jdk-two")) {
            assertEquals("", lease.token(a));
        }
        Files.writeString(a, "class A { int value; }");
        try (var lease = cache.lease(List.of(a), Set.of(), true, "jdk-one")) {
            assertEquals("", lease.token(a));
        }
        cache.clear();
        assertEquals(0, cache.bytes());
    }

    @Test
    void failedFramesAreDiscardedAndPublishedUnitsAreNeverDeclarations(@TempDir Path root)
            throws Exception {
        Path a = Files.writeString(root.resolve("A.java"), "class A {}"),
                b = Files.writeString(root.resolve("B.java"), "class B {}");
        var cache = new JavacDeclarationCache(2048);
        try (var lease = cache.lease(List.of(a, b), Set.of(a), true, "env")) {
            assertFalse(lease.declarations(a));
            lease.remember(a, new byte[100]);
            assertEquals(0, cache.bytes());
            lease.remember(b, new byte[100]);
            assertTrue(cache.bytes() > 0);
        }
        assertEquals(0, cache.bytes());
        try (var lease = cache.lease(List.of(a), Set.of(), false, "env")) {
            assertFalse(lease.declarations(a));
        }
        // Unpinned entries can be evicted by the next context.
        for (Path file : List.of(a, b))
            try (var lease = cache.lease(List.of(file), Set.of(), true, "env")) {
                lease.remember(file, new byte[1200]);
                lease.commit();
            }
        try (var lease = cache.lease(List.of(a, b), Set.of(), true, "env")) {
            assertEquals("", lease.token(a));
            assertFalse(lease.token(b).isEmpty());
        }
    }
}
