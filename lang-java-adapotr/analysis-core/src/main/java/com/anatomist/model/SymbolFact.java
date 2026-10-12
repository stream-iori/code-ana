package com.anatomist.model;

/** Minimal language-provider declaration exposed to framework/configuration analyzers. */
public record SymbolFact(
        String id,
        String symbolId,
        String kind,
        String module,
        String scope,
        String sourceFile,
        String metadata) {}
