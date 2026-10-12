package com.anatomist.core;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.java.JavaDeclarationOverlay;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Detached semantic operations used by publication; no compatibility AST is returned. */
public interface JavaSemanticBackend {
    void setTimings(IndexTimings timings);

    void setSourcePolicy(ScanPolicy policy);

    void setResourceLimits(ParserResourceLimits limits);

    int effectiveSourceCacheSize();

    long effectiveCombinedCacheSize();

    long effectiveAsmCacheMaxBytes();

    java.util.Map<String, String> environmentMetadata();

    String environmentFingerprint();

    void setDeclarationOverlay(JavaDeclarationOverlay overlay);

    ParseInventory analyzeFiles(List<Path> files, Consumer<JavaAnalysisContext> consumer);

    JavaBinaryRepository openBinaryRepository(JavaAnalysisContext context);

    default com.anatomist.core.java.JavaAnalysisStats analysisStats() {
        return com.anatomist.core.java.JavaAnalysisStats.EMPTY;
    }
}
