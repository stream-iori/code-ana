package com.anatomist.config;

import java.util.Locale;

/** Config-level selection of the optional Lombok structural model. */
public enum LombokMode {
    OFF,
    AST,
    COMPILE;

    public static LombokMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("lombok mode must be off, ast or compile: " + value);
        }
    }

    public String optionValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
