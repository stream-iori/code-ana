package com.anatomist.core.process;

import com.anatomist.json.Json;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Receipts identify private build subprocesses even if their Anatomist parent is killed. */
public final class SnapshotProcessReceipts implements AutoCloseable {
    public sealed interface Recovery permits InvalidReceipt, BuildProcess {}

    public record InvalidReceipt(Path path) implements Recovery {}

    public record BuildProcess(long pid, boolean owned) implements Recovery {}

    private static final ThreadLocal<Path> OWNER = new ThreadLocal<>();
    private final Path previous;

    private SnapshotProcessReceipts(Path directory) {
        previous = OWNER.get();
        OWNER.set(directory);
    }

    public static SnapshotProcessReceipts enter(Path directory) {
        return new SnapshotProcessReceipts(directory);
    }

    @Override
    public void close() {
        if (previous == null) OWNER.remove();
        else OWNER.set(previous);
    }

    /** Capture the owner on the calling thread, before the runner starts IO workers. */
    public static com.anatomist.core.process.ProcessRunner.Lifecycle lifecycle() {
        Path owner = OWNER.get();
        return process -> register(owner, process);
    }

    public static AutoCloseable watch(Process process) throws java.io.IOException {
        AutoCloseable receipt = register(OWNER.get(), process.toHandle());
        return () -> {
            try {
                stop(process.toHandle());
            } finally {
                receipt.close();
            }
        };
    }

    private static AutoCloseable register(Path owner, ProcessHandle process)
            throws java.io.IOException {
        if (owner == null) return () -> {};
        Path directory = Files.createDirectories(owner.resolve("processes"));
        Path receipt = directory.resolve(UUID.randomUUID().toString().replace("-", "") + ".json");
        Path pending = receipt.resolveSibling(receipt.getFileName() + ".tmp");
        try {
            Files.writeString(
                    pending,
                    Json.writeCompact(
                            Map.of(
                                    "kind",
                                    "snapshot-process-v1",
                                    "pid",
                                    process.pid(),
                                    "started",
                                    process.info()
                                            .startInstant()
                                            .map(Instant::toString)
                                            .orElse(""))));
            try {
                Files.move(pending, receipt, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(pending, receipt);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
        // Keep recovery evidence if bounded cleanup could not terminate the process.
        return () -> {
            if (!process.isAlive()) Files.deleteIfExists(receipt);
        };
    }

    public static List<Recovery> recover(Path owner, boolean execute) throws Exception {
        List<Recovery> result = new ArrayList<>();
        Path directory = owner.resolve("processes");
        if (!Files.isDirectory(directory)) return result;
        try (var files = Files.list(directory)) {
            for (Path receipt : files.toList()) {
                if (!receipt.getFileName().toString().matches("[a-f0-9]{32}\\.json(?:\\.tmp)?")
                        || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) continue;
                Object parsed;
                try {
                    parsed = Json.parseTree(Files.readString(receipt));
                } catch (RuntimeException invalid) {
                    result.add(new InvalidReceipt(receipt));
                    continue;
                }
                if (!(parsed instanceof Map<?, ?> row)
                        || !"snapshot-process-v1".equals(row.get("kind"))
                        || !(row.get("pid") instanceof Number)) continue;
                long pid = ((Number) row.get("pid")).longValue();
                var process = ProcessHandle.of(pid);
                boolean matching =
                        process.isPresent()
                                && process.get()
                                        .info()
                                        .startInstant()
                                        .map(Instant::toString)
                                        .orElse("unknown")
                                        .equals(row.get("started"));
                result.add(new BuildProcess(pid, matching));
                if (execute) {
                    if (matching) stop(process.get());
                    Files.deleteIfExists(receipt);
                }
            }
        }
        return result;
    }

    private static void stop(ProcessHandle process) throws Exception {
        com.anatomist.core.process.ProcessRunner.stop(process);
    }
}
