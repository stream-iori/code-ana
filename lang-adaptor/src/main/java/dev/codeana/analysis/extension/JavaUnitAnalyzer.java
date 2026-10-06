package dev.codeana.analysis.extension;

import dev.codeana.analysis.model.JavaSemanticModel;

/** Anatomist's file analysis boundary: frozen context queries, file-owned bodies and atomic output. */
public interface JavaUnitAnalyzer extends JavaModelAnalyzer {
    void analyzeUnit(ExtensionContext context, JavaSemanticModel.Unit unit, FactSink facts) throws Exception;

    @Override
    default void analyze(ExtensionContext context, FactSink facts) {
        throw new UnsupportedOperationException("JAVA_UNIT_ANALYZER_REQUIRES_RUNNER");
    }
}
