package com.anatomist.model;

import java.util.List;

/** AST-backed Java declaration staged separately and embedded into its final node. */
public class Declaration {
    public String symbolId;
    public String domain;
    public String language;
    public String providerId;
    public String entityKind;
    public String languageKind;
    public String qualifiedName;
    public String label;
    public String kind;
    public String declarationKind;
    public String typeKind;
    public String visibility;
    public List<String> modifiers = List.of();
    public List<String> declaredModifiers = List.of();
    public List<String> implicitModifiers = List.of();
    public String declaringType;
    public String namespace;
    public String sourceFile;
    public String sourceLocation;
    public Integer beginLine;
    public Integer beginColumn;
    public Integer endLine;
    public Integer endColumn;
    public String module;
    public String scope;
    public int nestingDepth;
    public boolean directMember;
    public boolean synthetic;
    public boolean bindingResolved;
    public String producerId;
}
