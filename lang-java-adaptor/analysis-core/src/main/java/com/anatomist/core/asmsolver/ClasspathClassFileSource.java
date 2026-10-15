package com.anatomist.core.asmsolver;

import com.anatomist.core.logging.AnatomistLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

/**
 * One indexed class-file source for an entire dependency classpath.
 *
 * <p>Classpath order is significant: the first entry containing a class wins. Jars are closed after
 * their entry tables are indexed and reopened through a small LRU only when bytes are requested.
 * This avoids both a linear chain of hundreds of type solvers and one permanently-open descriptor
 * per jar.
 */
public final class ClasspathClassFileSource implements ClassFileSource {

    private static final int MAX_OPEN_JARS = 32;
    private static final int MAX_TOLERANT_JARS = 16;

    static final String CACHE_DIR_PROPERTY = "anatomist.typeCache.dir";

    private final List<ClasspathEntry> origins;
    private final Map<String, Integer> directoryOrigins;
    private final Map<Path, JarFile> openJars = new LinkedHashMap<>(16, 0.75f, true);
    private PackedClasspathIndex jarOrigins = PackedClasspathIndex.empty();
    private final Map<String, List<Path>> providerCache = new LinkedHashMap<>();
    private final Map<String, List<Path>> providerFailures = new LinkedHashMap<>();
    private final Map<Path, Set<String>> tolerantEntries = new HashMap<>();
    private String fingerprint = "empty";

    public ClasspathClassFileSource(Iterable<Path> entries) {
        this(entries, 0);
    }

    /** Shares only the immutable inventory; every borrower owns its own JAR handles. */
    public ClasspathClassFileSource fork() {
        return new ClasspathClassFileSource(this);
    }

    private ClasspathClassFileSource(ClasspathClassFileSource source) {
        origins = source.origins;
        directoryOrigins = source.directoryOrigins;
        jarOrigins = source.jarOrigins;
        fingerprint = source.fingerprint;
        tolerantEntries.putAll(source.tolerantEntries);
    }

    public ClasspathClassFileSource(Iterable<Path> entries, int javaVersion) {
        origins = new ArrayList<>();
        directoryOrigins = new LinkedHashMap<>();
        if (entries == null) return;
        for (Path entry : entries) {
            if (entry == null) continue;
            Path normalized = entry.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) origins.add(new ClasspathEntry(normalized, false));
            else if (Files.isRegularFile(normalized))
                origins.add(new ClasspathEntry(normalized, true));
        }
        fingerprint = fingerprint(origins, javaVersion);
        Map<String, Integer> jarEntries = loadJarIndex();
        for (int i = 0; i < origins.size(); i++) {
            ClasspathEntry origin = origins.get(i);
            try {
                if (origin.jar) {
                    if (jarEntries == null) continue;
                    try {
                        indexJar(i, origin, jarEntries);
                    } catch (IOException invalidCentralDirectory) {
                        indexTolerantJar(i, origin, jarEntries);
                    }
                } else {
                    indexDirectory(i, origin);
                }
            } catch (IOException | RuntimeException e) {
                AnatomistLog.warn(
                        "failed to index classpath entry: "
                                + origin.path
                                + " ("
                                + e.getMessage()
                                + ")");
            }
        }
        if (jarEntries != null) {
            jarOrigins = PackedClasspathIndex.from(jarEntries);
            writeJarIndex();
        }
    }

    @Override
    public Optional<byte[]> find(String fqn) {
        int originId = origin(fqn);
        if (originId < 0 || originId >= origins.size()) return Optional.empty();
        ClasspathEntry origin = origins.get(originId);
        String classEntry = fqn.replace('.', '/') + ".class";
        try {
            if (!origin.jar) {
                return Optional.of(Files.readAllBytes(origin.path.resolve(classEntry)));
            }
            if (tolerantEntries.containsKey(origin.path))
                return readTolerantJar(origin.path, classEntry);
            JarFile jar = openJar(origin.path);
            JarEntry entry = jar.getJarEntry(classEntry);
            if (entry == null) return Optional.empty();
            try (InputStream in = jar.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            if (!origin.jar)
                throw new UncheckedIOException(
                        "failed to read class " + fqn + " from " + origin.path, e);
            try {
                return readTolerantJar(origin.path, classEntry);
            } catch (IOException fallbackFailure) {
                e.addSuppressed(fallbackFailure);
                throw new UncheckedIOException(
                        "failed to read class " + fqn + " from " + origin.path, e);
            }
        }
    }

    @Override
    public synchronized List<Path> providers(String binaryName) {
        if (binaryName == null || binaryName.isBlank()) return List.of();
        List<Path> cached = providerCache.get(binaryName);
        if (cached != null) return cached;
        String entry = binaryName.replace('.', '/') + ".class";
        List<Path> matches = new ArrayList<>();
        List<Path> failures = new ArrayList<>();
        for (ClasspathEntry origin : origins) {
            try {
                boolean found;
                if (!origin.jar) found = Files.isRegularFile(origin.path.resolve(entry));
                else if (tolerantEntries.containsKey(origin.path))
                    found = tolerantEntries.get(origin.path).contains(entry);
                else {
                    try {
                        found = openJar(origin.path).getJarEntry(entry) != null;
                    } catch (IOException invalidCentralDirectory) {
                        found = tolerantNames(origin.path).contains(entry);
                    }
                }
                if (found) matches.add(origin.path);
            } catch (IOException failure) {
                if (failures.size() < 8) failures.add(origin.path);
            }
            if (matches.size() >= 8) break;
        }
        List<Path> snapshot = List.copyOf(matches);
        if (providerCache.size() >= 256) {
            providerCache.clear();
            providerFailures.clear();
        }
        providerCache.put(binaryName, snapshot);
        providerFailures.put(binaryName, List.copyOf(failures));
        return snapshot;
    }

    @Override
    public synchronized List<Path> providerFailures(String binaryName) {
        providers(binaryName);
        return providerFailures.getOrDefault(binaryName, List.of());
    }

    private int origin(String binaryName) {
        int directory = directoryOrigins.getOrDefault(binaryName, -1);
        int jar = jarOrigins.findOrigin(binaryName);
        return directory < 0 ? jar : jar < 0 ? directory : Math.min(directory, jar);
    }

    @Override
    public Optional<String> artifactAttribute(String binaryName, String name) {
        int id = origin(binaryName);
        if (id < 0 || id >= origins.size()) return Optional.empty();
        ClasspathEntry selected = origins.get(id);
        try {
            InputStream input;
            if (selected.jar) {
                if (tolerantEntries.containsKey(selected.path)) {
                    byte[] bytes =
                            readTolerantJar(selected.path, "META-INF/MANIFEST.MF").orElse(null);
                    if (bytes == null || bytes.length > 1024 * 1024) return Optional.empty();
                    input = new java.io.ByteArrayInputStream(bytes);
                } else {
                    try {
                        JarFile jar = openJar(selected.path);
                        JarEntry entry = jar.getJarEntry("META-INF/MANIFEST.MF");
                        if (entry == null || entry.getSize() > 1024 * 1024) return Optional.empty();
                        input = jar.getInputStream(entry);
                    } catch (IOException invalidCentralDirectory) {
                        byte[] bytes =
                                readTolerantJar(selected.path, "META-INF/MANIFEST.MF").orElse(null);
                        if (bytes == null || bytes.length > 1024 * 1024) return Optional.empty();
                        input = new java.io.ByteArrayInputStream(bytes);
                    }
                }
            } else {
                Path manifest = selected.path.resolve("META-INF/MANIFEST.MF");
                if (!Files.isRegularFile(manifest) || Files.size(manifest) > 1024 * 1024)
                    return Optional.empty();
                input = Files.newInputStream(manifest);
            }
            try (InputStream owned = input) {
                byte[] bytes = owned.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) return Optional.empty();
                var manifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(bytes));
                return Optional.ofNullable(manifest.getMainAttributes().getValue(name));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "failed to read manifest from " + selected.path, failure);
        }
    }

    @Override
    public Set<String> knownClasses() {
        Set<String> names = new HashSet<>(directoryOrigins.keySet());
        names.addAll(jarOrigins.decodedNames());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public synchronized void close() {
        for (JarFile jar : openJars.values()) {
            try {
                jar.close();
            } catch (IOException ignore) {
                // best effort
            }
        }
        openJars.clear();
    }

    int indexedClassCount() {
        return directoryOrigins.size() + jarOrigins.size();
    }

    int packedIndexBytes() {
        return jarOrigins.packedBytes();
    }

    String fingerprint() {
        return fingerprint;
    }

    private void indexDirectory(int originId, ClasspathEntry origin) throws IOException {
        try (Stream<Path> walk = Files.walk(origin.path)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .forEach(
                            path -> {
                                String relative =
                                        origin.path.relativize(path).toString().replace('\\', '/');
                                if (ignoredClassEntry(relative)) return;
                                String fqn = toFqn(relative);
                                directoryOrigins.putIfAbsent(fqn, originId);
                            });
        }
    }

    private void indexJar(int originId, ClasspathEntry origin, Map<String, Integer> entries)
            throws IOException {
        try (JarFile jar = new JarFile(origin.path.toFile())) {
            for (var jarEntries = jar.entries(); jarEntries.hasMoreElements(); ) {
                JarEntry entry = jarEntries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || ignoredClassEntry(name))
                    continue;
                entries.putIfAbsent(toFqn(name), originId);
            }
        }
    }

    private void indexTolerantJar(int originId, ClasspathEntry origin, Map<String, Integer> entries)
            throws IOException {
        for (String name : tolerantNames(origin.path))
            if (name.endsWith(".class") && !ignoredClassEntry(name))
                entries.putIfAbsent(toFqn(name), originId);
    }

    private Set<String> tolerantNames(Path path) throws IOException {
        Set<String> cached = tolerantEntries.get(path);
        if (cached != null) return cached;
        requireZipHeader(path);
        Set<String> names = new HashSet<>();
        long bytes = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(path))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (names.size() >= 50_000) throw new IOException("ZIP entry budget exceeded");
                names.add(entry.getName());
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    bytes += count;
                    if (bytes > 256L * 1024 * 1024)
                        throw new IOException("ZIP decompression budget exceeded");
                }
                zip.closeEntry();
            }
        }
        Set<String> snapshot = Set.copyOf(names);
        if (tolerantEntries.size() >= MAX_TOLERANT_JARS) tolerantEntries.clear();
        tolerantEntries.put(path, snapshot);
        return snapshot;
    }

    private static Optional<byte[]> readTolerantJar(Path path, String wanted) throws IOException {
        requireZipHeader(path);
        try (var zip = new ZipInputStream(Files.newInputStream(path))) {
            java.util.zip.ZipEntry entry;
            int visited = 0;
            long bytesRead = 0;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++visited > 50_000) throw new IOException("ZIP entry budget exceeded");
                if (entry.getName().equals(wanted)) {
                    byte[] bytes = zip.readNBytes(16 * 1024 * 1024 + 1);
                    if (bytes.length > 16 * 1024 * 1024)
                        throw new IOException("ZIP class or manifest entry exceeds budget");
                    return Optional.of(bytes);
                }
                int count;
                while ((count = zip.read(buffer)) >= 0) {
                    bytesRead += count;
                    if (bytesRead > 256L * 1024 * 1024)
                        throw new IOException("ZIP decompression budget exceeded");
                }
                zip.closeEntry();
            }
        }
        return Optional.empty();
    }

    private static void requireZipHeader(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(4);
            if (header.length != 4
                    || header[0] != 'P'
                    || header[1] != 'K'
                    || !(header[2] == 3 && header[3] == 4 || header[2] == 5 && header[3] == 6))
                throw new IOException("ZIP local header unavailable: " + path);
        }
    }

    private synchronized JarFile openJar(Path path) throws IOException {
        JarFile existing = openJars.get(path);
        if (existing != null) return existing;
        JarFile opened = new JarFile(path.toFile());
        openJars.put(path, opened);
        if (openJars.size() > MAX_OPEN_JARS) {
            var eldest = openJars.entrySet().iterator().next();
            openJars.remove(eldest.getKey());
            eldest.getValue().close();
        }
        return opened;
    }

    private static boolean ignoredClassEntry(String name) {
        return name.startsWith("META-INF/")
                || name.equals("module-info.class")
                || name.endsWith("/module-info.class")
                || name.equals("package-info.class")
                || name.endsWith("/package-info.class");
    }

    private static String toFqn(String classEntry) {
        return classEntry.substring(0, classEntry.length() - ".class".length()).replace('/', '.');
    }

    private Map<String, Integer> loadJarIndex() {
        Path file = cacheFile();
        if (file == null || !Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            jarOrigins = PackedClasspathIndex.read(file, fingerprint);
            AnatomistLog.debug(
                    "classpath type index: cache hit classes="
                            + jarOrigins.size()
                            + " packed_bytes="
                            + jarOrigins.packedBytes());
            return null;
        } catch (IOException | RuntimeException e) {
            AnatomistLog.debug(
                    "classpath type index: ignoring cache " + file + " (" + e.getMessage() + ")");
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
            return new LinkedHashMap<>();
        }
    }

    private void writeJarIndex() {
        Path file = cacheFile();
        if (file == null) return;
        try {
            jarOrigins.write(file, fingerprint);
            AnatomistLog.debug(
                    "classpath type index: wrote classes="
                            + jarOrigins.size()
                            + " packed_bytes="
                            + jarOrigins.packedBytes());
        } catch (IOException e) {
            AnatomistLog.debug("classpath type index: write failed (" + e.getMessage() + ")");
        }
    }

    Path metadataCacheFile() {
        Path root = cacheRoot();
        return root == null ? null : root.resolve(fingerprint + ".types");
    }

    private Path cacheFile() {
        Path root = cacheRoot();
        return root == null ? null : root.resolve(fingerprint + ".origins");
    }

    private static Path cacheRoot() {
        String configured = System.getProperty(CACHE_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        String home = System.getProperty("user.home");
        return home == null || home.isBlank()
                ? null
                : Path.of(home, ".anatomist", "cache", "types");
    }

    private static String fingerprint(List<ClasspathEntry> entries, int javaVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("classpath-index-v2".getBytes(StandardCharsets.UTF_8));
            digest.update(Integer.toString(javaVersion).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < entries.size(); i++) {
                ClasspathEntry entry = entries.get(i);
                digest.update(Integer.toString(i).getBytes(StandardCharsets.UTF_8));
                digest.update(entry.path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) (entry.jar ? 1 : 0));
                if (entry.jar) {
                    try {
                        digest.update(
                                Long.toString(Files.size(entry.path))
                                        .getBytes(StandardCharsets.UTF_8));
                        digest.update(
                                Long.toString(Files.getLastModifiedTime(entry.path).toMillis())
                                        .getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        digest.update("unreadable".getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One shared origin per classpath entry, rather than one object per class. */
    private record ClasspathEntry(Path path, boolean jar) {}
}
