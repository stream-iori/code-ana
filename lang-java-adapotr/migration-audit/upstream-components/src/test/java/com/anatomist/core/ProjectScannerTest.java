package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

class ProjectScannerTest {
    @Test
    void oneInvocationSharesEnumerationAcrossSelectionAndSupport(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src"));
        Path selected = Files.writeString(root.resolve("App.java"), "class App {}");
        Path support = Files.writeString(root.resolve("Library.java"), "class Library {}");
        Files.writeString(root.resolve("Text.java"), "class Text {}");
        Files.writeString(root.resolve("Excluded.java"), "class Excluded {}");
        var policy =
                new ScanPolicy(
                        tmp,
                        List.of("src/App.java"),
                        List.of("src/Excluded.java"),
                        List.of("src/Text.java"),
                        Set.of());
        var roots = List.of(new SourceRoot(root, ".", SourceScope.MAIN));
        var discovery = new JavaSourceDiscovery();
        assertEquals(
                List.of(selected),
                new ProjectScanner(Set.of(), policy, discovery).scanSourceRoots(roots));
        assertEquals(
                List.of(selected, support),
                new ProjectScanner(Set.of(), policy.support(), discovery).scanSourceRoots(roots));
        assertEquals(1L, discovery.counts().get("source_root_walks"));
        Path added = Files.writeString(root.resolve("New.java"), "class New {}");
        Files.delete(support);
        var next = new JavaSourceDiscovery();
        assertEquals(
                List.of(selected, added),
                new ProjectScanner(Set.of(), policy.support(), next).scanSourceRoots(roots));
    }

    @Test
    void sharedEnumerationHonorsChangedExclusionsAndGeneratedRoots(@TempDir Path tmp)
            throws Exception {
        Path generated = Files.createDirectories(tmp.resolve("target/generated-sources"));
        Path file = Files.writeString(generated.resolve("Generated.java"), "class Generated {}");
        var discovery = new JavaSourceDiscovery();
        var ordinary = List.of(new SourceRoot(generated, ".", SourceScope.MAIN));
        var trusted = List.of(new SourceRoot(generated, ".", SourceScope.GENERATED));
        assertTrue(
                new ProjectScanner(Set.of(), ScanPolicy.all(tmp), discovery)
                        .scanSourceRoots(ordinary)
                        .isEmpty());
        assertEquals(
                List.of(file),
                new ProjectScanner(Set.of(), ScanPolicy.all(tmp), discovery)
                        .scanSourceRoots(trusted));
        var excluded =
                new ScanPolicy(
                        tmp, List.of("**"), List.of("target/generated-sources/**"), Set.of());
        assertTrue(
                new ProjectScanner(Set.of(), excluded, discovery)
                        .scanSourceRoots(trusted)
                        .isEmpty());
    }

    @Test
    void projectInsideBuildDirectoryIsNotExcluded(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("build/workspace"));
        Path source = Files.writeString(project.resolve("Type.java"), "class Type {}");
        assertEquals(
                List.of(source),
                new ProjectScanner(Set.of(), ScanPolicy.all(project)).scan(project));
    }

    @Test
    void customOutputRootsAndBlacklistedGeneratedSourcesStayExcluded(@TempDir Path tmp)
            throws Exception {
        Path generated = Files.createDirectories(tmp.resolve("custom/generated"));
        Files.writeString(generated.resolve("Generated.java"), "class Generated {}");
        Path output = Files.createDirectories(tmp.resolve("custom/classes"));
        Files.writeString(output.resolve("Noise.java"), "class Noise {}");
        var policy =
                new ScanPolicy(tmp, List.of("**"), List.of("custom/generated/**"), Set.of())
                        .excluding(List.of(output));
        assertTrue(new ProjectScanner(Set.of(), policy).scan(tmp).isEmpty());
        var root = new SourceRoot(generated, ".", SourceScope.GENERATED, SourceScope.TEST);
        assertEquals(
                SourceScope.TEST,
                SourceIdentityResolver.fromRoots(tmp, List.of(root))
                        .compilationScope(generated.resolve("Generated.java")));
    }

    @Test
    void scan_skipsDefaultExcludes(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path tgt = Files.createDirectories(tmp.resolve("target"));
        Files.writeString(src.resolve("Y.java"), "class Y {}");
        Files.writeString(tgt.resolve("X.java"), "class X {}");

        ProjectScanner scanner = new ProjectScanner();
        List<Path> files = scanner.scan(tmp);

        assertEquals(1, files.size(), "expected only Y.java; got " + files);
        assertEquals("Y.java", files.get(0).getFileName().toString());
    }

    @Test
    void scan_appliesCustomExcludes(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path foo = Files.createDirectories(tmp.resolve("foo"));
        Files.writeString(src.resolve("Y.java"), "class Y {}");
        Files.writeString(foo.resolve("Z.java"), "class Z {}");

        ProjectScanner scanner = new ProjectScanner(Set.of("foo"));
        List<Path> files = scanner.scan(tmp);

        assertEquals(1, files.size(), "expected only Y.java; got " + files);
        assertEquals("Y.java", files.get(0).getFileName().toString());
    }

    @Test
    void scan_ignoresSymlinks(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("Y.java"), "class Y {}");
        try {
            Files.createSymbolicLink(tmp.resolve("loop"), tmp);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return;
        }

        ProjectScanner scanner = new ProjectScanner();
        List<Path> files = scanner.scan(tmp);

        long count =
                files.stream().filter(p -> p.getFileName().toString().equals("Y.java")).count();
        assertEquals(1, count, "Y.java seen multiple times via symlink: " + files);
    }

    @Test
    void scan_skipsExplicitGeneratedSourceRootUnderTarget(@TempDir Path tmp) throws Exception {
        Path generated =
                Files.createDirectories(tmp.resolve("module/target/generated-sources/annotations"));
        Files.writeString(generated.resolve("Generated.java"), "class Generated {}");

        List<Path> files = new ProjectScanner().scan(generated);
        assertTrue(files.isEmpty(), "target generated sources must not be scanned; got " + files);
    }

    @Test
    void scan_trustsResolvedGeneratedRootWithoutOpeningTargetTree(@TempDir Path tmp)
            throws Exception {
        Path generated =
                Files.createDirectories(tmp.resolve("module/target/generated-sources/annotations"));
        Path source = generated.resolve("Generated.java");
        Files.writeString(source, "class Generated {}");
        Files.writeString(tmp.resolve("module/target/Noise.java"), "class Noise {}");

        SourceRoot root = new SourceRoot(generated, "module", SourceScope.GENERATED);
        List<Path> files = new ProjectScanner().scanSourceRoots(List.of(root));

        assertEquals(List.of(source), files);
    }

    @Test
    void scanPolicyAppliesProjectRelativeIncludeThenExclude(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("src/main/java/p"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Path keep = main.resolve("Keep.java");
        Files.writeString(keep, "class Keep {}");
        Files.writeString(main.resolve("DropIT.java"), "class DropIT {}");
        Files.writeString(other.resolve("Outside.java"), "class Outside {}");

        ScanPolicy policy =
                new ScanPolicy(tmp, List.of("src/**/*.java"), List.of("**/*IT.java"), Set.of());
        List<Path> files = new ProjectScanner(Set.of(), policy).scan(tmp);

        assertEquals(List.of(keep), files);
    }

    @Test
    void scanPolicyFingerprintIsOrderIndependent(@TempDir Path tmp) {
        SourceRoot root = new SourceRoot(tmp.resolve("src"), "app", SourceScope.MAIN);
        ScanPolicy first =
                new ScanPolicy(
                        tmp,
                        List.of("b/**", "a/**"),
                        List.of("**/B.java", "**/A.java"),
                        Set.of("foo"));
        ScanPolicy second =
                new ScanPolicy(
                        tmp,
                        List.of("a/**", "b/**"),
                        List.of("**/A.java", "**/B.java"),
                        Set.of("foo"));

        assertEquals(
                first.fingerprint(List.of(root), List.of(SourceScope.MAIN)),
                second.fingerprint(List.of(root), List.of(SourceScope.MAIN)));
    }
}
