package com.anatomist.framework;

import com.anatomist.config.ProjectConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Project inputs for detached framework analysis; contains no parser or compiler state. */
public record JavaFrameworkContext(
        Path projectRoot, List<Path> sourcePaths, ProjectConfig config, boolean springXml, com.anatomist.core.AnalysisContext analysisContext) {
    public JavaFrameworkContext(Path root, List<Path> paths, ProjectConfig config, boolean springXml) {
        this(root, paths, config, springXml, new com.anatomist.core.AnalysisContext(com.anatomist.core.AnalysisContext.Limits.defaults()));
    }
    public JavaFrameworkContext {
        projectRoot = Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
        sourcePaths = List.copyOf(sourcePaths);
        Objects.requireNonNull(config);
        Objects.requireNonNull(analysisContext);
    }
}
