package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class InternalCacheScanTest {
    @Test
    void cacheNeverBecomesSourceOrClasspath(@TempDir Path root) throws Exception {
        Path product = write(root, "src/main/java/Product.java", "class Product {}");
        Path cache = root.resolve(".diorama/cache/review-worktrees/review");
        Path duplicate = write(cache, "src/main/java/Duplicate.java", "class Duplicate {}");
        write(cache, "pom.xml", "<project/>");
        write(cache, "target/generated-sources/annotations/Generated.java", "class Generated {}");
        Files.createDirectories(cache.resolve("target/classes"));
        write(cache, "beans.xml", "<beans xmlns=\"http://www.springframework.org/schema/beans\"/>");
        Path generated =
                write(root, "target/generated-sources/annotations/Valid.java", "class Valid {}");
        var policy = ScanPolicy.all(root);
        var scanner = new ProjectScanner(Set.of(), policy);
        assertEquals(List.of(product), scanner.scan(root));
        assertTrue(scanner.scanFiles(root, Set.of(".xml")).isEmpty());
        assertFalse(policy.includes(duplicate));
        assertTrue(policy.includes(root.resolve(".diorama/config.java")));
        assertTrue(
                scanner.scanSourceRoots(
                                List.of(
                                        new SourceRoot(
                                                duplicate.getParent(), ".", SourceScope.GENERATED)))
                        .isEmpty());
        assertEquals(
                List.of(generated),
                scanner.scanSourceRoots(
                        List.of(
                                new SourceRoot(
                                        generated.getParent(), ".", SourceScope.GENERATED))));
        var detector = new ClasspathDetector();
        assertTrue(detector.detectSourcePaths(root).stream().noneMatch(p -> p.startsWith(cache)));
        assertTrue(
                detector.detectBuildOutputClasspath(root).stream()
                        .noneMatch(p -> p.startsWith(cache)));
    }

    @Test
    void managedProjectRootIsNotItselfExcluded(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve(".anatomist/workspace");
        Path source = write(root, "src/A.java", "class A {}");
        assertEquals(
                List.of(source), new ProjectScanner(Set.of(), ScanPolicy.all(root)).scan(root));
    }

    private static Path write(Path root, String relative, String contents) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, contents);
    }
}
