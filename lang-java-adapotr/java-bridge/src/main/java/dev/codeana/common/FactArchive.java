package dev.codeana.common;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Public CAF1 fact transport only. Compiler models never enter this archive. */
public final class FactArchive extends AbstractList<Object> implements AutoCloseable {
    private static final int MAGIC = 0x43414631, RECORD_LIMIT = 8 << 20;
    private final FileChannel channel;
    private final Path path;
    private final long maximumBytes;
    private final int maximumRecords;
    private long[] offsets = new long[1024];
    private int count;
    private long bytes = 4;
    private boolean readOnly;
    private boolean indexed = true;
    // CAF1 frames are unchanged; batch small writes within a fixed 64 KiB buffer.
    private final ByteBuffer pending = ByteBuffer.allocate(64 << 10);
    private long flushedBytes = 4;

    public FactArchive(Path path, long maximumBytes, int maximumRecords) throws IOException {
        this.path = path;
        this.maximumBytes = maximumBytes;
        this.maximumRecords = maximumRecords;
        channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
        write(ByteBuffer.allocate(4).putInt(MAGIC).flip(), 0);
    }

    public FactArchive(Path path, long maximumBytes, int maximumRecords, boolean indexed)
            throws IOException {
        this(path, maximumBytes, maximumRecords);
        this.indexed = indexed;
    }

    private FactArchive(Path path, long maximumBytes, int maximumRecords, String sha)
            throws IOException {
        this.path = path;
        this.maximumBytes = maximumBytes;
        this.maximumRecords = maximumRecords;
        if (Files.size(path) > maximumBytes || !Json.sha(path).equals(sha))
            throw new IOException("BASELINE_ARCHIVE_IDENTITY");
        channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            if (read(0, 4).getInt() != MAGIC) throw new IOException("BASELINE_ARCHIVE_VERSION");
            bytes = channel.size();
            for (long at = 4; at < bytes; ) {
                int length = read(at, 4).getInt();
                if (length < 0 || length > RECORD_LIMIT || at + 4 + length > bytes)
                    throw new IOException("BASELINE_ARCHIVE_LENGTH");
                reserve();
                offsets[count++] = at;
                at += 4 + length;
            }
            readOnly = true;
        } catch (Throwable failure) {
            channel.close();
            throw failure;
        }
    }

    public static FactArchive open(Path path, long bytes, int records, String sha)
            throws IOException {
        return new FactArchive(path, bytes, records, sha);
    }

    private void reserve() {
        if (count >= maximumRecords) throw new IllegalStateException("FACT_LIMIT count_limit=" + maximumRecords + "; count=" + count);
        if (indexed && count == offsets.length)
            offsets = Arrays.copyOf(offsets, Math.min(maximumRecords, offsets.length * 2));
    }

    public boolean add(Object value) {
        return addEncoded(Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    /** The caller supplies the same canonical UTF-8 bytes used for fact identity checks. */
    public boolean addEncoded(byte[] encoded) {
        if (readOnly) throw new IllegalStateException("SEALED_FACT_ARCHIVE");
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        if (encoded.length > RECORD_LIMIT)
            throw new IllegalStateException("FACT_BYTE_LIMIT record_bytes=" + encoded.length + "; record_limit=" + RECORD_LIMIT);
        if (bytes + 4 + encoded.length > maximumBytes)
            throw new IllegalStateException("FACT_BYTE_LIMIT byte_limit=" + maximumBytes + "; bytes=" + bytes + "; record_bytes=" + encoded.length);
        reserve();
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.ARCHIVE_APPEND, encoded.length, true)) {
            if (pending.remaining() < 4) flushPending();
            pending.putInt(encoded.length);
            if (pending.remaining() < encoded.length) flushPending();
            if (encoded.length >= pending.capacity()) {
                write(ByteBuffer.wrap(encoded), flushedBytes);
                flushedBytes += encoded.length;
            } else pending.put(encoded);
            if (indexed) offsets[count] = bytes;
            count++;
            bytes += 4 + encoded.length;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] encoded(int index) {
        if (!indexed) throw new IllegalStateException("WRITE_ONLY_FACT_ARCHIVE");
        Objects.checkIndex(index, count);
        try {
            flushPending();
            return read(offsets[index] + 4, read(offsets[index], 4).getInt()).array();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Object get(int index) {
        return Json.parse(encoded(index));
    }

    public int size() {
        return count;
    }

    public long bytes() {
        return bytes;
    }

    /** Move a sealed archive out of the compiler session's disposable directory. */
    public Map<String, Object> export(Path destination) throws IOException {
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.ARCHIVE_FLUSH, 0, false)) { flushPending(); }
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.ARCHIVE_FORCE, bytes, false)) { channel.force(true); }
        readOnly = true;
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.ARCHIVE_MOVE, bytes, false)) {
            Files.move(path, destination, StandardCopyOption.ATOMIC_MOVE);
        }
        String sha;
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.ARCHIVE_SHA, bytes, false)) { sha = Json.sha(destination); }
        return Json.object(
                "format",
                "CAF1",
                "path",
                destination.toString(),
                "count",
                count,
                "bytes",
                bytes,
                "sha256",
                sha);
    }

    private ByteBuffer read(long at, int size) throws IOException {
        ByteBuffer value = ByteBuffer.allocate(size);
        while (value.hasRemaining()) {
            int n = channel.read(value, at + value.position());
            if (n <= 0) throw new EOFException();
        }
        return value.flip();
    }

    private void write(ByteBuffer value, long at) throws IOException {
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.ARCHIVE_WRITE, value.remaining(), false)) {
            while (value.hasRemaining()) {
                int n = channel.write(value, at + value.position());
                if (n <= 0) throw new IOException("FACT_ARCHIVE_WRITE");
            }
        }
    }

    private void flushPending() throws IOException {
        if (pending.position() == 0) return;
        int length = pending.position();
        pending.flip();
        write(pending, flushedBytes);
        flushedBytes += length;
        pending.clear();
    }

    public void close() throws IOException {
        try {
            flushPending();
        } finally {
            channel.close();
        }
    }
}
