package com.anatomist.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ExtensionE2ERecipeContractTest {
    @Test
    void recipesCoverJvmNativeIncrementalAndProducerChecks() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        String justfile = Files.readString(root.resolve("justfile"));
        assertTrue(justfile.contains("extension-e2e-jvm:"));
        assertTrue(justfile.contains("extension-e2e-native:"));
        String script = Files.readString(root.resolve("scripts/extension-e2e.sh"));
        assertTrue(script.contains("--incremental"));
        assertTrue(script.contains("spring_xml = true"));
        assertTrue(script.contains("producer_id"));
        assertTrue(script.contains("diff -u"));
    }
}
