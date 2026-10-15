package com.anatomist.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Dependency-light SHA-256 helpers for stable content identities. */
public final class ContentHash {
    private static final int BUFFER_SIZE = 8192;

    private ContentHash() {}

    public static String sha256(String value) {
        return hex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /** Ordered content hashes, with at most four readers for large dependency inventories. */
    public static List<String> sha256Files(List<Path> files) throws IOException {
        List<Path> inputs = List.copyOf(files);
        long bytes = 0;
        if (inputs.size() >= 16)
            for (Path path : inputs) {
                bytes += Files.size(path);
                if (bytes >= 32L * 1024 * 1024) break;
            }
        var hashes = new ArrayList<String>(inputs.size());
        if (bytes < 32L * 1024 * 1024) {
            for (Path path : inputs) hashes.add(sha256(path));
            return List.copyOf(hashes);
        }
        // Only one wave is submitted at a time: no unbounded queue, compiler objects, or
        // cached file contents. The executor scope joins every reader on success or failure.
        try (var readers =
                Executors.newFixedThreadPool(
                        4, Thread.ofPlatform().name("anatomist-content-hash-", 0).factory())) {
            for (int start = 0; start < inputs.size(); start += 4) {
                var pending = new ArrayList<Future<String>>(4);
                try {
                    for (int index = start; index < Math.min(start + 4, inputs.size()); index++) {
                        Path path = inputs.get(index);
                        pending.add(readers.submit(() -> sha256(path)));
                    }
                    for (Future<String> hash : pending) hashes.add(hash.get());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("content hashing interrupted", interrupted);
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof IOException failure) throw failure;
                    if (failed.getCause() instanceof RuntimeException failure) throw failure;
                    if (failed.getCause() instanceof Error failure) throw failure;
                    throw new IOException("content hashing failed", failed.getCause());
                } finally {
                    pending.forEach(hash -> hash.cancel(true));
                }
            }
        }
        return List.copyOf(hashes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
