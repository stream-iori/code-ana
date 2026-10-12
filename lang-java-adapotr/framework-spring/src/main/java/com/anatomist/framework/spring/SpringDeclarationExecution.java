package com.anatomist.framework.spring;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.java.JavaModel;
import com.anatomist.framework.ExtensionReport;
import com.anatomist.framework.JavaUnitAnalysis;
import com.anatomist.json.Json;
import com.anatomist.model.ExtractionResult;

import java.util.Map;
import java.util.function.Consumer;

/** Publishes one declaration's framework facts only after its analysis succeeds. */
final class SpringDeclarationExecution {
    private SpringDeclarationExecution() {}

    static final class Progress {
        private int completed;
        private int skipped;

        int completed() {
            return completed;
        }

        int skipped() {
            return skipped;
        }
    }

    static void run(
            JavaUnitAnalysis unit,
            String analyzer,
            JavaModel.DeclarationRef reference,
            ExtensionReport report,
            ExtractionResult destination,
            Consumer<ExtractionResult> action) {
        run(unit, analyzer, reference, report, destination, action, new Progress());
    }

    static void run(
            JavaUnitAnalysis unit,
            String analyzer,
            JavaModel.DeclarationRef reference,
            ExtensionReport report,
            ExtractionResult destination,
            Consumer<ExtractionResult> action,
            Progress progress) {
        ExtractionResult isolated = new ExtractionResult();
        try {
            action.accept(isolated);
            destination.addFacts(isolated);
            progress.completed++;
        } catch (RuntimeException failure) {
            if (report == null) throw failure;
            progress.skipped++;
            String declaration = unit.identities().declaration(reference);
            report.diagnostic(
                    new IndexDiagnostic(
                            "warning",
                            "EXTENSION_JAVA_DECLARATION_FAILED",
                            "EXTENSION_FACT_EMIT",
                            unit.sourceFile(),
                            unit.identity().module(),
                            unit.identity().scope().name(),
                            declaration,
                            1,
                            failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                            "java",
                            analyzer,
                            null,
                            "declaration-analysis-failed",
                            Json.writeCompact(
                                    Map.of(
                                            "declaration",
                                            declaration,
                                            "analyzer",
                                            analyzer,
                                            "budget_item",
                                            failure.getMessage() != null
                                                            && failure.getMessage()
                                                                    .contains("budget")
                                                    ? "annotation-work"
                                                    : "none",
                                            "completed",
                                            progress.completed,
                                            "skipped",
                                            progress.skipped))));
        }
    }
}
