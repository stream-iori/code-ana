package dev.codeana.adapter;

import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.model.ExtractionResult;

/** In-process test observation, before Fact IR projection. No wire or C ABI exposure. */
public interface AnalysisObserver {
    AnalysisObserver NONE = new AnalysisObserver() {};

    default void model(JavaAnalysisContext context) {}

    default void extraction(ExtractionResult result) {}

    default com.anatomist.core.IndexTimings timings() {
        return null;
    }

    default void phase(String phase, String context, boolean starting) {}

    default void compilerStatistics(java.util.Map<String, Object> statistics) {}
    default void projectionStatistics(java.util.Map<String, Object> statistics) {}
}
