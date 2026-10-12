package com.anatomist.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

class ResolutionQualityTest {
    @Test
    void measuresFalsePositivesAndFalseNegativesSeparately() {
        ResolutionQuality.Metrics metrics =
                ResolutionQuality.evaluate(
                        Set.of("A#exact()", "B#expected()"), Set.of("A#exact()", "C#wrong()"));

        assertEquals(1, metrics.truePositives());
        assertEquals(1, metrics.falsePositives());
        assertEquals(1, metrics.falseNegatives());
        assertEquals(0.5, metrics.precision());
        assertEquals(0.5, metrics.recall());
        assertEquals(0.5, metrics.f1());
        assertFalse(metrics.passes(0.75, 0.75));
    }

    @Test
    void emptyExpectedAndActualIsPerfect() {
        ResolutionQuality.Metrics metrics = ResolutionQuality.evaluate(Set.of(), Set.of());
        assertEquals(1.0, metrics.precision());
        assertEquals(1.0, metrics.recall());
        assertTrue(metrics.passes(1.0, 1.0));
    }
}
