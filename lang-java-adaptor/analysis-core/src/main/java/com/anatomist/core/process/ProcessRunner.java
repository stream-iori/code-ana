package com.anatomist.core.process;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Owns process IO, deadlines and cleanup. Protocols and exit-code policy belong to callers. */
public final class ProcessRunner {
    public static final int SMALL_OUTPUT = 64 * 1024;
    public static final int CAPTURE_LIMIT = 16 * 1024 * 1024;
    public static final Duration CLEANUP_GRACE = Duration.ofSeconds(5);

    private ProcessRunner() {}

    @FunctionalInterface
    public interface Input {
        void write(OutputStream output) throws Exception;
    }

    @FunctionalInterface
    public interface Reader<T> {
        Read<T> read(InputStream input) throws Exception;
    }

    @FunctionalInterface
    public interface Lifecycle {
        AutoCloseable started(ProcessHandle process) throws Exception;
    }

    public record Spec(
            List<String> command,
            Path directory,
            Map<String, String> environment,
            Duration timeout,
            boolean mergeError,
            Lifecycle lifecycle) {
        public Spec {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            if (command.isEmpty() || timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("Invalid process specification");
        }

        public Spec(List<String> command, Path directory, Duration timeout) {
            this(command, directory, Map.of(), timeout, false, null);
        }

        public static Spec from(ProcessBuilder builder, Duration timeout) {
            return new Spec(
                    builder.command(),
                    builder.directory() == null ? null : builder.directory().toPath(),
                    builder.environment(),
                    timeout,
                    builder.redirectErrorStream(),
                    null);
        }
    }

    public record Read<T>(T value, boolean early) {
        public static <T> Read<T> complete(T value) {
            return new Read<>(value, false);
        }

        public static <T> Read<T> early(T value) {
            return new Read<>(value, true);
        }
    }

    public enum Kind {
        TIMED_OUT,
        CANCELLED,
        START_FAILED,
        IO_FAILED,
        OUTPUT_LIMIT,
        CLEANUP_FAILED
    }

    public sealed interface Outcome permits Completed, Early, Failed {}

    public record Completed(int exitCode) implements Outcome {}

    public record Early() implements Outcome {}

    public record Failed(Kind kind, Throwable cause) implements Outcome {}

    public enum DescendantScan {
        NOT_ATTEMPTED,
        AVAILABLE,
        DENIED,
        UNSUPPORTED,
        FAILED
    }

    @FunctionalInterface
    interface Scanner {
        void scan(ProcessHandle process, java.util.function.Consumer<ProcessHandle> found);
    }

    private static final Scanner SYSTEM_SCANNER =
            (process, found) -> {
                try (var descendants = process.descendants()) {
                    descendants.forEach(found);
                }
            };

    static final class Tracking {
        final Set<ProcessHandle> children = new LinkedHashSet<>();
        final Scanner scanner;
        DescendantScan status = DescendantScan.NOT_ATTEMPTED;

        Tracking(Scanner scanner) {
            this.scanner = scanner;
        }

        void scan(ProcessHandle process) {
            if (status != DescendantScan.NOT_ATTEMPTED && status != DescendantScan.AVAILABLE)
                return;
            try {
                scanner.scan(process, children::add);
                status = DescendantScan.AVAILABLE;
            } catch (UnsupportedOperationException denied) {
                status = DescendantScan.UNSUPPORTED;
            } catch (SecurityException denied) {
                status = DescendantScan.DENIED;
            } catch (RuntimeException failure) {
                if ("Operation not permitted (sysctl failed)".equals(failure.getMessage())
                        && Arrays.stream(failure.getStackTrace())
                                .anyMatch(
                                        frame ->
                                                frame.getClassName()
                                                                .equals(
                                                                        "java.lang.ProcessHandleImpl")
                                                        && frame.getMethodName()
                                                                .equals("getProcessPids0")
                                                        && frame.isNativeMethod())) {
                    status = DescendantScan.DENIED;
                } else {
                    status = DescendantScan.FAILED;
                    throw failure;
                }
            }
        }
    }

    public record Diagnostics(
            long pid,
            long elapsedMillis,
            long stdinBytes,
            long stdoutBytes,
            long stderrBytes,
            boolean stdinDone,
            boolean stdoutDone,
            boolean stderrDone,
            String phase,
            String stderrTail,
            boolean cleanupComplete,
            DescendantScan descendantScan) {
        public String cleanupScope() {
            return "tracked_processes";
        }

        public String summary() {
            return "pid="
                    + pid
                    + " phase="
                    + phase
                    + " elapsed_ms="
                    + elapsedMillis
                    + " bytes="
                    + stdinBytes
                    + "/"
                    + stdoutBytes
                    + "/"
                    + stderrBytes
                    + " streams_done="
                    + stdinDone
                    + "/"
                    + stdoutDone
                    + "/"
                    + stderrDone
                    + " cleanup_complete="
                    + cleanupComplete
                    + " cleanup_scope="
                    + cleanupScope()
                    + " descendant_scan="
                    + descendantScan.name().toLowerCase(Locale.ROOT);
        }
    }

    public record Result<T>(Outcome outcome, T value, Diagnostics diagnostics) {
        public T requireValue() throws Failure {
            if (outcome instanceof Failed failed) throw new Failure(failed, diagnostics);
            return value;
        }

        public int exitCode() {
            return outcome instanceof Completed completed ? completed.exitCode() : 0;
        }
    }

    public static String exitMessage(Result<?> result, String operation) {
        String summary =
                operation + " exit=" + result.exitCode() + "; " + result.diagnostics().summary();
        com.anatomist.core.logging.AnatomistLog.diagnostic(
                summary, result.diagnostics().stderrTail());
        String error =
                result.diagnostics().stderrTail().strip().replace('\n', ' ').replace('\r', ' ');
        return summary
                + (error.isEmpty() ? "" : "; " + error.substring(0, Math.min(512, error.length())));
    }

    public static final class Failure extends IOException {
        private final Kind kind;
        private final Diagnostics diagnostics;

        Failure(Failed failed, Diagnostics diagnostics) {
            super(failed.kind() + ": " + diagnostics.summary(), failed.cause());
            this.kind = failed.kind();
            this.diagnostics = diagnostics;
        }

        public Kind kind() {
            return kind;
        }

        public Diagnostics diagnostics() {
            return diagnostics;
        }
    }

    public static final class OutputLimit extends IOException {
        public OutputLimit(int limit) {
            super("Process output exceeds " + limit + " bytes");
        }
    }

    public static Input bytes(byte[] bytes) {
        return output -> {
            if (bytes != null) output.write(bytes);
        };
    }

    public static Reader<byte[]> capture(int limit) {
        return input -> Read.complete(readBounded(input, limit));
    }

    public static byte[] readBounded(InputStream input, int limit) throws IOException {
        var output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > limit - output.size()) throw new OutputLimit(limit);
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    /** Bounded individual records, with no duplicate full-output buffer. */
    public static List<String> zeroRecords(InputStream input) throws IOException {
        List<String> records = new ArrayList<>();
        var record = new ByteArrayOutputStream();
        var buffered = new BufferedInputStream(input);
        int next;
        while ((next = buffered.read()) != -1) {
            if (next == 0) {
                records.add(record.toString(StandardCharsets.UTF_8));
                record.reset();
            } else {
                if (record.size() >= SMALL_OUTPUT) throw new OutputLimit(SMALL_OUTPUT);
                record.write(next);
            }
        }
        if (record.size() != 0) throw new EOFException("Incomplete NUL-delimited process output");
        return records;
    }

    public static String line(InputStream input, int limit) throws IOException {
        var out = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != '\n') {
            if (out.size() >= limit) throw new OutputLimit(limit);
            out.write(next);
        }
        return next == -1 && out.size() == 0 ? null : out.toString(StandardCharsets.UTF_8);
    }

    public static <T> Result<T> run(Spec spec, Input input, Reader<T> reader) {
        return run(spec, input, reader, SYSTEM_SCANNER);
    }

    static <T> Result<T> run(Spec spec, Input input, Reader<T> reader, Scanner scanner) {
        long started = System.nanoTime(), deadline = started + spec.timeout().toNanos();
        var inBytes = new AtomicLong();
        var outBytes = new AtomicLong();
        var errBytes = new AtomicLong();
        Tail tail = new Tail(SMALL_OUTPUT);
        Process process = null;
        AutoCloseable registration = null;
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> writer = null, errors = null;
        Future<Read<T>> output = null;
        Tracking tracking = new Tracking(scanner);
        Outcome outcome;
        T value = null;
        String phase = "starting";
        boolean clean, outputCollected = false;
        boolean interrupted = false;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ProcessBuilder builder =
                    new ProcessBuilder(spec.command()).redirectErrorStream(spec.mergeError());
            if (spec.directory() != null) builder.directory(spec.directory().toFile());
            builder.environment().putAll(spec.environment());
            process = builder.start();
            if (spec.lifecycle() != null)
                registration = spec.lifecycle().started(process.toHandle());
            Process child = process;
            output =
                    workers.submit(
                            () -> {
                                InputStream stream = counting(child.getInputStream(), outBytes);
                                Read<T> result =
                                        reader.read(
                                                new FilterInputStream(stream) {
                                                    @Override
                                                    public void close() {
                                                        /* The runner owns this stream. */
                                                    }
                                                });
                                if (!result.early())
                                    stream.transferTo(OutputStream.nullOutputStream());
                                return result;
                            });
            errors =
                    workers.submit(
                            () -> {
                                counting(child.getErrorStream(), errBytes).transferTo(tail);
                                return null;
                            });
            writer =
                    workers.submit(
                            () -> {
                                try (OutputStream stream =
                                        new FilterOutputStream(child.getOutputStream()) {
                                            @Override
                                            public void write(int b) throws IOException {
                                                out.write(b);
                                                inBytes.incrementAndGet();
                                            }

                                            @Override
                                            public void write(byte[] b, int off, int len)
                                                    throws IOException {
                                                out.write(b, off, len);
                                                inBytes.addAndGet(len);
                                            }
                                        }) {
                                    if (input != null) input.write(stream);
                                }
                                return null;
                            });
            phase = "exchanging";
            long nextDescendantScan = 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.nanoTime() >= deadline) throw new TimeoutException();
                if (System.nanoTime() >= nextDescendantScan) {
                    tracking.scan(process.toHandle());
                    nextDescendantScan = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
                }
                if (!outputCollected && output.isDone()) {
                    Read<T> read = output.get();
                    value = read.value();
                    outputCollected = true;
                    if (read.early()) {
                        outcome = new Early();
                        break;
                    }
                }
                // Surface failed IO immediately, even when another stream is blocked.
                if (writer.isDone()) writer.get();
                if (errors.isDone()) errors.get();
                if (outputCollected && writer.isDone() && errors.isDone() && !process.isAlive()) {
                    outcome = new Completed(process.exitValue());
                    break;
                }
                phase =
                        !writer.isDone()
                                ? "stdin"
                                : !output.isDone()
                                        ? "stdout"
                                        : !errors.isDone() ? "stderr" : "exit";
                TimeUnit.NANOSECONDS.sleep(
                        Math.min(
                                TimeUnit.MILLISECONDS.toNanos(5),
                                Math.max(1, deadline - System.nanoTime())));
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            outcome = new Failed(Kind.CANCELLED, failure);
        } catch (TimeoutException failure) {
            outcome = new Failed(Kind.TIMED_OUT, failure);
        } catch (Throwable failure) {
            Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            outcome =
                    new Failed(
                            cause instanceof OutputLimit
                                    ? Kind.OUTPUT_LIMIT
                                    : process == null ? Kind.START_FAILED : Kind.IO_FAILED,
                            cause);
        } finally {
            interrupted |= Thread.interrupted();
        }
        long pid = process == null ? -1 : process.pid();
        boolean inDone = writer != null && writer.isDone(),
                outDone = output != null && output.isDone(),
                errDone = errors != null && errors.isDone();
        Throwable cleanupFailure = cleanup(process, tracking, registration, workers);
        clean = cleanupFailure == null;
        if (!clean) {
            if (outcome instanceof Failed failure) failure.cause().addSuppressed(cleanupFailure);
            else outcome = new Failed(Kind.CLEANUP_FAILED, cleanupFailure);
        }
        interrupted |= Thread.interrupted();
        if (interrupted) Thread.currentThread().interrupt();
        var diagnostics =
                new Diagnostics(
                        pid,
                        (System.nanoTime() - started) / 1_000_000,
                        inBytes.get(),
                        outBytes.get(),
                        errBytes.get(),
                        inDone,
                        outDone,
                        errDone,
                        phase,
                        tail.text(),
                        clean,
                        tracking.status);
        String command = Path.of(spec.command().getFirst()).getFileName().toString();
        if (outcome instanceof Failed failed)
            com.anatomist.core.logging.AnatomistLog.diagnostic(
                    command + " " + failed.kind() + ": " + diagnostics.summary(),
                    diagnostics.stderrTail());
        else com.anatomist.core.logging.AnatomistLog.debug(command + ": " + diagnostics.summary());
        return new Result<>(outcome, value, diagnostics);
    }

    /** Recovery of an externally recorded, identity-verified owned process. */
    public static void stop(ProcessHandle process) throws Exception {
        stop(process, SYSTEM_SCANNER);
    }

    static void stop(ProcessHandle process, Scanner scanner) throws Exception {
        long deadline = System.nanoTime() + CLEANUP_GRACE.toNanos();
        Tracking tracking = new Tracking(scanner);
        List<Future<?>> tasks = new ArrayList<>();
        Throwable failure = null;
        try {
            tracking.scan(process);
        } catch (Throwable error) {
            failure = error;
        }
        for (ProcessHandle child : tracking.children)
            tasks.add(cleanupTask(() -> terminate(child, deadline)));
        tasks.add(cleanupTask(() -> terminate(process, deadline)));
        failure = awaitCleanup(tasks, deadline, failure);
        boolean interrupted = Thread.interrupted();
        try {
            com.anatomist.core.logging.AnatomistLog.debug(
                    "stop pid="
                            + process.pid()
                            + " descendant_scan="
                            + tracking.status.name().toLowerCase(Locale.ROOT)
                            + " cleanup_scope=tracked_processes cleanup_complete="
                            + (failure == null));
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure instanceof Exception error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private static InputStream counting(InputStream source, AtomicLong count) {
        return new FilterInputStream(source) {
            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) count.incrementAndGet();
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                if (n > 0) count.addAndGet(n);
                return n;
            }
        };
    }

    static Throwable cleanup(
            Process process,
            Tracking tracking,
            AutoCloseable registration,
            ExecutorService workers) {
        long deadline = System.nanoTime() + CLEANUP_GRACE.toNanos();
        List<Future<?>> tasks = new ArrayList<>();
        Throwable failure = null;
        if (process != null) {
            try {
                tracking.scan(process.toHandle());
            } catch (Throwable error) {
                failure = error;
            }
            for (ProcessHandle child : tracking.children)
                tasks.add(cleanupTask(() -> terminate(child, deadline)));
            // Registration closes after direct-child termination, even when termination fails.
            tasks.add(
                    cleanupTask(
                            () -> {
                                Throwable primary = null;
                                try {
                                    terminate(process.toHandle(), deadline);
                                } catch (Throwable error) {
                                    primary = error;
                                }
                                try {
                                    if (registration != null) registration.close();
                                } catch (Throwable error) {
                                    primary = combine(primary, error);
                                }
                                if (primary instanceof Exception error) throw error;
                                if (primary instanceof Error error) throw error;
                            }));
            // Each stream may block independently. None may prevent other cleanup attempts.
            tasks.add(cleanupTask(() -> process.getOutputStream().close()));
            tasks.add(cleanupTask(() -> process.getInputStream().close()));
            tasks.add(cleanupTask(() -> process.getErrorStream().close()));
        } else if (registration != null) tasks.add(cleanupTask(registration));
        workers.shutdownNow();
        tasks.add(
                cleanupTask(
                        () -> {
                            if (!workers.awaitTermination(
                                    Math.max(1, deadline - System.nanoTime()),
                                    TimeUnit.NANOSECONDS))
                                throw new TimeoutException("Process IO workers did not terminate");
                        }));
        return awaitCleanup(tasks, deadline, failure);
    }

    private static void terminate(ProcessHandle process, long deadline) throws Exception {
        if (process.isAlive()) process.destroyForcibly();
        if (process.isAlive())
            process.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    static Future<?> cleanupTask(AutoCloseable action) {
        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            action.close();
                            return null;
                        });
        Thread.ofVirtual().name("anatomist-process-cleanup").start(task);
        return task;
    }

    private static Throwable combine(Throwable primary, Throwable secondary) {
        if (primary == null) return secondary;
        if (primary != secondary) primary.addSuppressed(secondary);
        return primary;
    }

    static Throwable awaitCleanup(List<Future<?>> tasks, long deadline, Throwable failure) {
        boolean interrupted = false;
        try {
            for (Future<?> task : tasks) {
                while (true) {
                    try {
                        task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    } catch (ExecutionException error) {
                        failure = combine(failure, error.getCause());
                        break;
                    } catch (TimeoutException | CancellationException error) {
                        failure = combine(failure, error);
                        task.cancel(true);
                        break;
                    }
                }
            }
            return failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Synchronized because diagnostics can be sampled after a failed cleanup. */
    public static final class Tail extends OutputStream {
        private final byte[] bytes;
        private int position, size;

        public Tail(int limit) {
            if (limit < 1) throw new IllegalArgumentException();
            bytes = new byte[limit];
        }

        @Override
        public synchronized void write(int b) {
            bytes[position] = (byte) b;
            position = (position + 1) % bytes.length;
            size = Math.min(size + 1, bytes.length);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int n = Math.min(len, bytes.length), start = off + len - n;
            int first = Math.min(n, bytes.length - position);
            System.arraycopy(b, start, bytes, position, first);
            System.arraycopy(b, start + first, bytes, 0, n - first);
            position = (position + n) % bytes.length;
            size = Math.min(bytes.length, size + n);
        }

        public synchronized String text() {
            byte[] result = new byte[size];
            int start = (position - size + bytes.length) % bytes.length;
            int first = Math.min(size, bytes.length - start);
            System.arraycopy(bytes, start, result, 0, first);
            System.arraycopy(bytes, 0, result, first, size - first);
            return new String(result, StandardCharsets.UTF_8);
        }
    }
}
