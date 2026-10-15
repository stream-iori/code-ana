package com.anatomist.core.javac;

import com.anatomist.core.java.JavaUnit;
import com.anatomist.core.process.ProcessConversation;
import com.anatomist.core.process.ProcessRunner;
import com.anatomist.core.process.SnapshotProcessReceipts;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** One reusable process; each exchange receives a complete bounded batch without decoding it. */
final class JavacWorkerSession implements AutoCloseable {
    private final ProcessConversation conversation;
    private final List<JavacBatchMetrics> batches = new ArrayList<>();

    List<JavacBatchMetrics> batches() {
        return List.copyOf(batches);
    }

    private long taskId;
    private long batchId;
    private final List<JavacBatchProtocol.Execution> executions = new ArrayList<>();

    JavacWorkerSession(List<String> command, Path directory, Duration timeout) {
        conversation =
                new ProcessConversation(
                        new ProcessRunner.Spec(
                                command,
                                directory,
                                Map.of(
                                        "JAVA_TOOL_OPTIONS",
                                        "",
                                        "_JAVA_OPTIONS",
                                        "",
                                        "JDK_JAVA_OPTIONS",
                                        ""),
                                timeout,
                                false,
                                SnapshotProcessReceipts.lifecycle()));
    }

    int launches() {
        return conversation.launches();
    }

    long pid() {
        return conversation.pid();
    }

    List<JavacBatchProtocol.Execution> executions() {
        return List.copyOf(executions);
    }

    synchronized List<JavacBatchProtocol.Result> executeBatch(
            List<JavacBatchProtocol.Request> requests) throws IOException {
        JavacCancellation.check();
        long batch = ++batchId;
        long started = System.nanoTime();
        var results =
                conversation.exchange(
                        (input, output) -> {
                            var reply =
                                    JavacBatchProtocol.exchange(
                                            input,
                                            output,
                                            conversation.launches(),
                                            batch,
                                            requests);
                            return new ProcessConversation.Reply<>(reply, reply.retire());
                        });
        var received = results;
        long generation = received.results().getFirst().execution().generation();
        batches.add(
                new JavacBatchMetrics(
                        generation,
                        batch,
                        received.heapUsed(),
                        received.heapMaximum(),
                        received.heapPeak(),
                        received.gcCount(),
                        received.gcMillis(),
                        received.results().stream()
                                .mapToLong(JavacBatchProtocol.Result::bytes)
                                .sum(),
                        System.nanoTime() - started,
                        received.transportBytes()));
        for (var result : received.results()) {
            executions.add(result.execution());
            com.anatomist.core.logging.AnatomistLog.debug(
                    "javac_task "
                            + com.anatomist.json.Json.writeCompact(evidence(result.execution())));
        }
        com.anatomist.core.logging.AnatomistLog.debug(
                "javac_batch id="
                        + batch
                        + " bytes="
                        + results.results().stream()
                                .mapToLong(JavacBatchProtocol.Result::bytes)
                                .sum()
                        + " exchange_nanos="
                        + (System.nanoTime() - started));
        return results.results();
    }

    static Map<String, Object> evidence(JavacBatchProtocol.Execution event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("generation", event.generation());
        value.put("batch_id", event.batchId());
        value.put("task_id", event.taskId());
        value.put("attempt_id", event.attemptId());
        value.put("lane_id", event.laneId());
        value.put("pid", event.pid());
        value.put("thread", event.thread());
        value.put("started", event.started());
        value.put("finished", event.finished());
        value.put("parse_started", event.parseStarted());
        value.put("parse_finished", event.parseFinished());
        value.put("analyze_started", event.analyzeStarted());
        value.put("analyze_finished", event.analyzeFinished());
        return Collections.unmodifiableMap(value);
    }

    synchronized JavacProtocol.Result execute(
            ProcessRunner.Input request,
            Consumer<JavaUnit> consumer,
            JavacDeclarationCache.Lease declarations,
            Path transcript)
            throws IOException {
        return execute(++taskId, 0, request, consumer, declarations, transcript);
    }

    synchronized JavacProtocol.Result execute(
            long logicalTaskId,
            int attemptId,
            ProcessRunner.Input request,
            Consumer<JavaUnit> consumer,
            JavacDeclarationCache.Lease declarations,
            Path transcript)
            throws IOException {
        try (var scratch =
                new JavacCompilationCache.Directory(
                        Files.createTempDirectory("anatomist-javac-result-"))) {
            Path result = scratch.path().resolve("received.bin");
            var received =
                    executeBatch(
                            List.of(
                                    new JavacBatchProtocol.Request(
                                            logicalTaskId, attemptId, 0, request, result)));
            JavacProtocol.Result decoded =
                    JavacProtocol.response(received.getFirst(), consumer, declarations);
            if (transcript != null)
                Files.copy(result, transcript, StandardCopyOption.REPLACE_EXISTING);
            return decoded;
        } catch (IOException failure) {
            try {
                conversation.retire();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        conversation.close();
    }
}
