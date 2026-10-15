package com.anatomist.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Cross-process read/write lock for SQLite index databases.
 *
 * <p>Uses a two-layer strategy:
 *
 * <ul>
 *   <li>JVM-level {@link ReentrantReadWriteLock} for same-process thread coordination
 *   <li>File-level {@link FileLock} (exclusive only) for cross-process coordination
 * </ul>
 *
 * <p>Readers acquire the JVM read lock (concurrent within same JVM) and a shared file lock (blocks
 * if another process holds exclusive). Writers acquire JVM write lock + exclusive file lock.
 */
public class IndexLock implements AutoCloseable {

    private static final long DEFAULT_TIMEOUT_MS = 60_000;
    private static final long POLL_INTERVAL_MS = 50;

    private static final ConcurrentHashMap<String, LockState> JVM_LOCKS = new ConcurrentHashMap<>();

    private final LockState state;
    private final boolean isWrite;

    private IndexLock(LockState state, boolean isWrite) {
        this.state = state;
        this.isWrite = isWrite;
    }

    public static IndexLock forWrite(Path dbPath) {
        return forWrite(dbPath, DEFAULT_TIMEOUT_MS);
    }

    public static IndexLock forWrite(Path dbPath, long timeoutMs) {
        requireValidTimeout(timeoutMs);
        LockState state = stateFor(dbPath);
        long startedAt = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Step 1: JVM write lock
        try {
            if (!state.rwLock.writeLock().tryLock(timeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new LockTimeoutException(
                        "Write lock JVM-level timeout after " + timeoutMs + "ms");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LockException("Interrupted waiting for JVM write lock", ie);
        }

        // Step 2: File exclusive lock
        long remaining = remainingNanos(startedAt, timeoutNanos);
        try {
            acquireFileLock(state, false, remaining, timeoutMs);
        } catch (RuntimeException e) {
            state.rwLock.writeLock().unlock();
            throw e;
        }

        return new IndexLock(state, true);
    }

    public static IndexLock forRead(Path dbPath) {
        return forRead(dbPath, DEFAULT_TIMEOUT_MS);
    }

    public static IndexLock forRead(Path dbPath, long timeoutMs) {
        requireValidTimeout(timeoutMs);
        LockState state = stateFor(dbPath);
        long startedAt = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);

        // Step 1: JVM read lock
        try {
            if (!state.rwLock.readLock().tryLock(timeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new LockTimeoutException(
                        "Read lock JVM-level timeout after " + timeoutMs + "ms");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LockException("Interrupted waiting for JVM read lock", ie);
        }

        // Step 2: File shared lock (for cross-process blocking by writer in another JVM)
        long remaining = remainingNanos(startedAt, timeoutNanos);
        try {
            acquireFileLock(state, true, remaining, timeoutMs);
        } catch (RuntimeException e) {
            state.rwLock.readLock().unlock();
            throw e;
        }

        return new IndexLock(state, false);
    }

    static Path lockPathFor(Path dbPath) {
        return dbPath.resolveSibling(dbPath.getFileName() + ".lock");
    }

    @Override
    public void close() {
        releaseFileLock(state);
        if (isWrite) {
            state.rwLock.writeLock().unlock();
        } else {
            state.rwLock.readLock().unlock();
        }
    }

    private static LockState stateFor(Path dbPath) {
        String key = dbPath.toAbsolutePath().normalize().toString();
        return JVM_LOCKS.computeIfAbsent(key, k -> new LockState(lockPathFor(dbPath)));
    }

    private static void acquireFileLock(
            LockState state, boolean shared, long timeoutNanos, long configuredTimeoutMs) {
        long startedAt = System.nanoTime();
        synchronized (state.channelLock) {
            ensureChannel(state);
            while (true) {
                try {
                    FileLock fl = state.channel.tryLock(0, Long.MAX_VALUE, shared);
                    if (fl != null) {
                        state.fileLock = fl;
                        state.lockCount++;
                        return;
                    }
                } catch (OverlappingFileLockException e) {
                    // Same JVM already holds a file lock — share via JVM lock
                    state.lockCount++;
                    return;
                } catch (IOException e) {
                    throw new LockException("File lock acquisition failed", e);
                }

                long remaining = remainingNanos(startedAt, timeoutNanos);
                if (remaining == 0) {
                    throw new LockTimeoutException(
                            (shared ? "Read" : "Write")
                                    + " file lock timeout after "
                                    + configuredTimeoutMs
                                    + "ms");
                }

                try {
                    TimeUnit.NANOSECONDS.sleep(
                            Math.min(TimeUnit.MILLISECONDS.toNanos(POLL_INTERVAL_MS), remaining));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LockException("Interrupted waiting for file lock", ie);
                }
            }
        }
    }

    private static void requireValidTimeout(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("lock timeout must be >= 0ms");
        }
    }

    private static long remainingNanos(long startedAt, long timeoutNanos) {
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed < 0 || elapsed >= timeoutNanos) return 0;
        return timeoutNanos - elapsed;
    }

    private static void releaseFileLock(LockState state) {
        synchronized (state.channelLock) {
            state.lockCount--;
            if (state.lockCount <= 0) {
                state.lockCount = 0;
                if (state.fileLock != null && state.fileLock.isValid()) {
                    try {
                        state.fileLock.release();
                    } catch (IOException ignored) {
                    }
                    state.fileLock = null;
                }
                if (state.channel != null && state.channel.isOpen()) {
                    try {
                        state.channel.close();
                    } catch (IOException ignored) {
                    }
                    state.channel = null;
                }
            }
        }
    }

    private static void ensureChannel(LockState state) {
        if (state.channel == null || !state.channel.isOpen()) {
            try {
                Files.createDirectories(state.lockPath.getParent());
                state.channel =
                        FileChannel.open(
                                state.lockPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new LockException("Cannot open lock file: " + state.lockPath, e);
            }
        }
    }

    private static class LockState {
        final Path lockPath;
        final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        final Object channelLock = new Object();
        FileChannel channel;
        FileLock fileLock;
        int lockCount;

        LockState(Path lockPath) {
            this.lockPath = lockPath;
        }
    }

    public static class LockException extends RuntimeException {
        public LockException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    public static class LockTimeoutException extends RuntimeException {
        public LockTimeoutException(String msg) {
            super(msg);
        }
    }
}
