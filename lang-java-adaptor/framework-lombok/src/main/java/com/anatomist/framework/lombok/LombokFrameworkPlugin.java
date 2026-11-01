package com.anatomist.framework.lombok;

import com.anatomist.config.LombokMode;
import com.anatomist.framework.*;
import java.util.List;

/** Compile-time Lombok module: declaration overlays and source-use evidence share the task account. */
public final class LombokFrameworkPlugin implements JavaFrameworkPlugin {
    @Override public String id() { return "lombok"; }
    @Override public String version() { return "1"; }
    @Override public JavaModelAnalyzerRegistry detachedAnalyzers(JavaFrameworkContext context) {
        if(context.config().lombokMode()==LombokMode.OFF) return JavaModelAnalyzerRegistry.NONE;
        var configs=context.capturedConfigs();
        var declaration=configs==null
                ? new LombokJavaModelExtension(context.projectRoot(),context.config().lombokStrict())
                : new LombokJavaModelExtension(context.projectRoot(),context.config().lombokStrict(),configs,context.analysisContext());
        var evidence=configs==null
                ? new LombokJavaCallEvidence(context.projectRoot())
                : new LombokJavaCallEvidence(context.projectRoot(),configs,context.analysisContext());
        return new JavaModelAnalyzerRegistry(List.of(declaration),List.of(),List.of(),List.of(),List.of(),List.of(evidence));
    }
}
