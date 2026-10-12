package com.anatomist.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildVersionTest {
    @Test
    void readsFilteredMavenVersionResource() {
        assertFalse(BuildVersion.version().isBlank());
        assertFalse(BuildVersion.version().contains("${"));
        assertTrue(BuildVersion.display().startsWith("anatomist "));
    }
}
