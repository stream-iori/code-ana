package com.anatomist.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class SmokeRecipeContractTest {

    @Test
    void smokeRecipe_usesCanonicalPipelinesAndFailsFast() throws Exception {
        String justfile = Files.readString(repoRoot().resolve("justfile"));
        String smoke = recipeBody(justfile, "smoke:");

        assertTrue(
                smoke.contains("set -euo pipefail"),
                "smoke recipe must fail fast and propagate command failures");
        assertTrue(smoke.contains("--then structure --recursive"));
        assertTrue(smoke.contains("--then call-sites --then source --limit 20"));
        assertTrue(smoke.contains("preview references 20"));
        assertFalse(smoke.contains(" <\"$TMP/"), "smoke must use the current fused pipeline");
        assertFalse(smoke.contains("callees-of"));
        assertFalse(smoke.contains("context --enrich"));
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static String recipeBody(String justfile, String recipeHeader) {
        String lineMarker = "\n" + recipeHeader;
        int marker = justfile.indexOf(lineMarker);
        int start = -1;
        if (justfile.startsWith(recipeHeader)) {
            start = 0;
        } else if (marker >= 0) {
            start = marker + 1;
        }
        assertTrue(start >= 0, "missing recipe: " + recipeHeader);
        int next = justfile.indexOf("\n# ", start + recipeHeader.length());
        return next >= 0 ? justfile.substring(start, next) : justfile.substring(start);
    }
}
