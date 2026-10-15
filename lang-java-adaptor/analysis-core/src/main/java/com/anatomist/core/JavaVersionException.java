package com.anatomist.core;

/** User-facing Java language-level validation failure. */
public final class JavaVersionException extends RuntimeException {
    private final int exitCode;

    public JavaVersionException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
