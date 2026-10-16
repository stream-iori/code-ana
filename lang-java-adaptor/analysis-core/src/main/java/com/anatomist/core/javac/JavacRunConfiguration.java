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
        JavacMavenInputs moduleInputs) {
    public JavacRunConfiguration {
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
