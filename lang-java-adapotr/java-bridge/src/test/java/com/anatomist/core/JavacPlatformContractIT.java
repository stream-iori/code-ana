package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.asmsolver.JavaBinarySources;
import com.anatomist.core.javac.JavacToolchain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/** Selected platform inputs are explicit in both JVM and native execution. */
class JavacPlatformContractIT {
    @Test
    void selectedPlatformSuppliesBinaryHeadersWithoutHostReflection() {
        try (var repository =
                JavaBinarySources.open(
                        List.of(), Path.of(System.getProperty("java.home")), 25, 1024 * 1024)) {
            assertEquals(
                    "java.lang.String", repository.find("java.lang.String").orElseThrow().name());
            assertTrue(repository.find("com.anatomist.cli.IndexCommand").isEmpty());
        }
    }

    @Test
    void disabledPlatformFailsExplicitly() {
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        config.setVmClasspath(false);
        assertTrue(
                assertThrows(
                                JavaVersionException.class,
                                () -> JavacToolchain.resolve(config.javacOptions(), 25, null))
                        .getMessage()
                        .contains("JAVAC_PLATFORM_REQUIRED"));
    }

    @Test
    void missingSelectedJdkCannotFallBackToRunningVm() {
        assertTrue(
                assertThrows(
                                JavaVersionException.class,
                                () ->
                                        JavacToolchain.resolve(
                                                new ProjectConfig().javacOptions(), 25, null))
                        .getMessage()
                        .contains("JAVAC_JDK_HOME_REQUIRED"));
    }
}
