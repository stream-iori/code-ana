package com.anatomist.core;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.JavacBackend;
import com.anatomist.core.javac.JavacToolchain;
import com.anatomist.extract.JavaDeclarationFacts;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.model.ExtractionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Real javac fixtures for detached extraction tests, with an explicit test-process SDK. */
public final class JavaFactsTestSupport {
    private JavaFactsTestSupport() {}

    public static JavaAnalysisContext analyze(Path directory, String name, String source)
            throws Exception {
        return analyze(directory, name, source, List.of());
    }

    public static JavaAnalysisContext analyze(
            Path directory, String name, String source, List<Path> classpath) throws Exception {
        Path file = Files.writeString(directory.resolve(name), source);
        var contexts = new ArrayList<JavaAnalysisContext>();
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        classpath,
                        List.of(directory),
                        config.javacOptions())
                .analyzeFiles(List.of(file), contexts::add);
        return contexts.stream()
                .filter(
                        context ->
                                context.publish().contains(file)
                                        && context.units().stream()
                                                .anyMatch(unit -> unit.path().equals(file)))
                .findFirst()
                .orElseThrow();
    }

    public static ExtractionResult declarations(Path directory, String name, String source)
            throws Exception {
        return declarations(directory, name, source, JavaExtensionData.EMPTY);
    }

    public static ExtractionResult declarations(
            Path directory, String name, String source, JavaExtensionData extensions)
            throws Exception {
        Path file = Files.writeString(directory.resolve(name), source);
        var contexts = new ArrayList<JavaAnalysisContext>();
        backend(directory).analyzeFiles(List.of(file), contexts::add);
        var context = contexts.getFirst();
        var unit =
                context.units().stream()
                        .filter(value -> value.path().equals(file))
                        .findFirst()
                        .orElseThrow();
        var augmented =
                new JavaUnit(
                        unit.path(),
                        unit.tree(),
                        unit.processing(),
                        unit.bindings(),
                        unit.types(),
                        unit.declarations(),
                        unit.declarationInventory(),
                        unit.sourceHash(),
                        extensions);
        var result = new ExtractionResult();
        try (var model =
                new JavaModel(
                        new JavaAnalysisContext(
                                context.module(),
                                context.scope(),
                                List.of(augmented),
                                context.publish(),
                                context.diagnostics(),
                                context.analysisFailure(),
                                context.sourceIdentities()))) {
            new JavaDeclarationFacts(model, directory, ".", "MAIN").extract(file, result);
        }
        return result;
    }

    public static ExtractionResult extract(Path directory, String name, String source)
            throws Exception {
        return extract(directory, name, source, new ProjectConfig());
    }

    public static ExtractionResult extract(
            Path directory, String name, String source, ProjectConfig config) throws Exception {
        return extract(
                directory,
                name,
                source,
                config,
                com.anatomist.framework.JavaModelAnalyzerRegistry.NONE,
                diagnostic -> {});
    }

    public static ExtractionResult extract(
            Path directory,
            String name,
            String source,
            ProjectConfig config,
            com.anatomist.framework.JavaModelAnalyzerRegistry registry,
            java.util.function.Consumer<IndexDiagnostic> diagnostics)
            throws Exception {
        Path file = Files.writeString(directory.resolve(name), source);
        var backend = backend(directory, config);
        backend.setDeclarationOverlay(registry.overlays());
        var result = new ExtractionResult();
        backend.analyzeFiles(
                List.of(file),
                context -> {
                    try (var binaries = backend.openBinaryRepository(context)) {
                        new JavaModelExtractor(
                                        new com.anatomist.framework.JavaFrameworkContext(
                                                directory, List.of(), config, false),
                                        binaries,
                                        registry)
                                .extract(
                                        context,
                                        unit -> {
                                            result.addFacts(unit.facts());
                                            unit.diagnostics().forEach(diagnostics);
                                        });
                    }
                });
        return result;
    }

    public static JavacBackend backend(Path directory) {
        return backend(directory, new ProjectConfig());
    }

    public static Path targetHome(int release) throws Exception {
        String configured = System.getenv("ANATOMIST_TEST_JDK_" + release);
        if (configured != null) return Path.of(configured);
        Path host = Path.of(System.getProperty("java.home"));
        if (com.anatomist.core.nativeimage.JdkTypeCatalogBuilder.releaseOf(host) == release)
            return host;
        String sdkman = System.getenv("SDKMAN_DIR");
        Path candidates =
                sdkman == null || sdkman.isBlank()
                        ? Path.of(System.getProperty("user.home"), ".sdkman/candidates/java")
                        : Path.of(sdkman, "candidates/java");
        if (Files.isDirectory(candidates)) {
            try (var entries = Files.list(candidates)) {
                for (Path candidate : entries.sorted().toList()) {
                    if (candidate.getFileName().toString().equals("current")) continue;
                    try {
                        if (com.anatomist.core.nativeimage.JdkTypeCatalogBuilder.releaseOf(
                                        candidate)
                                == release) return candidate;
                    } catch (RuntimeException ignored) {
                        // An incomplete local SDK is not a test toolchain.
                    }
                }
            }
        }
        throw new IllegalStateException("configure ANATOMIST_TEST_JDK_" + release);
    }

    public static JavacBackend backend(Path directory, int release) throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(targetHome(release).toString());
        return new JavacBackend(
                JavacToolchain.resolve(config.javacOptions(), release, null),
                List.of(),
                List.of(directory),
                config.javacOptions());
    }

    public static ExtractionResult extract(Path directory, String name, String source, int release)
            throws Exception {
        Path file = Files.writeString(directory.resolve(name), source);
        var backend = backend(directory, release);
        var result = new ExtractionResult();
        backend.analyzeFiles(
                List.of(file),
                context -> {
                    try (var binaries = backend.openBinaryRepository(context)) {
                        new JavaModelExtractor(directory, binaries)
                                .extract(context, unit -> result.addFacts(unit.facts()));
                    }
                });
        return result;
    }

    private static JavacBackend backend(Path directory, ProjectConfig config) {
        config.setJavaHome(System.getProperty("java.home"));
        return new JavacBackend(
                JavacToolchain.resolve(config.javacOptions(), 25, null),
                List.of(),
                List.of(directory),
                config.javacOptions());
    }
}
