package com.anatomist.core.javac;

import com.anatomist.core.ContentHash;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Only successful, isolated compilations are published. No project target/classes is consumed. */
final class JavacCompilationCache {
    private JavacCompilationCache() {}

    record Directory(Path path) implements Closeable {
        @Override
        public void close() throws IOException {
            remove(path);
        }
    }

    static String auxiliaryFingerprint(List<Path> roots, List<Path> classpath) throws IOException {
        StringBuilder material = new StringBuilder();
        for (Path entry : classpath) append(material, entry);
        Set<Path> configs = new TreeSet<>();
        for (Path root : roots) {
            for (Path at = root; at != null; at = at.getParent())
                if (Files.isRegularFile(at.resolve("lombok.config")))
                    configs.add(at.resolve("lombok.config"));
        }
        for (Path config : configs) append(material, config);
        return ContentHash.sha256(material.toString());
    }

    static Path cached(Path cacheDirectory, String environment, List<Path> sources)
            throws IOException {
        StringBuilder material = new StringBuilder(environment);
        for (Path source : sources) append(material, source);
        return cacheDirectory
                .resolve("compilations")
                .resolve(ContentHash.sha256(material.toString()));
    }

    static final class ProcessorUnavailable extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ProcessorUnavailable(String message) {
            super(message);
        }
    }

    static Path processor(List<Path> classpath) {
        List<Path> candidates =
                classpath.stream()
                        .filter(Files::isRegularFile)
                        .filter(
                                path ->
                                        path.getFileName()
                                                .toString()
                                                .matches("lombok(?:-[0-9].*)?\\.jar"))
                        .toList();
        if (candidates.size() != 1)
            throw new ProcessorUnavailable(
                    "expected exactly one project-locked Lombok processor JAR on the resolved classpath; found "
                            + candidates.size());
        return candidates.getFirst();
    }

    static boolean valid(Path directory) throws IOException {
        Path analysis = directory.resolve("analysis.bin"),
                manifest = directory.resolve("manifest.txt");
        if (!Files.isRegularFile(analysis) || !Files.isRegularFile(manifest)) return false;
        boolean verifiedAnalysis = false;
        for (String line : Files.readAllLines(manifest)) {
            int separator = line.indexOf(' ');
            if (separator != 64) return false;
            Path input = directory.resolve(line.substring(separator + 1)).normalize();
            if (!input.startsWith(directory)
                    || !Files.isRegularFile(input)
                    || !ContentHash.sha256(input).equals(line.substring(0, separator)))
                return false;
            if (input.equals(analysis)) verifiedAnalysis = true;
        }
        return verifiedAnalysis;
    }

    static void publish(Path temporary, Path target) throws IOException {
        StringBuilder manifest = new StringBuilder();
        try (var files = Files.walk(temporary)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                manifest.append(ContentHash.sha256(file))
                        .append(' ')
                        .append(temporary.relativize(file))
                        .append('\n');
        }
        Files.writeString(temporary.resolve("manifest.txt"), manifest);
        if (Files.exists(target)) {
            if (valid(target)) return;
            remove(target);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            if (!valid(target)) throw failure;
        }
    }

    static void remove(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    private static void append(StringBuilder material, Path path) throws IOException {
        material.append(path.toAbsolutePath().normalize()).append('=');
        if (Files.isRegularFile(path)) material.append(ContentHash.sha256(path));
        else if (Files.isDirectory(path)) {
            try (var files = Files.walk(path)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                    material.append(path.relativize(file))
                            .append(':')
                            .append(ContentHash.sha256(file))
                            .append(';');
            }
        } else material.append("missing");
        material.append('\n');
    }
}
