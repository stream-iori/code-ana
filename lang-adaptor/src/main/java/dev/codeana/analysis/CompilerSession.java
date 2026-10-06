package dev.codeana.analysis;

import dev.codeana.common.AnalysisLimits;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.RecordArchive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Task-owned, bounded target-JDK JVM pool. Every request creates a fresh javac context. */
public final class CompilerSession implements AutoCloseable {
    private static final long LOG_BYTES = 1024 * 1024;
    private final Set<Child> children = new HashSet<>();
    private final Deque<Child> idle = new ArrayDeque<>();
    private final List<RecordArchive> archives = new ArrayList<>();
    private final List<AutoCloseable> models = new ArrayList<>();
    private int peakProcesses, launches, requests;
    private static final long MAX_SIGNATURE_PINS = 256L << 20;
    private final long signaturePinLimit;
    private long signaturePinBytes;

    synchronized boolean reserveSignaturePins(long bytes) throws IOException {
        check();
        if (bytes < 0) throw new IllegalArgumentException("SIGNATURE_PIN_BYTES");
        if (bytes > signaturePinLimit - signaturePinBytes) return false;
        signaturePinBytes += bytes;
        return true;
    }

    synchronized void releaseSignaturePins(long bytes) {
        if (bytes < 0 || bytes > signaturePinBytes)
            throw new IllegalStateException("SIGNATURE_PIN_ACCOUNTING");
        signaturePinBytes -= bytes;
    }

    synchronized long signaturePinBytes() {
        return signaturePinBytes;
    }

    private volatile boolean cancelled;
    private final Path scratch, javaHome, bridge;
    private final long compilerHeapBytes, timeoutSeconds;
    private final int parallelism;
    private final AnalysisLimits outputLimits;
    private final long startedNanos = System.nanoTime();
    private long phaseBytes;
    private int phaseRecords;
    private volatile byte[] heapFailure =
            "{\"error\":\"NATIVE_HEAP_EXHAUSTED\"}".getBytes(StandardCharsets.UTF_8);

    /** Reserve a bounded failure receipt before entering an allocation-heavy phase. */
    public synchronized void mark(String context, String phase) {
        String bounded = context.length() > 1024 ? context.substring(0, 1024) : context;
        String boundedPhase = phase.length() > 512 ? phase.substring(0, 512) : phase;
        heapFailure =
                Json.write(
                                Json.object(
                                        "error",
                                        "NATIVE_HEAP_EXHAUSTED",
                                        "context",
                                        bounded,
                                        "phase",
                                        boundedPhase))
                        .getBytes(StandardCharsets.UTF_8);
        // Task-owned bounded timing evidence, independent of semantic output and ABI.
        if (phaseRecords < 4096 && phaseBytes < LOG_BYTES) {
            byte[] entry = (Json.write(Json.object("elapsed_nanos", System.nanoTime() - startedNanos,
                    "context", bounded, "phase", boundedPhase)) + "\n").getBytes(StandardCharsets.UTF_8);
            if (entry.length <= LOG_BYTES - phaseBytes) {
                try {
                    Files.write(scratch.resolve("phases.ndjson"), entry,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    phaseBytes += entry.length;
                    phaseRecords++;
                } catch (IOException unavailable) {
                    // A diagnostic failure must not change successful semantic output.
                    phaseBytes = LOG_BYTES;
                }
            }
        }
        // The bounded history may be full. Always retain the current location separately.
        Path latest=scratch.resolve("phase-latest.json"), temporary=scratch.resolve("phase-latest.tmp");
        try {
            byte[] current=Json.write(Json.object("elapsed_nanos",System.nanoTime()-startedNanos,
                    "context",bounded,"phase",boundedPhase)).getBytes(StandardCharsets.UTF_8);
            Files.write(temporary,current);
            try {Files.move(temporary,latest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,latest,StandardCopyOption.REPLACE_EXISTING);}
        } catch(IOException unavailable) {
            // Diagnostics never change semantic success or the reserved failure receipt.
        }
    }

    public byte[] heapFailure() {
        return heapFailure;
    }

    private static final class Child {
        final Process process;
        final DataOutputStream commands;
        final Path work, log;

        Child(Process process, Path work, Path log) {
            this.process = process;
            this.work = work;
            this.log = log;
            commands = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        }
    }

    public CompilerSession(Map<String, Object> request) throws IOException {
        this(request, signaturePinBudget(request));
    }

    private static long signaturePinBudget(Map<String, Object> request) throws IOException {
        if (!request.containsKey("signature_pin_bytes")) return MAX_SIGNATURE_PINS;
        try { return new java.math.BigDecimal(request.get("signature_pin_bytes").toString()).longValueExact(); }
        catch (RuntimeException invalid) { throw new IOException("SIGNATURE_PIN_BUDGET", invalid); }
    }

    CompilerSession(Map<String, Object> request, long signaturePinLimit) throws IOException {
        if (signaturePinLimit < 1 || signaturePinLimit > MAX_SIGNATURE_PINS)
            throw new IllegalArgumentException("SIGNATURE_PIN_BUDGET");
        this.signaturePinLimit = signaturePinLimit;
        outputLimits = new AnalysisLimits(request);
        javaHome = Path.of(Json.string(request, "java_home")).toRealPath();
        bridge = Path.of(Json.string(request, "bridge")).toRealPath();
        compilerHeapBytes =
                request.containsKey("compiler_heap_bytes")
                        ? Json.number(request, "compiler_heap_bytes")
                        : 128L * 1024 * 1024;
        if (compilerHeapBytes < 8L * 1024 * 1024
                || compilerHeapBytes > 512L * 1024 * 1024
                || compilerHeapBytes % (1024 * 1024) != 0)
            throw new IOException("INVALID_JAVA_COMPILER_HEAP");
        long requested =
                request.containsKey("parallelism") ? Json.number(request, "parallelism") : 1;
        if (requested < 1 || requested > 8) throw new IOException("PARALLELISM_BUDGET");
        parallelism = (int) requested;
        long seconds = 120;
        if (request.containsKey("compiler_timeout_seconds")) {
            try {
                seconds =
                        new java.math.BigDecimal(request.get("compiler_timeout_seconds").toString())
                                .longValueExact();
            } catch (RuntimeException invalid) {
                throw new IOException("COMPILER_TIMEOUT_BUDGET", invalid);
            }
        }
        timeoutSeconds = seconds;
        if (timeoutSeconds < 1 || timeoutSeconds > 3600)
            throw new IOException("COMPILER_TIMEOUT_BUDGET");
        if (!Files.isExecutable(javaHome.resolve("bin/java"))
                || !Files.isRegularFile(javaHome.resolve("lib/tools.jar")))
            throw new IOException("JDK8_COMPILER_REQUIRED");
        scratch = Files.createTempDirectory(Path.of(Json.string(request, "scratch")), "compiler-");
    }

    private synchronized Child acquire() throws Exception {
        while (true) {
            check();
            while (!idle.isEmpty()) {
                Child child = idle.removeFirst();
                if (child.process.isAlive()) return child;
                discard(child);
            }
            if (children.size() < parallelism) {
                Path work = Files.createTempDirectory(scratch, "jvm-");
                Path log = work.resolve("stderr.log");
                ProcessBuilder builder =
                        new ProcessBuilder(
                                javaHome.resolve("bin/java").toString(),
                                "-Xmx" + (compilerHeapBytes / (1024 * 1024)) + "m",
                                "-cp",
                                bridge + File.pathSeparator + javaHome.resolve("lib/tools.jar"),
                                "dev.codeana.compiler.CompilerBridge",
                                "--session");
                for (String option :
                        List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
                    builder.environment().remove(option);
                builder.directory(work.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
                Child child = new Child(builder.start(), work, log);
                children.add(child);
                launches++;
                peakProcesses = Math.max(peakProcesses, children.size());
                return child;
            }
            wait(25);
        }
    }

    private synchronized void release(Child child, boolean healthy) throws Exception {
        if (!healthy || cancelled || !child.process.isAlive()) discard(child);
        else idle.addLast(child);
        notifyAll();
    }

    private void discard(Child child) throws Exception {
        child.process.destroyForcibly();
        if (!child.process.waitFor(2, TimeUnit.SECONDS))
            throw new IOException("COMPILER_CHILD_STILL_RUNNING");
        child.commands.close();
        children.remove(child);
        idle.remove(child);
        deleteTree(child.work);
    }

    public Map<String, Object> analyze(Map<String, Object> context) throws Exception {
        check();
        mark(Json.string(context, "context"), "compiler_model");
        long archiveBytes = ModelArchive.byteLimit(context);
        Path work = Files.createTempDirectory(scratch, "context-");
        Path request = work.resolve("request.json"),
                result = work.resolve("model.json"),
                receipt = work.resolve("receipt.json");
        Child child = null;
        boolean healthy = false;
        try {
            Files.writeString(request, Json.write(context), StandardCharsets.UTF_8);
            if (Files.size(request) > Json.MAX_BYTES)
                throw new IOException("COMPILER_REQUEST_BUDGET");
            String nonce = UUID.randomUUID().toString();
            byte[] command =
                    Json.write(
                                    Json.object(
                                            "request",
                                            request.toString(),
                                            "result",
                                            result.toString(),
                                            "receipt",
                                            receipt.toString(),
                                            "nonce",
                                            nonce))
                            .getBytes(StandardCharsets.UTF_8);
            if (command.length > 65536) throw new IOException("COMPILER_COMMAND_BUDGET");
            child = acquire();
            synchronized (this) {
                requests++;
            }
            child.commands.writeInt(command.length);
            child.commands.write(command);
            child.commands.flush();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!Files.isRegularFile(receipt)) {
                check();
                if (!child.process.isAlive()) throw failure(child);
                if (System.nanoTime() > end) throw new IOException("COMPILER_TIMEOUT");
                if (Files.exists(result) && Files.size(result) > archiveBytes
                        || Files.exists(child.log) && Files.size(child.log) > LOG_BYTES)
                    throw new IOException("COMPILER_DISK_BUDGET");
                Thread.sleep(10);
            }
            check();
            if (Files.size(child.log) > LOG_BYTES) throw new IOException("COMPILER_DISK_BUDGET");
            if (Files.size(receipt) > 16384) throw new IOException("COMPILER_RECEIPT_BUDGET");
            Map<String, Object> record = Json.map(Json.parse(Json.read(receipt)));
            if (Json.number(record, "version") != 9 || !nonce.equals(record.get("nonce")))
                throw new IOException("COMPILER_RESULT_MISMATCH");
            if (record.containsKey("error"))
                throw new IOException("COMPILER_FAILED: " + record.get("error"));
            if (!Objects.equals(record.get("context"), context.get("context"))
                    || Json.number(record, "bytes") != Files.size(result)
                    || Files.size(result) > archiveBytes
                    || Json.number(record, "model_archive_bytes") != archiveBytes)
                throw new IOException("COMPILER_RESULT_MISMATCH");
            Path retained = scratch.resolve("input-model-" + UUID.randomUUID() + ".bin");
            Files.move(result, retained);
            mark(Json.string(context,"context"), "compiler_model_decode");
            ModelArchive.OpenModel model =
                    ModelArchive.open(
                            retained,
                            Json.string(record, "sha256"),
                            archiveBytes,
                            Json.string(context, "context"));
            synchronized (this) {
                models.add(model.lifetime());
            }
            healthy = true;
            return model.model();
        } finally {
            try {
                if (child != null) release(child, healthy);
            } finally {
                deleteTree(work);
            }
        }
    }

    private IOException failure(Child child) throws IOException {
        byte[] tail;
        try (InputStream in = Files.newInputStream(child.log)) {
            tail = in.readNBytes(4096);
        }
        return new IOException("COMPILER_FAILED: " + new String(tail, StandardCharsets.UTF_8));
    }

    public synchronized RecordArchive newFactArchive() throws IOException {
        check();
        RecordArchive archive =
                RecordArchive.createFacts(
                        scratch.resolve("facts-" + UUID.randomUUID() + ".bin"),
                        outputLimits.bytes,
                        outputLimits.facts);
        archives.add(archive);
        return archive;
    }

    /** Context facts are copied to the task archive before this lease is released. */
    public synchronized void releaseFactArchive(RecordArchive archive) throws IOException {
        if (archive == null) return;
        archive.close();
        archives.removeIf(value -> value == archive);
        Files.deleteIfExists(archive.path());
    }

    /**
     * Rows may be dropped by callers, but type roots can escape into later context recovery.
     * Keep the bounded archive lease until this task closes, including reduced-plan fallback.
     */
    public void releaseModel(Map<String, Object> model) throws Exception {
        if(model.get("syntax") instanceof ModelArchive.SyntaxRows rows) rows.releaseNodeCache();
        // Later contexts may rewarm earlier archives through escaped roots. Snapshot
        // spools share those leases, so release their caches as well, never their rows.
        synchronized (this) {
            for (AutoCloseable archive : models)
                if (archive instanceof RecordArchive)
                    ((RecordArchive) archive).releaseNodeCaches();
        }
    }

    public record StagedModel(Path path, String checksum, String context, long maxBytes) {
        public Map<String, Object> read() throws IOException {
            return ModelArchive.read(path, checksum, maxBytes, context);
        }

        public ModelArchive.OpenModel open() throws IOException {
            return ModelArchive.open(path, checksum, maxBytes, context);
        }
    }

    public StagedModel stageModel(Map<String, Object> model, Map<String, Object> context)
            throws IOException {
        check();
        long archiveBytes = ModelArchive.byteLimit(context);
        Path path = scratch.resolve("project-model-" + UUID.randomUUID() + ".bin");
        ModelArchive.write(path, model, archiveBytes);
        return new StagedModel(path, Json.sha(path), Json.string(model, "context"), archiveBytes);
    }

    public int factLimit() {
        return outputLimits.facts;
    }

    public Path javaHome() {
        return javaHome;
    }

    synchronized Path newInputDirectory(String prefix) throws IOException {
        check();
        return Files.createTempDirectory(scratch, prefix);
    }

    public synchronized int peakProcesses() {
        return peakProcesses;
    }

    public synchronized int launches() {
        return launches;
    }

    public synchronized int requests() {
        return requests;
    }

    public synchronized void cancel() {
        cancelled = true;
        for (Child child : children) child.process.destroyForcibly();
        notifyAll();
    }

    public void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        cancel();
        Exception failure = null;
        for (Child child : List.copyOf(children)) {
            try {
                discard(child);
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
        }
        for (RecordArchive archive : archives) {
            try {
                archive.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
        }
        archives.clear();
        for (AutoCloseable model : models) {
            try {
                model.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
        }
        models.clear();
        if (failure != null) throw new IOException("COMPILER_POOL_CLEANUP_FAILED", failure);
        deleteTree(scratch);
    }
}
