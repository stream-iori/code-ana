package com.anatomist.core.java;

import com.anatomist.core.ContentHash;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;

/** Verified original text and UTF-16 coordinates, independent of parser token implementations. */
public final class JavaSource {
    public record Position(int line, int column) {}

    private final com.anatomist.core.AnalysisContext analysis;
    private final Path path;
    private final String text;
    private final int[] lines;

    public JavaSource(Path path, String text, String expectedHash) { this(path, text, expectedHash, null); }

    public JavaSource(Path path, String text, String expectedHash, com.anatomist.core.AnalysisContext analysis) {
        this.analysis=analysis;
        if (analysis != null) analysis.charge(1, 1, 256L + 3L * text.length());
        this.path = Objects.requireNonNull(path);
        this.text = Objects.requireNonNull(text);
        if (expectedHash == null
                || expectedHash.isEmpty()
                || !ContentHash.sha256(text).equals(expectedHash))
            throw new IllegalArgumentException("JAVA_SOURCE_CHANGED: " + path);
        int initial = (int) Math.min((long) text.length() + 1, 256);
        if (analysis != null) analysis.charge(1, 1, 16L + 4L * initial);
        int[] starts = new int[initial];
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (analysis != null && (i & 255) == 0) analysis.charge(1, 0, 0);
            char character = text.charAt(i);
            if (character == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (count == starts.length) {
                    int capacity = (int) Math.min((long) text.length() + 1, (long) starts.length * 2);
                    if (analysis != null) analysis.charge(1, 1, 16L + 4L * capacity);
                    starts = Arrays.copyOf(starts, capacity);
                }
                starts[count++] = i + 1;
            } else if (character == '\n') {
                if (count == starts.length) {
                    int capacity = (int) Math.min((long) text.length() + 1, (long) starts.length * 2);
                    if (analysis != null) analysis.charge(1, 1, 16L + 4L * capacity);
                    starts = Arrays.copyOf(starts, capacity);
                }
                starts[count++] = i + 1;
            }
        }
        if (analysis != null) analysis.charge(1, 1, 16L + 4L * count);
        lines = Arrays.copyOf(starts, count);
    }

    public static JavaSource read(JavaUnit unit) throws IOException {
        return read(unit, null);
    }

    public static JavaSource read(JavaUnit unit, com.anatomist.core.AnalysisContext analysis) throws IOException {
        if (analysis != null) analysis.charge(1, 1, 256L + 3L * Files.size(unit.path()));
        return new JavaSource(unit.path(), Files.readString(unit.path()), unit.sourceHash(), analysis);
    }

    public Path path() {
        return path;
    }

    public String text() {
        return text;
    }

    public String slice(SourceSpan span) {
        if (!span.present() || span.end() > text.length())
            throw new IllegalArgumentException("source span is unavailable");
        if(analysis!=null)analysis.charge(1,1,32L+3L*(span.end()-span.start()));
        return text.substring(Math.toIntExact(span.start()), Math.toIntExact(span.end()));
    }

    public Position position(long offset) {
        if (offset < 0 || offset > text.length())
            throw new IllegalArgumentException("source offset is unavailable");
        if(analysis!=null)analysis.charge(1L+32-Integer.numberOfLeadingZeros(lines.length),1,32);
        int point = Math.toIntExact(offset);
        int found = Arrays.binarySearch(lines, point);
        int line = found >= 0 ? found : -found - 2;
        return new Position(line + 1, point - lines[line] + 1);
    }
}
