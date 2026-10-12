package com.anatomist.core.process;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

class ProcessRunnerTest {
    @Test
    void restrictedScanningDoesNotFailQueriesOrRetry() throws Exception {
        for (RuntimeException restricted :
                List.of(
                        new SecurityException("denied"),
                        new UnsupportedOperationException("unsupported"),
                        sysctlFailure())) {
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            var result =
                    ProcessRunner.run(
                            child("eof", Duration.ofSeconds(10)),
                            null,
                            ProcessRunner.capture(100),
                            (process, found) -> {
                                attempts.incrementAndGet();
                                throw restricted;
                            });
            assertEquals(
                    "eof",
                    new String(result.requireValue(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1, result.exitCode());
            assertEquals(1, attempts.get());
            assertTrue(result.diagnostics().cleanupComplete());
            assertEquals("tracked_processes", result.diagnostics().cleanupScope());
            assertEquals(
                    restricted instanceof UnsupportedOperationException
                            ? ProcessRunner.DescendantScan.UNSUPPORTED
                            : ProcessRunner.DescendantScan.DENIED,
                    result.diagnostics().descendantScan());
        }
    }

    private static RuntimeException sysctlFailure() {
        var failure = new RuntimeException("Operation not permitted (sysctl failed)");
        failure.setStackTrace(
                new StackTraceElement[] {
                    new StackTraceElement(
                            "java.lang.ProcessHandleImpl", "getProcessPids0", null, -2)
                });
        return failure;
    }

    @Test
    void unrelatedScanExceptionsRemainFailuresAndStillReapChild() {
        for (RuntimeException failure :
                List.of(
                        new IllegalStateException("bug"),
                        new RuntimeException("Operation not permitted (sysctl failed)"))) {
            var result =
                    ProcessRunner.run(
                            child("sleep", Duration.ofSeconds(10)),
                            null,
                            ProcessRunner.capture(100),
                            (process, found) -> {
                                throw failure;
                            });
            assertSame(
                    failure,
                    assertThrows(ProcessRunner.Failure.class, result::requireValue).getCause());
            assertEquals(
                    ProcessRunner.DescendantScan.FAILED, result.diagnostics().descendantScan());
            assertTrue(result.diagnostics().cleanupComplete());
            assertFalse(
                    ProcessHandle.of(result.diagnostics().pid())
                            .map(ProcessHandle::isAlive)
                            .orElse(false));
        }
    }

    @Test
    void restrictedTimeoutAndEarlyCompletionStillCleanDirectChild() throws Exception {
        ProcessRunner.Scanner denied =
                (process, found) -> {
                    throw new SecurityException();
                };
        var timeout =
                ProcessRunner.run(
                        child("sleep", Duration.ofMillis(800)),
                        null,
                        ProcessRunner.capture(100),
                        denied);
        assertEquals(
                ProcessRunner.Kind.TIMED_OUT,
                assertThrows(ProcessRunner.Failure.class, timeout::requireValue).kind());
        assertTrue(timeout.diagnostics().cleanupComplete());
        var early =
                ProcessRunner.run(
                        child("sleep", Duration.ofSeconds(10)),
                        null,
                        in -> ProcessRunner.Read.early(ProcessRunner.line(in, 100)),
                        denied);
        assertNotNull(early.requireValue());
        assertTrue(early.diagnostics().cleanupComplete());
        assertFalse(
                ProcessHandle.of(early.diagnostics().pid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
        var limited =
                ProcessRunner.run(
                        child("duplex", Duration.ofSeconds(10)),
                        ProcessRunner.bytes(new byte[100000]),
                        ProcessRunner.capture(1024),
                        denied);
        assertEquals(
                ProcessRunner.Kind.OUTPUT_LIMIT,
                assertThrows(ProcessRunner.Failure.class, limited::requireValue).kind());
        assertTrue(limited.diagnostics().cleanupComplete());
    }

    @Test
    void restrictedCancellationPreservesInterruptAndCleansChild() throws Exception {
        var pid = new CompletableFuture<Long>();
        var result = new CompletableFuture<ProcessRunner.Result<Integer>>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread owner =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    var value =
                                            ProcessRunner.run(
                                                    child("sleep", Duration.ofSeconds(30)),
                                                    null,
                                                    input -> {
                                                        pid.complete(
                                                                Long.parseLong(
                                                                        ProcessRunner.line(
                                                                                input, 100)));
                                                        return ProcessRunner.Read.complete(
                                                                input.read());
                                                    },
                                                    (process, found) -> {
                                                        throw new SecurityException();
                                                    });
                                    interrupted.set(Thread.currentThread().isInterrupted());
                                    result.complete(value);
                                });
        try {
            long owned = pid.get(10, TimeUnit.SECONDS);
            owner.interrupt();
            var value = result.get(8, TimeUnit.SECONDS);
            assertEquals(
                    ProcessRunner.Kind.CANCELLED,
                    assertThrows(ProcessRunner.Failure.class, value::requireValue).kind());
            assertTrue(interrupted.get());
            assertTrue(value.diagnostics().cleanupComplete());
            assertFalse(ProcessHandle.of(owned).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            owner.interrupt();
            owner.join(8000);
        }
    }

    @Test
    void partialScanRetainsKnownChildrenAndStopsRetrying() throws Exception {
        Process owned = new ProcessBuilder("sleep", "60").start();
        try {
            var tracking =
                    new ProcessRunner.Tracking(
                            (process, found) -> {
                                found.accept(owned.toHandle());
                                throw new SecurityException();
                            });
            tracking.scan(ProcessHandle.current());
            assertEquals(Set.of(owned.toHandle()), tracking.children);
            assertEquals(ProcessRunner.DescendantScan.DENIED, tracking.status);
            var result =
                    ProcessRunner.run(
                            child("eof", Duration.ofSeconds(10)),
                            null,
                            ProcessRunner.capture(100),
                            (process, found) -> {
                                found.accept(owned.toHandle());
                                throw new SecurityException();
                            });
            assertEquals(1, result.exitCode());
            assertTrue(result.diagnostics().cleanupComplete());
            assertTrue(owned.waitFor(5, TimeUnit.SECONDS));
        } finally {
            owned.destroyForcibly();
            owned.waitFor();
        }
    }

    @Test
    void restrictedStopStillTerminatesOwnedProcess() throws Exception {
        Process owned = new ProcessBuilder("sleep", "60").start();
        try {
            ProcessRunner.stop(
                    owned.toHandle(),
                    (process, found) -> {
                        throw new SecurityException();
                    });
            assertTrue(owned.waitFor(5, TimeUnit.SECONDS));
        } finally {
            owned.destroyForcibly();
            owned.waitFor();
        }
    }

    @Test
    void cleanupScanFailureDoesNotSkipRegistrationOrKill() throws Exception {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var cleanupError = new IllegalStateException("cleanup scan");
        var tracking =
                new ProcessRunner.Tracking(
                        (process, found) -> {
                            throw cleanupError;
                        });
        Process owned = new ProcessBuilder("sleep", "60").start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            assertSame(
                    cleanupError,
                    ProcessRunner.cleanup(owned, tracking, () -> closed.set(true), workers));
            assertTrue(closed.get());
            assertFalse(owned.isAlive());
            assertTrue(workers.isTerminated());
        } finally {
            owned.destroyForcibly();
            owned.waitFor();
        }
    }

    @Test
    void unknownStopScanErrorIsReportedAfterTermination() throws Exception {
        Process owned = new ProcessBuilder("sleep", "60").start();
        try {
            var primary = new IllegalStateException("scan failed");
            assertSame(
                    primary,
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    ProcessRunner.stop(
                                            owned.toHandle(),
                                            (process, found) -> {
                                                throw primary;
                                            })));
            assertTrue(owned.waitFor(5, TimeUnit.SECONDS));
        } finally {
            owned.destroyForcibly();
            owned.waitFor();
        }
    }

    @Test
    void registrationFailureDoesNotHidePrimaryOrPreventTermination() {
        var spec = child("sleep", Duration.ofSeconds(10));
        var closeError = new IOException("registration close");
        var primary = new IOException("reader");
        var registered =
                new ProcessRunner.Spec(
                        spec.command(),
                        null,
                        Map.of(),
                        spec.timeout(),
                        false,
                        process ->
                                () -> {
                                    throw closeError;
                                });
        var result =
                ProcessRunner.run(
                        registered,
                        null,
                        in -> {
                            throw primary;
                        },
                        (process, found) -> {
                            throw new SecurityException();
                        });
        assertSame(
                primary,
                assertThrows(ProcessRunner.Failure.class, result::requireValue).getCause());
        assertTrue(Arrays.asList(primary.getSuppressed()).contains(closeError));
        assertFalse(result.diagnostics().cleanupComplete());
        assertFalse(
                ProcessHandle.of(result.diagnostics().pid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
    }

    @Test
    void independentCleanupAttemptsRetainFailuresAndShareDeadline() throws Exception {
        var attempted = new java.util.concurrent.atomic.AtomicBoolean();
        var first = new IOException("stream");
        var second = new IOException("registration");
        var tasks =
                List.of(
                        ProcessRunner.cleanupTask(
                                () -> {
                                    throw first;
                                }),
                        ProcessRunner.cleanupTask(
                                () -> {
                                    attempted.set(true);
                                    throw second;
                                }),
                        ProcessRunner.cleanupTask(() -> Thread.sleep(60000)));
        long started = System.nanoTime();
        var failure =
                ProcessRunner.awaitCleanup(
                        tasks, started + TimeUnit.MILLISECONDS.toNanos(150), null);
        assertSame(first, failure);
        assertTrue(attempted.get());
        assertTrue(Arrays.asList(failure.getSuppressed()).contains(second));
        assertTrue(
                Arrays.stream(failure.getSuppressed())
                        .anyMatch(TimeoutException.class::isInstance));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
    }

    private ProcessRunner.Spec child(String mode, Duration timeout) {
        return new ProcessRunner.Spec(
                List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Producer.class.getName(),
                        mode),
                null,
                timeout);
    }

    @Test
    void fullDuplexAndBoundedDiagnostics() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        Arrays.fill(payload, (byte) 'x');
        var result =
                ProcessRunner.run(
                        child("duplex", Duration.ofSeconds(10)),
                        ProcessRunner.bytes(payload),
                        ProcessRunner.capture(4 * 1024 * 1024));
        assertEquals(payload.length, result.requireValue().length);
        assertEquals(0, result.exitCode());
        assertEquals(payload.length, result.diagnostics().stdinBytes());
        assertEquals(payload.length, result.diagnostics().stderrBytes());
        assertEquals(ProcessRunner.SMALL_OUTPUT, result.diagnostics().stderrTail().length());
        assertTrue(result.diagnostics().cleanupComplete());
    }

    @Test
    void blockedWriterTimesOutAndReapsChild() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(9),
                () -> {
                    var result =
                            ProcessRunner.run(
                                    child("sleep", Duration.ofMillis(800)),
                                    ProcessRunner.bytes(new byte[2 * 1024 * 1024]),
                                    ProcessRunner.capture(1024));
                    assertEquals(
                            ProcessRunner.Kind.TIMED_OUT,
                            assertThrows(ProcessRunner.Failure.class, result::requireValue).kind());
                    assertFalse(
                            ProcessHandle.of(result.diagnostics().pid())
                                    .map(ProcessHandle::isAlive)
                                    .orElse(false));
                    assertTrue(result.diagnostics().cleanupComplete());
                });
    }

    @Test
    void captureLimitIsFailureNotPartialSuccess() {
        var result =
                ProcessRunner.run(
                        child("duplex", Duration.ofSeconds(10)),
                        ProcessRunner.bytes(new byte[100000]),
                        ProcessRunner.capture(1024));
        assertEquals(
                ProcessRunner.Kind.OUTPUT_LIMIT,
                assertThrows(ProcessRunner.Failure.class, result::requireValue).kind());
        assertFalse(
                ProcessHandle.of(result.diagnostics().pid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
    }

    @Test
    void earlyCompletionAndParserFailureStopProducer() throws Exception {
        var early =
                ProcessRunner.run(
                        child("sleep", Duration.ofSeconds(10)),
                        null,
                        in -> ProcessRunner.Read.early(ProcessRunner.line(in, 100)));
        assertNotNull(early.requireValue());
        assertInstanceOf(ProcessRunner.Early.class, early.outcome());
        assertFalse(
                ProcessHandle.of(early.diagnostics().pid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false));
        var primary = new IOException("bad protocol");
        var failed =
                ProcessRunner.run(
                        child("sleep", Duration.ofSeconds(10)),
                        null,
                        in -> {
                            throw primary;
                        });
        assertSame(
                primary,
                assertThrows(ProcessRunner.Failure.class, failed::requireValue).getCause());
        assertTrue(failed.diagnostics().cleanupComplete());
    }

    @Test
    void interruptCancelsOwnedProcessAndPreservesFlag() throws Exception {
        var pid = new CompletableFuture<Long>();
        var done = new CompletableFuture<Boolean>();
        Thread owner =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    var result =
                                            ProcessRunner.run(
                                                    child("sleep", Duration.ofSeconds(30)),
                                                    null,
                                                    in -> {
                                                        pid.complete(
                                                                Long.parseLong(
                                                                        ProcessRunner.line(
                                                                                in, 100)));
                                                        return ProcessRunner.Read.complete(
                                                                in.read());
                                                    });
                                    done.complete(
                                            result.outcome() instanceof ProcessRunner.Failed f
                                                    && f.kind() == ProcessRunner.Kind.CANCELLED
                                                    && Thread.currentThread().isInterrupted()
                                                    && result.diagnostics().cleanupComplete());
                                });
        try {
            long child = pid.get(10, TimeUnit.SECONDS);
            owner.interrupt();
            assertTrue(done.get(8, TimeUnit.SECONDS));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            owner.interrupt();
            owner.join(8000);
        }
    }

    @Test
    void noInputClosesStdinAndExitOneIsPreserved() throws Exception {
        var result =
                ProcessRunner.run(
                        child("eof", Duration.ofSeconds(10)), null, ProcessRunner.capture(100));
        assertEquals(
                "eof", new String(result.requireValue(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, result.exitCode());
    }

    @Test
    void fastEmptyOutputStillCollectsTheReaderValue() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            var result =
                    ProcessRunner.run(
                            child("exit-zero", Duration.ofSeconds(10)),
                            null,
                            input -> ProcessRunner.Read.complete(ProcessRunner.zeroRecords(input)));
            assertNotNull(result.requireValue());
            assertTrue(result.requireValue().isEmpty());
            assertEquals(0, result.exitCode());
        }
    }

    @Test
    void inheritedPipesHaveBoundedCleanupAfterParentExit() throws Exception {
        var descendant = new java.util.concurrent.atomic.AtomicLong();
        var result =
                ProcessRunner.run(
                        child("descendant", Duration.ofSeconds(2)),
                        null,
                        input -> {
                            descendant.set(Long.parseLong(ProcessRunner.line(input, 100)));
                            return ProcessRunner.Read.complete(input.read());
                        });
        assertTrue(result.diagnostics().elapsedMillis() < 7500);
        assertTrue(result.diagnostics().cleanupComplete(), result.diagnostics().summary());
        assertTrue(descendant.get() > 0);
        assertFalse(ProcessHandle.of(descendant.get()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void startupFailureAndBrokenPipeAreExplicit() {
        var missing =
                ProcessRunner.run(
                        new ProcessRunner.Spec(
                                List.of("/nonexistent/anatomist-test"),
                                null,
                                Duration.ofSeconds(1)),
                        null,
                        ProcessRunner.capture(100));
        assertEquals(
                ProcessRunner.Kind.START_FAILED,
                assertThrows(ProcessRunner.Failure.class, missing::requireValue).kind());
        var broken =
                ProcessRunner.run(
                        child("exit", Duration.ofSeconds(5)),
                        ProcessRunner.bytes(new byte[2 * 1024 * 1024]),
                        ProcessRunner.capture(100));
        assertEquals(
                ProcessRunner.Kind.IO_FAILED,
                assertThrows(ProcessRunner.Failure.class, broken::requireValue).kind());
        assertTrue(broken.diagnostics().cleanupComplete());
    }

    @Test
    void tailRetainsLastBytesAcrossWraps() throws Exception {
        var tail = new ProcessRunner.Tail(5);
        tail.write("abc".getBytes());
        tail.write("0123456789".getBytes());
        tail.write('x');
        assertEquals("6789x", tail.text());
    }

    public static class Producer {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "duplex" -> {
                    byte[] block = new byte[8192];
                    int n;
                    while ((n = System.in.read(block)) != -1) {
                        System.out.write(block, 0, n);
                        System.err.write(block, 0, n);
                    }
                }
                case "sleep" -> {
                    System.out.println(ProcessHandle.current().pid());
                    System.out.flush();
                    Thread.sleep(60000);
                }
                case "exit" -> System.exit(2);
                case "exit-zero" -> {}
                case "descendant" -> {
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    Producer.class.getName(),
                                    "sleep")
                            .inheritIO()
                            .start();
                    Thread.sleep(500);
                }
                case "eof" -> {
                    if (System.in.read() == -1) System.out.print("eof");
                    System.exit(1);
                }
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
