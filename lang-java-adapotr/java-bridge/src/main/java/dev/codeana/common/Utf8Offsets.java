package dev.codeana.common;

import java.util.Arrays;

/** Sparse UTF-16 to captured UTF-8 positions. ASCII needs no per-character table. */
public final class Utf8Offsets {
    private final int characters;
    private final int[] ends, differences, surrogateSplits;

    public Utf8Offsets(String text) {
        characters = text.length();
        int changes = 0, splits = 0;
        for (int i = 0; i < characters; ) {
            int cp = text.codePointAt(i), width = Character.charCount(cp);
            if (cp >= 128) changes++;
            if (width == 2) splits++;
            i += width;
        }
        ends = new int[changes];
        differences = new int[changes];
        surrogateSplits = new int[splits];
        int difference = 0, change = 0, split = 0;
        for (int i = 0; i < characters; ) {
            int cp = text.codePointAt(i), width = Character.charCount(cp);
            int bytes = cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
            if (cp >= 128) {
                difference += bytes - width;
                ends[change] = i + width;
                differences[change++] = difference;
            }
            if (width == 2) surrogateSplits[split++] = i + 1;
            i += width;
        }
    }

    /** Returns -1 for out-of-range positions and the interior of a surrogate pair. */
    public long offset(long position) {
        if (position < 0 || position > characters) return -1;
        int character = (int) position;
        if (Arrays.binarySearch(surrogateSplits, character) >= 0) return -1;
        int index = Arrays.binarySearch(ends, character);
        if (index < 0) index = -index - 2;
        return position + (index < 0 ? 0 : differences[index]);
    }

    public long indexBytes() {
        return 4L * (ends.length + differences.length + surrogateSplits.length);
    }
}
