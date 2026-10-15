package com.anatomist.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical file-eligibility policy shared by full and incremental indexing. */
public final class ScanPolicy {
    public enum Mode {
        INDEXED,
        TEXT_ONLY,
        EXCLUDED
    }

    public static final String VERSION = "anatomist-scan-policy-v2";
    public static final String HARD_EXCLUDE_POLICY_VERSION = "v2";

    private final Path projectRoot;
    private final List<String> includes;
    private final List<String> excludes;
    private final List<PathGlob> includeGlobs;
    private final List<PathGlob> excludeGlobs;
    private final List<String> textOnly;
    private final List<PathGlob> textOnlyGlobs;
    private final Set<String> excludedDirNames;

    public ScanPolicy(
            Path projectRoot,
            List<String> includes,
            List<String> excludes,
            Set<String> excludedDirNames) {
        this(projectRoot, includes, excludes, List.of(), excludedDirNames);
    }

    public ScanPolicy(
            Path projectRoot,
            List<String> includes,
            List<String> excludes,
            List<String> textOnly,
            Set<String> excludedDirNames) {
        this.projectRoot = realIfPresent(projectRoot);
        this.includes =
                normalized(includes == null || includes.isEmpty() ? List.of("**") : includes);
        this.excludes = normalized(excludes == null ? List.of() : excludes);
        this.includeGlobs = this.includes.stream().map(PathGlob::new).toList();
        this.excludeGlobs = this.excludes.stream().map(PathGlob::new).toList();
        this.textOnly = normalized(textOnly);
        this.textOnlyGlobs = this.textOnly.stream().map(PathGlob::new).toList();
        this.excludedDirNames = excludedDirNames == null ? Set.of() : Set.copyOf(excludedDirNames);
    }

    public static ScanPolicy all(Path projectRoot) {
        return new ScanPolicy(projectRoot, List.of("**"), List.of(), Set.of());
    }

    Path relativePath(Path path) {
        return projectRoot.relativize(normalizedFile(path));
    }

    public boolean includes(Path file) {
        return classify(file) == Mode.INDEXED;
    }

    /** Current-file discovery may select paths outside source includes, but never exclusions. */
    public boolean excludes(Path file) {
        Path absolute = normalizedFile(file);
        if (!absolute.startsWith(projectRoot)) return true;
        String relative = projectRoot.relativize(absolute).toString().replace('\\', '/');
        return containsExcludedDir(relative)
                || internal(relative)
                || excludeGlobs.stream().anyMatch(glob -> glob.matches(relative));
    }

    /** Project-relative matching without resolving live filesystem objects. */
    public boolean excludesRelative(String relative) {
        return containsExcludedDir(relative)
                || internal(relative)
                || excludeGlobs.stream().anyMatch(glob -> glob.matches(relative));
    }

    public boolean textOnlyRelative(String relative) {
        return textOnlyGlobs.stream().anyMatch(glob -> glob.matches(relative));
    }

    public boolean textOnly(Path file) {
        Path absolute = normalizedFile(file);
        return absolute.startsWith(projectRoot)
                && textOnlyGlobs.stream()
                        .anyMatch(
                                glob ->
                                        glob.matches(
                                                projectRoot
                                                        .relativize(absolute)
                                                        .toString()
                                                        .replace('\\', '/')));
    }

    public boolean mayContainTextOnly(Path directory) {
        Path absolute = normalizedFile(directory);
        if (!absolute.startsWith(projectRoot)) return false;
        String relative = projectRoot.relativize(absolute).toString().replace('\\', '/');
        for (String pattern : textOnly) {
            StringBuilder prefix = new StringBuilder();
            for (String part : pattern.split("/")) {
                if (part.contains("*") || part.contains("?")) break;
                if (!prefix.isEmpty()) prefix.append('/');
                prefix.append(part);
            }
            String literal = prefix.toString();
            if (relative.isEmpty()
                    || literal.isEmpty()
                    || literal.equals(relative)
                    || literal.startsWith(relative + "/")
                    || relative.startsWith(literal + "/")) return true;
        }
        return false;
    }

    public Mode classify(Path file) {
        Path absolute = normalizedFile(file);
        String candidate;
        try {
            candidate = projectRoot.relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            candidate = absolute.toString().replace('\\', '/');
        }
        if (containsExcludedDir(candidate) || internal(candidate)) return Mode.EXCLUDED;
        String logicalPath = candidate;
        boolean included = includeGlobs.stream().anyMatch(glob -> glob.matches(logicalPath));
        if (excludeGlobs.stream().anyMatch(glob -> glob.matches(logicalPath))) return Mode.EXCLUDED;
        if (textOnly(file)) return Mode.TEXT_ONLY;
        return included ? Mode.INDEXED : Mode.EXCLUDED;
    }

    public boolean internal(Path path) {
        Path absolute = normalizedFile(path);
        return absolute.startsWith(projectRoot)
                && internal(projectRoot.relativize(absolute).toString());
    }

    public static boolean internal(String path) {
        String normalized = "/" + path.replace('\\', '/') + "/";
        if (normalized.contains("/.diorama/cache/")) return true;
        for (String part : path.replace('\\', '/').split("/"))
            if (part.equals(".git") || part.equals(".anatomist")) return true;
        return false;
    }

    private Path normalizedFile(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(projectRoot) ? absolute : realIfPresent(absolute);
    }

    private static Path realIfPresent(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException absent) {
            return path.toAbsolutePath().normalize();
        }
    }

    public ScanPolicy support() {
        return new ScanPolicy(projectRoot, List.of("**"), excludes, textOnly, excludedDirNames);
    }

    public ScanPolicy excluding(List<Path> directories) {
        List<String> patterns = new ArrayList<>(excludes);
        for (Path directory : directories) {
            Path absolute = normalizedFile(directory);
            if (absolute.startsWith(projectRoot)) {
                String relative = projectRoot.relativize(absolute).toString().replace('\\', '/');
                patterns.add(relative);
                patterns.add(relative + "/**");
            }
        }
        return new ScanPolicy(projectRoot, includes, patterns, textOnly, excludedDirNames);
    }

    public List<String> includes() {
        return includes;
    }

    public List<String> excludes() {
        return excludes;
    }

    public List<String> textOnlyPatterns() {
        return textOnly;
    }

    public Set<String> excludedDirNames() {
        return excludedDirNames;
    }

    public String canonical(List<SourceRoot> roots, List<SourceScope> scopes) {
        StringBuilder out = new StringBuilder(VERSION + "\n");
        out.append("hard=").append(HARD_EXCLUDE_POLICY_VERSION).append('\n');
        for (SourceScope scope : scopes.stream().sorted().toList()) {
            out.append("scope=").append(scope).append('\n');
        }
        for (SourceRoot root :
                roots.stream()
                        .sorted(
                                Comparator.comparing(
                                        r -> r.module() + "@" + r.scope() + "=" + r.path()))
                        .toList()) {
            out.append("root=")
                    .append(root.module())
                    .append('@')
                    .append(root.scope())
                    .append('=')
                    .append(root.path().toAbsolutePath().normalize())
                    .append('\n');
            out.append("compilation-root=")
                    .append(root.module())
                    .append('@')
                    .append(root.compilationScope())
                    .append('=')
                    .append(root.path().toAbsolutePath().normalize())
                    .append('\n');
        }
        includes.forEach(value -> out.append("include=").append(value).append('\n'));
        excludes.forEach(value -> out.append("exclude=").append(value).append('\n'));
        textOnly.forEach(value -> out.append("text-only=").append(value).append('\n'));
        excludedDirNames.stream()
                .sorted()
                .forEach(value -> out.append("exclude-dir=").append(value).append('\n'));
        return out.toString();
    }

    public String fingerprint(List<SourceRoot> roots, List<SourceScope> scopes) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            canonical(roots, scopes)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean containsExcludedDir(String candidate) {
        if (excludedDirNames.isEmpty()) return false;
        for (String part : candidate.split("/")) {
            if (excludedDirNames.contains(part)) return true;
        }
        return false;
    }

    private static List<String> normalized(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) unique.add(value.replace('\\', '/'));
        List<String> out = new ArrayList<>(unique);
        out.sort(String::compareTo);
        return List.copyOf(out);
    }
}
