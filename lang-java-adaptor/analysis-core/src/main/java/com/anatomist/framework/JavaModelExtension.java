package com.anatomist.framework;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.JavaAnalysisContext;

/** Augments detached declaration inventories, without creating executable bodies or parser ASTs. */
public interface JavaModelExtension extends ExtensionPoint {
    default void verifyInputs() {}

    JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries);
    /** The production path cannot silently call a legacy unmetered callback. */
    default JavaAnalysisContext augment(JavaAnalysisContext context,JavaBinaryRepository binaries,
            com.anatomist.core.AnalysisContext resources) {
        if(resources==null)return augment(context,binaries);
        throw new com.anatomist.core.FatalAnalysisFailure("MODEL_EXTENSION_BUDGET_REQUIRED: "+id());
    }
}
