package com.anatomist.framework;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.AnalysisContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Detached project inputs. A null configuration inventory is explicit diagnostic live input. */
public record JavaFrameworkContext(
        Path projectRoot, List<Path> sourcePaths, ProjectConfig config, boolean springXml,
        AnalysisContext analysisContext, Map<Path,String> capturedConfigs) {
    public JavaFrameworkContext(Path root,List<Path> paths,ProjectConfig config,boolean springXml) {
        this(root,paths,config,springXml,new AnalysisContext(AnalysisContext.Limits.defaults()),null);
    }
    public JavaFrameworkContext(Path root,List<Path> paths,ProjectConfig config,boolean springXml,AnalysisContext resources) {
        this(root,paths,config,springXml,resources,null);
    }
    public JavaFrameworkContext {
        projectRoot=Objects.requireNonNull(projectRoot).toAbsolutePath().normalize();
        sourcePaths=List.copyOf(sourcePaths);
        Objects.requireNonNull(config);
        Objects.requireNonNull(analysisContext);
        // Extensions admit/copy this captured inventory against the supplied task account.
        if(capturedConfigs!=null)capturedConfigs=java.util.Collections.unmodifiableMap(capturedConfigs);
    }
}
