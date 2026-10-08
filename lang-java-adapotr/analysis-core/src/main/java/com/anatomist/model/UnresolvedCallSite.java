package com.anatomist.model;

import java.util.Objects;

/** A real source call site without a declaration target; it never creates a guessed edge. */
public record UnresolvedCallSite(
        String callerId,
        String sourceFile,
        int beginLine,
        int beginColumn,
        int endLine,
        int endColumn,
        int ordinal,
        String syntaxTarget,
        String receiverStaticType,
        String dispatchKind,
        String context,
        String metadata,
        String producerId) {
    public UnresolvedCallSite {
        Objects.requireNonNull(callerId);
        Objects.requireNonNull(sourceFile);
        Objects.requireNonNull(syntaxTarget);
        Objects.requireNonNull(producerId);
        if (callerId.isBlank()
                || sourceFile.isBlank()
                || ordinal < 0
                || beginLine < 1
                || beginColumn < 1
                || endLine < beginLine
                || endColumn < 1
                || endLine == beginLine && endColumn < beginColumn)
            throw new IllegalArgumentException("invalid source call site");
    }

    public UnresolvedCallSite withProducer(String producer) {
        return new UnresolvedCallSite(
                callerId,
                sourceFile,
                beginLine,
                beginColumn,
                endLine,
                endColumn,
                ordinal,
                syntaxTarget,
                receiverStaticType,
                dispatchKind,
                context,
                metadata,
                producer);
    }

    public UnresolvedCallSite withCaller(String caller) {
        return new UnresolvedCallSite(
                caller,
                sourceFile,
                beginLine,
                beginColumn,
                endLine,
                endColumn,
                ordinal,
                syntaxTarget,
                receiverStaticType,
                dispatchKind,
                context,
                metadata,
                producerId);
    }

    public UnresolvedCallSite withMetadata(String value) {
        return new UnresolvedCallSite(
                callerId,
                sourceFile,
                beginLine,
                beginColumn,
                endLine,
                endColumn,
                ordinal,
                syntaxTarget,
                receiverStaticType,
                dispatchKind,
                context,
                value,
                producerId);
    }
}
