package com.anatomist.core.javac;

import com.anatomist.core.ContentHash;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Encoded declarations only. Announced references stay pinned until the worker has exited. */
final class JavacDeclarationCache {
    static final long DEFAULT_BYTES = 64L * 1024 * 1024;
    private final long maximum;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(32, .75f, true);
    private final Set<Key> pinned = new HashSet<>();
    private long bytes;
    private long hits;
    private boolean active;

    private record Key(Path path, String sourceHash, String environment) {}

    private record Entry(byte[] encoded, String token, long weight) {}

    JavacDeclarationCache(long maximum) {
        this.maximum = maximum;
    }

    Lease lease(List<Path> sources, Set<Path> publish, boolean enabled, String environment)
            throws IOException {
        if (active) throw new IllegalStateException("declaration cache lease already active");
        Map<Path, String> hashes = new LinkedHashMap<>();
        if (enabled)
            for (Path source : sources)
                if (!publish.contains(source)) hashes.put(source, ContentHash.sha256(source));
        active = true;
        return new Lease(hashes, environment);
    }

    long bytes() {
        return bytes;
    }

    long hits() {
        return hits;
    }

    void clear() {
        if (active) throw new IllegalStateException("declaration cache lease still active");
        entries.clear();
        bytes = 0;
    }

    final class Lease implements AutoCloseable {
        private final Map<Path, String> hashes;
        private final String environment;
        private final Set<Key> inserted = new HashSet<>();
        private boolean committed;
        private boolean closed;

        private Lease(Map<Path, String> hashes, String environment) {
            this.hashes = hashes;
            this.environment = environment;
            for (Path path : hashes.keySet()) {
                Key key = key(path);
                if (entries.containsKey(key)) pinned.add(key);
            }
        }

        boolean declarations(Path path) {
            return hashes.containsKey(path);
        }

        private Key key(Path path) {
            return new Key(path, hashes.get(path), environment);
        }

        synchronized String token(Path path) {
            Entry entry = entries.get(key(path));
            return entry == null ? "" : entry.token();
        }

        synchronized byte[] reference(Path path, String token) throws IOException {
            Key key = key(path);
            Entry entry = entries.get(key);
            if (!pinned.contains(key) || entry == null || !entry.token().equals(token))
                throw new IOException("JAVAC_PROTOCOL_INVALID: unknown declaration reference");
            hits++;
            return entry.encoded();
        }

        synchronized void remember(Path path, byte[] encoded) {
            if (!declarations(path)) return;
            Key key = key(path);
            // The worker must send the same content for a pinned, unchanged key.
            // An already announced frame stays pinned until the task has completed.
            if (pinned.contains(key)) return;
            long weight =
                    encoded.length + 256L + 2L * (path.toString().length() + environment.length());
            if (weight > maximum) return;
            Entry previous = entries.remove(key);
            if (previous != null) bytes -= previous.weight();
            var iterator = entries.entrySet().iterator();
            while (bytes + weight > maximum && iterator.hasNext()) {
                var candidate = iterator.next();
                if (pinned.contains(candidate.getKey())) continue;
                bytes -= candidate.getValue().weight();
                iterator.remove();
            }
            if (bytes + weight > maximum) return;
            entries.put(key, new Entry(encoded, digest(encoded), weight));
            bytes += weight;
            inserted.add(key);
        }

        synchronized void commit() {
            committed = true;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (!committed)
                for (Key key : inserted) {
                    Entry entry = entries.remove(key);
                    if (entry != null) bytes -= entry.weight();
                }
            pinned.clear();
            active = false;
        }
    }

    private static String digest(byte[] encoded) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
