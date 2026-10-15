package com.anatomist.core.javac;

import com.anatomist.core.process.ProcessRunner;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Versioned completion receipts for host-owned result files; the pipe carries no model payload. */
final class JavacBatchProtocol {
    static final int MAGIC = 0x414A4231;
    static final int VERSION = 2;
    static final int TASK_END = 0x414A5445;
    static final int BATCH_END = 0x414A4245;
    static final long DISK_BYTES = 768L * 1024 * 1024;

    record Request(
            long taskId, int attemptId, int laneId, ProcessRunner.Input writer, Path result) {
        Request {
            if (taskId <= 0 || attemptId < 0 || laneId < 0 || laneId > 1)
                throw new IllegalArgumentException("invalid javac task identity");
            Objects.requireNonNull(writer);
            Objects.requireNonNull(result);
        }
    }

    record Execution(
            long generation,
            long batchId,
            long taskId,
            int attemptId,
            int laneId,
            long pid,
            String thread,
            long started,
            long finished,
            long parseStarted,
            long parseFinished,
            long analyzeStarted,
            long analyzeFinished) {}

    record Result(Request request, long bytes, String sha256, Execution execution) {}

    record Batch(
            List<Result> results,
            boolean retire,
            long heapUsed,
            long heapMaximum,
            long heapPeak,
            long gcCount,
            long gcMillis,
            long transportBytes) {}

    private JavacBatchProtocol() {}

    static Batch exchange(
            InputStream input,
            OutputStream output,
            long generation,
            long batchId,
            List<Request> tasks)
            throws Exception {
        if (tasks.isEmpty() || tasks.size() > 2)
            throw new IllegalArgumentException("invalid javac batch width");
        Map<Long, Request> expected = new LinkedHashMap<>();
        Set<Integer> lanes = new HashSet<>();
        for (Request task : tasks)
            if (expected.putIfAbsent(task.taskId(), task) != null || !lanes.add(task.laneId()))
                throw new IllegalArgumentException("duplicate javac task or lane");
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(generation);
        out.writeLong(batchId);
        out.writeInt(tasks.size());
        for (Request task : tasks) {
            JavacCancellation.check();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            task.writer().write(new LimitedRequest(bytes));
            out.writeLong(task.taskId());
            out.writeInt(task.attemptId());
            out.writeInt(task.laneId());
            out.writeUTF(task.result().toString());
            out.writeInt(bytes.size());
            bytes.writeTo(out);
        }
        out.flush();
        CountedInput counted = new CountedInput(input);
        DataInputStream in = new DataInputStream(counted);
        if (in.readInt() != MAGIC
                || in.readInt() != VERSION
                || in.readLong() != generation
                || in.readLong() != batchId
                || in.readInt() != tasks.size()) throw invalid("batch identity/version mismatch");
        Map<Long, Result> received = new HashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            long taskId = in.readLong();
            int attempt = in.readInt(), lane = in.readInt();
            Request task = expected.get(taskId);
            if (task == null
                    || received.containsKey(taskId)
                    || task.attemptId() != attempt
                    || task.laneId() != lane) throw invalid("task identity mismatch");
            long length = in.readLong();
            if (length < 0 || length > JavacProtocol.MAX_RESPONSE_BYTES - 1024)
                throw invalid("result exceeds task budget");
            byte[] checksum = new byte[32];
            in.readFully(checksum);
            long pid = in.readLong();
            String thread = in.readUTF();
            long started = in.readLong(),
                    finished = in.readLong(),
                    parseStarted = in.readLong(),
                    parseFinished = in.readLong(),
                    analyzeStarted = in.readLong(),
                    analyzeFinished = in.readLong();
            if (pid <= 0
                    || thread.isEmpty()
                    || finished < started
                    || parseStarted < started
                    || parseFinished < parseStarted
                    || analyzeStarted < parseFinished
                    || analyzeFinished < analyzeStarted
                    || finished < analyzeFinished) throw invalid("invalid execution intervals");
            if (in.readInt() != TASK_END || in.readLong() != taskId)
                throw invalid("result terminal mismatch");
            if (!Files.isRegularFile(task.result(), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(task.result()) != length)
                throw invalid("result file size or type mismatch");
            received.put(
                    taskId,
                    new Result(
                            task,
                            length,
                            HexFormat.of().formatHex(checksum),
                            new Execution(
                                    generation,
                                    batchId,
                                    taskId,
                                    attempt,
                                    lane,
                                    pid,
                                    thread,
                                    started,
                                    finished,
                                    parseStarted,
                                    parseFinished,
                                    analyzeStarted,
                                    analyzeFinished)));
        }
        if (in.readInt() != BATCH_END || in.readLong() != generation || in.readLong() != batchId)
            throw invalid("missing batch terminal");
        long used = in.readLong(), maximum = in.readLong();
        boolean retire = in.readBoolean();
        long peak = in.readLong(), gcCount = in.readLong(), gcMillis = in.readLong();
        if (peak < 0 || gcCount < 0 || gcMillis < 0)
            throw invalid("invalid process resource counters");
        if (used < 0 || maximum <= 0 || used > maximum) throw invalid("invalid heap status");
        return new Batch(
                tasks.stream().map(task -> received.get(task.taskId())).toList(),
                retire,
                used,
                maximum,
                peak,
                gcCount,
                gcMillis,
                out.size() + counted.bytes);
    }

    private static final class CountedInput extends FilterInputStream {
        private long bytes;

        CountedInput(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) bytes++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = in.read(buffer, offset, length);
            if (count > 0) bytes += count;
            return count;
        }
    }

    private static IOException invalid(String reason) {
        return new IOException("JAVAC_PROTOCOL_INVALID: " + reason);
    }

    private static final class LimitedRequest extends FilterOutputStream {
        private long remaining = JavacProtocol.MAX_BYTES;

        LimitedRequest(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            take(1);
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            take(length);
            out.write(bytes, offset, length);
        }

        private void take(int length) throws IOException {
            if (length < 0 || length > remaining)
                throw new IOException("javac request exceeds budget");
            remaining -= length;
        }
    }
}
