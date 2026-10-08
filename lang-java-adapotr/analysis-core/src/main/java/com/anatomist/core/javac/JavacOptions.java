package com.anatomist.core.javac;

import java.nio.file.Path;

/** Immutable parser inputs; configuration selection belongs above the parser boundary. */
public record JavacOptions(
        Path javaHome,
        boolean toolchainExecution,
        boolean vmClasspath,
        boolean lombokCompile,
        boolean lombokStrict,
        Path cacheDirectory,
        int analysisThreads,
        int workerHeapMb) {
    public JavacOptions(
            Path javaHome,
            boolean toolchainExecution,
            boolean vmClasspath,
            boolean lombokCompile,
            boolean lombokStrict,
            Path cacheDirectory) {
        this(
                javaHome,
                toolchainExecution,
                vmClasspath,
                lombokCompile,
                lombokStrict,
                cacheDirectory,
                1,
                1024);
    }

    public JavacOptions {
        if (analysisThreads < 1 || analysisThreads > 8)
            throw new IllegalArgumentException("javac_analysis_threads must be between 1 and 8");
        if (workerHeapMb <= 0)
            throw new IllegalArgumentException("javac_worker_heap_mb must be positive");
        cacheDirectory =
                java.util.Objects.requireNonNull(cacheDirectory).toAbsolutePath().normalize();
    }
}
