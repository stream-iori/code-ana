package com.anatomist.core.javac;

import com.anatomist.core.*;
import com.anatomist.core.java.JavaDeclarationOverlay;

import java.time.Duration;
import java.util.*;

/** Atomic captured-run configuration; no setter ordering is exposed to the bridge. */
public record JavacRunConfiguration(
        Map<String, JavacBackend.ModelBudget> modelBudgets,
        long decodeBudgetBytes,
        boolean sourceSignatures,
        Duration batchTimeout,
        IndexTimings timings,
        JavaDeclarationOverlay overlay,
        List<JavacBackend.CapturedContext> contexts,
        JavaSourcePlan sourcePlan,
        JavacMavenInputs moduleInputs,
        AnalysisContext analysisContext) {
    public JavacRunConfiguration(Map<String,JavacBackend.ModelBudget> budgets,long decodeBytes,boolean signatures,Duration timeout,IndexTimings timings,JavaDeclarationOverlay overlay,List<JavacBackend.CapturedContext> contexts,JavaSourcePlan sources,JavacMavenInputs modules) {
        this(budgets,decodeBytes,signatures,timeout,timings,overlay,contexts,sources,modules,new AnalysisContext(AnalysisContext.Limits.defaults()));
    }
    public JavacRunConfiguration {
        Objects.requireNonNull(analysisContext);
        analysisContext.charge(1,1,128L*(modelBudgets.size()+contexts.size()+1L));
        modelBudgets = Map.copyOf(modelBudgets);
        contexts = List.copyOf(contexts);
        Objects.requireNonNull(overlay);
        Objects.requireNonNull(sourcePlan);
        Objects.requireNonNull(moduleInputs);
        if (decodeBudgetBytes < (64L << 10) || decodeBudgetBytes > (2L << 30))
            throw new IllegalArgumentException("JAVA_MODEL_DECODE_BYTES_RANGE");
        if (batchTimeout.isNegative() || batchTimeout.isZero())
            throw new IllegalArgumentException("invalid active worker timeout");
    }
}
