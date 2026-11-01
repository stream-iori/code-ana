package com.anatomist.core.java;

import java.util.List;
import java.util.Objects;

/** Detached lexical evidence. UTF-16 half-open offsets refer to the verified original source. */
public record JavaComment(Kind kind, SourceSpan span, String body, List<Segment> segments,
                          List<Integer> documentOwners) {
    public enum Kind { LINE, BLOCK, DOC }
    public record Segment(int bodyStart, int bodyEnd, int sourceStart, int sourceEnd) {}

    public JavaComment {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(span);
        Objects.requireNonNull(body);
        segments = List.copyOf(segments);
        documentOwners = List.copyOf(documentOwners);
        var seen = new java.util.HashSet<Integer>();
        for (Integer owner : documentOwners)
            if (owner == null || owner < 0 || !seen.add(owner) || kind != Kind.DOC)
                throw new IllegalArgumentException("invalid document owner");
        if (!span.present() || span.start() >= span.end())
            throw new IllegalArgumentException("invalid comment source range");
        int bodyEnd = 0;
        long sourceEnd = span.start();
        for (Segment segment : segments) {
            if (segment.bodyStart() != bodyEnd || segment.bodyEnd() <= bodyEnd
                    || segment.bodyEnd() > body.length() || segment.sourceStart() < sourceEnd
                    || segment.sourceEnd() <= segment.sourceStart() || segment.sourceEnd() > span.end()
                    || splitsScalar(body, segment.bodyStart()) || splitsScalar(body, segment.bodyEnd()))
                throw new IllegalArgumentException("invalid comment body map");
            bodyEnd = segment.bodyEnd();
            sourceEnd = segment.sourceEnd();
        }
        if (bodyEnd != body.length()) throw new IllegalArgumentException("incomplete comment body map");
    }

    private static boolean splitsScalar(String value, int offset) {
        return offset > 0 && offset < value.length()
                && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset));
    }
}
