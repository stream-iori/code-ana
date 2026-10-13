package com.anatomist.core;

/**
 * Version of the persisted graph meaning, independent of the SQLite schema.
 *
 * <p>Bump this value whenever extraction or resolution changes can make an otherwise
 * schema-compatible index describe different graph facts.
 */
public final class GraphSemantics {
    public static final String META_KEY = "graph_semantics_version";
    public static final int VERSION = 26;

    private GraphSemantics() {}

    public static int parse(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
