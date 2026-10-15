package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PathGlobTest {

    @Test
    void literalAndPrefixPatternsRespectPathBoundaries() {
        assertTrue(new PathGlob("**").matches(""));
        assertTrue(new PathGlob("src/App.java").matches("./src/App.java"));
        assertFalse(new PathGlob("src/App.java").matches("src/App.java.bak"));
        var subtree = new PathGlob("target/classes/**");
        assertTrue(subtree.matches("target/classes"));
        assertTrue(subtree.matches("target/classes/p/A.class"));
        assertFalse(subtree.matches("target/classes-old/A.class"));
        assertFalse(subtree.matches("other/target/classes/A.class"));
        assertTrue(new PathGlob("src/**/A?.java").matches("src/a/b/Ab.java"));
    }

    @Test
    void doubleStarCrossesDirectoriesButSingleStarDoesNot() {
        assertTrue(new PathGlob("src/**/A.java").matches("src/main/java/A.java"));
        assertTrue(new PathGlob("**/*.java").matches("A.java"));
        assertFalse(new PathGlob("src/*/A.java").matches("src/main/java/A.java"));
    }

    @Test
    void questionMarkMatchesOneCharacter() {
        assertTrue(new PathGlob("src/A?.java").matches("src/Ab.java"));
        assertFalse(new PathGlob("src/A?.java").matches("src/A.java"));
    }
}
