package dev.codeana.common;

import dev.codeana.graph.GraphRecords;
import com.google.protobuf.CodedOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Task-owned, bounded protobuf rows. No whole-fact JSON and no project-wide dedup map. */
public final class GraphArchive implements AutoCloseable {
    private final Path path;
    private final long maxBytes, maxRows;
    private final MessageDigest digest;
    private final DataOutputStream output;
    private final CodedOutputStream encoded;
    private long count, bytes = 4;
    private boolean sealed;

    public GraphArchive(Path path, long maxBytes, long maxRows) throws IOException {
        this.path = path;
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        output = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(Files.newOutputStream(path), digest), 64 << 10));
        output.writeInt(0x47524131);
        encoded=CodedOutputStream.newInstance(output,64 << 10);
    }

    public void add(GraphRecords.Row row) throws IOException {
        int length = row.getSerializedSize();
        if (sealed || length <= 0 || length > 8 << 20) throw new IOException("GRAPH_RECORD_BUDGET");
        if (count >= maxRows || bytes + length + 4 > maxBytes) throw new IOException("GRAPH_ARCHIVE_BUDGET");
        encoded.writeRawByte(length >>> 24);
        encoded.writeRawByte(length >>> 16);
        encoded.writeRawByte(length >>> 8);
        encoded.writeRawByte(length);
        row.writeTo(encoded);
        bytes += length + 4L;
        count++;
    }

    public long size() { return count; }
    public long bytes() { return bytes; }

    public Map<String, Object> export(Path destination) throws IOException {
        if (sealed) throw new IOException("GRAPH_ARCHIVE_ALREADY_SEALED");
        encoded.flush();
        output.close();
        sealed = true;
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) { file.force(true); }
        String sha = HexFormat.of().formatHex(digest.digest());
        Files.move(path, destination, StandardCopyOption.ATOMIC_MOVE);
        return Json.object("format", "GRA1", "path", destination.toString(),
                "count", count, "bytes", bytes, "sha256", sha);
    }

    @Override public void close() throws IOException {
        if (!sealed) { encoded.flush(); output.close(); sealed = true; }
        Files.deleteIfExists(path);
    }
}
