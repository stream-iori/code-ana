package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class JavaBinarySessionIT {
    @Test
    void sharedInventoriesPreservePrefixOrderAndBorrowerOwnership(@TempDir Path directory)
            throws Exception {
        Path baseline = classes(directory.resolve("baseline"), "Base");
        Path prefix = classes(directory.resolve("prefix"), "Prefix");
        Path home = Path.of(System.getProperty("java.home"));
        try (var session = new JavaBinarySources.Session(List.of(baseline), home, 25, null)) {
            try (var first = session.open(List.of(prefix, baseline), 8192)) {
                assertEquals("Prefix", first.find("p.Example").orElseThrow().superclass());
                assertTrue(first.find("java.lang.String").isPresent());
                assertTrue(
                        first.artifactAttribute("p.Example", "Implementation-Version").isEmpty());
            }
            try (var second = session.open(List.of(baseline), 8192)) {
                assertEquals("Base", second.find("p.Example").orElseThrow().superclass());
                assertTrue(second.find("java.lang.String").isPresent());
                assertEquals(
                        "baseline",
                        second.artifactAttribute("p.Example", "Implementation-Version")
                                .orElseThrow());
            }
            // A different dependency order cannot borrow the suffix inventory.
            try (var reordered = session.open(List.of(baseline, prefix), 0)) {
                assertEquals("Base", reordered.find("p.Example").orElseThrow().superclass());
            }
            session.close();
            assertThrows(IllegalStateException.class, () -> session.open(List.of(baseline), 0));
        }
    }

    @Test
    void closingPlatformBorrowerKeepsOwnerAndOtherBorrowersUsable() {
        var owner = new JdkClassFileSource(Path.of(System.getProperty("java.home")), 25);
        try (owner;
                var first = owner.fork();
                var second = owner.fork()) {
            assertTrue(first.find("java.lang.String").isPresent());
            first.close();
            assertThrows(IllegalStateException.class, () -> first.find("java.lang.String"));
            assertTrue(second.find("java.lang.String").isPresent());
            assertTrue(owner.find("java.lang.String").isPresent());
            owner.close();
            assertTrue(second.find("java.lang.String").isPresent());
        }
    }

    private static Path classes(Path directory, String parent) throws Exception {
        Files.createDirectories(directory.resolve("p"));
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Example", null, parent, null);
        writer.visitEnd();
        Files.write(directory.resolve("p/Example.class"), writer.toByteArray());
        if (parent.equals("Base")) {
            Files.createDirectories(directory.resolve("META-INF"));
            Files.writeString(
                    directory.resolve("META-INF/MANIFEST.MF"),
                    "Manifest-Version: 1.0\nImplementation-Version: baseline\n\n");
        }
        return directory;
    }
}
