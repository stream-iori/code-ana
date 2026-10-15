package com.anatomist.core.javac;

import java.io.IOException;

/** Owns one lane until decode, model consumption and any rollback have finished. */
final class JavacAnalysisAttempt implements AutoCloseable {
    private JavacSignaturePlan.Input input;
    private final JavacDeclarationCache.Lease declarations;
    private final JavacSignaturePlan.CatalogueLease catalogues;
    private final JavacCompilationCache.Directory directory;
    private final JavacBatchProtocol.Request request;
    private boolean closed;
    private java.nio.file.Path compilationTarget;
    private boolean cached;
    private boolean processing;
    private JavacBatchProtocol.Result received;

    void received(JavacBatchProtocol.Result value) {
        received = value;
    }

    JavacBatchProtocol.Result received() {
        return received;
    }

    void compilation(java.nio.file.Path target, boolean cached, boolean processing) {
        this.compilationTarget = target;
        this.cached = cached;
        this.processing = processing;
    }

    java.nio.file.Path compilationTarget() {
        return compilationTarget;
    }

    java.nio.file.Path directory() {
        return directory.path();
    }

    boolean cached() {
        return cached;
    }

    boolean processing() {
        return processing;
    }

    JavacAnalysisAttempt(
            JavacSignaturePlan.Input input,
            JavacDeclarationCache.Lease declarations,
            JavacSignaturePlan.CatalogueLease catalogues,
            JavacCompilationCache.Directory directory,
            JavacBatchProtocol.Request request) {
        this.input = input;
        this.declarations = declarations;
        this.catalogues = catalogues;
        this.directory = directory;
        this.request = request;
    }

    JavacSignaturePlan.Input input() {
        return input;
    }

    JavacDeclarationCache.Lease declarations() {
        return declarations;
    }

    JavacBatchProtocol.Request request() {
        return request;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        input = null;
        Throwable failure = null;
        for (AutoCloseable resource : new AutoCloseable[] {declarations, catalogues, directory}) {
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IOException("javac attempt cleanup failed", failure);
    }
}
