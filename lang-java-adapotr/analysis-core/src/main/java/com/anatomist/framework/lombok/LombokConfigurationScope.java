package com.anatomist.framework.lombok;

import com.anatomist.core.ContentHash;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded immutable configuration inventory, including ancestor and per-source overrides. */
final class LombokConfigurationScope {
    private final Path project;
    private final Map<Path, String> contents;
    private final String fingerprint;
    private final boolean captured;

    LombokConfigurationScope(Path project) {
        this.project = project.toAbsolutePath().normalize();
        captured = false;
        contents = snapshot(this.project);
        fingerprint = fingerprint(contents);
    }

    LombokConfigurationScope(Path project, Map<Path, String> capturedContents) {
        this.project = project.toAbsolutePath().normalize();
        captured = true;
        contents = Map.copyOf(capturedContents);
        fingerprint = fingerprint(contents, this.project);
    }

    String fingerprint() {
        return fingerprint;
    }

    void verify() {
        if (!captured && !fingerprint.equals(fingerprint(snapshot(project))))
            throw new IllegalStateException(
                    "LOMBOK_CONFIG_CHANGED: configuration changed during analysis");
    }

    LombokConfiguration forSource(Path source) {
        Path directory = source.toAbsolutePath().normalize().getParent();
        if (directory == null || !directory.startsWith(project))
            throw new IllegalArgumentException(
                    "Lombok source outside configuration inventory: " + source);
        List<String> layers = new ArrayList<>();
        boolean stopped = false;
        for (int depth = 0; directory != null; directory = directory.getParent()) {
            if (++depth > 256)
                throw new IllegalArgumentException("Lombok configuration exceeds depth budget");
            String content = contents.get(directory.resolve("lombok.config"));
            if (content != null) {
                layers.add(content);
                if (stops(content)) { stopped = true; break; }
            }
            if (captured && directory.equals(project)) break;
        }
        if (captured && !stopped) {
            // Rust captures external ancestors under numbered logical paths, nearest first.
            Path ancestors = project.resolve(".code-ana/lombok-ancestors");
            for (var entry : new TreeMap<>(contents).entrySet()) {
                if (!entry.getKey().startsWith(ancestors)) continue;
                layers.add(entry.getValue());
                if (stops(entry.getValue())) break;
            }
        }
        Collections.reverse(layers);
        return LombokConfiguration.fromContent(String.join("\n", layers));
    }

    private static boolean stops(String content) {
        String last = null;
        for (String raw : content.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator > 0 && line.substring(0, separator).strip().equals("config.stopBubbling"))
                last = line.substring(separator + 1).strip();
        }
        return "true".equalsIgnoreCase(last);
    }

    private static Map<Path, String> snapshot(Path project) {
        Map<Path, String> result = new TreeMap<>();
        Inventory inventory = new Inventory(result);
        try {
            Files.walkFileTree(
                    project,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directory, BasicFileAttributes attributes) {
                            inventory.visit();
                            if (!directory.equals(project)
                                    && Set.of(".git", ".anatomist")
                                            .contains(directory.getFileName().toString()))
                                return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                                throws IOException {
                            inventory.visit();
                            if (file.getFileName().toString().equals("lombok.config"))
                                inventory.read(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
            int depth = 0;
            for (Path ancestor = project.getParent();
                    ancestor != null;
                    ancestor = ancestor.getParent()) {
                if (++depth > 256)
                    throw new IllegalArgumentException("Lombok configuration exceeds depth budget");
                Path file = ancestor.resolve("lombok.config");
                if (Files.exists(file)) inventory.read(file);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "LOMBOK_CONFIG_UNREADABLE: " + failure.getMessage(), failure);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String fingerprint(Map<Path, String> contents) {
        return fingerprint(contents, null);
    }

    private static String fingerprint(Map<Path, String> contents, Path root) {
        StringBuilder material = new StringBuilder("lombok-config-scope-v1\n");
        contents.forEach(
                (file, content) -> {
                    String path = root == null ? file.toString() : root.relativize(file).toString();
                    material.append(path.length())
                            .append(':')
                            .append(path)
                            .append(':')
                            .append(ContentHash.sha256(content))
                            .append('\n');
                });
        return ContentHash.sha256(material.toString());
    }

    private static final class Inventory {
        private final Map<Path, String> result;
        private int entries;
        private long bytes;

        private Inventory(Map<Path, String> result) {
            this.result = result;
        }

        private void visit() {
            if (++entries > 200_000)
                throw new IllegalArgumentException(
                        "Lombok configuration inventory exceeds work budget");
        }

        private void read(Path file) throws IOException {
            if (!Files.isRegularFile(file))
                throw new IOException("not a configuration file: " + file);
            if (result.size() >= 4096 || Files.size(file) > 1024 * 1024)
                throw new IllegalArgumentException("Lombok configuration inventory exceeds budget");
            byte[] content;
            try (var input = Files.newInputStream(file)) {
                content = input.readNBytes(1024 * 1024 + 1);
            }
            bytes += content.length;
            if (content.length > 1024 * 1024 || bytes > 8L * 1024 * 1024)
                throw new IllegalArgumentException(
                        "Lombok configuration inventory exceeds byte budget");
            result.put(
                    file.toAbsolutePath().normalize(),
                    new String(content, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
