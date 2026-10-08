package com.anatomist.core.java;

/** Half-open offsets into the original UTF-16 source, never pretty-printed text. */
public record SourceSpan(long start, long end) {
    public static final SourceSpan ABSENT = new SourceSpan(-1, -1);

    public SourceSpan {
        if (!(start == -1 && end == -1) && (start < 0 || end < start))
            throw new IllegalArgumentException("invalid source span: " + start + ":" + end);
    }

    public boolean present() {
        return start >= 0;
    }
}
