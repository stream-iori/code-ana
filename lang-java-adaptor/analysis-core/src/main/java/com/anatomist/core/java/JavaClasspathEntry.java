package com.anatomist.core.java;

import java.nio.file.Path;
import java.util.Objects;

/** One ordered, content-identified compiler dependency for a single module task. */
public record JavaClasspathEntry(Path path, String coordinate, String sha256, String identity) {
    public JavaClasspathEntry {
        path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        coordinate = Objects.requireNonNull(coordinate);
        sha256 = Objects.requireNonNull(sha256);
        identity = Objects.requireNonNull(identity);
    }

    public JavaClasspathEntry(Path path, String coordinate, String sha256) {
        this(path, coordinate, sha256, path.toAbsolutePath().normalize().toString());
    }
}
