package com.anatomist.core.java;

import com.anatomist.core.ContentHash;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;

/** Verified original text and UTF-16 coordinates, independent of parser token implementations. */
public final class JavaSource {
    public record Position(int line, int column) {}

    private final Path path;
    private final String text;
    private final int[] lines;

    public JavaSource(Path path, String text, String expectedHash) {
        this.path = Objects.requireNonNull(path);
        this.text = Objects.requireNonNull(text);
        if (expectedHash == null
                || expectedHash.isEmpty()
                || !ContentHash.sha256(text).equals(expectedHash))
            throw new IllegalArgumentException("JAVA_SOURCE_CHANGED: " + path);
        int[] starts = new int[Math.min(text.length() + 1, 256)];
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (count == starts.length)
                    starts = Arrays.copyOf(starts, Math.min(text.length() + 1, starts.length * 2));
                starts[count++] = i + 1;
            } else if (character == '\n') {
                if (count == starts.length)
                    starts = Arrays.copyOf(starts, Math.min(text.length() + 1, starts.length * 2));
                starts[count++] = i + 1;
            }
        }
        lines = Arrays.copyOf(starts, count);
    }

    public static JavaSource read(JavaUnit unit) throws IOException {
        return new JavaSource(unit.path(), Files.readString(unit.path()), unit.sourceHash());
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
        return text.substring(Math.toIntExact(span.start()), Math.toIntExact(span.end()));
    }

    public Position position(long offset) {
        if (offset < 0 || offset > text.length())
            throw new IllegalArgumentException("source offset is unavailable");
        int point = Math.toIntExact(offset);
        int found = Arrays.binarySearch(lines, point);
        int line = found >= 0 ? found : -found - 2;
        return new Position(line + 1, point - lines[line] + 1);
    }
}
