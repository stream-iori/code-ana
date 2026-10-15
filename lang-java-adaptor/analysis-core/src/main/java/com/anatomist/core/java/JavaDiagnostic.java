package com.anatomist.core.java;

import java.nio.file.Path;

/** Original compiler diagnostic, retained even when local recovery produces useful facts. */
public record JavaDiagnostic(
        Path path, String kind, String code, long line, long column, String message) {}
