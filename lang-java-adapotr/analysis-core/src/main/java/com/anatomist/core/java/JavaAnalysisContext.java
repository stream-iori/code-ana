package com.anatomist.core.java;

import com.anatomist.core.SourceIdentity;
import com.anatomist.core.SourceScope;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One validated worker response, consumed synchronously before the next context is opened. */
public record JavaAnalysisContext(
        String module,
        SourceScope scope,
        List<JavaUnit> units,
        Set<Path> publish,
        List<JavaDiagnostic> diagnostics,
        String analysisFailure,
        Map<Path, SourceIdentity> sourceIdentities,
        List<Path> classpath,
        String classpathSource,
        List<JavaClasspathEntry> classpathEntries,
        int targetRelease) {
    public JavaAnalysisContext {
        units = List.copyOf(units);
        publish = Set.copyOf(publish);
        diagnostics = List.copyOf(diagnostics);
        sourceIdentities = Map.copyOf(sourceIdentities);
        classpath = List.copyOf(classpath);
        classpathSource = java.util.Objects.requireNonNull(classpathSource);
        classpathEntries = List.copyOf(classpathEntries);
    }

    public JavaAnalysisContext(
            String module,
            SourceScope scope,
            List<JavaUnit> units,
            Set<Path> publish,
            List<JavaDiagnostic> diagnostics,
            String analysisFailure,
            Map<Path, SourceIdentity> sourceIdentities,
            List<Path> classpath,
            String classpathSource,
            List<JavaClasspathEntry> classpathEntries) {
        this(
                module,
                scope,
                units,
                publish,
                diagnostics,
                analysisFailure,
                sourceIdentities,
                classpath,
                classpathSource,
                classpathEntries,
                0);
    }

    public JavaAnalysisContext(
            String module,
            SourceScope scope,
            List<JavaUnit> units,
            Set<Path> publish,
            List<JavaDiagnostic> diagnostics,
            String analysisFailure,
            Map<Path, SourceIdentity> sourceIdentities,
            List<Path> classpath,
            String classpathSource) {
        this(
                module,
                scope,
                units,
                publish,
                diagnostics,
                analysisFailure,
                sourceIdentities,
                classpath,
                classpathSource,
                List.of());
    }

    public JavaAnalysisContext(
            String module,
            SourceScope scope,
            List<JavaUnit> units,
            Set<Path> publish,
            List<JavaDiagnostic> diagnostics,
            String analysisFailure,
            Map<Path, SourceIdentity> sourceIdentities) {
        this(
                module,
                scope,
                units,
                publish,
                diagnostics,
                analysisFailure,
                sourceIdentities,
                List.of(),
                "unknown",
                List.of());
    }

    public JavaAnalysisContext(
            String module,
            SourceScope scope,
            List<JavaUnit> units,
            Set<Path> publish,
            List<JavaDiagnostic> diagnostics,
            String analysisFailure) {
        this(
                module,
                scope,
                units,
                publish,
                diagnostics,
                analysisFailure,
                Map.of(),
                List.of(),
                "unknown",
                List.of());
    }

    public SourceIdentity identity(Path path) {
        return sourceIdentities.getOrDefault(path, new SourceIdentity(module, scope));
    }
}
