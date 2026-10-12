package com.anatomist.model;

import static com.anatomist.model.GraphConstants.Confidence.EXTRACTED;
import static com.anatomist.model.GraphConstants.Relation.CALLS;
import static com.anatomist.model.GraphConstants.Relation.REFERENCES;

public class Edge {
    public String sourceId;
    public String targetId;
    public String externalTargetFqn;
    public String externalTargetSymbol;
    public String externalTargetLanguage;
    public String externalTargetProviderId;
    public String relation;
    public String semantic;
    public String mechanism;
    public String language;
    public String providerId;
    public String callKind;
    public String confidence;
    public String resolution;
    public String context;
    public boolean isExternal;
    public String sourceFile;
    public String sourceLocation;

    /** Exact source range for source-backed sites; null for derived relations. */
    public Integer beginLine;

    public Integer beginColumn;
    public Integer endLine;
    public Integer endColumn;
    public Integer sourceOrdinal;
    public String syntaxTarget;
    public String receiverStaticType;
    public String metadata;
    public String producerId;

    public Edge() {}

    /**
     * Convert the extractor draft fields into the exclusive persisted target model. Contradictory
     * target fields fail before they can reach SQLite.
     */
    public EdgeTarget target() {
        if (isExternal) {
            if (targetId != null) {
                throw new IllegalStateException(
                        "external edge cannot also have internal target: " + relation);
            }
            return new EdgeTarget.External(externalTargetFqn, resolution);
        }
        if (externalTargetFqn != null) {
            throw new IllegalStateException(
                    "internal edge cannot also have external target: " + relation);
        }
        return new EdgeTarget.Internal(targetId);
    }

    public static Edge call(
            String sourceId, String targetId, String callKind, String sourceLocation) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.targetId = targetId;
        e.relation = CALLS;
        e.callKind = callKind;
        e.confidence = EXTRACTED;
        e.sourceLocation = sourceLocation;
        e.isExternal = false;
        return e;
    }

    public static Edge externalCall(
            String sourceId, String externalTargetFqn, String callKind, String sourceLocation) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.externalTargetFqn = externalTargetFqn;
        e.relation = CALLS;
        e.callKind = callKind;
        e.confidence = EXTRACTED;
        e.sourceLocation = sourceLocation;
        e.isExternal = true;
        e.resolution = GraphConstants.Resolution.CLASSPATH;
        return e;
    }

    public static Edge reference(String sourceId, String targetId, boolean isExternal) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.targetId = targetId;
        e.relation = REFERENCES;
        e.confidence = EXTRACTED;
        e.isExternal = isExternal;
        return e;
    }

    public Edge context(String context) {
        this.context = context;
        return this;
    }

    public Edge sourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
        return this;
    }

    public Edge metadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    public Edge producerId(String producerId) {
        this.producerId = producerId;
        return this;
    }
}
