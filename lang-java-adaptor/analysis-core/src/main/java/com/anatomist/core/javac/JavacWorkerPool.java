package com.anatomist.core.javac;

import com.anatomist.core.IndexTimings;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Owns compiler lanes and joins all work before releasing its processes. */
final class JavacWorkerPool implements AutoCloseable {
    final List<JavacWorkerSession> workers = new ArrayList<>();
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final IndexTimings timings;
    private boolean closed;

    JavacWorkerPool(
            List<String> command, Path root, Duration timeout, int threads, IndexTimings timings) {
        this.timings = timings;
        for (int i = 0; i < threads; i++)
            workers.add(new JavacWorkerSession(command, root, timeout));
    }

    void execute(List<JavacAnalysisAttempt> attempts) throws IOException {
        long started = System.nanoTime();
        List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
        try {
            for (int slot = 0; slot < attempts.size(); slot++) {
                var attempt = attempts.get(slot);
                if (attempt.cached()) continue;
                var worker = workers.get(slot);
                pending.add(
                        executor.submit(
                                () -> {
                                    attempt.received(
                                            worker.executeBatch(List.of(attempt.request()))
                                                    .getFirst());
                                    return null;
                                }));
            }
            for (var future : pending) future.get();
        } catch (InterruptedException failure) {
            abort(pending, failure);
            Thread.currentThread().interrupt();
            throw new IOException("javac batch interrupted", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            abort(pending, failure);
            if (failure.getCause() instanceof IOException io) throw io;
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IOException("javac batch failed", failure.getCause());
        } catch (RuntimeException | Error failure) {
            abort(pending, failure);
            throw failure;
        } finally {
            pending.forEach(future -> future.cancel(true));
            if (timings != null) timings.stop("javac_worker_wait", started);
        }
    }

    private void abort(List<java.util.concurrent.Future<?>> pending, Throwable failure) {
        pending.forEach(future -> future.cancel(true));
        try {
            close();
        } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        executor.shutdownNow();
        for (var worker : workers)
            try {
                worker.close();
            } catch (Throwable error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        executor.close();
        if (failure != null)
            throw new IllegalStateException("failed to close javac workers", failure);
    }
}
