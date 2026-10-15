package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JavacSourceLexemesTest {
    @Test
    void identifiersUseOriginalUtf16OffsetsAndIgnoreCommentsAndLiterals() {
        String escaped = "\\u0046oo";
        String source = "/* class Foo {} */ @Hint(\"Foo\") class " + escaped + " { int 𐐀; }";
        var lexemes = new JavacSourceLexemes(source);
        var type = lexemes.name(0, source.length(), "Foo", false, true, true);
        assertEquals(source.indexOf(escaped), type.start());
        assertEquals(source.indexOf(escaped) + escaped.length() - 1, type.end());
        var field = lexemes.name(0, source.length(), "𐐀", false, false, true);
        assertEquals(source.indexOf("𐐀"), field.start());
        assertEquals(field.start() + 1, field.end());
    }
}
