package dev.codeana.bridge;

import dev.codeana.adapter.AnalysisSession;
import dev.codeana.adapter.AnalysisTelemetry;
import dev.codeana.common.AnalysisLimits;
import dev.codeana.common.GraphArchive;
import dev.codeana.common.Json;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;

/** ABI v4: caller-owned input/output buffers; all entry points catch ordinary Java exceptions. */
public final class NativeBridge {
    private static final ConcurrentHashMap<Long, Task> TASKS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT = new AtomicLong(1);
    // Reserve terminal responses before analysis. An exhausted heap must not allocate
    // a diagnostic or leave the Rust host polling an already terminated thread.
    private static final byte[] HEAP_FAILURE =
            "{\"error\":\"NATIVE_HEAP_EXHAUSTED\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STACK_FAILURE =
            "{\"error\":\"NATIVE_STACK_EXHAUSTED\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FATAL_FAILURE =
            "{\"error\":\"NATIVE_ANALYSIS_FATAL\"}".getBytes(StandardCharsets.UTF_8);

    static String errorDetails(Throwable failure) {
        StringBuilder text = new StringBuilder();
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty() && seen.size() < 16 && text.length() < 8192) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            String message = current.toString();
            if (message.length() > 2048) message = message.substring(0, 2048);
            if (!text.isEmpty()) text.append("\ncaused or suppressed: ");
            text.append(message);
            StackTraceElement[] frames=current.getStackTrace();
            for(int i=0;i<Math.min(frames.length,8) && text.length()<8192;i++)
                text.append("\n at ").append(frames[i]);
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) {
                if (pending.size() >= 16) break;
                pending.addLast(suppressed);
            }
        }
        return text.substring(0, Math.min(text.length(), 8192));
    }

    private static final class Task {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        volatile byte[] errorOutput = new byte[0];
        volatile byte[] heapFailure = HEAP_FAILURE;
        volatile FileChannel output;
        Path outputPath, outputDirectory, archivePath;
        volatile long outputLength;
        volatile int state;
        volatile AnalysisSession compiler;
        volatile Thread thread;
        volatile boolean cancelled;
        volatile int completionFd = -1;
        boolean sealed;

        void run() {
            AnalysisTelemetry telemetry = null;
            Path telemetryPath = null;
            int terminalState = -1;
            try {
                telemetry = new AnalysisTelemetry();
                Map<String, Object> request = Json.map(Json.parse(input.toByteArray()));
                input.reset();
                long version = Json.number(request, "version");
                if (version != 10) throw new IllegalArgumentException("ABI_REQUEST_VERSION");
                request.put("defer_fact_dedup", true);
                AnalysisLimits limits = new AnalysisLimits(request);
                telemetryPath =
                        Path.of(Json.string(request, "scratch")).resolve("native-timings.json");
                try (AnalysisSession session = new AnalysisSession(request, telemetry)) {
                    compiler = session;
                    if (cancelled) session.cancel();
                    Map<String, Object> result = session.analyze();
                    outputDirectory =
                            Files.createTempDirectory(
                                    Path.of(Json.string(request, "scratch")), "native-result-");
                    outputPath = outputDirectory.resolve("result.json");
                    var archive = (GraphArchive) result.remove("facts");
                    telemetry.phase("archive_seal", "", true);
                    archivePath = outputDirectory.resolve("graph.rows");
                    result.put("graph_archive", archive.export(archivePath));
                    telemetry.phase("archive_seal", "", false);
                    outputLength = Json.writeTo(outputPath, result, limits.bytes);
                    output = FileChannel.open(outputPath, StandardOpenOption.READ);
                } finally {
                    compiler = null;
                }
                terminalState = 1;
            } catch (Exception e) {
                String error = cancelled ? "CANCELLED" : errorDetails(e);
                if (error.length() > 8192) error = error.substring(0, 8192);
                errorOutput =
                        Json.write(Json.object("error", error)).getBytes(StandardCharsets.UTF_8);
                outputLength = errorOutput.length;
            } catch (Error fatal) {
                errorOutput =
                        fatal instanceof OutOfMemoryError
                                ? heapFailure
                                : fatal instanceof StackOverflowError
                                        ? STACK_FAILURE
                                        : FATAL_FAILURE;
                outputLength = errorOutput.length;
            } finally {
                try {
                    if (telemetryPath != null && telemetry != null)
                        telemetry.save(
                                telemetryPath,
                                terminalState,
                                terminalState == -1
                                        ? new String(errorOutput, StandardCharsets.UTF_8)
                                        : null);
                } catch (Throwable ignoredTelemetryFailure) {
                    // Even an exhausted heap must expose the preallocated terminal response.
                } finally {
                    state = terminalState;
                    int fd = completionFd;
                    completionFd = -1;
                    CompletionNotification.complete(fd);
                }
            }
        }

        void cancel() {
            cancelled = true;
            AnalysisSession c = compiler;
            if (c != null) c.cancel();
            Thread running = thread;
            if (running != null) running.interrupt();
        }
    }

    @CEntryPoint(name = "codeana_java_abi_version")
    public static int version(IsolateThread thread) {
        return 4;
    }

    @CEntryPoint(name = "codeana_java_heap_bytes")
    public static long heapBytes(IsolateThread thread) {
        return Runtime.getRuntime().maxMemory();
    }

    @CEntryPoint(name = "codeana_java_session_create")
    public static long create(IsolateThread thread) {
        try {
            if (TASKS.size() >= 4) return 0;
            long id = NEXT.getAndIncrement();
            TASKS.put(id, new Task());
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    @CEntryPoint(name = "codeana_java_input_chunk")
    public static int input(IsolateThread thread, long id, CCharPointer bytes, int length) {
        try {
            Task t = TASKS.get(id);
            if (t == null || bytes.isNull() || length < 0 || length > 8 * 1024 * 1024) return -2;
            synchronized (t) {
                if (t.sealed || t.input.size() > Json.MAX_BYTES - length) return -2;
                byte[] copy = new byte[length];
                for (int i = 0; i < length; i++) copy[i] = bytes.read(i);
                t.input.write(copy);
                return 0;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @CEntryPoint(name = "codeana_java_seal_input")
    public static int seal(IsolateThread thread, long id) {
        try {
            Task t = TASKS.get(id);
            if (t == null) return -2;
            synchronized (t) {
                if (t.sealed) return -2;
                t.sealed = true;
                return 0;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @CEntryPoint(name = "codeana_java_analyze_start")
    public static int start(IsolateThread thread, long id) {
        try {
            Task t = TASKS.get(id);
            if (t == null) return -2;
            synchronized (t) {
                if (!t.sealed || t.thread != null) return -2;
                t.thread = new Thread(t::run, "codeana-analysis");
                t.thread.start();
                return 0;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @CEntryPoint(name = "codeana_java_set_completion_fd")
    public static int completionFd(IsolateThread thread, long id, int fd) {
        try {
            Task task = TASKS.get(id);
            if (task == null || fd < 0) return -2;
            synchronized (task) {
                if (task.thread != null || task.completionFd >= 0) return -2;
                int owned = CompletionNotification.duplicate(fd);
                if (owned < 0) return -1;
                task.completionFd = owned;
                return 0;
            }
        } catch (Exception failure) { return -1; }
    }

    @CEntryPoint(name = "codeana_java_poll_event")
    public static int poll(IsolateThread thread, long id) {
        Task t = TASKS.get(id);
        if (t != null && t.state == 0 && t.thread != null && !t.thread.isAlive()) {
            t.errorOutput = FATAL_FAILURE;
            t.outputLength = FATAL_FAILURE.length;
            t.state = -1;
        }
        return t == null ? -2 : t.state;
    }

    @CEntryPoint(name = "codeana_java_read_result_chunk")
    public static int read(
            IsolateThread thread, long id, long offset, CCharPointer output, int capacity) {
        try {
            Task t = TASKS.get(id);
            if (t == null
                    || t.state == 0
                    || output.isNull()
                    || capacity < 0
                    || capacity > 8 * 1024 * 1024
                    || offset < 0
                    || offset > t.outputLength) return -2;
            int n = (int) Math.min(capacity, t.outputLength - offset);
            if (t.state == -1) {
                for (int i = 0; i < n; i++) output.write(i, t.errorOutput[(int) offset + i]);
                return n;
            }
            byte[] bytes = new byte[n];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long position = offset;
            while (buffer.hasRemaining()) {
                int read = t.output.read(buffer, position);
                if (read <= 0) return -1;
                position += read;
            }
            for (int i = 0; i < n; i++) output.write(i, bytes[i]);
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    @CEntryPoint(name = "codeana_java_cancel")
    public static int cancel(IsolateThread thread, long id) {
        try {
            Task t = TASKS.get(id);
            if (t == null) return -2;
            t.cancel();
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    @CEntryPoint(name = "codeana_java_publish_ack")
    public static int ack(IsolateThread thread, long id) {
        Task t = TASKS.get(id);
        return t != null && t.state == 1 ? 0 : -2;
    }

    @CEntryPoint(name = "codeana_java_session_close")
    public static int close(IsolateThread thread, long id) {
        try {
            Task t = TASKS.get(id);
            if (t == null) return -2;
            t.cancel();
            if (t.thread != null) {
                t.thread.join(2000);
                if (t.thread.isAlive()) return -1;
            }
            if (t.output != null) t.output.close();
            int fd = t.completionFd;
            t.completionFd = -1;
            if (fd >= 0) CompletionNotification.close(fd);
            if (t.outputPath != null) Files.deleteIfExists(t.outputPath);
            if (t.archivePath != null) Files.deleteIfExists(t.archivePath);
            if (t.outputDirectory != null) Files.deleteIfExists(t.outputDirectory);
            TASKS.remove(id);
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
