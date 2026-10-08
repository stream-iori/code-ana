package com.anatomist.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProjectScanner {

    public static final Set<String> DEFAULT_EXCLUDES =
            Set.of("target", "build", ".gradle", ".git", ".idea", "node_modules");

    private final Set<String> excludedDirs;
    private final ScanPolicy scanPolicy;
    private final JavaSourceDiscovery discovery;
    private long rootWalks;

    long rootWalks() {
        return rootWalks;
    }

    public ProjectScanner() {
        this(Collections.emptySet(), null);
    }

    public ProjectScanner(Set<String> additionalExcludes) {
        this(additionalExcludes, null);
    }

    public ProjectScanner(Set<String> additionalExcludes, ScanPolicy scanPolicy) {
        this(additionalExcludes, scanPolicy, null);
    }

    public ProjectScanner(
            Set<String> additionalExcludes, ScanPolicy scanPolicy, JavaSourceDiscovery discovery) {
        Set<String> merged = new HashSet<>(DEFAULT_EXCLUDES);
        if (additionalExcludes != null) merged.addAll(additionalExcludes);
        this.excludedDirs = Collections.unmodifiableSet(merged);
        this.scanPolicy = scanPolicy;
        this.discovery = discovery;
    }

    public Set<String> excludedDirs() {
        return excludedDirs;
    }

    public List<Path> scan(Path root) {
        return scan(root, false, Set.of(".java"));
    }

    private List<Path> scan(Path root, boolean trustedRoot, Set<String> extensions) {
        return scanRoot(root, trustedRoot, extensions);
    }

    List<Path> scanRoot(Path root, boolean trustedRoot, Set<String> extensions) {
        if (root == null || !Files.isDirectory(root) || internalRoot(root)) {
            return Collections.emptyList();
        }
        if (!trustedRoot && excludedRoot(root)) {
            return Collections.emptyList();
        }
        List<Path> out = new ArrayList<>();
        try {
            rootWalks++;
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            if ((!dir.equals(root) && containsExcludedDir(root.relativize(dir)))
                                    || scanPolicy != null && scanPolicy.excludes(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name =
                                    file.getFileName()
                                            .toString()
                                            .toLowerCase(java.util.Locale.ROOT);
                            if (attrs.isRegularFile()
                                    && extensions.stream().anyMatch(name::endsWith)
                                    && (scanPolicy == null || scanPolicy.includes(file))) {
                                out.add(file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed scanning " + root, e);
        }
        out.sort(
                java.util.Comparator.comparing(
                        path -> path.toAbsolutePath().normalize().toString()));
        return out;
    }

    /**
     * Scan resolved source roots while allowing an explicitly classified generated root to live
     * below Maven's target directory. Ordinary target trees remain excluded.
     */
    public List<Path> scanSourceRoots(List<SourceRoot> roots) {
        return scanSourceRoots(roots, Set.of(".java"));
    }

    public List<Path> scanSourceRoots(List<SourceRoot> roots, Set<String> extensions) {
        Set<Path> unique = new java.util.LinkedHashSet<>();
        if (roots == null) return List.of();
        if (discovery != null
                && (extensions == null
                        || extensions.isEmpty()
                        || extensions.equals(Set.of(".java")))) {
            return discovery.discover(this, scanPolicy, roots);
        }
        Set<String> normalizedExtensions =
                extensions == null || extensions.isEmpty()
                        ? Set.of(".java")
                        : extensions.stream()
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (SourceRoot root : roots) {
            unique.addAll(
                    scan(root.path(), root.scope() == SourceScope.GENERATED, normalizedExtensions));
        }
        List<Path> out = new ArrayList<>(unique);
        out.sort(
                java.util.Comparator.comparing(
                        path -> path.toAbsolutePath().normalize().toString()));
        return out;
    }

    public List<Path> scan(List<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path r : roots) out.addAll(scan(r));
        out.sort(
                java.util.Comparator.comparing(
                        path -> path.toAbsolutePath().normalize().toString()));
        return out;
    }

    /** Enumerate resource candidates under the same exclusion policy as Java sources. */
    public List<Path> scanFiles(Path root, Set<String> extensions) {
        return scan(root, false, extensions);
    }

    private boolean excludedRoot(Path root) {
        if (scanPolicy != null) return containsExcludedName(scanPolicy.relativePath(root));
        if (containsExcludedName(root.normalize().getFileName())) return true;
        String path = root.toString().replace('\\', '/');
        return path.contains("/target/generated-sources/")
                || path.contains("/target/generated-test-sources/");
    }

    private boolean internalRoot(Path root) {
        return scanPolicy == null
                ? ScanPolicy.internal(root.toString())
                : scanPolicy.internal(root);
    }

    private boolean containsExcludedDir(Path path) {
        return ScanPolicy.internal(path.toString()) || containsExcludedName(path);
    }

    private boolean containsExcludedName(Path path) {
        for (Path part : path) {
            if (excludedDirs.contains(part.toString())) return true;
        }
        return false;
    }
}
