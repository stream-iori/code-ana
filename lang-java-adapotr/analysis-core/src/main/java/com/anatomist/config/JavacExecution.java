package com.anatomist.config;

import java.util.Locale;

public enum JavacExecution {
    TOOLCHAIN,
    NATIVE;

    public static JavacExecution parse(String value) {
        if (value == null)
            throw new IllegalArgumentException("javac_execution must be toolchain or native");
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "toolchain" -> TOOLCHAIN;
            case "native" -> NATIVE;
            default ->
                    throw new IllegalArgumentException(
                            "javac_execution must be toolchain or native");
        };
    }
}
