package com.anatomist.application;

import com.anatomist.framework.*;
import com.anatomist.framework.sofa.SofaFrameworkPlugin;
import com.anatomist.framework.lombok.LombokFrameworkPlugin;
import com.anatomist.framework.spring.SpringFrameworkPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The single, explicit build-time composition point for Java framework plugins. */
public final class FrameworkPlugins {
    private static final FrameworkPlugins BUILT_INS =
            selected(new SpringFrameworkPlugin(), true);
    private final List<JavaFrameworkPlugin> plugins;

    FrameworkPlugins(List<JavaFrameworkPlugin> plugins) {
        this.plugins = List.copyOf(plugins);
        Set<String> seen = new HashSet<>();
        for (JavaFrameworkPlugin plugin : this.plugins) {
            if (plugin == null
                    || plugin.id() == null
                    || plugin.id().isBlank()
                    || plugin.version() == null
                    || plugin.version().isBlank()) {
                throw new IllegalArgumentException("plugin id and version must be present");
            }
            if (!seen.add(plugin.id()))
                throw new IllegalArgumentException("duplicate plugin id: " + plugin.id());
            for (String required : plugin.requires()) {
                if (!seen.contains(required))
                    throw new IllegalArgumentException(
                            "plugin " + plugin.id() + " requires earlier plugin " + required);
            }
        }
    }

    /** Every production selection includes the optional Lombok module; the module owns its mode check. */
    public static FrameworkPlugins selected(JavaFrameworkPlugin spring, boolean sofa) {
        var plugins = new ArrayList<JavaFrameworkPlugin>();
        if (spring != null) plugins.add(spring);
        if (sofa) plugins.add(new SofaFrameworkPlugin());
        plugins.add(new LombokFrameworkPlugin());
        return new FrameworkPlugins(plugins);
    }

    public static FrameworkPlugins builtIns() {
        return BUILT_INS;
    }

    public String fingerprint() {
        return prepareDetached(
                        new JavaFrameworkContext(
                                Path.of("."),
                                List.of(),
                                new com.anatomist.config.ProjectConfig(),
                                false))
                .fingerprint();
    }

    public JavaModelAnalyzerRegistry prepareDetached(JavaFrameworkContext context) {
        return prepareDetached(context, context.capturedConfigs());
    }

    /** Boundary overload: config files come from the captured logical directory tree. */
    public JavaModelAnalyzerRegistry prepareDetached(
            JavaFrameworkContext context, java.util.Map<Path, String> capturedConfigs) {
        context = new JavaFrameworkContext(context.projectRoot(),context.sourcePaths(),context.config(),context.springXml(),context.analysisContext(),capturedConfigs);
        var declarations = new ArrayList<JavaModelExtension>();
        var analyzers = new ArrayList<JavaModelAnalyzer>();
        var providers = new ArrayList<ProjectResourceProvider>();
        var resources = new ArrayList<ProjectResourceAnalyzer>();
        var contracts = new ArrayList<String>();
        var evidence = new ArrayList<JavaCallSiteEvidenceProvider>();
        for (JavaFrameworkPlugin plugin : plugins) {
            var registry = plugin.detachedAnalyzers(context);
            declarations.addAll(registry.declarations());
            analyzers.addAll(registry.analyzers());
            evidence.addAll(registry.callSiteEvidenceProviders());
            providers.addAll(registry.resourceProviders());
            resources.addAll(registry.resourceAnalyzers());
            contracts.add(
                    com.anatomist.json.Json.writeCompact(
                            List.of(
                                    plugin.getClass().getName(),
                                    plugin.id(),
                                    plugin.version(),
                                    plugin.requires())));
        }
        return new JavaModelAnalyzerRegistry(
                declarations, analyzers, providers, resources, contracts, evidence);
    }
}
