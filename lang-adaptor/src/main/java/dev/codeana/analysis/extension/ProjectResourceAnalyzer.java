package dev.codeana.analysis.extension;

import java.util.*;

/** Runs after Java facts and resource inventory have been committed. */
public interface ProjectResourceAnalyzer extends ExtensionPoint {
    Set<String> resourceKinds();
    default ResourceSelector selector() { return resource -> resourceKinds().contains(resource.kind()); }
    void analyzeResources(ExtensionContext context, List<ProjectResource> resources, FactSink facts)
            throws Exception;
}
