package com.anatomist.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves each indexed file to a stable project-relative module and source scope. */
public final class SourceIdentityResolver {

    private final Path projectRoot;
    private final List<SourceRoot> roots;
    private final java.util.Map<Path, SourceRoot> exact = new java.util.HashMap<>();

    public SourceIdentityResolver(Path projectRoot, List<Path> sourcePaths) {
        this(projectRoot, inferRoots(projectRoot, sourcePaths), true);
    }

    private SourceIdentityResolver(Path projectRoot, List<SourceRoot> roots, boolean ignored) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.roots =
                roots.stream()
                        .sorted(
                                Comparator.comparingInt((SourceRoot r) -> r.path().getNameCount())
                                        .reversed())
                        .toList();
        for (SourceRoot root : this.roots) exact.putIfAbsent(root.path(), root);
    }

    public static SourceIdentityResolver fromRoots(Path projectRoot, List<SourceRoot> roots) {
        return new SourceIdentityResolver(projectRoot, roots, true);
    }

    private long calls, hits, scans;
    public record LookupStats(long calls, long exactHits, long scannedRoots) {}
    public LookupStats statistics() { return new LookupStats(calls, hits, scans); }

    public SourceIdentity resolve(String sourceFile) {
        calls++;
        if (sourceFile == null || sourceFile.isBlank())
            return new SourceIdentity(".", SourceScope.MAIN);
        Path file = Path.of(sourceFile);
        if (!file.isAbsolute()) file = projectRoot.resolve(file);
        Path normalized = file.toAbsolutePath().normalize();
        SourceRoot direct = exact.get(normalized);
        if (direct != null) { hits++; return new SourceIdentity(direct.module(), direct.scope()); }
        for (SourceRoot root : roots) {
            scans++;
            if (normalized.startsWith(root.path())) {
                return new SourceIdentity(root.module(), root.scope());
            }
        }
        return inferIdentity(projectRoot, normalized);
    }

    public SourceScope compilationScope(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        SourceRoot direct = exact.get(normalized);
        if (direct != null) return direct.compilationScope();
        for (SourceRoot root : roots)
            if (normalized.startsWith(root.path())) return root.compilationScope();
        return resolve(path.toString()).scope() == SourceScope.TEST
                ? SourceScope.TEST
                : SourceScope.MAIN;
    }

    public List<SourceRoot> roots() {
        return roots;
    }

    public static List<SourceRoot> inferRoots(Path projectRoot, List<Path> sourcePaths) {
        List<SourceRoot> out = new ArrayList<>();
        if (sourcePaths == null) return out;
        Path root = projectRoot.toAbsolutePath().normalize();
        for (Path sourcePath : sourcePaths) {
            Path normalized = sourcePath.toAbsolutePath().normalize();
            SourceIdentity identity = inferIdentity(root, normalized);
            out.add(new SourceRoot(normalized, identity.module(), identity.scope()));
        }
        return out;
    }

    private static SourceIdentity inferIdentity(Path projectRoot, Path path) {
        String rel;
        try {
            rel = projectRoot.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            rel = path.toString().replace('\\', '/');
        }
        String[] markers = {
            "/src/main/java",
            "/src/test/java",
            "/src/main/resources",
            "/src/test/resources",
            "/target/generated-sources/",
            "/target/generated-test-sources/",
            "/build/generated/"
        };
        SourceScope scope =
                rel.contains("/src/test/java")
                                || rel.startsWith("src/test/java")
                                || rel.contains("/src/test/resources")
                                || rel.startsWith("src/test/resources")
                        ? SourceScope.TEST
                        : rel.contains("/target/generated-sources/")
                                        || rel.startsWith("target/generated-sources/")
                                        || rel.contains("/target/generated-test-sources/")
                                        || rel.startsWith("target/generated-test-sources/")
                                        || rel.contains("/build/generated/")
                                        || rel.startsWith("build/generated/")
                                ? SourceScope.GENERATED
                                : SourceScope.MAIN;
        String module = ".";
        String padded = "/" + rel;
        int marker = Integer.MAX_VALUE;
        for (String candidate : markers) {
            int i = padded.indexOf(candidate);
            if (i >= 0 && i < marker) marker = i;
        }
        if (marker != Integer.MAX_VALUE) {
            String prefix = marker <= 1 ? "" : padded.substring(1, marker);
            module = prefix.isBlank() ? "." : prefix;
        } else if (!rel.isBlank()) {
            module = rel;
        }
        return new SourceIdentity(module, scope);
    }
}
