package com.anatomist.core.process;

import java.io.*;
import java.util.concurrent.*;

/** Owns a serial, reusable subprocess; protocols decide when a completed reply retires it. */
public final class ProcessConversation implements AutoCloseable {
    @FunctionalInterface
    public interface Exchange<T> {
        Reply<T> run(InputStream input, OutputStream output) throws Exception;
    }

    public record Reply<T>(T value, boolean retire) {}

    private final ProcessRunner.Spec spec;
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private Process process;
    private InputStream input;
    private OutputStream output;
    private AutoCloseable registration;
    private ProcessRunner.Tail tail = new ProcessRunner.Tail(ProcessRunner.SMALL_OUTPUT);
    private Thread errorDrain;
    private volatile int launches;
    private boolean closed;

    public ProcessConversation(ProcessRunner.Spec spec) {
        if (spec.mergeError())
            throw new IllegalArgumentException("conversation requires separate stderr");
        this.spec = spec;
    }

    public int launches() {
        return launches;
    }

    public synchronized long pid() {
        return process == null ? -1 : process.pid();
    }

    public synchronized <T> T exchange(Exchange<T> exchange) throws IOException {
        if (closed) throw new IllegalStateException("process conversation is closed");
        try {
            start();
            InputStream taskInput = input;
            OutputStream taskOutput = output;
            Future<Reply<T>> pending = io.submit(() -> exchange.run(taskInput, taskOutput));
            Reply<T> reply;
            try {
                reply = pending.get(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new IOException("PROCESS_TIMED_OUT: " + spec.command().getFirst(), timeout);
            } catch (InterruptedException cancelled) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("PROCESS_CANCELLED: " + spec.command().getFirst(), cancelled);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException failure) throw failure;
                if (cause instanceof RuntimeException failure) throw failure;
                if (cause instanceof Error failure) throw failure;
                throw new IOException("process conversation failed", cause);
            }
            if (reply.retire()) stop();
            return reply.value();
        } catch (IOException | RuntimeException | Error failure) {
            Process failedProcess = process;
            try {
                stop();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            // Keep the original exception type/cause, and attach bounded child
            // process evidence before a later launch replaces the stderr tail.
            if (failedProcess != null) {
                String exit = failedProcess.isAlive() ? "running" : Integer.toString(failedProcess.exitValue());
                failure.addSuppressed(new IOException("PROCESS_DIAGNOSTIC: pid="
                        + failedProcess.pid() + "; exit=" + exit + "; stderr=" + tail.text()));
            }
            throw failure;
        }
    }

    private void start() throws IOException {
        if (process != null && process.isAlive()) return;
        stop();
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        if (spec.directory() != null) builder.directory(spec.directory().toFile());
        builder.environment().putAll(spec.environment());
        tail = new ProcessRunner.Tail(ProcessRunner.SMALL_OUTPUT);
        process = builder.start();
        try {
            registration =
                    spec.lifecycle() == null
                            ? () -> {}
                            : spec.lifecycle().started(process.toHandle());
        } catch (Exception failure) {
            throw new IOException("failed to register process", failure);
        }
        input = new BufferedInputStream(process.getInputStream(), 64 * 1024);
        output = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
        InputStream errors = process.getErrorStream();
        ProcessRunner.Tail diagnostics = tail;
        errorDrain = Thread.startVirtualThread(
                () -> {
                    try (errors) {
                        errors.transferTo(diagnostics);
                    } catch (IOException ignored) {
                        /* Worker cleanup closes the pipe. */
                    }
                });
        launches++;
    }

    public synchronized void retire() throws IOException {
        stop();
    }

    private void stop() throws IOException {
        if (process == null) return;
        Throwable failure = null;
        try {
            ProcessRunner.stop(process.toHandle());
            if (errorDrain != null) errorDrain.join(1000);
        } catch (Exception cleanup) {
            if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = cleanup;
        }
        for (AutoCloseable resource :
                new AutoCloseable[] {input, output, process.getErrorStream(), registration}) {
            if (resource == null) continue;
            try {
                resource.close();
            } catch (Exception cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (!process.isAlive()) {
            process = null;
            registration = null;
            input = null;
            output = null;
            errorDrain = null;
        }
        if (failure != null)
            throw new IOException("process cleanup failed: " + tail.text(), failure);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            stop();
        } finally {
            io.shutdownNow();
        }
    }
}
