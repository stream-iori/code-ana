package com.anatomist.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExtractionResult {
    public static final int FLUSH_THRESHOLD = 50_000;

    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final List<Annotation> annotations = new ArrayList<>();
    public final List<AnnotationMetaRelation> annotationMetaRelations = new ArrayList<>();
    public final List<SemanticAnnotation> semanticAnnotations = new ArrayList<>();
    public final List<Declaration> declarations = new ArrayList<>();
    public final List<UnresolvedCallSite> unresolvedCallSites = new ArrayList<>();
    public final Map<String, Object> stats = new HashMap<>();

    /** Number of graph facts currently retained by this batch. */
    public int factCount() {
        return nodes.size()
                + edges.size()
                + annotations.size()
                + annotationMetaRelations.size()
                + semanticAnnotations.size()
                + declarations.size()
                + unresolvedCallSites.size();
    }

    /** Merge one successfully isolated extension result into this batch. */
    public void addFacts(ExtractionResult other) {
        if (other == null) return;
        nodes.addAll(other.nodes);
        edges.addAll(other.edges);
        annotations.addAll(other.annotations);
        annotationMetaRelations.addAll(other.annotationMetaRelations);
        semanticAnnotations.addAll(other.semanticAnnotations);
        declarations.addAll(other.declarations);
        unresolvedCallSites.addAll(other.unresolvedCallSites);
        other.stats.forEach(
                (key, value) -> {
                    if (value instanceof Number number
                            && stats.get(key) instanceof Number current) {
                        stats.put(key, current.longValue() + number.longValue());
                    } else {
                        stats.put(key, value);
                    }
                });
    }

    /** Release all graph facts after a staging flush while preserving aggregate stats. */
    public void clearFacts() {
        nodes.clear();
        edges.clear();
        annotations.clear();
        annotationMetaRelations.clear();
        semanticAnnotations.clear();
        declarations.clear();
        unresolvedCallSites.clear();
    }
}
