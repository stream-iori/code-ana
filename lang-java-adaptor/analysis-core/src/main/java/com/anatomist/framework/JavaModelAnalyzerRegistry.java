package com.anatomist.framework;

import com.anatomist.core.ContentHash;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.java.JavaDeclarationOverlay;

import java.util.*;

/** Validated deterministic detached extensions, independent of the transition AST registry. */
public record JavaModelAnalyzerRegistry(
        List<JavaModelExtension> declarations,
        List<JavaModelAnalyzer> analyzers,
        List<ProjectResourceProvider> resourceProviders,
        List<ProjectResourceAnalyzer> resourceAnalyzers,
        List<String> pluginContracts,
        List<JavaCallSiteEvidenceProvider> callSiteEvidenceProviders) {
    public static final String META_KEY = "extension_fingerprint";
    public static final JavaModelAnalyzerRegistry NONE =
            new JavaModelAnalyzerRegistry(List.of(), List.of());

    public JavaModelAnalyzerRegistry(
            List<JavaModelExtension> declarations, List<JavaModelAnalyzer> analyzers) {
        this(declarations, analyzers, List.of(), List.of(), List.of(), List.of());
    }

    public JavaModelAnalyzerRegistry(
            List<JavaModelExtension> declarations,
            List<JavaModelAnalyzer> analyzers,
            List<ProjectResourceProvider> providers,
            List<ProjectResourceAnalyzer> resources) {
        this(declarations, analyzers, providers, resources, List.of(), List.of());
    }

    public JavaModelAnalyzerRegistry(
            List<JavaModelExtension> declarations,
            List<JavaModelAnalyzer> analyzers,
            List<ProjectResourceProvider> providers,
            List<ProjectResourceAnalyzer> resources,
            List<String> contracts) {
        this(declarations, analyzers, providers, resources, contracts, List.of());
    }

    public JavaModelAnalyzerRegistry {
        declarations = List.copyOf(declarations);
        analyzers = List.copyOf(analyzers);
        resourceProviders = List.copyOf(resourceProviders);
        resourceAnalyzers = List.copyOf(resourceAnalyzers);
        pluginContracts = List.copyOf(pluginContracts);
        callSiteEvidenceProviders = List.copyOf(callSiteEvidenceProviders);
        Set<String> ids = new HashSet<>();
        for (var extension :
                points(
                        declarations,
                        analyzers,
                        resourceProviders,
                        resourceAnalyzers,
                        callSiteEvidenceProviders)) {
            if (extension.id() == null || extension.id().isBlank() || !ids.add(extension.id()))
                throw new IllegalArgumentException("invalid or duplicate detached extension id");
        }
    }

    public JavaDeclarationOverlay overlays() {
        var overlays = new JavaModelExtensions(declarations);
        String identity = fingerprint();
        return new JavaDeclarationOverlay() {
            @Override
            public String fingerprint() {
                return identity;
            }

            @Override
            public boolean isEmpty() {
                return overlays.isEmpty();
            }

            @Override
            public void verifyInputs() {
                overlays.verifyInputs();
            }

            @Override
            public JavaAnalysisContext augment(
                    JavaAnalysisContext context, JavaBinaryRepository binaries) {
                return overlays.augment(context, binaries);
            }

            @Override
            public JavaAnalysisContext augment(
                    JavaAnalysisContext context,
                    JavaBinaryRepository binaries,
                    com.anatomist.core.IndexTimings timings) {
                return overlays.augment(context, binaries, timings);
            }
        };
    }

    public String fingerprint() {
        StringBuilder material = new StringBuilder("anatomist-detached-extensions-v1\n");
        for (var extension :
                points(
                        declarations,
                        analyzers,
                        resourceProviders,
                        resourceAnalyzers,
                        callSiteEvidenceProviders)) {
            for (String field :
                    List.of(
                            extension.getClass().getName(),
                            extension.id(),
                            extension.version(),
                            extension.producerId(),
                            extension.fingerprintMaterial()))
                material.append(field.length()).append(':').append(field);
            material.append('\n');
        }
        for (String contract : pluginContracts)
            material.append(contract.length()).append(':').append(contract);
        return ContentHash.sha256(material.toString());
    }

    private static List<ExtensionPoint> points(
            List<JavaModelExtension> declarations,
            List<JavaModelAnalyzer> analyzers,
            List<ProjectResourceProvider> providers,
            List<ProjectResourceAnalyzer> resources,
            List<JavaCallSiteEvidenceProvider> evidence) {
        List<ExtensionPoint> points = new ArrayList<>(declarations);
        points.addAll(analyzers);
        points.addAll(providers);
        points.addAll(resources);
        points.addAll(evidence);
        return points;
    }
}
