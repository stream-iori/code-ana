package com.anatomist.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ExternalCliRecipeContractTest {

    @Test
    void externalCliRecipeAndScriptCoverFacadeHandlerAndDaoChecks() throws Exception {
        Path root = repoRoot();
        String justfile = Files.readString(root.resolve("justfile"));
        assertTrue(
                justfile.contains("external-cli"),
                "justfile should expose an opt-in external project CLI recipe");

        Path script = root.resolve("scripts/verify-external-cli.sh");
        assertTrue(Files.isRegularFile(script), "missing external CLI verification script");
        String body = Files.readString(script);

        assertTrue(body.contains("SettleApplyServiceV3"), "script should verify facade API");
        assertTrue(
                body.contains("SettleTaskExecuteService"), "script should verify task facade API");
        assertTrue(body.contains("ReconHandler"), "script should verify handler entrypoints");
        assertTrue(body.contains("TrafficSettleEngineDAO"), "script should verify DAO graph");
        assertTrue(
                body.contains("PROJECT#PROJECT="),
                "script should accept documented `just external-cli PROJECT=/path` form");
        assertTrue(
                body.contains("set +u"),
                "script should source SDKMAN without nounset breaking sdkman-init.sh");
        assertTrue(!body.contains("mapfile"), "script should stay compatible with macOS bash 3");
        assertTrue(body.contains(" trace --to "), "script should verify forward trace");
        assertTrue(
                body.contains(" references --direction incoming"),
                "script should verify reverse DAO lookup");
        assertTrue(body.contains(" accesses --limit"), "script should verify DAO field reads");
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }
}
