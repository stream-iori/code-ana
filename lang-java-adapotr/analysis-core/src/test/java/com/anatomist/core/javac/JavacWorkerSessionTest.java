package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class JavacWorkerSessionTest {
    @Test
    void readsTerminalFramesWithoutEofAndRecyclesAfterEightTasks(@TempDir Path directory)
            throws Exception {
        long first;
        long last;
        try (var session = session(directory, "valid", Duration.ofSeconds(10))) {
            assertEquals(0, session.launches());
            session.execute(out -> out.write(0), unit -> fail(), null, null);
            first = session.pid();
            for (int task = 2; task <= 7; task++) {
                session.execute(out -> out.write(0), unit -> fail(), null, null);
                assertEquals(first, session.pid());
            }
            session.execute(out -> out.write(0), unit -> fail(), null, null);
            assertFalse(ProcessHandle.of(first).map(ProcessHandle::isAlive).orElse(false));
            session.execute(out -> out.write(0), unit -> fail(), null, null);
            last = session.pid();
            assertNotEquals(first, last);
            assertEquals(2, session.launches());
        }
        assertFalse(ProcessHandle.of(last).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void malformedAndTimedOutTasksDestroyWorkersAndPermitFreshTasks(@TempDir Path directory)
            throws Exception {
        for (String mode :
                List.of(
                        "mismatch",
                        "truncated",
                        "hang",
                        "checksum",
                        "generation",
                        "lane",
                        "oversize",
                        "model-bad-terminal")) {
            try (var session = session(directory, mode, Duration.ofMillis(500))) {
                assertThrows(
                        IOException.class,
                        () -> session.execute(out -> out.write(0), unit -> fail(), null, null));
                assertEquals(-1, session.pid(), mode);
            }
        }
        try (var session = session(directory, "heap", Duration.ofSeconds(10))) {
            session.execute(out -> out.write(0), unit -> fail(), null, null);
            assertEquals(-1, session.pid());
            session.execute(out -> out.write(0), unit -> fail(), null, null);
            assertEquals(2, session.launches());
        }
    }

    @Test
    void recordedPayloadHasItsOwnTerminalAndCanBeDecodedOffline(@TempDir Path directory)
            throws Exception {
        Path transcript = directory.resolve("analysis.bin");
        try (var session = session(directory, "valid", Duration.ofSeconds(10))) {
            var response = session.execute(out -> out.write(0), unit -> fail(), null, transcript);
            try (InputStream saved = java.nio.file.Files.newInputStream(transcript)) {
                assertEquals(
                        response.version(),
                        JavacProtocol.response(saved, unit -> fail()).version());
            }
        }
    }

    @Test
    void interruptionTerminatesBlockedExchangeWithoutWaitingForCloseLock(@TempDir Path directory)
            throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var session = session(directory, "hang", Duration.ofMinutes(10))) {
            Thread caller =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            session.execute(
                                                    out -> {
                                                        out.write(0);
                                                        entered.countDown();
                                                    },
                                                    unit -> fail(),
                                                    null,
                                                    null);
                                        } catch (Throwable caught) {
                                            failure.set(caught);
                                        }
                                    });
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(10000);
            assertFalse(caller.isAlive());
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(failure.get().getMessage().contains("CANCELLED"));
            assertEquals(-1, session.pid());
        }
    }

    @Test
    void reversedCompletionRemainsEncodedAndIsReturnedInRequestOrder(@TempDir Path directory)
            throws Exception {
        try (var session = session(directory, "valid", Duration.ofSeconds(10))) {
            var requests =
                    List.of(
                            new JavacBatchProtocol.Request(
                                    8, 2, 0, out -> out.write(0), directory.resolve("left.bin")),
                            new JavacBatchProtocol.Request(
                                    9, 0, 1, out -> out.write(0), directory.resolve("right.bin")));
            var results = session.executeBatch(requests);
            assertEquals(
                    List.of(8L, 9L),
                    results.stream().map(result -> result.request().taskId()).toList());
            assertEquals(2, results.getFirst().execution().attemptId());
            assertTrue(java.nio.file.Files.size(requests.getFirst().result()) > 0);
            assertEquals(2, session.executions().size());
            var batch = session.batches().getFirst();
            assertEquals(results.getFirst().execution().generation(), batch.generation());
            assertEquals(1, batch.batchId());
            assertTrue(batch.resultBytes() > 0);
            assertEquals(batch.resultBytes(), batch.toMap().get("result_bytes"));
        }
    }

    @Test
    void diskFailureStopsWorkerAndDoesNotDecode(@TempDir Path directory) throws Exception {
        try (var session = session(directory, "valid", Duration.ofSeconds(10))) {
            var task =
                    new JavacBatchProtocol.Request(
                            1, 0, 0, out -> out.write(0), directory.resolve("missing/out.bin"));
            assertThrows(IOException.class, () -> session.executeBatch(List.of(task)));
            assertEquals(-1, session.pid());
            assertTrue(session.executions().isEmpty());
        }
    }

    private static JavacWorkerSession session(Path directory, String mode, Duration timeout) {
        return new JavacWorkerSession(
                List.of(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        FakeWorker.class.getName(),
                        mode),
                directory,
                timeout);
    }

    public static final class FakeWorker {
        public static void main(String[] arguments) throws Exception {
            DataInputStream in = new DataInputStream(System.in);
            DataOutputStream out = new DataOutputStream(System.out);
            int completed = 0;
            while (in.readInt() == JavacBatchProtocol.MAGIC) {
                assertEquals(JavacBatchProtocol.VERSION, in.readInt());
                long generation = in.readLong(), batch = in.readLong();
                int count = in.readInt();
                long[] ids = new long[count];
                Path[] results = new Path[count];
                int[] attempts = new int[count], lanes = new int[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = in.readLong();
                    attempts[i] = in.readInt();
                    lanes[i] = in.readInt();
                    results[i] = Path.of(in.readUTF());
                    in.readNBytes(in.readInt());
                }
                if (arguments[0].equals("hang")) {
                    in.read();
                    return;
                }
                out.writeInt(JavacBatchProtocol.MAGIC);
                out.writeInt(JavacBatchProtocol.VERSION);
                out.writeLong(arguments[0].equals("generation") ? generation + 1 : generation);
                out.writeLong(arguments[0].equals("mismatch") ? batch + 1 : batch);
                out.writeInt(count);
                out.flush();
                if (arguments[0].equals("truncated")) return;
                byte[] payload = payload(arguments[0].equals("model-bad-terminal"));
                for (int i = count - 1; i >= 0; i--) {
                    out.writeLong(ids[i]);
                    out.writeInt(attempts[i]);
                    out.writeInt(arguments[0].equals("lane") ? 2 : lanes[i]);
                    out.writeLong(
                            arguments[0].equals("oversize")
                                    ? JavacProtocol.MAX_RESPONSE_BYTES + 1
                                    : payload.length);
                    byte[] hash =
                            java.security.MessageDigest.getInstance("SHA-256").digest(payload);
                    if (arguments[0].equals("checksum")) hash[0] ^= 1;
                    out.write(hash);
                    out.writeLong(ProcessHandle.current().pid());
                    out.writeUTF("fake");
                    for (int j = 0; j < 6; j++) out.writeLong(1);
                    java.nio.file.Files.write(results[i], payload);
                    out.writeInt(JavacBatchProtocol.TASK_END);
                    out.writeLong(ids[i]);
                }
                completed += count;
                out.writeInt(
                        arguments[0].equals("model-bad-terminal")
                                ? 0
                                : JavacBatchProtocol.BATCH_END);
                out.writeLong(generation);
                out.writeLong(batch);
                out.writeLong(arguments[0].equals("heap") ? 8 : 1);
                out.writeLong(10);
                boolean retire = arguments[0].equals("heap") || completed >= 8;
                out.writeBoolean(retire);
                out.writeLong(2);
                out.writeLong(0);
                out.writeLong(0);
                out.flush();
                if (retire) return;
            }
        }

        private static byte[] payload(boolean model) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(JavacProtocol.MAGIC);
            out.writeInt(1);
            out.writeByte('8');
            out.writeInt(model ? 1 : 0);
            if (model) {
                text(out, "/source/Example.java");
                text(out, "a".repeat(64));
                out.writeBoolean(false);
                ByteArrayOutputStream bytesTree = new ByteArrayOutputStream();
                DataOutputStream tree = new DataOutputStream(bytesTree);
                tree.writeByte(1);
                tree.writeInt(0);
                text(tree, "COMPILATION_UNIT");
                tree.writeLong(0);
                tree.writeLong(0);
                tree.writeInt(0);
                out.writeInt(bytesTree.size());
                out.write(bytesTree.toByteArray());
                out.writeInt(0);
                out.writeInt(0);
                out.writeInt(0);
                out.writeInt(0);
                out.writeInt(4);
                out.writeInt(0);
            }
            out.writeBoolean(true);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0x414A4544);
            return bytes.toByteArray();
        }

        private static void text(DataOutputStream out, String value) throws IOException {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }
}
