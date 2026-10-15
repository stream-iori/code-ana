package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndexTimingsTest {

    @Test
    void repeatedPhasesAccumulateAndMillisAreReadOnly() {
        IndexTimings timings = new IndexTimings();

        timings.addNanos("phase", 1_500_000L);
        timings.addNanos("phase", 2_500_000L);
        timings.addNanos(null, 10_000_000L);
        timings.addNanos("ignored", -1L);

        assertEquals(4L, timings.millis().get("phase"));
        assertTrue(timings.millis().keySet().stream().noneMatch("ignored"::equals));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> timings.millis().put("other", 1L));
    }
}
