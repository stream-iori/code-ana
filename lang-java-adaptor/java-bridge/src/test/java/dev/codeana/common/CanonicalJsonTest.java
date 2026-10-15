package dev.codeana.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void canonicalOrderingHandlesDifferentMapImplementationsAndRejectsNonStringKeys() {
        Map<String, Object> expected = new TreeMap<>();
        Map<String, Object> reversed = new LinkedHashMap<>();
        for (String key : List.of("中", "a", "😀", "aa", "A")) expected.put(key, List.of(key, Json.object("z", 1, "a", true)));
        var keys = new ArrayList<>(expected.keySet());
        Collections.reverse(keys);
        for (String key : keys) reversed.put(key, expected.get(key));
        assertEquals(Json.write(expected), Json.write(reversed));
        assertEquals(Json.write(expected), Json.write(new HashMap<>(expected)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of(1, "invalid")));
    }
    @Test
    void nestedObjectOrderCannotChangeEncodedFactIdentity() {
        var first = Json.object("sample_details", Json.object("line", 1, "raw_javac", Json.object("code", "bad", "column", 4)));
        var second = Json.object("sample_details", Json.object("raw_javac", Json.object("column", 4, "code", "bad"), "line", 1));
        assertEquals(first, second);
        assertEquals(Json.write(first), Json.write(second));
        assertEquals(Json.key("diagnostic", Json.write(first)), Json.key("diagnostic", Json.write(second)));
        assertNotEquals(Json.write(List.of(first, second)), Json.write(List.of(first)));
    }
}
