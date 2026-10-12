package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.javac.JavacBackend;
import com.anatomist.core.javac.JavacToolchain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class TargetJdkStreamResolutionIT {
    @Test
    void actualFixtureListStreamUsesSelectedPlatform() throws Exception {
        Path service = Path.of("fixtures/mini-spring-shop/service/src/main/java").toAbsolutePath();
        Path domain = Path.of("fixtures/mini-spring-shop/domain/src/main/java").toAbsolutePath();
        Path file = service.resolve("com/example/shop/service/PriceCalculator.java");
        assertTrue(Files.isRegularFile(file));
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var contexts = new ArrayList<JavaAnalysisContext>();
        new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(domain, service),
                        config.javacOptions())
                .analyzeFiles(List.of(file), contexts::add);
        assertTrue(
                contexts.stream()
                        .flatMap(context -> context.units().stream())
                        .flatMap(unit -> unit.bindings().values().stream())
                        .anyMatch(
                                binding ->
                                        binding.declarationResolved()
                                                && binding.name().equals("stream")
                                                && binding.owner().equals("java.util.Collection")));
    }
}
