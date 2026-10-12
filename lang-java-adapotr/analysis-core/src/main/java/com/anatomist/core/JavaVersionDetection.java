package com.anatomist.core;

import java.nio.file.Path;
import java.util.List;

/** Static build-file evidence used to select the target javac toolchain. */
public record JavaVersionDetection(
        int version,
        Source source,
        Path evidenceFile,
        String evidenceExpression,
        List<IndexDiagnostic> diagnostics) {
    public static final int MIN_SUPPORTED_VERSION = 8;
    public static final int MAX_SUPPORTED_VERSION = 25;

    public enum Source {
        CLI,
        CONFIG,
        MAVEN,
        GRADLE,
        FALLBACK,
        UNKNOWN
    }

    public JavaVersionDetection {
        source = source == null ? Source.UNKNOWN : source;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public boolean found() {
        return version > 0;
    }

    public boolean supported() {
        return version >= MIN_SUPPORTED_VERSION && version <= MAX_SUPPORTED_VERSION;
    }

    public static String supportedRange() {
        return MIN_SUPPORTED_VERSION + ".." + MAX_SUPPORTED_VERSION;
    }

    public static JavaVersionDetection unknown(List<IndexDiagnostic> diagnostics) {
        return new JavaVersionDetection(-1, Source.UNKNOWN, null, null, diagnostics);
    }
}
