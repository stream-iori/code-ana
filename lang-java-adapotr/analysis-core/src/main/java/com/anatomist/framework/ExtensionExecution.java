package com.anatomist.framework;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FactOrigin;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** One recovery and observation boundary for extension callbacks. No global state or caches. */
public final class ExtensionExecution {
    public enum Stage {
        RESOURCE(
                "extension_resource_discovery",
                "EXTENSION_RESOURCE_DISCOVERY_FAILED",
                "EXTENSION_RESOURCE"),
        PROJECT(
                "extension_project_analyze",
                "EXTENSION_PROJECT_ANALYZER_FAILED",
                "EXTENSION_PROJECT"),
        JAVA("extension_fact_emit", "EXTENSION_JAVA_ANALYZER_FAILED", "EXTENSION_FACT_EMIT"),
        CALL("extension_fact_enrich", "EXTENSION_CALL_EVIDENCE_FAILED", "EXTENSION_FACT_ENRICH");
        private final String timing, code, phase;

        Stage(String timing, String code, String phase) {
            this.timing = timing;
            this.code = code;
            this.phase = phase;
        }
    }

    public record Source(String file, String module, String scope) {
        public static Source file(String file) {
            return new Source(file, null, null);
        }
    }

    private ExtensionExecution() {}

    public static <T> T run(
            ExtensionPoint extension,
            Stage stage,
            Source source,
            ExtensionReport report,
            Supplier<T> action,
            T fallback) {
        long started = report == null ? 0L : report.start();
        try {
            return action.get();
        } catch (RuntimeException failure) {
            if (report != null)
                report.diagnostic(
                        new IndexDiagnostic(
                                "warning",
                                stage.code,
                                stage.phase,
                                source.file(),
                                source.module(),
                                source.scope(),
                                extension.id(),
                                1,
                                failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            return fallback;
        } finally {
            if (report != null) report.stop(stage.timing, extension.id(), started);
        }
    }

    /** Publish only a successful callback's facts; preserve the primary diagnostic on failure. */
    public static boolean facts(
            ExtensionPoint extension,
            Stage stage,
            Source source,
            ExtensionReport report,
            ExtractionResult destination,
            Consumer<ExtractionResult> action) {
        return run(
                extension,
                stage,
                source,
                report,
                () -> {
                    ExtractionResult isolated = new ExtractionResult();
                    action.accept(isolated);
                    FactOrigin.stamp(
                            isolated,
                            FactOrigin.beginning(),
                            stage == Stage.PROJECT ? null : source.file(),
                            extension.producerId());
                    destination.addFacts(isolated);
                    return true;
                },
                false);
    }
}
