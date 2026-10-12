package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Runs every matching project analyzer over one shared resource inventory. */
public final class ProjectAnalysisRunner {
    public Set<String> run(
            JavaModelAnalyzerRegistry extensions,
            JavaFrameworkContext context,
            List<ProjectResource> inventory,
            ProjectFactView facts,
            ExtractionResult result,
            ExtensionReport report) {
        return run(extensions.resourceAnalyzers(), context, inventory, facts, result, report);
    }

    private Set<String> run(
            List<ProjectResourceAnalyzer> analyzers,
            JavaFrameworkContext context,
            List<ProjectResource> inventory,
            ProjectFactView facts,
            ExtractionResult result,
            ExtensionReport report) {
        Set<String> producers = new LinkedHashSet<>();
        for (ProjectResourceAnalyzer analyzer : analyzers) {
            List<ProjectResource> selected =
                    ExtensionExecution.run(
                            analyzer,
                            ExtensionExecution.Stage.PROJECT,
                            ExtensionExecution.Source.file(null),
                            report,
                            () ->
                                    analyzer.enabled(context)
                                            ? inventory.stream()
                                                    .filter(analyzer.selector()::matches)
                                                    .toList()
                                            : List.of(),
                            List.of());
            if (selected.isEmpty()) continue;
            if (ExtensionExecution.facts(
                    analyzer,
                    ExtensionExecution.Stage.PROJECT,
                    ExtensionExecution.Source.file(selected.getFirst().sourceFile()),
                    report,
                    result,
                    isolated -> analyzer.analyze(context, selected, facts, isolated))) {
                producers.add(analyzer.producerId());
            }
        }
        return Set.copyOf(producers);
    }
}
