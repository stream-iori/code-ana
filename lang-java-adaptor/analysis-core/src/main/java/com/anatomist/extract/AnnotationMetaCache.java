package com.anatomist.extract;

import java.util.LinkedHashMap;
import java.util.List;

/** Small context-local cache of direct metadata, never a depth-truncated closure or an AST. */
final class AnnotationMetaCache {
    record Definition(String source, String location, List<String> parents) {
        Definition {
            parents = List.copyOf(parents);
        }

        long weight(String name) {
            long bytes = 64L + 2L * name.length();
            if (source != null) bytes += 2L * source.length();
            if (location != null) bytes += 2L * location.length();
            for (String parent : parents) bytes += 64L + 2L * parent.length();
            return bytes;
        }
    }

    record Origin(String annotation, String parent, String source) {}

    private record Entry(Definition definition, long weight) {}

    private static final long MAX_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 1024;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private long weight;

    Definition get(String name) {
        Entry entry = entries.get(name);
        return entry == null ? null : entry.definition();
    }

    void put(String name, Definition definition) {
        long bytes = definition.weight(name);
        if (bytes > MAX_BYTES) return;
        Entry previous = entries.put(name, new Entry(definition, bytes));
        if (previous != null) weight -= previous.weight();
        weight += bytes;
        while (entries.size() > MAX_ENTRIES || weight > MAX_BYTES) {
            weight -= entries.pollFirstEntry().getValue().weight();
        }
    }
}
