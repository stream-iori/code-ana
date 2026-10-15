package com.anatomist.framework;

import com.anatomist.core.ProjectScanner;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Discovers non-Java project resources for one or more project analyzers. */
public interface ProjectResourceProvider extends ExtensionPoint {
    boolean enabled(JavaFrameworkContext context);

    Set<String> kinds();

    boolean mayContain(Path path);

    List<ProjectResource> discover(JavaFrameworkContext context, ProjectScanner scanner);
}
