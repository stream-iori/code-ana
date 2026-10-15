package com.anatomist.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class SearchSelectionTest {
    @Test
    void multiplePathsMatchWithExistingGlobSemantics() {
        var selection = new SearchSelection("p", null, List.of("src/**/A?.java", "docs/*.md"));
        assertTrue(selection.matchesPath("src/A1.java"));
        assertTrue(selection.matchesPath("src/main/java/A2.java"));
        assertTrue(selection.matchesPath("docs/README.md"));
        assertFalse(selection.matchesPath("docs/sub/README.md"));
        assertFalse(selection.matchesPath("src/main/A.java"));
        assertFalse(selection.matchesPath(null));
    }

    @Test
    void pathPatternsAreValidatedAtConstructionAndSelectionIsImmutable() {
        var paths = new ArrayList<>(List.of("src/**/A.java"));
        var selection = new SearchSelection(null, null, paths);
        paths.clear();
        assertEquals(List.of("src/**/A.java"), selection.paths());
        assertTrue(selection.matchesPath("src/main/A.java"));
        assertEquals(selection, new SearchSelection(null, null, List.of("src/**/A.java")));
        assertEquals(
                selection.hashCode(),
                new SearchSelection(null, null, List.of("src/**/A.java")).hashCode());
        for (String invalid :
                List.of("", "/absolute.java", "src/../A.java", "src//A.java", "src\\A.java"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SearchSelection(null, null, List.of(invalid)));
    }
}
