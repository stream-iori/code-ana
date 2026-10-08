package com.anatomist.core;

import java.util.List;
import java.util.Locale;

/** Explainable result of resolving the analysis classpath. */
public record ClasspathDetectionResult(
        Status status,
        List<String> entries,
        Integer mavenExitCode,
        int moduleOutputFiles,
        int buildOutputEntries,
        String errorSample,
        List<IndexDiagnostic> diagnostics) {
    public enum Status {
        NOT_REQUESTED,
        EXPLICIT,
        /** Reused from the prior SQLite index rather than re-detected. */
        INDEX_METADATA,
        CACHE_HIT,
        FULL,
        PARTIAL,
        UNAVAILABLE
    }

    public ClasspathDetectionResult {
        status = status == null ? Status.NOT_REQUESTED : status;
        entries = entries == null ? List.of() : List.copyOf(entries);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static ClasspathDetectionResult notRequested() {
        return new ClasspathDetectionResult(
                Status.NOT_REQUESTED, List.of(), null, 0, 0, null, List.of());
    }

    public static ClasspathDetectionResult explicit(List<String> entries) {
        return new ClasspathDetectionResult(Status.EXPLICIT, entries, null, 0, 0, null, List.of());
    }

    public static ClasspathDetectionResult cacheHit(List<String> entries, int buildOutputEntries) {
        return new ClasspathDetectionResult(
                Status.CACHE_HIT, entries, null, 0, buildOutputEntries, null, List.of());
    }

    public static ClasspathDetectionResult indexMetadata(List<String> entries) {
        return new ClasspathDetectionResult(
                Status.INDEX_METADATA, entries, null, 0, 0, null, List.of());
    }

    public String wireStatus() {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /** Clear name for the legacy moduleOutputFiles component. */
    public int mavenClasspathFiles() {
        return moduleOutputFiles;
    }
}
