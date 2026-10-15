package com.anatomist.framework;

import com.anatomist.core.ProjectScanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs enabled providers and builds one deterministic shared inventory. */
public final class ProjectResourceDiscovery {
    public List<ProjectResource> discover(
            JavaModelAnalyzerRegistry extensions,
            JavaFrameworkContext context,
            ProjectScanner scanner,
            ExtensionReport report) {
        return discover(extensions.resourceProviders(), context, scanner, report);
    }

    private List<ProjectResource> discover(
            List<ProjectResourceProvider> providers,
            JavaFrameworkContext context,
            ProjectScanner scanner,
            ExtensionReport report) {
        Map<String, ProjectResource> unique = new LinkedHashMap<>();
        for (ProjectResourceProvider provider : providers) {
            ExtensionExecution.run(
                    provider,
                    ExtensionExecution.Stage.RESOURCE,
                    ExtensionExecution.Source.file(null),
                    report,
                    () -> {
                        if (!provider.enabled(context)) return false;
                        for (ProjectResource resource : provider.discover(context, scanner)) {
                            if (resource == null
                                    || resource.kind() == null
                                    || resource.sourceFile() == null) continue;
                            unique.putIfAbsent(
                                    resource.kind() + "\u0000" + resource.sourceFile(), resource);
                        }
                        return true;
                    },
                    false);
        }
        List<ProjectResource> out = new ArrayList<>(unique.values());
        out.sort(
                Comparator.comparing(ProjectResource::kind)
                        .thenComparing(ProjectResource::sourceFile));
        return List.copyOf(out);
    }
}
