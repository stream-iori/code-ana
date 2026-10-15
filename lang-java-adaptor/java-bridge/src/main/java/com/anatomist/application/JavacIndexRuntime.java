package com.anatomist.application;

import com.anatomist.core.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Shared detached Java execution for full, incremental and snapshot indexing. */
public final class JavacIndexRuntime {
    private final JavaSemanticBackend backend;
    private final JavaFrameworkContext context;
    private final JavaModelAnalyzerRegistry extensions;
    private final IndexTimings timings;

    public JavacIndexRuntime(JavaSemanticBackend backend, JavaFrameworkContext context) {
        this(backend, context, null);
    }

    public JavacIndexRuntime(
            JavaSemanticBackend backend, JavaFrameworkContext context, IndexTimings timings) {
        this.backend = Objects.requireNonNull(backend);
        this.context = Objects.requireNonNull(context);
        this.timings = timings;
        extensions = FrameworkPlugins.builtIns().prepareDetached(context);
        backend.setDeclarationOverlay(extensions.overlays());
    }

    public JavaModelAnalyzerRegistry extensions() {
        return extensions;
    }

    public JavaFrameworkContext context() {
        return context;
    }

    public ParseInventory extract(
            List<Path> files, Consumer<JavaModelExtractor.UnitFacts> consumer) {
        Map<Path, List<String>> parseFailures = new LinkedHashMap<>();
        ParseInventory inventory =
                backend.analyzeFiles(
                        files,
                        analysis -> {
                            var errors =
                                    analysis.diagnostics().stream()
                                            .filter(d -> d.code().equals("JAVAC_PARSE_FAILED"))
                                            .toList();
                            for (var diagnostic : errors) {
                                for (Path published : analysis.publish()) {
                                    if (published.equals(diagnostic.path())
                                            || !analysis.publish().contains(diagnostic.path())) {
                                        parseFailures.put(
                                                published,
                                                List.of(
                                                        "javac parse failed in "
                                                                + diagnostic.path()
                                                                + ": "
                                                                + diagnostic.message()));
                                    }
                                }
                            }
                            try (var binaries = backend.openBinaryRepository(analysis)) {
                                new JavaModelExtractor(context, binaries, extensions, timings)
                                        .extract(
                                                analysis,
                                                unit -> {
                                                    if (!parseFailures.containsKey(unit.source()))
                                                        consumer.accept(unit);
                                                });
                            }
                        });
        Map<Path, List<String>> failures = new LinkedHashMap<>(inventory.failures());
        failures.putAll(parseFailures);
        return new ParseInventory(
                inventory.scannedFiles(),
                inventory.attemptedFiles(),
                inventory.parsedFiles()
                        - (int)
                                parseFailures.keySet().stream()
                                        .filter(path -> !inventory.failures().containsKey(path))
                                        .count(),
                failures);
    }

    public static long unresolved(List<IndexDiagnostic> diagnostics) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.code().equals("NAVIGATION_TARGET_UNRESOLVED"))
                .mapToLong(IndexDiagnostic::count)
                .sum();
    }
}
