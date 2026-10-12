package com.anatomist.model;

public class Annotation {
    public String nodeId;
    public String annotationFqn;

    /** Name as written in source, retained even when FQN resolution fails. */
    public String rawName;

    public String attributes;

    /** Structural target (type/callable/value) and optional sub-target path. */
    public String targetKind;

    public String targetPath;
    public String language;
    public String providerId;
    public String mechanism;
    public String resolutionStatus;
    public String sourceLocation;
    public Integer beginLine;
    public Integer beginColumn;
    public Integer endLine;
    public Integer endColumn;

    /** Source identity used for producer-scoped incremental replacement. */
    public String sourceFile;

    public String producerId;
}
