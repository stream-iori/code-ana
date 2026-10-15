package com.anatomist.config;

import java.util.Locale;

/** Target-JDK compiler backend. */
public enum ParserBackend {
    JAVAC;

    public static ParserBackend parse(String value) {
        if (value != null && value.toLowerCase(Locale.ROOT).equals("javac")) return JAVAC;
        throw new IllegalArgumentException(
                "parser must be javac; the javaparser backend has been removed");
    }
}
