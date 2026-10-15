package com.anatomist.config;

import java.util.Locale;

/** Controls diagnostic collection without changing indexed source facts. */
public enum ObservabilityMode {
    OFF,
    BASIC,
    FULL;

    public static ObservabilityMode parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("mode must be off, basic, or full");
        }
    }
}
