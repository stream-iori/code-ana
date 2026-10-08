package com.anatomist.core.asmsolver;

import com.anatomist.core.nativeimage.JdkTypeCatalogBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipFile;

/** Reads the selected JDK's rt.jar/jmods. Never falls back to the indexing process's JDK. */
public final class JdkClassFileSource implements ClassFileSource {
    private Map<String, Location> classes = new LinkedHashMap<>();
    private boolean closed;

    public JdkClassFileSource(Path home, int expectedRelease) {
        int actual = JdkTypeCatalogBuilder.releaseOf(home);
        if (actual != expectedRelease) {
            throw new IllegalArgumentException(
                    "JDK_HOME_RELEASE_MISMATCH: expected "
                            + expectedRelease
                            + " but was "
                            + actual);
        }
        try {
            if (actual == 8) {
                Path lib =
                        Files.isDirectory(home.resolve("jre/lib"))
                                ? home.resolve("jre/lib")
                                : home.resolve("lib");
                Path rt = lib.resolve("rt.jar");
                if (!Files.isRegularFile(rt)) throw new IOException("missing rt.jar in " + home);
                index(rt, "");
                for (Path jar : archives(lib, ".jar")) if (!jar.equals(rt)) index(jar, "");
                if (Files.isDirectory(lib.resolve("ext"))) {
                    for (Path jar : archives(lib.resolve("ext"), ".jar")) index(jar, "");
                }
            } else {
                List<Path> modules = archives(home.resolve("jmods"), ".jmod");
                if (modules.isEmpty()) throw new IOException("missing jmods in " + home);
                for (Path module : modules) index(module, "classes/");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read selected JDK " + home, e);
        }
    }

    /** Shares the immutable inventory; readers still open and own their ZIP handles. */
    public JdkClassFileSource fork() {
        if (closed) throw new IllegalStateException("JDK class-file source is closed");
        return new JdkClassFileSource(Collections.unmodifiableMap(classes));
    }

    private JdkClassFileSource(Map<String, Location> classes) {
        this.classes = classes;
    }

    private static List<Path> archives(Path directory, String suffix) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private void index(Path archive, String prefix) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class")) continue;
                String fqn = name.substring(prefix.length(), name.length() - 6).replace('/', '.');
                if (!"module-info".equals(fqn))
                    classes.putIfAbsent(fqn, new Location(archive, name));
            }
        }
    }

    @Override
    public Optional<byte[]> find(String fqn) {
        if (closed) throw new IllegalStateException("JDK class-file source is closed");
        Location location = classes.get(fqn);
        if (location == null) return Optional.empty();
        try (var zip = new ZipFile(location.archive().toFile())) {
            var entry = zip.getEntry(location.entry());
            if (entry == null) throw new IOException("JDK archive changed: " + location);
            try (var in = zip.getInputStream(entry)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + fqn + " from selected JDK", e);
        }
    }

    @Override
    public Set<String> knownClasses() {
        if (closed) throw new IllegalStateException("JDK class-file source is closed");
        return Collections.unmodifiableSet(classes.keySet());
    }

    @Override
    public void close() {
        closed = true;
        classes = Map.of();
    }

    private record Location(Path archive, String entry) {}
}
