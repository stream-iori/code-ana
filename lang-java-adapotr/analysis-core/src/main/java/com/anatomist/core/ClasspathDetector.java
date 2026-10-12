package com.anatomist.core;

import com.anatomist.core.logging.AnatomistLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClasspathDetector {

    static final String MAVEN_JAVA_HOME_ENV = "ANATOMIST_MAVEN_JAVA_HOME";
    private static final int MAVEN_OUTPUT_TAIL_BYTES = 64 * 1024;
    private volatile String lastMavenOutput = "";
    private Path forcedMavenJavaHome;
    private Path fingerprintRoot;
    private String fingerprintStamp;
    private String fingerprintValue;
    private static final Pattern MAVEN_MODULE =
            Pattern.compile("<module\\b[^>]*>\\s*([^<]+?)\\s*</module>", Pattern.CASE_INSENSITIVE);

    /**
     * Filename written by {@code dependency:build-classpath}. Relative (not absolute) on purpose:
     * in a multi-module reactor Maven runs the goal once per module and a relative path lands one
     * file in *each* module's basedir, letting us union the full reactor classpath. An absolute
     * path would be overwritten by every module, leaving only the last one's deps.
     */
    static final String CP_FILE = "anatomist-classpath.txt";

    public List<String> detect(Path projectRoot) {
        return detectResult(projectRoot).entries();
    }

    public ClasspathDetectionResult detectResult(Path projectRoot) {
        if (projectRoot == null || !isMavenProject(projectRoot)) {
            return ClasspathDetectionResult.notRequested();
        }
        List<Path> buildOutputs = detectBuildOutputClasspath(projectRoot);
        List<String> cached = readClasspathCache(projectRoot);
        if (!cached.isEmpty()) {
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(cached);
            buildOutputs.forEach(path -> union.add(path.toString()));
            AnatomistLog.debug(
                    "classpath: cache hit with " + cached.size() + " dependency entries");
            return ClasspathDetectionResult.cacheHit(new ArrayList<>(union), buildOutputs.size());
        }
        // Clear any stragglers from a prior interrupted run so the union is clean.
        deleteClasspathFiles(projectRoot);
        try {
            List<String> mvnArgs =
                    Arrays.asList(
                            "dependency:build-classpath",
                            // scope=test is the widest Maven scope (compile + provided +
                            // runtime + system + test); a code-intelligence index wants to
                            // resolve as many types as possible, and extra jars are nearly
                            // free for AsmTypeSolver.
                            "-DincludeScope=test",
                            // fail-at-end: one unresolvable module (e.g. a legacy
                            // system-scope tools.jar dep that's gone on JDK 9+) must not
                            // zero out the whole reactor's classpath. Good modules still
                            // write their per-module file; we union whatever landed.
                            "-fae",
                            "-q",
                            "-Dmdep.outputFile=" + CP_FILE);
            AnatomistLog.debug(
                    "classpath: running 'mvn " + String.join(" ", mvnArgs) + "' in " + projectRoot);
            int code = runMvn(projectRoot, mvnArgs);
            List<Path> cpFiles = findClasspathFiles(projectRoot);
            if (code != 0
                    && !hasClasspathEntries(cpFiles)
                    && needsLegacyJdkRetry(lastMavenOutput)) {
                Path java8 = findJava8Home();
                if (java8 != null && !java8.equals(selectMavenJavaHome(projectRoot))) {
                    AnatomistLog.debug("classpath: retrying Maven with legacy JDK " + java8);
                    deleteClasspathFiles(projectRoot);
                    forcedMavenJavaHome = java8;
                    try {
                        code = runMvn(projectRoot, mvnArgs);
                    } finally {
                        forcedMavenJavaHome = null;
                    }
                    cpFiles = findClasspathFiles(projectRoot);
                }
            }
            // Union every module's output, preserving first-seen order. Done even
            // on non-zero exit: with -fae the modules that succeeded already wrote
            // their files, and a partial classpath beats an empty one.
            java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
            AnatomistLog.debug(
                    "classpath: mvn exited "
                            + code
                            + "; "
                            + cpFiles.size()
                            + " per-module classpath file(s) written");
            for (Path f : cpFiles) {
                String content = Files.readString(f, StandardCharsets.UTF_8).trim();
                if (content.isEmpty()) continue;
                int countBefore = union.size();
                for (String entry : content.split(java.io.File.pathSeparator)) {
                    String t = entry.trim();
                    if (!t.isEmpty()) union.add(t);
                }
                AnatomistLog.debug(
                        "classpath:   "
                                + projectRoot.relativize(f.getParent())
                                + " contributed "
                                + (union.size() - countBefore)
                                + " new jar(s)");
            }
            if (code != 0) {
                warn(
                        "mvn dependency:build-classpath exited with code "
                                + code
                                + "; proceeding with classpath from the "
                                + union.size()
                                + " entries that did resolve (some modules failed)"
                                + failureOutputSuffix());
            }
            if (code == 0 && !union.isEmpty()) writeClasspathCache(projectRoot, union);
            for (Path output : buildOutputs) {
                union.add(output.toString());
            }
            AnatomistLog.debug("classpath: union total = " + union.size() + " entrie(s)");
            if (code == 0) {
                return new ClasspathDetectionResult(
                        ClasspathDetectionResult.Status.FULL,
                        new ArrayList<>(union),
                        code,
                        cpFiles.size(),
                        buildOutputs.size(),
                        null,
                        List.of());
            }
            String sample = boundedMavenSample(lastMavenOutput);
            String diagnosticCode = union.isEmpty() ? "CLASSPATH_UNAVAILABLE" : "CLASSPATH_PARTIAL";
            String message =
                    union.isEmpty()
                            ? "Maven classpath detection failed and produced no usable entries."
                            : "Maven classpath detection was partial; successful module outputs were retained.";
            IndexDiagnostic diagnostic =
                    new IndexDiagnostic(
                            "warning",
                            diagnosticCode,
                            "CLASSPATH",
                            null,
                            null,
                            null,
                            null,
                            1,
                            sample == null ? message : message + " " + sample);
            return new ClasspathDetectionResult(
                    union.isEmpty()
                            ? ClasspathDetectionResult.Status.UNAVAILABLE
                            : ClasspathDetectionResult.Status.PARTIAL,
                    new ArrayList<>(union),
                    code,
                    cpFiles.size(),
                    buildOutputs.size(),
                    sample,
                    List.of(diagnostic));
        } catch (IOException | InterruptedException e) {
            warn(
                    "mvn classpath detection failed ("
                            + e.getMessage()
                            + "), proceeding with empty classpath");
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            String sample = boundedMavenSample(e.getMessage());
            List<String> outputEntries = buildOutputs.stream().map(Path::toString).toList();
            return new ClasspathDetectionResult(
                    outputEntries.isEmpty()
                            ? ClasspathDetectionResult.Status.UNAVAILABLE
                            : ClasspathDetectionResult.Status.PARTIAL,
                    outputEntries,
                    null,
                    0,
                    buildOutputs.size(),
                    sample,
                    List.of(
                            new IndexDiagnostic(
                                    "warning",
                                    "CLASSPATH_UNAVAILABLE",
                                    "CLASSPATH",
                                    null,
                                    null,
                                    null,
                                    null,
                                    1,
                                    "Maven classpath detection failed. "
                                            + (sample == null ? "" : sample))));
        } finally {
            deleteClasspathFiles(projectRoot);
        }
    }

    private static String boundedMavenSample(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        int start = Math.max(0, value.length() - 500);
        value = value.substring(start);
        StringBuilder safe = new StringBuilder(value.length());
        int cursor = 0;
        while (cursor < value.length()) {
            int scheme = value.indexOf("://", cursor);
            if (scheme < 0) {
                safe.append(value, cursor, value.length());
                break;
            }
            safe.append(value, cursor, scheme + 3);
            int credentialsEnd = value.indexOf('@', scheme + 3);
            int boundary = firstBoundary(value, scheme + 3);
            if (credentialsEnd >= 0 && (boundary < 0 || credentialsEnd < boundary)) {
                safe.append("***@");
                cursor = credentialsEnd + 1;
            } else {
                cursor = scheme + 3;
            }
        }
        return safe.toString();
    }

    private static int firstBoundary(String value, int start) {
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\') return i;
        }
        return -1;
    }

    private static boolean hasClasspathEntries(List<Path> files) {
        for (Path file : files) {
            try {
                if (!Files.readString(file, StandardCharsets.UTF_8).isBlank()) return true;
            } catch (IOException ignore) {
                // Treat unreadable output as unusable and allow the retry.
            }
        }
        return false;
    }

    private List<String> readClasspathCache(Path projectRoot) {
        Path file = classpathCacheFile(projectRoot);
        if (file == null || !Files.isRegularFile(file)) return List.of();
        try (var ignoredLock =
                com.anatomist.store.IndexLock.forRead(file.getParent().resolve("maintenance"))) {
            List<String> entries =
                    Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .toList();
            if (entries.isEmpty()) return List.of();
            boolean valid = entries.stream().map(Path::of).allMatch(Files::isRegularFile);
            if (!valid) {
                Files.deleteIfExists(file);
                return List.of();
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void writeClasspathCache(Path projectRoot, java.util.Set<String> entries) {
        Path file = classpathCacheFile(projectRoot);
        if (file == null) return;
        Path temporary = null;
        try (var ignoredLock =
                com.anatomist.store.IndexLock.forRead(file.getParent().resolve("maintenance"))) {
            try {
                Files.createDirectories(file.getParent());
                temporary = Files.createTempFile(file.getParent(), "classpath-", ".tmp");
                Files.write(temporary, entries, StandardCharsets.UTF_8);
                try {
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (temporary != null) Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException e) {
            AnatomistLog.debug("classpath: failed to write cache: " + e.getMessage());
        }
    }

    private Path classpathCacheFile(Path projectRoot) {
        String key = classpathInputFingerprint(projectRoot);
        Path root = classpathCacheRoot();
        return key == null || root == null ? null : root.resolve(key + ".txt");
    }

    protected Path classpathCacheRoot() {
        String configured = systemProperty("anatomist.classpath.cache.dir");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String anatomistHome = environment("ANATOMIST_HOME");
        if (anatomistHome != null && !anatomistHome.isBlank()) {
            return Path.of(anatomistHome, "cache", "classpath");
        }
        String home = systemProperty("user.home");
        return home == null || home.isBlank()
                ? null
                : Path.of(home, ".anatomist", "cache", "classpath");
    }

    /**
     * Stable fingerprint of Maven inputs that determine the detected classpath. It deliberately
     * excludes Java source files so normal incremental indexing can reuse its parser environment
     * without invoking Maven.
     */
    public String classpathInputFingerprint(Path projectRoot) {
        try {
            Path normalized = projectRoot.toAbsolutePath().normalize();
            List<Path> inputs = classpathInputFiles(normalized);
            String stamp = fingerprintStamp(inputs);
            if (normalized.equals(fingerprintRoot) && stamp.equals(fingerprintStamp)) {
                return fingerprintValue;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(normalized.toString().getBytes(StandardCharsets.UTF_8));
            String override = environment(MAVEN_JAVA_HOME_ENV);
            if (override != null) digest.update(override.getBytes(StandardCharsets.UTF_8));
            for (Path input : inputs) {
                String identity =
                        input.startsWith(normalized)
                                ? normalized.relativize(input).toString()
                                : input.toString();
                digest.update(identity.getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(input));
            }
            fingerprintRoot = normalized;
            fingerprintStamp = stamp;
            fingerprintValue = HexFormat.of().formatHex(digest.digest());
            return fingerprintValue;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Maven classpath inputs without walking target/, source trees, or unrelated nested projects.
     */
    private List<Path> classpathInputFiles(Path projectRoot) throws IOException {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        Path rootPom = projectRoot.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) pending.add(rootPom);
        while (!pending.isEmpty()) {
            Path pom = pending.removeFirst().toAbsolutePath().normalize();
            if (!pom.startsWith(projectRoot) || !Files.isRegularFile(pom) || !files.add(pom))
                continue;
            String xml = Files.readString(pom, StandardCharsets.UTF_8);
            Matcher modules = MAVEN_MODULE.matcher(xml);
            while (modules.find()) {
                String module = modules.group(1).trim();
                if (module.isEmpty() || module.contains("${")) continue;
                Path modulePom =
                        pom.getParent()
                                .resolve(module)
                                .resolve("pom.xml")
                                .toAbsolutePath()
                                .normalize();
                if (modulePom.startsWith(projectRoot) && Files.isRegularFile(modulePom)) {
                    pending.addLast(modulePom);
                }
            }
        }
        for (String relative :
                List.of(".mvn/maven.config", ".mvn/jvm.config", ".mvn/extensions.xml")) {
            Path input = projectRoot.resolve(relative);
            if (Files.isRegularFile(input)) files.add(input.toAbsolutePath().normalize());
        }
        String home = systemProperty("user.home");
        if (home != null) {
            Path settings = Path.of(home, ".m2", "settings.xml").toAbsolutePath().normalize();
            if (Files.isRegularFile(settings)) files.add(settings);
        }
        return files.stream().sorted().toList();
    }

    private static String fingerprintStamp(List<Path> inputs) throws IOException {
        StringBuilder stamp = new StringBuilder();
        for (Path input : inputs) {
            BasicFileAttributes attributes = Files.readAttributes(input, BasicFileAttributes.class);
            stamp.append(input)
                    .append('\n')
                    .append(attributes.size())
                    .append(':')
                    .append(attributes.lastModifiedTime())
                    .append('\n');
        }
        return stamp.toString();
    }

    /**
     * Locate every per-module {@link #CP_FILE} under the reactor, sorted for deterministic union
     * order.
     */
    private List<Path> findClasspathFiles(Path projectRoot) {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(
                            p ->
                                    p.getFileName() != null
                                            && CP_FILE.equals(p.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            warn("failed to collect classpath files under " + projectRoot + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Remove generated {@link #CP_FILE} files so they don't pollute the work tree. */
    private void deleteClasspathFiles(Path projectRoot) {
        for (Path f : findClasspathFiles(projectRoot)) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException ignore) {
                // best-effort cleanup
            }
        }
    }

    public List<Path> detectSourcePaths(Path projectRoot) {
        return detectSourcePaths(projectRoot, false);
    }

    /**
     * Discover source roots. When {@code includeTests} is true, each module's {@code src/test/java}
     * is collected alongside {@code src/main/java} — and test-only modules (no {@code
     * src/main/java} of their own) are picked up too. Within a module the main root sorts before
     * its test root.
     */
    public List<Path> detectSourcePaths(Path projectRoot, boolean includeTests) {
        if (projectRoot == null) return Collections.emptyList();
        if (isMavenProject(projectRoot)) {
            Path mainSrc = projectRoot.resolve("src/main/java");
            Path testSrc = projectRoot.resolve("src/test/java");
            boolean hasMain = Files.isDirectory(mainSrc);
            boolean hasTest = Files.isDirectory(testSrc);
            if (hasMain) {
                List<Path> out = new ArrayList<>();
                out.add(mainSrc);
                out.addAll(findGeneratedSourceRoots(projectRoot));
                if (includeTests && hasTest) out.add(testSrc);
                return out;
            }
            // Multi-module reactor: no src/main/java at the root, so collect
            // every module's source root(s) instead.
            List<Path> modules = findModuleSourceRoots(projectRoot, includeTests);
            if (!modules.isEmpty()) return modules;
            return Collections.emptyList();
        }
        return Files.isDirectory(projectRoot) ? List.of(projectRoot) : Collections.emptyList();
    }

    /**
     * Walk the reactor for every {@code src/main/java} (and, when requested, {@code src/test/java})
     * directory, skipping build-output and VCS trees. Returns sorted, deduplicated roots.
     */
    private List<Path> findModuleSourceRoots(Path projectRoot, boolean includeTests) {
        Path mainTail = Path.of("src", "main", "java");
        Path testTail = Path.of("src", "test", "java");
        try {
            java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
            Files.walkFileTree(
                    projectRoot,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            if (!dir.equals(projectRoot)
                                    && isUnderExcludedDir(projectRoot.relativize(dir))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (dir.endsWith(mainTail)
                                    || (includeTests && dir.endsWith(testTail))) {
                                roots.add(dir);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
            roots.addAll(findGeneratedSourceRoots(projectRoot));
            return roots.stream().sorted().toList();
        } catch (IOException e) {
            warn(
                    "failed to scan for module source roots under "
                            + projectRoot
                            + ": "
                            + e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Path> detectBuildOutputClasspath(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Collections.emptyList();
        Path classesTail = Path.of("target", "classes");
        Path testClassesTail = Path.of("target", "test-classes");
        try {
            List<Path> outputs = new ArrayList<>();
            Files.walkFileTree(
                    projectRoot,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            Path relative = projectRoot.relativize(dir);
                            if (containsHardExcludedDir(relative))
                                return FileVisitResult.SKIP_SUBTREE;
                            if (dir.endsWith(classesTail) || dir.endsWith(testClassesTail)) {
                                if (!isNestedUnderBuildOutput(relative)) outputs.add(dir);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
            return outputs.stream().sorted().toList();
        } catch (IOException e) {
            warn(
                    "failed to scan for build output classpath under "
                            + projectRoot
                            + ": "
                            + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Path> findGeneratedSourceRoots(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return Collections.emptyList();
        try {
            List<Path> roots = new ArrayList<>();
            Files.walkFileTree(
                    projectRoot,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            if (dir.equals(projectRoot)) return FileVisitResult.CONTINUE;
                            Path relative = projectRoot.relativize(dir);
                            if (containsHardExcludedDir(relative))
                                return FileVisitResult.SKIP_SUBTREE;
                            Path parent = dir.getParent();
                            String name =
                                    dir.getFileName() == null ? "" : dir.getFileName().toString();
                            if (parent != null
                                    && "target".equals(parent.getFileName().toString())
                                    && !"generated-sources".equals(name)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (parent != null
                                    && "generated-sources"
                                            .equals(parent.getFileName().toString())) {
                                Path grand = parent.getParent();
                                if (grand != null
                                        && "target".equals(grand.getFileName().toString())) {
                                    if (isGeneratedJavaSourceRoot(dir)) roots.add(dir);
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
            roots.sort(Path::compareTo);
            return roots;
        } catch (IOException e) {
            warn(
                    "failed to scan for generated source roots under "
                            + projectRoot
                            + ": "
                            + e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isGeneratedJavaSourceRoot(Path p) {
        String s = p.toString().replace('\\', '/');
        if (!s.contains("/target/generated-sources/")) return false;
        Path parent = p.getParent();
        if (parent == null || !"generated-sources".equals(parent.getFileName().toString()))
            return false;
        Path grand = parent.getParent();
        if (grand == null || !"target".equals(grand.getFileName().toString())) return false;
        try (Stream<Path> children = Files.walk(p)) {
            return children.anyMatch(
                    child ->
                            Files.isRegularFile(child)
                                    && child.getFileName().toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isNestedUnderBuildOutput(Path relative) {
        int targetClassesSeen = 0;
        String prev = "";
        for (Path part : relative) {
            String name = part.toString();
            if ("classes".equals(name) && "target".equals(prev)) targetClassesSeen++;
            if ("test-classes".equals(name) && "target".equals(prev)) targetClassesSeen++;
            if (targetClassesSeen > 1) return true;
            prev = name;
        }
        return false;
    }

    private static boolean isUnderExcludedDir(Path relative) {
        if (ScanPolicy.internal(relative.toString())) return true;
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("target")
                    || name.equals("build")
                    || name.equals(".git")
                    || name.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHardExcludedDir(Path relative) {
        if (ScanPolicy.internal(relative.toString())) return true;
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("build")
                    || name.equals(".gradle")
                    || name.equals(".git")
                    || name.equals(".idea")
                    || name.equals("node_modules")) return true;
        }
        return false;
    }

    protected boolean isMavenProject(Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"));
    }

    /**
     * Detect the highest Java language version declared across every pom.xml in the project tree,
     * reading {@code <maven.compiler.source>} (preferred) or {@code <java.version>} property.
     * Returns {@link OptionalInt#empty()} when no pom.xml is present or no version is declared.
     */
    public OptionalInt detectJavaVersion(Path projectRoot) {
        JavaVersionDetection detection = detectJavaVersionDetailed(projectRoot);
        return detection.found() ? OptionalInt.of(detection.version()) : OptionalInt.empty();
    }

    public JavaVersionDetection detectJavaVersionDetailed(Path projectRoot) {
        return BuildJavaVersionDetector.detect(projectRoot);
    }

    /** Test seam — subclasses may override to avoid spawning a real Maven process. */
    protected int runMvn(Path workingDir, List<String> args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(mavenExecutable());
        cmd.addAll(args);
        java.util.Map<String, String> environment = new java.util.HashMap<>();
        Path javaHome = selectMavenJavaHome(workingDir);
        if (javaHome != null) {
            environment.put("JAVA_HOME", javaHome.toString());
            environment.put(
                    "PATH",
                    javaHome.resolve("bin")
                            + java.io.File.pathSeparator
                            + System.getenv().getOrDefault("PATH", ""));
            AnatomistLog.debug("classpath: Maven JAVA_HOME=" + javaHome);
        }
        var tail = new com.anatomist.core.process.ProcessRunner.Tail(MAVEN_OUTPUT_TAIL_BYTES);
        var spec =
                new com.anatomist.core.process.ProcessRunner.Spec(
                        cmd,
                        workingDir,
                        environment,
                        java.time.Duration.ofSeconds(300),
                        true,
                        com.anatomist.core.process.SnapshotProcessReceipts.lifecycle());
        var result =
                com.anatomist.core.process.ProcessRunner.run(
                        spec,
                        null,
                        input -> {
                            input.transferTo(tail);
                            return com.anatomist.core.process.ProcessRunner.Read.complete(null);
                        });
        setLastMavenOutput(tail.text());
        if (result.outcome() instanceof com.anatomist.core.process.ProcessRunner.Failed
                || result.exitCode() != 0)
            AnatomistLog.diagnostic(
                    "Maven classpath: " + result.diagnostics().summary(), tail.text());
        try {
            result.requireValue();
        } catch (com.anatomist.core.process.ProcessRunner.Failure failure) {
            if (failure.kind() == com.anatomist.core.process.ProcessRunner.Kind.CANCELLED) {
                var interrupted = new InterruptedException(failure.getMessage());
                interrupted.initCause(failure);
                throw interrupted;
            }
            throw failure;
        }
        return result.exitCode();
    }

    protected void setLastMavenOutput(String output) {
        lastMavenOutput = output == null ? "" : output;
    }

    /** Select a Maven runtime without changing anatomist's own JVM. */
    protected Path selectMavenJavaHome(Path projectRoot) {
        if (forcedMavenJavaHome != null) return forcedMavenJavaHome;
        String override = environment(MAVEN_JAVA_HOME_ENV);
        Path explicit = usableJavaHome(override, false);
        if (explicit != null) return explicit;
        if (override != null && !override.isBlank()) {
            warn(MAVEN_JAVA_HOME_ENV + " is not a usable JDK home: " + override);
        }

        OptionalInt projectVersion = detectJavaVersion(projectRoot);
        boolean legacyToolsJar = requiresLegacyToolsJar(projectRoot);
        if (!legacyToolsJar && (projectVersion.isEmpty() || projectVersion.getAsInt() > 8))
            return null;

        return findJava8Home();
    }

    private Path findJava8Home() {
        Path current = usableJavaHome(systemProperty("java.home"), true);
        if (current != null) return current;
        Path envJdk8 = usableJavaHome(environment("JAVA8_HOME"), true);
        if (envJdk8 != null) return envJdk8;
        Path macJdk8 = usableJavaHome(discoverMacJava8Home(), true);
        return macJdk8 != null ? macJdk8 : discoverSdkmanJava8Home();
    }

    private static boolean needsLegacyJdkRetry(String output) {
        if (output == null || output.isBlank()) return false;
        return output.contains("tools.jar") || output.contains("jdk.tools:jdk.tools");
    }

    /** Legacy system-scope jdk.tools dependencies require a JDK 8 Maven runtime. */
    private boolean requiresLegacyToolsJar(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return false;
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            for (Path pom :
                    walk.filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.getFileName() != null
                                                    && "pom.xml"
                                                            .equals(path.getFileName().toString()))
                            .toList()) {
                try {
                    String xml = Files.readString(pom, StandardCharsets.UTF_8);
                    if (xml.contains("tools.jar")
                            || xml.contains("<artifactId>jdk.tools</artifactId>")) {
                        return true;
                    }
                } catch (IOException ignore) {
                    // Continue scanning other module poms.
                }
            }
        } catch (IOException ignore) {
            return false;
        }
        return false;
    }

    protected String environment(String name) {
        return System.getenv(name);
    }

    protected String mavenExecutable() {
        return "mvn";
    }

    protected String systemProperty(String name) {
        return System.getProperty(name);
    }

    protected String discoverMacJava8Home() {
        Path command = Path.of("/usr/libexec/java_home");
        if (!Files.isExecutable(command)) return null;
        var result =
                com.anatomist.core.process.ProcessRunner.run(
                        new com.anatomist.core.process.ProcessRunner.Spec(
                                List.of(command.toString(), "-v", "1.8"),
                                null,
                                java.time.Duration.ofSeconds(5)),
                        null,
                        com.anatomist.core.process.ProcessRunner.capture(
                                com.anatomist.core.process.ProcessRunner.SMALL_OUTPUT));
        try {
            byte[] output = result.requireValue();
            return result.exitCode() == 0
                    ? new String(output, StandardCharsets.UTF_8).trim()
                    : null;
        } catch (com.anatomist.core.process.ProcessRunner.Failure unavailable) {
            AnatomistLog.debug("java_home: " + unavailable.getMessage());
            return null;
        }
    }

    private Path discoverSdkmanJava8Home() {
        String home = systemProperty("user.home");
        if (home == null || home.isBlank()) return null;
        Path candidates = Path.of(home, ".sdkman", "candidates", "java");
        if (!Files.isDirectory(candidates)) return null;
        try (Stream<Path> children = Files.list(candidates)) {
            return children.sorted()
                    .map(path -> usableJavaHome(path.toString(), true))
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path usableJavaHome(String raw, boolean requireToolsJar) {
        if (raw == null || raw.isBlank()) return null;
        Path home;
        try {
            home = Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        if ("jre".equals(home.getFileName() == null ? "" : home.getFileName().toString())) {
            Path parent = home.getParent();
            if (parent != null) home = parent;
        }
        if (!Files.isExecutable(home.resolve("bin/java"))) return null;
        if (requireToolsJar && !Files.isRegularFile(home.resolve("lib/tools.jar"))) return null;
        return home;
    }

    private String failureOutputSuffix() {
        String output = lastMavenOutput == null ? "" : lastMavenOutput.strip();
        if (output.isEmpty()) {
            return "; set " + MAVEN_JAVA_HOME_ENV + " when the project requires a specific JDK";
        }
        return "; Maven output: " + boundedMavenSample(output);
    }

    private static void warn(String msg) {
        com.anatomist.core.logging.AnatomistLog.notice("WARN: " + msg);
    }
}
