package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Pin {@link ClassFileSource} behaviour for both in-memory and jar-backed variants. Keeps the rest
 * of the AsmTypeSolver stack testable without needing real third-party jars on disk.
 */
class ClassFileSourceTest {

    @Test
    void unreadableEntryDoesNotHideLaterProvider(@TempDir Path directory) throws Exception {
        Path broken = Files.writeString(directory.resolve("broken.jar"), "not a jar");
        Path valid = directory.resolve("valid.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(valid))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
        }
        try (var source = new ClasspathClassFileSource(List.of(broken, valid))) {
            assertEquals(List.of(valid), source.providers("com.x.Foo"));
            assertEquals(List.of(broken), source.providerFailures("com.x.Foo"));
        }
    }

    @Test
    void legacyZip64CentralDirectoryFallsBackToLocalEntries(@TempDir Path directory)
            throws Exception {
        Path legacy = directory.resolve("legacy.jar");
        byte[] classBytes = miniClass("com.x.Foo");
        try (var jar = new JarOutputStream(Files.newOutputStream(legacy))) {
            var entry = new JarEntry("com/x/Foo.class");
            entry.setExtra(new byte[] {1, 0, 2, 0, 0, 0});
            jar.putNextEntry(entry);
            jar.write(classBytes);
            jar.closeEntry();
        }
        byte[] archive = Files.readAllBytes(legacy);
        boolean changed = false;
        for (int index = 0; index < archive.length - 28; index++) {
            if (archive[index] == 'P'
                    && archive[index + 1] == 'K'
                    && archive[index + 2] == 1
                    && archive[index + 3] == 2) {
                java.util.Arrays.fill(archive, index + 20, index + 28, (byte) 0xff);
                int nameLength =
                        Byte.toUnsignedInt(archive[index + 28])
                                + (Byte.toUnsignedInt(archive[index + 29]) << 8);
                int extra = index + 46 + nameLength;
                archive[extra] = 1;
                archive[extra + 1] = 0;
                archive[extra + 2] = 0;
                archive[extra + 3] = 0;
                changed = true;
                break;
            }
        }
        assertTrue(changed);
        Files.write(legacy, archive);
        assertThrows(
                java.util.zip.ZipException.class, () -> new java.util.jar.JarFile(legacy.toFile()));
        try (var source = new ClasspathClassFileSource(List.of(legacy))) {
            assertEquals(List.of(legacy), source.providers("com.x.Foo"));
            assertTrue(source.providerFailures("com.x.Foo").isEmpty());
            assertArrayEquals(classBytes, source.find("com.x.Foo").orElseThrow());
        }
    }

    @Test
    void manifestAttributeUsesTheExactFirstClassOrigin(@TempDir Path directory) throws Exception {
        List<Path> jars = new java.util.ArrayList<>();
        for (String version : List.of("first", "second")) {
            Path path = directory.resolve(version + ".jar");
            var manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            manifest.getMainAttributes().putValue("Artifact-Version", version);
            try (var jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
                jar.putNextEntry(new JarEntry("com/x/Foo.class"));
                jar.write(miniClass("com.x.Foo"));
                jar.closeEntry();
            }
            jars.add(path);
        }
        try (var source = new ClasspathClassFileSource(jars)) {
            assertEquals(jars, source.providers("com.x.Foo"));
            assertTrue(source.providers("com.x.Absent").isEmpty());
            assertEquals(
                    "first",
                    source.artifactAttribute("com.x.Foo", "Artifact-Version").orElseThrow());
            assertTrue(source.artifactAttribute("com.x.Foo", "Missing").isEmpty());
            assertTrue(source.artifactAttribute("com.x.Absent", "Artifact-Version").isEmpty());
        }
        Path classes = Files.createDirectory(directory.resolve("classes"));
        Files.createDirectories(classes.resolve("com/x"));
        Files.write(classes.resolve("com/x/Foo.class"), miniClass("com.x.Foo"));
        try (var source =
                new ClasspathClassFileSource(List.of(classes, jars.getFirst(), jars.getLast()))) {
            assertEquals(
                    List.of(classes, jars.getFirst(), jars.getLast()),
                    source.providers("com.x.Foo"));
            assertTrue(source.artifactAttribute("com.x.Foo", "Artifact-Version").isEmpty());
            assertArrayEquals(miniClass("com.x.Foo"), source.find("com.x.Foo").orElseThrow());
        }
    }

    @Test
    void sharedInventoryDoesNotShareOwnedJarHandles(@TempDir Path tmp) throws Exception {
        Path jarPath = tmp.resolve("shared.jar");
        byte[] bytes = miniClass("com.x.Foo");
        try (var jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(bytes);
            jar.closeEntry();
        }
        try (var inventory = new ClasspathClassFileSource(List.of(jarPath));
                var first = inventory.fork();
                var second = inventory.fork()) {
            assertArrayEquals(bytes, first.find("com.x.Foo").orElseThrow());
            assertArrayEquals(bytes, second.find("com.x.Foo").orElseThrow());
            first.close();
            Files.delete(jarPath);
            assertArrayEquals(bytes, second.find("com.x.Foo").orElseThrow());
            assertEquals(inventory.knownClasses(), second.knownClasses());
        }
    }

    @Test
    void inMemory_returnsBytesForKnownFqn() {
        byte[] bytes = miniClass("com.x.Foo");
        ClassFileSource src = new InMemoryClassFileSource(java.util.Map.of("com.x.Foo", bytes));
        Optional<byte[]> got = src.find("com.x.Foo");
        assertTrue(got.isPresent());
        assertArrayEquals(bytes, got.get());
    }

    @Test
    void inMemory_emptyForUnknown() {
        ClassFileSource src = new InMemoryClassFileSource(java.util.Map.of());
        assertTrue(src.find("does.not.Exist").isEmpty());
    }

    @Test
    void inMemory_indexesKnownClasses() {
        ClassFileSource src =
                new InMemoryClassFileSource(
                        java.util.Map.of(
                                "com.x.A", miniClass("com.x.A"),
                                "com.x.B", miniClass("com.x.B")));
        assertEquals(java.util.Set.of("com.x.A", "com.x.B"), src.knownClasses());
    }

    @Test
    void jarBacked_readsClassFromJar(@TempDir Path tmp) throws Exception {
        Path jarPath = tmp.resolve("test.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("com/x/Bar.class"));
            jar.write(miniClass("com.x.Bar"));
            jar.closeEntry();
        }
        try (ClassFileSource src = new JarClassFileSource(jarPath)) {
            assertEquals(java.util.Set.of("com.x.Foo", "com.x.Bar"), src.knownClasses());
            assertTrue(src.find("com.x.Foo").isPresent());
            assertTrue(src.find("com.x.Bar").isPresent());
            assertTrue(src.find("com.x.Missing").isEmpty());
            // Bytes must round-trip
            assertArrayEquals(miniClass("com.x.Foo"), src.find("com.x.Foo").get());
        }
    }

    @Test
    void jarBacked_ignoresNonClassEntries(@TempDir Path tmp) throws Exception {
        Path jarPath = tmp.resolve("test.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("README.md"));
            jar.write("hello".getBytes());
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jar.write("Manifest-Version: 1.0\n".getBytes());
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
        }
        try (ClassFileSource src = new JarClassFileSource(jarPath)) {
            assertEquals(java.util.Set.of("com.x.Foo"), src.knownClasses());
        }
    }

    @Test
    void classpathSource_readsDirectoriesAndJarsWithFirstEntryWins(@TempDir Path tmp)
            throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/com/x"));
        byte[] directoryFoo = miniClass("com.x.Foo");
        Files.write(classes.resolve("Foo.class"), directoryFoo);
        Files.write(classes.resolve("package-info.class"), miniClass("com.x.package-info"));

        Path jarPath = tmp.resolve("deps.jar");
        byte[] jarFoo = miniClass("com.x.FooFromJar");
        byte[] jarBar = miniClass("com.x.Bar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(jarFoo);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("com/x/Bar.class"));
            jar.write(jarBar);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/versions/9/com/x/NewFoo.class"));
            jar.write(miniClass("com.x.NewFoo"));
            jar.closeEntry();
        }

        try (ClassFileSource source =
                new ClasspathClassFileSource(java.util.List.of(tmp.resolve("classes"), jarPath))) {
            assertEquals(java.util.Set.of("com.x.Foo", "com.x.Bar"), source.knownClasses());
            assertArrayEquals(directoryFoo, source.find("com.x.Foo").orElseThrow());
            assertArrayEquals(jarBar, source.find("com.x.Bar").orElseThrow());
            assertTrue(source.find("com.x.Missing").isEmpty());
        }
    }

    @Test
    void classpathSourcePersistsPackedOriginAndTypeCaches(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path jarPath = tmp.resolve("deps.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("com/x/Foo.class"));
            jar.write(miniClass("com.x.Foo"));
            jar.closeEntry();
        }
        String previous = System.getProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
        System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, cache.toString());
        try {
            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath));
                    JavaBinaryRepository solver = new JavaBinaryRepository(source, 1024 * 1024)) {
                assertTrue(
                        (solver.find("com.x.Foo").orElseThrow().access()
                                        & org.objectweb.asm.Opcodes.ACC_INTERFACE)
                                == 0);
                assertEquals(1, source.indexedClassCount());
                assertTrue(source.packedIndexBytes() > 0);
            }
            try (var files = Files.list(cache)) {
                assertEquals(1, files.count(), "packed origin cache should be written");
            }

            try (ClasspathClassFileSource source = new ClasspathClassFileSource(List.of(jarPath));
                    JavaBinaryRepository solver = new JavaBinaryRepository(source, 1024 * 1024)) {
                assertTrue(solver.find("com.x.Foo").isPresent());
                Files.delete(jarPath);
                assertTrue(
                        solver.find("com.x.Foo").isPresent(),
                        "session metadata cache must not reopen the jar");
            }
        } finally {
            if (previous == null) System.clearProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
            else System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, previous);
        }
    }

    @Test
    void classpathSourceInvalidatesCacheForJavaVersionAndJarChanges(@TempDir Path tmp)
            throws Exception {
        Path cache = tmp.resolve("cache");
        Path jarPath = tmp.resolve("deps.jar");
        writeJar(jarPath, "com.x.Foo");
        String previous = System.getProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
        System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, cache.toString());
        try {
            String java8Fingerprint;
            try (ClasspathClassFileSource source =
                    new ClasspathClassFileSource(List.of(jarPath), 8)) {
                java8Fingerprint = source.fingerprint();
                assertTrue(source.find("com.x.Foo").isPresent());
            }
            try (ClasspathClassFileSource source =
                    new ClasspathClassFileSource(List.of(jarPath), 17)) {
                assertNotEquals(java8Fingerprint, source.fingerprint());
            }

            writeJar(jarPath, "com.x.Foo", "com.x.Bar");
            try (ClasspathClassFileSource source =
                    new ClasspathClassFileSource(List.of(jarPath), 8)) {
                assertNotEquals(java8Fingerprint, source.fingerprint());
                assertTrue(source.find("com.x.Bar").isPresent());
            }
        } finally {
            if (previous == null) System.clearProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY);
            else System.setProperty(ClasspathClassFileSource.CACHE_DIR_PROPERTY, previous);
        }
    }

    private static void writeJar(Path jarPath, String... fqns) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (String fqn : fqns) {
                jar.putNextEntry(new JarEntry(fqn.replace('.', '/') + ".class"));
                jar.write(miniClass(fqn));
                jar.closeEntry();
            }
        }
    }

    /** Produce a minimal valid class file. */
    static byte[] miniClass(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                fqn.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
