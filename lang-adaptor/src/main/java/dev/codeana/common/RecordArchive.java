package dev.codeana.common;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Task-owned framed records. Only offsets, never decoded records, are retained. Java 8 compatible.
 */
public final class RecordArchive extends AbstractList<Object>
        implements AutoCloseable, FactHeaders, FactHeaders.Encoded {
    public static final int MAGIC = 0x43414631;
    private static final int MODEL_MAGIC = 0x43414d31;
    private boolean binaryModel;
    private byte[] readPage;
    private long readPageStart=-1;
    private int readPageLength;
    public static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;
    private static final int PAGE = 4096;
    private final Path path;
    private final FileChannel channel;
    private final List<long[]> offsets = new ArrayList<long[]>();
    private final long maxBytes;
    private final int maxRecords;
    private long bytes = 4;
    private int count;
    private boolean sealed, closed;
    private RecordArchive headers;
    private Routing routing;
    private final List<AutoCloseable> owned = new ArrayList<AutoCloseable>();

    /** Bounded routing for trusted task-produced rows; reopened/unknown rows use strict projection. */
    private static final class Routing {
        private static final String[] KINDS = {"", "declaration", "external_symbol", "call_site",
                "call_target", "relation", "type", "facet", "coverage", "diagnostic", "resource",
                "dependency", "provenance", "reference", "file"};
        private final List<int[]> pages = new ArrayList<int[]>();
        private final List<long[]> identities = new ArrayList<long[]>();
        private static final int ID_KNOWN = 1 << 29;
        private final Map<String, Integer> worldIds = new HashMap<String, Integer>();
        private final List<String> worlds = new ArrayList<String>();
        private long worldBytes;

        void add(int position, Object kind, Object world, Long identity) {
            if (position >= 5_000_000) return;
            int code = 0;
            for (int i = 1; i < KINDS.length; i++)
                if (KINDS[i].equals(kind)) { code = i; break; }
            int worldId = 0;
            if (world instanceof String) {
                String value = FactHeaders.encodedText((String) world);
                Integer known = worldIds.get(value);
                if (known != null) worldId = known;
                else {
                    long weight = 96L + 8L * value.length();
                    if (worlds.size() < 4096 && weight <= (1L << 20) - worldBytes) {
                        worlds.add(value);
                        worldId = worlds.size();
                        worldIds.put(value, worldId);
                        worldBytes += weight;
                    }
                }
            }
            if (position % PAGE == 0) {
                pages.add(new int[PAGE]);
                identities.add(new long[PAGE]);
            }
            pages.get(position / PAGE)[position % PAGE] = (worldId << 4) | code
                    | (identity == null ? 0 : ID_KNOWN);
            if (identity != null) identities.get(position / PAGE)[position % PAGE] = identity;
        }

        int code(int position) {
            return position < 5_000_000 ? pages.get(position / PAGE)[position % PAGE] : 0;
        }
    }
    private boolean headerComplete = true;

    private RecordArchive(Path path, FileChannel channel, long maxBytes, int maxRecords) {
        this.path = path;
        this.channel = channel;
        this.maxBytes = maxBytes;
        this.maxRecords = maxRecords;
    }

    public static RecordArchive create(Path path, long maxBytes, int maxRecords)
            throws IOException {
        return create(path,maxBytes,maxRecords,false);
    }

    static RecordArchive createModel(Path path,long maxBytes,int maxRecords) throws IOException {
        return create(path,maxBytes,maxRecords,true);
    }

    private static RecordArchive create(Path path,long maxBytes,int maxRecords,boolean binaryModel)
            throws IOException {
        if (maxBytes < 4 || maxRecords < 0) throw new IOException("RECORD_ARCHIVE_BUDGET");
        FileChannel channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
        try {
            RecordArchive result = new RecordArchive(path, channel, maxBytes, maxRecords);
            result.binaryModel=binaryModel;
            result.write(integerBuffer(binaryModel?MODEL_MAGIC:MAGIC), 0);
            return result;
        } catch (Exception failed) {
            try {
                channel.close();
            } catch (IOException cleanup) {
                failed.addSuppressed(cleanup);
            }
            throw failed;
        }
    }

    /** Optional task-owned metadata. Exhaustion validates/selects encoded records, never drops facts. */
    public static RecordArchive createFacts(Path path, long maxBytes, int maxRecords)
            throws IOException {
        return createFacts(path, maxBytes, maxRecords, Math.min(maxBytes, 64L << 20));
    }

    public static RecordArchive createFacts(
            Path path, long maxBytes, int maxRecords, long headerBytes) throws IOException {
        if (headerBytes < 4 || headerBytes > (64L << 20))
            throw new IOException("FACT_HEADER_BUDGET");
        RecordArchive result = create(path, maxBytes, maxRecords);
        try {
            result.routing = new Routing();
            result.headers =
                    create(
                            path.resolveSibling(path.getFileName() + ".headers"),
                            headerBytes,
                            maxRecords);
            return result;
        } catch (Exception failed) {
            try {
                result.close();
            } catch (IOException cleanup) {
                failed.addSuppressed(cleanup);
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failed.addSuppressed(cleanup);
            }
            throw failed;
        }
    }

    public static RecordArchive open(Path path, long maxBytes, int maxRecords, String checksum)
            throws IOException {
        if (Files.size(path) > maxBytes || !Json.sha(path).equals(checksum))
            throw new IOException("RECORD_ARCHIVE_CHECKSUM");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        RecordArchive result = new RecordArchive(path, channel, maxBytes, maxRecords);
        try {
            int magic=result.integer(0);
            if(magic!=MAGIC && magic!=MODEL_MAGIC)throw new IOException("RECORD_ARCHIVE_VERSION");
            result.binaryModel=magic==MODEL_MAGIC;
            long position = 4, size = channel.size();
            channel.position(4);
            DataInputStream frames=new DataInputStream(new BufferedInputStream(
                    java.nio.channels.Channels.newInputStream(channel),65536));
            while (position < size) {
                if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
                int length=frames.readInt();
                if (length < 1 || length > MAX_RECORD_BYTES || size - position - 4 < length)
                    throw new IOException("RECORD_ARCHIVE_TRUNCATED");
                result.offset(position);
                long remaining=length;
                while(remaining>0) {
                    long skipped=frames.skip(remaining);
                    if(skipped==0) {if(frames.read()<0)throw new EOFException("RECORD_ARCHIVE_TRUNCATED");skipped=1;}
                    remaining-=skipped;
                }
                position += 4L + length;
            }
            result.bytes = position;
            result.sealed = true;
            return result;
        } catch (Exception failed) {
            try {
                channel.close();
            } catch (IOException cleanup) {
                failed.addSuppressed(cleanup);
            }
            throw failed;
        }
    }

    private void offset(long position) throws IOException {
        if (count >= maxRecords) throw new IOException("RECORD_ARCHIVE_COUNT_BUDGET");
        if (count % PAGE == 0) offsets.add(new long[PAGE]);
        offsets.get(count / PAGE)[count % PAGE] = position;
        count++;
    }

    @Override
    public synchronized boolean add(Object value) {
        if (Thread.currentThread().isInterrupted())
            throw new UncheckedIOException(new IOException("CANCELLED"));
        if (sealed || closed) throw new IllegalStateException("RECORD_ARCHIVE_SEALED");
        try {
            byte[] encoded = binaryModel?BinaryModelCodec.encode(value):
                    Json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] metadata =
                    headers != null && headerComplete
                            ? Json.write(FactHeaders.project(value))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            : null;
            addEncoded(encoded);
            if (routing != null) {
                Map<String, Object> fact = Json.map(value);
                routing.add(count - 1, fact.get("kind"), Json.map(fact.get("owner")).get("world"),
                        fact.get("id") instanceof String ? FactHeaders.identityHash((String) fact.get("id")) : null);
            }
            if (metadata != null) {
                if (metadata.length <= MAX_RECORD_BYTES
                        && metadata.length + 4L <= headers.maxBytes - headers.bytes)
                    headers.addEncoded(metadata);
                else headerComplete = false;
            }
            return true;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private void addEncoded(byte[] encoded) throws IOException {
        if (encoded.length < 1
                || encoded.length > MAX_RECORD_BYTES
                || count >= maxRecords
                || encoded.length + 4L > maxBytes - bytes)
            throw new IOException(
                    "RECORD_ARCHIVE_BUDGET file="
                            + path.getFileName()
                            + " count="
                            + count
                            + " count_limit="
                            + maxRecords
                            + " bytes="
                            + bytes
                            + " next_bytes="
                            + (encoded.length + 4L)
                            + " byte_limit="
                            + maxBytes);
        long start = bytes;
        write(integerBuffer(encoded.length), start);
        write(ByteBuffer.wrap(encoded), start + 4);
        offset(start);
        bytes += 4L + encoded.length;
    }

    @Override
    public boolean addAll(Collection<?> values) {
        if (values == this) throw new IllegalArgumentException("RECORD_ARCHIVE_SELF_COPY");
        if (!(values instanceof List) || !(values instanceof FactHeaders.Encoded))
            return super.addAll(values);
        List<?> source = (List<?>) values;
        int length = source.size();
        for (int index = 0; index < length; index++) FactHeaders.copy(source, index, this);
        return length != 0;
    }

    /** Transfer a framed, bounded row without decoding its possibly large attribute graph. */
    @Override
    public void copyRecord(int index, RecordArchive destination) {
        if (destination == this) throw new IllegalArgumentException("RECORD_ARCHIVE_SELF_COPY");
        if(binaryModel!=destination.binaryModel) {destination.add(get(index));return;}
        if (Thread.currentThread().isInterrupted())
            throw new UncheckedIOException(new IOException("CANCELLED"));
        boolean needsHeader, needsRouting;
        synchronized (destination) {
            if (destination.closed || destination.sealed)
                throw new IllegalStateException("RECORD_ARCHIVE_SEALED");
            needsHeader = destination.headers != null && destination.headerComplete;
            needsRouting = destination.routing != null;
        }
        try {
            byte[] encoded, metadata;
            Object kind = null, world = null;
            Long identity = null;
            // Do not hold both archives' locks: opposite-direction copies must not deadlock.
            synchronized (this) {
                if (closed) throw new IllegalStateException("RECORD_ARCHIVE_CLOSED");
                if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
                long position = offsets.get(index / PAGE)[index % PAGE];
                int length = integer(position);
                if (length < 1 || length > MAX_RECORD_BYTES || length + 4L > bytes - position)
                    throw new IOException("RECORD_ARCHIVE_TRUNCATED");
                ByteBuffer buffer = ByteBuffer.allocate(length);
                read(buffer, position + 4);
                encoded = buffer.array();
                if (needsRouting) {
                    // Reopened archives have no trusted routing: validate before committing the copy.
                    if (routing == null) {
                        Map<String, Object> header = factHeader(index);
                        kind = header.get("kind"); world = header.get("world");
                        if (header.get("id") instanceof String)
                            identity = FactHeaders.identityHash((String) header.get("id"));
                    } else {
                        kind = factKind(index); world = factWorld(index);
                        if ((routing.code(index) & Routing.ID_KNOWN) != 0)
                            identity = routing.identities.get(index / PAGE)[index % PAGE];
                        else {
                            Object value = factHeader(index).get("id");
                            if (value instanceof String) identity = FactHeaders.identityHash((String) value);
                        }
                    }
                }
                metadata = needsHeader
                        ? Json.write(factHeader(index)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : null;
            }
            synchronized (destination) {
                if (destination.closed || destination.sealed)
                    throw new IllegalStateException("RECORD_ARCHIVE_SEALED");
                destination.addEncoded(encoded);
                if (destination.routing != null)
                    destination.routing.add(destination.count - 1, kind, world, identity);
                if (metadata != null && destination.headerComplete) {
                    if (metadata.length <= MAX_RECORD_BYTES
                            && metadata.length + 4L <= destination.headers.maxBytes - destination.headers.bytes)
                        destination.headers.addEncoded(metadata);
                    else destination.headerComplete = false;
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    public synchronized void seal() throws IOException {
        if (closed) throw new IOException("RECORD_ARCHIVE_CLOSED");
        if (headers != null) headers.seal();
        channel.force(false);
        sealed = true;
    }

    @Override
    public Object get(int index) {
        if(!binaryModel)return Json.parse(encoded(index));
        try {return BinaryModelCodec.decode(encoded(index));}
        catch(IOException failed) {throw new UncheckedIOException(failed);}
    }

    /** Frame size from the validated positional directory; no extra file read. */
    public long recordBytes(int index) {
        if(closed)throw new IllegalStateException("RECORD_ARCHIVE_CLOSED");
        if(index<0 || index>=count)throw new IndexOutOfBoundsException();
        long start=offsets.get(index/PAGE)[index%PAGE];
        long end=index+1<count?offsets.get((index+1)/PAGE)[(index+1)%PAGE]:bytes;
        return end-start;
    }

    byte[] encoded(int index) {
        if (Thread.currentThread().isInterrupted())
            throw new UncheckedIOException(new IOException("CANCELLED"));
        if (closed) throw new IllegalStateException("RECORD_ARCHIVE_CLOSED");
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        try {
            long offset = offsets.get(index / PAGE)[index % PAGE];
            int length = integer(offset);
            if (length < 1 || length > MAX_RECORD_BYTES || length + 4L > bytes - offset)
                throw new IOException("RECORD_ARCHIVE_TRUNCATED");
            ByteBuffer encoded = ByteBuffer.allocate(length);
            read(encoded, offset + 4);
            return encoded.array();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    @Override
    public Map<String, Object> factHeader(int index) {
        if (Thread.currentThread().isInterrupted())
            throw new UncheckedIOException(new IOException("CANCELLED"));
        if (closed) throw new IllegalStateException("RECORD_ARCHIVE_CLOSED");
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
        return headers != null && index < headers.size()
                ? Collections.unmodifiableMap(Json.map(headers.get(index)))
                : FactHeaders.project(binaryModel?get(index):Json.factProjection(encoded(index)));
    }

    private void checkRoutingIndex(int index) {
        if (Thread.currentThread().isInterrupted())
            throw new UncheckedIOException(new IOException("CANCELLED"));
        if (closed) throw new IllegalStateException("RECORD_ARCHIVE_CLOSED");
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
    }

    @Override
    public String factKind(int index) {
        checkRoutingIndex(index);
        int code = routing == null ? 0 : routing.code(index) & 15;
        return code == 0 ? Json.string(factHeader(index), "kind") : Routing.KINDS[code];
    }

    @Override
    public String factWorld(int index) {
        checkRoutingIndex(index);
        int code = routing == null ? 0 : (routing.code(index) & ~Routing.ID_KNOWN) >>> 4;
        return code == 0 ? Json.string(factHeader(index), "world") : routing.worlds.get(code - 1);
    }

    @Override
    public boolean mightHaveId(int index, Set<Long> wanted) {
        checkRoutingIndex(index);
        if (routing == null || (routing.code(index) & Routing.ID_KNOWN) == 0)
            return wanted.contains(FactHeaders.identityHash(Json.string(factHeader(index), "id")));
        return wanted.contains(routing.identities.get(index / PAGE)[index % PAGE]);
    }

    private int integer(long position) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        read(buffer, position);
        buffer.flip();
        return buffer.getInt();
    }

    private static ByteBuffer integerBuffer(int value) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(value);
        buffer.flip();
        return buffer;
    }

    private synchronized void read(ByteBuffer buffer, long position) throws IOException {
        // Sealed model frames are immutable and repeatedly traversed by independent passes.
        // Cache encoded bytes only: mutable row aliasing and graph leases stay unchanged.
        if(binaryModel && sealed && buffer.remaining()<=65536) {
            int length=buffer.remaining();
            if(readPage==null)readPage=new byte[65536];
            if(position<readPageStart || position+length>readPageStart+readPageLength) {
                readPageStart=position;readPageLength=0;
                int wanted=(int)Math.min(readPage.length,bytes-position);
                ByteBuffer page=ByteBuffer.wrap(readPage,0,wanted);
                while(page.hasRemaining()) {
                    int n=channel.read(page,readPageStart+page.position());
                    if(n<0)throw new EOFException("RECORD_ARCHIVE_TRUNCATED");
                }
                readPageLength=wanted;
            }
            if(length>readPageStart+readPageLength-position)throw new EOFException("RECORD_ARCHIVE_TRUNCATED");
            buffer.put(readPage,(int)(position-readPageStart),length);return;
        }
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position);
            if (n < 0) throw new EOFException("RECORD_ARCHIVE_TRUNCATED");
            position += n;
        }
    }

    private void write(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) position += channel.write(buffer, position);
    }

    @Override
    public int size() {
        return count;
    }

    public int recordLimit() {
        return maxRecords;
    }

    public long bytes() {
        return bytes;
    }

    public Path path() {
        return path;
    }

    long byteLimit() { return maxBytes; }

    /** Drop decoded caches without ending the task lease or invalidating escaped type roots. */
    public interface CacheOwner extends AutoCloseable {
        void releaseNodeCache();
    }

    public synchronized void releaseNodeCaches() {
        if (closed) return;
        for (AutoCloseable resource : owned)
            if (resource instanceof CacheOwner) ((CacheOwner) resource).releaseNodeCache();
    }

    /** Model snapshots share this archive's task lifetime and cannot outlive its cleanup. */
    void own(AutoCloseable resource) {
        if (closed) throw new IllegalStateException("RECORD_ARCHIVE_CLOSED");
        owned.add(resource);
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            channel.close();
        } catch (IOException failed) {
            failure = failed;
        } finally {
            offsets.clear();
            readPage=null;readPageStart=-1;readPageLength=0;
            routing = null;
        }
        for (AutoCloseable resource : owned) {
            try { resource.close(); }
            catch (Exception failed) {
                IOException cleanup = failed instanceof IOException ? (IOException) failed : new IOException(failed);
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        owned.clear();
        if (headers != null) {
            try {
                headers.close();
            } catch (IOException failed) {
                if (failure == null) failure = failed;
                else failure.addSuppressed(failed);
            }
            try {
                Files.deleteIfExists(headers.path());
            } catch (IOException failed) {
                if (failure == null) failure = failed;
                else failure.addSuppressed(failed);
            }
        }
        if (failure != null) throw failure;
    }
}
