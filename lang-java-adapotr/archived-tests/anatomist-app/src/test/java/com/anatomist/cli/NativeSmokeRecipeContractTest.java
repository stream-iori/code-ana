package com.anatomist.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class NativeSmokeRecipeContractTest {

    @Test
    void nativeSmokeRecipe_recordsLogsAndPrintsNativeDiagnostics() throws Exception {
        String justfile = Files.readString(repoRoot().resolve("justfile"));
        String wrapper = recipeBody(justfile, "native-smoke:");
        String recipe = recipeBody(justfile, "native-smoke-existing:");

        assertTrue(
                wrapper.contains("just native-smoke-existing"),
                "build wrapper should delegate without duplicating smoke logic");

        assertTrue(
                recipe.contains("--enable-native-access=ALL-UNNAMED"),
                "JVM side should avoid JDK 25 native-access warnings");
        assertTrue(recipe.contains("JVM_LOG="), "JVM index log path should be explicit");
        assertTrue(recipe.contains("NATIVE_LOG="), "native index log path should be explicit");
        assertTrue(
                recipe.contains("codesign -dv"),
                "macOS native diagnostics should include codesign");
        assertTrue(recipe.contains("xattr -l"), "macOS native diagnostics should include xattr");
        assertTrue(
                recipe.contains("spctl --assess"), "macOS native diagnostics should include spctl");
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static String recipeBody(String justfile, String recipeHeader) {
        int start = justfile.indexOf(recipeHeader);
        assertTrue(start >= 0, "missing recipe: " + recipeHeader);
        int next = justfile.indexOf("\n# ", start + recipeHeader.length());
        return next >= 0 ? justfile.substring(start, next) : justfile.substring(start);
    }
}
