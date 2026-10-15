package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;

/** Framework facts emitted from detached syntax, declarations and binary metadata. */
public interface JavaModelAnalyzer extends ExtensionPoint {
    void analyze(JavaUnitAnalysis unit, ExtractionResult result);

    default void analyze(JavaUnitAnalysis unit, ExtractionResult result, ExtensionReport report) {
        analyze(unit, result);
    }
}
