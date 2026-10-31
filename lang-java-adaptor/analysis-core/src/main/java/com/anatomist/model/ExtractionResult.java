package com.anatomist.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExtractionResult {
    public static final int FLUSH_THRESHOLD = 50_000;

    public final List<Node> nodes;
    public final List<Edge> edges;
    public final List<Annotation> annotations;
    public final List<AnnotationMetaRelation> annotationMetaRelations;
    public final List<Declaration> declarations;
    public final List<UnresolvedCallSite> unresolvedCallSites;
    public final Map<String,Object> stats = new HashMap<>();
    private final com.anatomist.core.AnalysisContext analysis;
    public com.anatomist.core.AnalysisContext analysisContext() {return analysis;}
    public ExtractionResult() { this(null); }
    public ExtractionResult(com.anatomist.core.AnalysisContext context) {
        this.analysis=context;
        if(context!=null) context.charge(1,7,512);
        nodes = context==null ? new ArrayList<>() : new FactList<>(context);
        edges = context==null ? new ArrayList<>() : new FactList<>(context);
        annotations = context==null ? new ArrayList<>() : new FactList<>(context);
        annotationMetaRelations = context==null ? new ArrayList<>() : new FactList<>(context);
        declarations = context==null ? new ArrayList<>() : new FactList<>(context);
        unresolvedCallSites = context==null ? new ArrayList<>() : new FactList<>(context);
    }
    private static final class FactList<T> extends java.util.AbstractList<T> implements java.util.RandomAccess {
        private final ArrayList<T> values=new ArrayList<>();
        private final com.anatomist.core.AnalysisContext context;
        FactList(com.anatomist.core.AnalysisContext context) { this.context=context; }
        public int size() { return values.size(); }
        public T get(int index) { return values.get(index); }
        public void add(int index,T value) { context.charge(1,1,retained(value)); values.add(index,value); modCount++; }
        public T set(int index,T value) { context.charge(1,1,retained(value)); return values.set(index,value); }
        public T remove(int index) { T old=values.remove(index);modCount++;return old; }
        public void clear() { if(!values.isEmpty()) { values.clear();modCount++; } }
        public boolean addAll(java.util.Collection<? extends T> values) { return addAll(size(),values); }
        public boolean addAll(int index,java.util.Collection<? extends T> incoming) {
            long bytes=0;for(T value:incoming)bytes=Math.addExact(bytes,retained(value));
            context.charge(incoming.size(),incoming.size(),bytes);
            boolean changed=values.addAll(index,incoming);if(changed)modCount++;return changed;
        }
    }
    private static long text(String... values) { long bytes=256;for(String value:values) if(value!=null)bytes=Math.addExact(bytes,32L+3L*value.length());return bytes; }
    private static long retained(Object value) {
        if(value instanceof Node v)return text(v.id, v.symbolId, v.domain, v.language, v.providerId, v.entityKind, v.languageKind, v.label, v.kind, v.qualifiedName, v.pkg, v.namespace, v.sourceFile, v.sourceLocation, v.module, v.scope, v.metadata, v.producerId);
        if(value instanceof Edge v)return text(v.sourceId, v.targetId, v.externalTargetFqn, v.externalTargetSymbol, v.externalTargetLanguage, v.externalTargetProviderId, v.relation, v.semantic, v.mechanism, v.language, v.providerId, v.callKind, v.confidence, v.resolution, v.context, v.sourceFile, v.sourceLocation, v.syntaxTarget, v.receiverStaticType, v.metadata, v.producerId);
        if(value instanceof Annotation v)return text(v.nodeId, v.annotationFqn, v.rawName, v.attributes, v.targetKind, v.targetPath, v.language, v.providerId, v.mechanism, v.resolutionStatus, v.sourceLocation, v.sourceFile, v.producerId);
        if(value instanceof AnnotationMetaRelation v)return text(v.annotationFqn, v.metaAnnotationFqn, v.rawName, v.language, v.providerId, v.mechanism, v.resolutionStatus, v.sourceFile, v.sourceLocation, v.producerId);
        if(value instanceof Declaration v)return text(v.symbolId, v.domain, v.language, v.providerId, v.entityKind, v.languageKind, v.qualifiedName, v.label, v.kind, v.declarationKind, v.typeKind, v.visibility, v.declaringType, v.namespace, v.sourceFile, v.sourceLocation, v.module, v.scope, v.producerId);
        if(value instanceof UnresolvedCallSite v)return text(v.callerId(), v.sourceFile(), v.syntaxTarget(), v.receiverStaticType(), v.dispatchKind(), v.context(), v.metadata(), v.producerId());
        throw new IllegalArgumentException("UNKNOWN_GRAPH_FACT");
    }

    /** Number of graph facts currently retained by this batch. */
    public int factCount() {
        return nodes.size()
                + edges.size()
                + annotations.size()
                + annotationMetaRelations.size()
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
        declarations.clear();
        unresolvedCallSites.clear();
    }
}
