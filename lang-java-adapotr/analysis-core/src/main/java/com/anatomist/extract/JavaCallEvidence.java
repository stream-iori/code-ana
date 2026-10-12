package com.anatomist.extract;

import com.anatomist.core.java.JavaNode;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.ExtractionResult;

import java.util.*;

/** Isolated enrichment of existing fallback sites, independent of call target selection. */
final class JavaCallEvidence {
    private record Position(int beginLine, int beginColumn, int endLine, int endColumn) {}

    private record Enrichment(String metadata, String counter) {}

    private JavaCallEvidence() {}

    static void enrich(
            JavaUnitAnalysis unit,
            ExtractionResult facts,
            List<JavaCallSiteEvidenceProvider> providers,
            ExtensionReport report) {
        if (providers.isEmpty()) return;
        Map<Position, JavaNode> calls = new HashMap<>();
        var source = unit.model().source(unit.source());
        for (var node : unit.model().syntax(unit.source()).nodes()) {
            if (!node.kind().equals("METHOD_INVOCATION") || !node.span().present()) continue;
            var begin = source.position(node.start());
            var end = source.position(Math.max(node.start(), node.end() - 1));
            var position = new Position(begin.line(), begin.column(), end.line(), end.column());
            if (calls.putIfAbsent(position, node) != null)
                throw new IllegalArgumentException("duplicate source call position");
        }
        for (int i = 0; i < facts.unresolvedCallSites.size(); i++) {
            var site = facts.unresolvedCallSites.get(i);
            var call =
                    calls.get(
                            new Position(
                                    site.beginLine(),
                                    site.beginColumn(),
                                    site.endLine(),
                                    site.endColumn()));
            String metadata =
                    observe(
                            unit,
                            call,
                            site.receiverStaticType(),
                            site.dispatchKind(),
                            "unresolved",
                            site.metadata(),
                            providers,
                            report);
            facts.unresolvedCallSites.set(i, site.withMetadata(metadata));
        }
        for (var edge : facts.edges) {
            if (!edge.relation.equals("CALLS") || !edge.confidence.equals("INFERRED")) continue;
            if (edge.beginLine == null
                    || edge.beginColumn == null
                    || edge.endLine == null
                    || edge.endColumn == null) continue;
            var call =
                    calls.get(
                            new Position(
                                    edge.beginLine,
                                    edge.beginColumn,
                                    edge.endLine,
                                    edge.endColumn));
            edge.metadata =
                    observe(
                            unit,
                            call,
                            edge.receiverStaticType,
                            edge.callKind,
                            edge.resolution,
                            edge.metadata,
                            providers,
                            report);
        }
    }

    private static String observe(
            JavaUnitAnalysis unit,
            JavaNode call,
            String owner,
            String kind,
            String resolution,
            String metadata,
            List<JavaCallSiteEvidenceProvider> providers,
            ExtensionReport report) {
        if (call == null) return metadata;
        for (var provider : providers) {
            String original = metadata;
            Enrichment result =
                    ExtensionExecution.run(
                            provider,
                            ExtensionExecution.Stage.CALL,
                            new ExtensionExecution.Source(
                                    unit.sourceFile(),
                                    unit.identity().module(),
                                    unit.identity().scope().name()),
                            report,
                            () -> {
                                var evidence =
                                        provider.observe(
                                                unit,
                                                call,
                                                new JavaCallSiteEvidenceProvider.FallbackCallSite(
                                                        owner, kind, resolution));
                                if (evidence.isEmpty()) return null;
                                Map<String, Object> values = new LinkedHashMap<>();
                                if (original != null
                                        && Json.parseTree(original) instanceof Map<?, ?> existing)
                                    existing.forEach(
                                            (key, value) -> values.put(key.toString(), value));
                                var found = evidence.get();
                                if (values.containsKey(found.namespace()))
                                    throw new IllegalArgumentException(
                                            "duplicate call evidence namespace "
                                                    + found.namespace());
                                values.put(found.namespace(), found.value());
                                return new Enrichment(Json.writeCompact(values), found.counter());
                            },
                            null);
            if (result != null) {
                metadata = result.metadata();
                report.increment(result.counter(), 1);
            }
        }
        return metadata;
    }
}
