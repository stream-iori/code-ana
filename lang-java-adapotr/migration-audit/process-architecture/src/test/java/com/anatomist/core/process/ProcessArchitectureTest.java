package com.anatomist.core.process;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

class ProcessArchitectureTest {
    @Test
    void productionProcessLifecycleHasOneOwner() throws Exception {
        Pattern direct =
                Pattern.compile(
                        "new\\s+ProcessBuilder\\s*\\(|Runtime\\.getRuntime\\(\\)\\.exec\\(|\\.waitFor\\s*\\(|\\.destroyForcibly\\s*\\(");
        List<String> violations = new ArrayList<>();
        for (String module :
                List.of(
                        "analysis-core",
                        "framework-spring",
                        "framework-sofa",
                        "java-bridge", "jvm-runner", "native-bridge")) {
            try (var files = Files.walk(Path.of(module, "src/main/java"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (file.toString().contains("/core/process/")) continue;
                    if (direct.matcher(Files.readString(file)).find())
                        violations.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), violations, "External process lifecycle belongs to core.process");
    }
}
