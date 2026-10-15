package com.anatomist.core.asmsolver;

import com.anatomist.core.java.JavaBinaryType;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Session-owned binary declarations. No parser solver or host-JVM class loading is involved. */
public final class JavaBinaryRepository implements AutoCloseable {
    private final ClassFileSource source;
    private final long maximumWeight;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private long weight;
    private boolean closed;

    public JavaBinaryRepository(ClassFileSource source, long maximumWeight) {
        this.source = Objects.requireNonNull(source);
        if (maximumWeight < 0) throw new IllegalArgumentException("negative cache budget");
        this.maximumWeight = maximumWeight;
    }

    /** Exact binary names take precedence; a class-file hit must prove a dotted nested name. */
    public Optional<JavaBinaryType> find(String requested) {
        if (closed) throw new IllegalStateException("binary repository is closed");
        if (requested == null || requested.isBlank()) return Optional.empty();
        String candidate = requested;
        while (true) {
            Entry cached = cache.get(candidate);
            if (cached != null) return Optional.of(cached.type());
            var bytes = source.find(candidate);
            if (bytes.isPresent()) {
                JavaBinaryType type = JavaBinaryReader.read(bytes.get());
                if (!type.name().equals(candidate)) {
                    throw new IllegalArgumentException(
                            "class-file name mismatch: " + candidate + " != " + type.name());
                }
                // A conservative weight proxy includes retained member/annotation objects. Large
                // individual declarations are consumed uncached instead of breaking the budget.
                long cost = Math.max(4096L, bytes.get().length * 8L);
                if (cost <= maximumWeight) {
                    while (weight > maximumWeight - cost)
                        weight -= cache.pollFirstEntry().getValue().weight();
                    cache.put(candidate, new Entry(type, cost));
                    weight += cost;
                }
                return Optional.of(type);
            }
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) return Optional.empty();
            candidate =
                    candidate.substring(0, separator) + "$" + candidate.substring(separator + 1);
        }
    }

    public Optional<String> artifactAttribute(String binaryName, String name) {
        if (closed) throw new IllegalStateException("binary repository is closed");
        return source.artifactAttribute(binaryName, name);
    }

    public List<Path> providers(String binaryName) {
        if (closed) throw new IllegalStateException("binary repository is closed");
        if (binaryName == null || binaryName.isBlank()) return List.of();
        String candidate = binaryName;
        while (true) {
            List<Path> found = source.providers(candidate);
            if (!found.isEmpty()) return found;
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) return List.of();
            candidate =
                    candidate.substring(0, separator) + "$" + candidate.substring(separator + 1);
        }
    }

    public List<Path> providerFailures(String binaryName) {
        if (closed) throw new IllegalStateException("binary repository is closed");
        return source.providerFailures(binaryName);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cache.clear();
        weight = 0;
        source.close();
    }

    private record Entry(JavaBinaryType type, long weight) {}
}
