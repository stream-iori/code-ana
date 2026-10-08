package com.anatomist.framework;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.JavaAnalysisContext;

/** Augments detached declaration inventories, without creating executable bodies or parser ASTs. */
public interface JavaModelExtension extends ExtensionPoint {
    default void verifyInputs() {}

    JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries);
}
