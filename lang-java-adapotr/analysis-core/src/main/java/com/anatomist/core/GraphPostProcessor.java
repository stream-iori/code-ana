package com.anatomist.core;

import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GraphPostProcessor {

    public record Summary(int reboundExternalTargets, int droppedDanglingFacts) {}

    public Summary process(ExtractionResult result) {
        return process(result, Set.of());
    }

    public Summary process(ExtractionResult result, Set<String> survivingNodeIds) {
        return process(result, EdgeTargetBinder.prepare(survivingNodeIds));
    }

    public Summary process(ExtractionResult result, EdgeTargetBinder.Catalogue catalogue) {
        if (result == null) return new Summary(0, 0);
        backfillFactOrigins(result);
        int rebound = EdgeTargetBinder.bindExternalTargets(result, catalogue);
        int dropped = pruneDanglingInternalEdges(result, catalogue);
        return new Summary(rebound, dropped);
    }

    private static void backfillFactOrigins(ExtractionResult result) {
        Map<String, String> sourceFiles = new HashMap<>();
        for (Node node : result.nodes) {
            if (node.id != null && node.sourceFile != null) {
                sourceFiles.putIfAbsent(node.id, node.sourceFile);
            }
        }
        for (Edge edge : result.edges) {
            if (edge.sourceFile == null && edge.sourceId != null) {
                edge.sourceFile = sourceFiles.get(edge.sourceId);
            }
        }
        for (var annotation : result.annotations) {
            if (annotation.sourceFile == null && annotation.nodeId != null) {
                annotation.sourceFile = sourceFiles.get(annotation.nodeId);
            }
        }
    }

    private static int pruneDanglingInternalEdges(
            ExtractionResult r, EdgeTargetBinder.Catalogue catalogue) {
        Set<String> known = new HashSet<>();
        for (Node n : r.nodes) {
            if (n.id != null) known.add(n.id);
        }

        int before = r.edges.size() + r.annotations.size();
        boolean dbg = AnatomistLog.isDebugEnabled();
        r.edges.removeIf(
                e -> {
                    boolean drop =
                            !e.isExternal && (e.targetId == null || !known.contains(e.targetId) && !catalogue.contains(e.targetId));
                    if (drop && dbg) debugDrop("target", e);
                    return drop;
                });
        r.edges.removeIf(
                e -> {
                    boolean drop = e.sourceId == null || !known.contains(e.sourceId) && !catalogue.contains(e.sourceId);
                    if (drop && dbg) debugDrop("source", e);
                    return drop;
                });
        r.annotations.removeIf(
                a -> {
                    boolean drop = a.nodeId == null || !known.contains(a.nodeId) && !catalogue.contains(a.nodeId);
                    if (drop && dbg) {
                        AnatomistLog.debug(
                                "pruned annotation (dangling node): "
                                        + a.annotationFqn
                                        + " on "
                                        + a.nodeId);
                    }
                    return drop;
                });
        return before - r.edges.size() - r.annotations.size();
    }

    private static void debugDrop(String side, Edge e) {
        AnatomistLog.debug(
                "pruned edge (dangling "
                        + side
                        + "): "
                        + e.relation
                        + " "
                        + e.sourceId
                        + " -> "
                        + e.targetId);
    }
}
