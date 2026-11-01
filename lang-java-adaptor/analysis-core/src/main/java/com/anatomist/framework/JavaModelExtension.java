package com.anatomist.framework;

import com.anatomist.core.AnalysisContext;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.JavaAnalysisContext;

/** Augments detached declarations; every implementation must provide the budgeted entry. */
public interface JavaModelExtension extends ExtensionPoint {
    default void verifyInputs() {}

    /** Explicit diagnostic convenience, with a finite account. Production supplies its task account. */
    default JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries) {
        return augment(context, binaries, new AnalysisContext(AnalysisContext.Limits.defaults()));
    }

    JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries,
            AnalysisContext resources);
}
