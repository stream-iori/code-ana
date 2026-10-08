package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;

import java.util.List;

/** Emits facts from project resources after Java facts have been staged. */
public interface ProjectResourceAnalyzer extends ExtensionPoint {
    boolean enabled(JavaFrameworkContext context);

    ResourceSelector selector();

    void analyze(
            JavaFrameworkContext context,
            List<ProjectResource> resources,
            ProjectFactView facts,
            ExtractionResult result);
}
