package com.anatomist.worker;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Java 8 batch transport. Only this thread owns the protocol streams. */
final class WorkerBatch {
    static final int MAGIC = 0x414A4231;
    static final int VERSION = 2;
    static final int TASK_END = 0x414A5445;
    static final int BATCH_END = 0x414A4245;
    static final long RESULT_LIMIT = 128L * 1024 * 1024;

    private WorkerBatch() {}

    static void run(int threads) throws Exception {
        if (threads != 1 && threads != 2) throw new IOException("invalid analysis thread count");
        OutputStream protocol = System.out;
        System.setOut(System.err);
        DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 65536));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(protocol, 65536));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int completed = 0;
            long generation = -1, previousBatch = 0;
            while (true) {
                int first = in.read();
                if (first < 0) return;
                if ((first << 24
                                        | in.readUnsignedByte() << 16
                                        | in.readUnsignedByte() << 8
                                        | in.readUnsignedByte())
                                != MAGIC
                        || in.readInt() != VERSION)
                    throw new IOException("unsupported batch protocol");
                long incomingGeneration = in.readLong(), batch = in.readLong();
                if (incomingGeneration <= 0
                        || (generation != -1 && incomingGeneration != generation)
                        || batch <= previousBatch) throw new IOException("invalid batch identity");
                generation = incomingGeneration;
                previousBatch = batch;
                int count = in.readInt();
                if (count < 1 || count > 2) throw new IOException("invalid batch width");
                List<Request> requests = new ArrayList<Request>();
                Set<Long> ids = new HashSet<Long>();
                Set<Integer> lanes = new HashSet<Integer>();
                for (int i = 0; i < count; i++) {
                    long id = in.readLong();
                    int attempt = in.readInt(), lane = in.readInt();
                    Path directory = Paths.get(in.readUTF());
                    int length = in.readInt();
                    if (id <= 0
                            || attempt < 0
                            || lane < 0
                            || lane > 1
                            || !ids.add(id)
                            || !lanes.add(lane)
                            || length < 0
                            || length > 32 * 1024 * 1024)
                        throw new IOException("invalid task request");
                    byte[] bytes = new byte[length];
                    in.readFully(bytes);
                    DataInputStream header = new DataInputStream(new ByteArrayInputStream(bytes));
                    header.readInt();
                    header.readInt();
                    if (header.readBoolean() && count != 1)
                        throw new IOException("processor task requires an exclusive batch");
                    requests.add(new Request(id, attempt, lane, directory, bytes));
                }
                CompletionService<Result> completions = new ExecutorCompletionService<Result>(pool);
                for (final Request request : requests)
                    completions.submit(
                            new Callable<Result>() {
                                public Result call() throws Exception {
                                    return execute(request);
                                }
                            });
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(generation);
                out.writeLong(batch);
                out.writeInt(count);
                boolean retire = false;
                for (int i = 0; i < count; i++) {
                    Result result = completions.take().get();
                    Request request = result.request;
                    out.writeLong(request.id);
                    out.writeInt(request.attempt);
                    out.writeInt(request.lane);
                    long size = Files.size(result.file);
                    if (size > RESULT_LIMIT - 1024)
                        throw new IOException("result exceeds task budget");
                    out.writeLong(size);
                    out.write(result.digest);
                    out.writeLong(
                            Long.parseLong(
                                    java.lang.management.ManagementFactory.getRuntimeMXBean()
                                            .getName()
                                            .split("@")[0]));
                    out.writeUTF(result.thread);
                    out.writeLong(result.started);
                    out.writeLong(result.finished);
                    for (long event : result.intervals) out.writeLong(event);
                    out.writeInt(TASK_END);
                    out.writeLong(request.id);
                    out.flush();
                    retire |= result.failed;
                }
                completed += count;
                Runtime runtime = Runtime.getRuntime();
                long used = runtime.totalMemory() - runtime.freeMemory(),
                        maximum = runtime.maxMemory();
                retire |= completed >= 8 || used > maximum / 10 * 7;
                out.writeInt(BATCH_END);
                out.writeLong(generation);
                out.writeLong(batch);
                out.writeLong(used);
                out.writeLong(maximum);
                out.writeBoolean(retire);
                long heapPeak = 0, gcCount = 0, gcMillis = 0;
                for (java.lang.management.MemoryPoolMXBean memory :
                        java.lang.management.ManagementFactory.getMemoryPoolMXBeans())
                    if (memory.getType() == java.lang.management.MemoryType.HEAP
                            && memory.getPeakUsage() != null)
                        heapPeak += memory.getPeakUsage().getUsed();
                for (java.lang.management.GarbageCollectorMXBean collector :
                        java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                    gcCount += Math.max(0, collector.getCollectionCount());
                    gcMillis += Math.max(0, collector.getCollectionTime());
                }
                out.writeLong(heapPeak);
                out.writeLong(gcCount);
                out.writeLong(gcMillis);
                out.flush();
                if (retire) return;
            }
        } finally {
            pool.shutdownNow();
            // On failure the host terminates the process and owns every task directory,
            // including outputs from futures that have not yet been delivered.
        }
    }

    private static Result execute(Request request) throws Exception {
        Path file = request.directory.resolveSibling(request.directory.getFileName() + ".part");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        JavacWorker worker = new JavacWorker();
        long started = System.nanoTime();
        boolean failed;
        try (DataOutputStream output =
                new DataOutputStream(
                        new java.security.DigestOutputStream(
                                new BufferedOutputStream(Files.newOutputStream(file)), digest))) {
            failed =
                    worker.run(
                            new DataInputStream(new ByteArrayInputStream(request.bytes)), output);
        }
        Files.move(file, request.directory, StandardCopyOption.ATOMIC_MOVE);
        return new Result(
                request,
                request.directory,
                digest.digest(),
                failed,
                Thread.currentThread().getName(),
                started,
                System.nanoTime(),
                worker.intervals());
    }

    private static final class Request {
        final long id;
        final int attempt, lane;
        final Path directory;
        final byte[] bytes;

        Request(long id, int attempt, int lane, Path directory, byte[] bytes) {
            this.id = id;
            this.attempt = attempt;
            this.lane = lane;
            this.directory = directory;
            this.bytes = bytes;
        }
    }

    private static final class Result {
        final Request request;
        final Path file;
        final byte[] digest;
        final boolean failed;
        final String thread;
        final long started, finished;
        final long[] intervals;

        Result(
                Request request,
                Path file,
                byte[] digest,
                boolean failed,
                String thread,
                long started,
                long finished,
                long[] intervals) {
            this.request = request;
            this.file = file;
            this.digest = digest;
            this.failed = failed;
            this.thread = thread;
            this.started = started;
            this.finished = finished;
            this.intervals = intervals;
        }
    }
}
