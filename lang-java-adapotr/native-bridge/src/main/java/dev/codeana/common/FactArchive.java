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
    private final long maximumBytes;
    private final int maximumRecords;
    private long[] offsets = new long[1024];
    private int count;
    private long bytes = 4;
    private boolean readOnly;

    public FactArchive(Path path, long maximumBytes, int maximumRecords) throws IOException {
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

    private FactArchive(Path path, long maximumBytes, int maximumRecords, String sha)
            throws IOException {
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
        if (count >= maximumRecords) throw new IllegalStateException("FACT_LIMIT");
        if (count == offsets.length)
            offsets = Arrays.copyOf(offsets, Math.min(maximumRecords, offsets.length * 2));
    }

    public boolean add(Object value) {
        if (readOnly) throw new IllegalStateException("SEALED_FACT_ARCHIVE");
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        byte[] encoded = Json.write(value).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > RECORD_LIMIT || bytes + 4 + encoded.length > maximumBytes)
            throw new IllegalStateException("FACT_BYTE_LIMIT");
        reserve();
        try {
            write(ByteBuffer.allocate(4).putInt(encoded.length).flip(), bytes);
            write(ByteBuffer.wrap(encoded), bytes + 4);
            offsets[count++] = bytes;
            bytes += 4 + encoded.length;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] encoded(int index) {
        Objects.checkIndex(index, count);
        try {
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

    private ByteBuffer read(long at, int size) throws IOException {
        ByteBuffer value = ByteBuffer.allocate(size);
        while (value.hasRemaining()) {
            int n = channel.read(value, at + value.position());
            if (n <= 0) throw new EOFException();
        }
        return value.flip();
    }

    private void write(ByteBuffer value, long at) throws IOException {
        while (value.hasRemaining()) {
            int n = channel.write(value, at + value.position());
            if (n <= 0) throw new IOException("FACT_ARCHIVE_WRITE");
        }
    }

    public void close() throws IOException {
        channel.close();
    }
}
