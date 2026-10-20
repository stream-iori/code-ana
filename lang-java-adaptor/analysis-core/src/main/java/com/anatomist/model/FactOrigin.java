package com.anatomist.model;

/** Applies source and producer ownership to a newly emitted slice of facts. */
public final class FactOrigin {
    private FactOrigin() {}

    public static Cursor cursor(ExtractionResult result) {
        return new Cursor(
                result.nodes.size(),
                result.edges.size(),
                result.annotations.size(),
                result.annotationMetaRelations.size(),
                result.declarations.size(),
                result.unresolvedCallSites.size());
    }

    public static Cursor beginning() {
        return new Cursor(0, 0, 0, 0, 0, 0);
    }

    public static void stamp(
            ExtractionResult result, Cursor start, String sourceFile, String producerId) {
        stamp(result, start, sourceFile, producerId, true);
    }

    /** Apply core defaults without overwriting an AST extension's explicit ownership. */
    public static void stampDefault(
            ExtractionResult result, Cursor start, String sourceFile, String producerId) {
        stamp(result, start, sourceFile, producerId, false);
    }

    private static void stamp(
            ExtractionResult result,
            Cursor start,
            String sourceFile,
            String producerId,
            boolean forceProducer) {
        for (int i = start.nodes(); i < result.nodes.size(); i++) {
            Node fact = result.nodes.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            if (forceProducer || fact.producerId == null) fact.producerId = producerId;
        }
        for (int i = start.edges(); i < result.edges.size(); i++) {
            Edge fact = result.edges.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            if (forceProducer || fact.producerId == null) fact.producerId = producerId;
        }
        for (int i = start.annotations(); i < result.annotations.size(); i++) {
            Annotation fact = result.annotations.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            if (forceProducer || fact.producerId == null) fact.producerId = producerId;
        }
        for (int i = start.annotationMetaRelations();
                i < result.annotationMetaRelations.size();
                i++) {
            AnnotationMetaRelation fact = result.annotationMetaRelations.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            if (forceProducer || fact.producerId == null) fact.producerId = producerId;
        }
        for (int i = start.declarations(); i < result.declarations.size(); i++) {
            Declaration fact = result.declarations.get(i);
            if (fact.sourceFile == null) fact.sourceFile = sourceFile;
            if (forceProducer || fact.producerId == null) fact.producerId = producerId;
        }
        if (forceProducer)
            for (int i = start.unresolvedCallSites(); i < result.unresolvedCallSites.size(); i++)
                result.unresolvedCallSites.set(
                        i, result.unresolvedCallSites.get(i).withProducer(producerId));
    }

    public record Cursor(
            int nodes,
            int edges,
            int annotations,
            int annotationMetaRelations,
            int declarations,
            int unresolvedCallSites) {
        public Cursor(
                int nodes,
                int edges,
                int annotations,
                int annotationMetaRelations,
                int declarations) {
            this(nodes, edges, annotations, annotationMetaRelations, declarations, 0);
        }
    }
}
