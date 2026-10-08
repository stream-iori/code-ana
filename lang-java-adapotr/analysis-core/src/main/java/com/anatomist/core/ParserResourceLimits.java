package com.anatomist.core;

/** Bounded parser caches; zero entry counts select heap-aware defaults. */
public record ParserResourceLimits(int sourceEntries, int combinedEntries, long asmMaxBytes) {
    public static final ParserResourceLimits DEFAULT =
            new ParserResourceLimits(0, 0, 256L * 1024 * 1024);

    public ParserResourceLimits {
        if (sourceEntries < 0 || combinedEntries < 0 || asmMaxBytes <= 0)
            throw new IllegalArgumentException("invalid parser resource limits");
    }
}
