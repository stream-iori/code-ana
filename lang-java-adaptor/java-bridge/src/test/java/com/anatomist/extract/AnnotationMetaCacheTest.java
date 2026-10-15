package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class AnnotationMetaCacheTest {
    @Test
    void lruEvictionRetainsRecentlyUsedDefinitions() {
        var cache = new AnnotationMetaCache();
        var definition = new AnnotationMetaCache.Definition(null, null, List.of("p.Parent"));
        for (int i = 0; i < 1024; i++) cache.put("p.A" + i, definition);
        assertNotNull(cache.get("p.A0"));
        cache.put("p.Extra", definition);
        assertNotNull(cache.get("p.A0"));
        assertNull(cache.get("p.A1"));
        assertNotNull(cache.get("p.Extra"));
    }

    @Test
    void byteBudgetRejectsOversizedValuesAndEvictsEarlierEntries() {
        var cache = new AnnotationMetaCache();
        var large = new AnnotationMetaCache.Definition(null, null, List.of("x".repeat(300_000)));
        cache.put("p.First", large);
        cache.put("p.Second", large);
        assertNull(cache.get("p.First"));
        assertNotNull(cache.get("p.Second"));
        cache.put(
                "p.Oversized",
                new AnnotationMetaCache.Definition(null, null, List.of("x".repeat(600_000))));
        assertNull(cache.get("p.Oversized"));
        assertNotNull(cache.get("p.Second"));
    }

    @Test
    void directMetadataIsSnapshottedAndDifferentContextsNeverShareDefinitions() {
        var parents = new ArrayList<>(List.of("p.V1"));
        var first = new AnnotationMetaCache();
        first.put("p.Composed", new AnnotationMetaCache.Definition(null, null, parents));
        parents.clear();
        var second = new AnnotationMetaCache();
        second.put("p.Composed", new AnnotationMetaCache.Definition(null, null, List.of("p.V2")));
        assertEquals(List.of("p.V1"), first.get("p.Composed").parents());
        assertEquals(List.of("p.V2"), second.get("p.Composed").parents());
    }
}
