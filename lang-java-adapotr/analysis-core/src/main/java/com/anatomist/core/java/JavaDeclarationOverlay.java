package com.anatomist.core.java;

import com.anatomist.core.ContentHash;
import com.anatomist.core.asmsolver.JavaBinaryRepository;

/**
 * Parsing-layer hook for detached declarations; framework composition lives above this boundary.
 */
public interface JavaDeclarationOverlay {
    JavaDeclarationOverlay NONE =
            new JavaDeclarationOverlay() {
                @Override
                public String fingerprint() {
                    return ContentHash.sha256("java-model-extensions-v1\n");
                }

                @Override
                public boolean isEmpty() {
                    return true;
                }

                @Override
                public JavaAnalysisContext augment(
                        JavaAnalysisContext context, JavaBinaryRepository binaries) {
                    return context;
                }
            };

    String fingerprint();

    default void verifyInputs() {}

    default boolean isEmpty() {
        return false;
    }

    JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries);

    default JavaAnalysisContext augment(
            JavaAnalysisContext context,
            JavaBinaryRepository binaries,
            com.anatomist.core.IndexTimings timings) {
        return augment(context, binaries);
    }
}
