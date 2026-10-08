package com.anatomist.model;

public class Node {
    public String id;
    public String symbolId;

    /** Language-neutral semantic domain and provider provenance. */
    public String domain;

    public String language;
    public String providerId;
    public String entityKind;
    public String languageKind;
    public String label;
    public String kind;
    public String qualifiedName;
    public String pkg;
    public String namespace;
    public String sourceFile;
    public String sourceLocation;
    public Integer beginLine;
    public Integer beginColumn;
    public Integer endLine;
    public Integer endColumn;
    public Integer sourceOrdinal;
    public String module;
    public String scope;
    public String metadata;
    public String producerId;

    public Node() {}

    public Node sourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
        return this;
    }

    public Node sourceLocation(String loc) {
        this.sourceLocation = loc;
        return this;
    }

    public Node module(String module) {
        this.module = module;
        return this;
    }

    public Node scope(String scope) {
        this.scope = scope;
        return this;
    }

    public Node metadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    public Node producerId(String producerId) {
        this.producerId = producerId;
        return this;
    }
}
