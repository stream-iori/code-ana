package dev.codeana.common;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Compiler/model 5: framed rows, ordered sections, and shared immutable type trees. Java 8. */
public final class ModelArchive {
    public static final int VERSION = 6;
    private static final List<String> SECTIONS = Arrays.asList("sources", "declarations", "observations",
            "syntax", "external", "annotation_types", "diagnostics");
    private static final int MAX_ROWS = 2000000;
    private static final long MAX_TYPE_BYTES = 64L * 1024 * 1024;
    public static final long DEFAULT_BYTES = 64L * 1024 * 1024;
    public static final long MIN_BYTES = 64L * 1024, MAX_BYTES = 1024L * 1024 * 1024;
    /** Exact per-context archive cap; control JSON and each physical record remain separately bounded. */
    public static long byteLimit(Map<String,Object> request) throws IOException {
        long limit = DEFAULT_BYTES;
        if (request.containsKey("model_archive_bytes")) {
            try { limit = new java.math.BigDecimal(request.get("model_archive_bytes").toString()).longValueExact(); }
            catch (RuntimeException invalid) { throw new IOException("JAVA_MODEL_ARCHIVE_BYTES_RANGE", invalid); }
        }
        if (limit < MIN_BYTES || limit > MAX_BYTES) throw new IOException("JAVA_MODEL_ARCHIVE_BYTES_RANGE");
        return limit;
    }
    private ModelArchive() {}

    private static final class Encoder {
        final RecordArchive records;
        final ModelGraphs graphs;
        final Map<String,Integer> types = new HashMap<String,Integer>();
        final List<Integer> typePositions = new ArrayList<Integer>();
        long typeBytes;
        Encoder(RecordArchive records) {this(records,false);}
        Encoder(RecordArchive records,boolean encodingOnly) { this.records=records;graphs=new ModelGraphs(encodingOnly); }
        Object pack(Object raw, int depth) {
            if (depth>Json.MAX_DEPTH) throw new IllegalArgumentException("MODEL_DEPTH_BUDGET");
            if (TypeGraph.isView(raw)) return graphs.pack(Json.map(raw),records);
            if (raw instanceof Map) {
                Map<String,Object> value=Json.map(raw), packed=new LinkedHashMap<String,Object>();
                for (Map.Entry<String,Object> entry:value.entrySet()) packed.put(entry.getKey(),pack(entry.getValue(),depth+1));
                if (value.containsKey("kind") && value.get("version") instanceof Number
                        && ((Number)value.get("version")).intValue()==2) {
                    byte[] bytes=Json.write(packed).getBytes(StandardCharsets.UTF_8);
                    String key=Json.sha(bytes);
                    Integer id=types.get(key);
                    if(id==null) {
                        if (types.size()>=100000 || bytes.length>MAX_TYPE_BYTES-typeBytes)
                            throw new IllegalArgumentException("MODEL_TYPE_TABLE_BUDGET");
                        id=types.size()+1; types.put(key,id); typeBytes+=bytes.length;
                        typePositions.add(records.size());
                        records.add(Json.object("kind","type","id",id,"value",packed));
                    }
                    return Json.object("$type",id);
                }
                if (value.containsKey("$type") || value.containsKey("$object") || value.containsKey("$graph")) {
                    List<Object> entries=new ArrayList<Object>();
                    for(Map.Entry<String,Object> entry:packed.entrySet()) entries.add(Arrays.asList(entry.getKey(),entry.getValue()));
                    return Json.object("$object",entries);
                }
                return packed;
            }
            if (raw instanceof List) {
                List<Object> values=new ArrayList<Object>();
                for (Object value:Json.list(raw)) values.add(pack(value,depth+1));
                return values;
            }
            return raw;
        }
    }
    /** Task-owned compiler row spool; type trees are interned before hitting disk. */
    public static final class RowSpool extends AbstractList<Object> implements AutoCloseable {
        private static final int PAGE = 4096;
        private final RecordArchive records;
        private final Encoder encoder;
        private final List<int[]> positions = new ArrayList<int[]>();
        private final Map<Integer,Object> cache = new LinkedHashMap<Integer,Object>(64,.75f,true) {
            protected boolean removeEldestEntry(Map.Entry<Integer,Object> entry) { return size()>64; }
        };
        private final class SpoolTypes extends AbstractList<Object> implements ModelGraphs.Types {
            public ModelGraphs graphs() { return encoder.graphs; }
            public int size() { return encoder.typePositions.size(); }
            public Object get(int index) {
                if (index<0 || index>=size()) throw new IndexOutOfBoundsException();
                Object value = cache.get(index);
                if (value!=null) return value;
                try { value=freezeType(Json.map(unpack(Json.map(records.get(encoder.typePositions.get(index))).get("value"),this,0))); }
                catch(IOException failure) { throw new UncheckedIOException(failure); }
                cache.put(index,value); return value;
            }
        }
        private final List<Object> types = new SpoolTypes();
        private Object pending;
        private int count;
        private boolean closed, sealed;
        public RowSpool(Path work, String section) throws IOException { this(work, section, DEFAULT_BYTES); }
        public RowSpool(Path work, String section, long maxBytes) throws IOException {
            records=RecordArchive.create(work.resolve("model-rows-"+section+"-"+UUID.randomUUID()+".bin"),
                    maxBytes,MAX_ROWS);
            encoder=new Encoder(records);
        }
        public boolean add(Object row) {
            if (closed) throw new IllegalStateException("MODEL_SPOOL_CLOSED");
            if (sealed) throw new IllegalStateException("MODEL_SPOOL_SEALED");
            flush();
            pending=row; return true;
        }
        private void flush() {
            if (pending!=null) {
                Object packed=encoder.pack(pending,0);
                int position=records.size(); records.add(Json.object("value",packed));
                if(count%PAGE==0) positions.add(new int[PAGE]);
                positions.get(count/PAGE)[count%PAGE]=position; count++;
                pending=null;
            }
        }
        /** Finish a stable snapshot, dropping encoder identities but retaining bounded row decoding. */
        public void seal() throws IOException {
            if (closed) throw new IllegalStateException("MODEL_SPOOL_CLOSED");
            if (sealed) return;
            flush(); records.seal(); encoder.graphs.seal(); encoder.types.clear(); sealed=true;
        }
        private void prepareTransfer() throws IOException {
            if(closed)throw new IllegalStateException("MODEL_SPOOL_CLOSED");
            if(!sealed) {flush();records.seal();encoder.graphs.retireEncoderIdentities();encoder.types.clear();sealed=true;}
        }
        public int size() { return count+(pending==null ? 0 : 1); }
        public Object get(int index) {
            if (closed) throw new IllegalStateException("MODEL_SPOOL_CLOSED");
            if(index<0 || index>=size()) throw new IndexOutOfBoundsException();
            if(index==count) return pending;
            try { return unpack(Json.map(records.get(positions.get(index/PAGE)[index%PAGE])).get("value"),types,0); }
            catch(IOException failure) { throw new UncheckedIOException(failure); }
        }
        public void close() throws IOException {
            if(!closed) {
                closed=true; pending=null; cache.clear(); positions.clear(); encoder.types.clear(); encoder.typePositions.clear(); encoder.graphs.close();
                try { records.close(); } finally { java.nio.file.Files.deleteIfExists(records.path()); }
            }
        }
    }
    public static long write(Path path, Map<String,Object> model, long maxBytes) throws IOException {
        return write(path,model,maxBytes,false);
    }
    /** Compiler-owned transfer; caller still closes residual spools if a transfer fails. */
    public static long writeAndReleaseRows(Path path, Map<String,Object> model, long maxBytes) throws IOException {
        return write(path,model,maxBytes,true);
    }
    private static long write(Path path, Map<String,Object> model, long maxBytes,boolean releaseRows) throws IOException {
        if (Json.number(model,"version")!=VERSION) throw new IOException("MODEL_ARCHIVE_VERSION");
        if(releaseRows)for(String section:SECTIONS)if(model.get(section) instanceof RowSpool)((RowSpool)model.get(section)).prepareTransfer();
        try (RecordArchive records=RecordArchive.create(path,maxBytes,MAX_ROWS)) {
            records.add(Json.object("kind","header","version",VERSION,"context",Json.string(model,"context"),"sections",SECTIONS));
            Encoder encoder=new Encoder(records,true);
            Map<String,Object> counts=new LinkedHashMap<String,Object>();
            for (String section:SECTIONS) {
                List<Object> rows=Json.list(model.get(section));
                counts.put(section,rows.size());
                for (Object row:rows) records.add(Json.object("kind","row","section",section,"value",encoder.pack(row,0)));
                if(releaseRows && rows instanceof RowSpool) {((RowSpool)rows).close();encoder.graphs.retireEncoderIdentities();}
            }
            records.add(Json.object("kind","end","counts",counts,"types",encoder.types.size(),"graphs",encoder.graphs.counts()));
            records.seal(); return records.bytes();
        }
    }
    /** Validate every deferred reference and escaped object without duplicating its decoded containers. */
    private static void validatePacked(Object raw, int types, ModelGraphs graphs, int depth) throws IOException {
        if (depth>Json.MAX_DEPTH) throw new IOException("MODEL_DEPTH_BUDGET");
        if (raw instanceof Map) {
            Map<String,Object> value=Json.map(raw);
            if (value.containsKey("$object")) {
                if (value.size()!=1 || !(value.get("$object") instanceof List)) throw new IOException("MODEL_OBJECT_ESCAPE");
                Set<String> keys=new HashSet<String>();
                for(Object entry:Json.list(value.get("$object"))) {
                    List<Object> pair=Json.list(entry);
                    if(pair.size()!=2 || !(pair.get(0) instanceof String) || !keys.add((String)pair.get(0))) throw new IOException("MODEL_OBJECT_ESCAPE");
                    validatePacked(pair.get(1),types,graphs,depth+1);
                }
            } else if(value.containsKey("$graph")) {
                if(value.size()!=1)throw new IOException("MODEL_GRAPH_REFERENCE");
                graphs.validate(value.get("$graph"));
            } else if(value.containsKey("$type")) {
                long id=Json.number(value,"$type");
                if(value.size()!=1 || id<1 || id>types) throw new IOException("MODEL_TYPE_REFERENCE");
            } else for(Object child:value.values()) validatePacked(child,types,graphs,depth+1);
        } else if(raw instanceof List) for(Object child:Json.list(raw)) validatePacked(child,types,graphs,depth+1);
    }
    static Object unpack(Object raw, List<Object> types, int depth) throws IOException {
        if(depth>Json.MAX_DEPTH) throw new IOException("MODEL_DEPTH_BUDGET");
        if(raw instanceof Map) {
            Map<String,Object> value=Json.map(raw);
            if(value.containsKey("$object")) {
                if(value.size()!=1) throw new IOException("MODEL_OBJECT_ESCAPE");
                Map<String,Object> object=new LinkedHashMap<String,Object>();
                for(Object entry:Json.list(value.get("$object"))) {
                    List<Object> pair=Json.list(entry);
                    if(pair.size()!=2 || !(pair.get(0) instanceof String) || object.containsKey(pair.get(0)))
                        throw new IOException("MODEL_OBJECT_ESCAPE");
                    object.put((String)pair.get(0),unpack(pair.get(1),types,depth+1));
                }
                return object;
            }
            if(value.containsKey("$graph")) {
                if(value.size()!=1 || !(types instanceof ModelGraphs.Types))throw new IOException("MODEL_GRAPH_REFERENCE");
                return ((ModelGraphs.Types)types).graphs().view(value.get("$graph"));
            }
            if(value.containsKey("$type")) {
                long id=Json.number(value,"$type");
                if(value.size()!=1 || id<1 || id>types.size()) throw new IOException("MODEL_TYPE_REFERENCE");
                return types.get((int)id-1);
            }
            Map<String,Object> unpacked=new LinkedHashMap<String,Object>();
            for(Map.Entry<String,Object> entry:value.entrySet()) unpacked.put(entry.getKey(),unpack(entry.getValue(),types,depth+1));
            return unpacked;
        }
        if(raw instanceof List) {
            List<Object> values=new ArrayList<Object>();
            for(Object value:Json.list(raw)) values.add(unpack(value,types,depth+1));
            return values;
        }
        return raw;
    }
    /** Validated shared type nodes are recursively immutable and safe across callback snapshots. */
    public static final class ImmutableType extends AbstractMap<String,Object> {
        private final Map<String,Object> values;
        private ImmutableType(Map<String,Object> values) { this.values=values; }
        public Set<Map.Entry<String,Object>> entrySet() { return values.entrySet(); }
        public Object get(Object key) { return values.get(key); }
        public boolean containsKey(Object key) { return values.containsKey(key); }
        public int size() { return values.size(); }
    }
    /** Freeze a detached semantic type while retaining already immutable compiler subtrees. */
    public static Map<String,Object> freezeType(Map<String,Object> type) {
        if (type instanceof ImmutableType || TypeGraph.isView(type)) return type;
        return new ImmutableType(Json.map(immutable(type,0)));
    }
    /** Mutable declaration/observation sections, with immutable syntax decoded one row at a time. */
    public static final class OpenModel implements AutoCloseable {
        private final RecordArchive records;
        private final Map<String,Object> model;
        private OpenModel(RecordArchive records, Map<String,Object> model) { this.records=records; this.model=model; }
        public Map<String,Object> model() { return model; }
        public AutoCloseable lifetime() { return records; }
        public void close() throws IOException { records.close(); }
    }
    /** Stable archive-backed snapshot; consumers cannot change its rows or their nested values. */
    public static final class SyntaxRows extends AbstractList<Object> {
        private final RecordArchive records;
        private final List<Object> types;
        private final int[] positions;
        private SyntaxRows(RecordArchive records, List<Object> types, int[] positions) {
            this.records=records; this.types=types; this.positions=positions;
        }
        public int size() { return positions.length; }
        public AutoCloseable lifetime() { return records; }
        public Path path() { return records.path(); }
        public Object get(int index) {
            if (index<0 || index>=positions.length) throw new IndexOutOfBoundsException();
            try { return immutable(unpack(Json.map(records.get(positions[index])).get("value"),types,0),0); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
        }
    }
    static Object immutable(Object raw, int depth) {
        if (depth>Json.MAX_DEPTH) throw new IllegalArgumentException("MODEL_DEPTH_BUDGET");
        if (raw instanceof ImmutableType || TypeGraph.isView(raw)) return raw;
        if (raw instanceof Map) {
            Map<String,Object> copy=new LinkedHashMap<String,Object>();
            for (Map.Entry<String,Object> entry:Json.map(raw).entrySet()) copy.put(entry.getKey(),immutable(entry.getValue(),depth+1));
            return Collections.unmodifiableMap(copy);
        }
        if (raw instanceof List) {
            List<Object> copy=new ArrayList<Object>();
            for (Object item:Json.list(raw)) copy.add(immutable(item,depth+1));
            return Collections.unmodifiableList(copy);
        }
        return raw;
    }
    public static Map<String,Object> read(Path path, String checksum, long maxBytes, String context) throws IOException {
        try(OpenModel value=open(path,checksum,maxBytes,context,false)) { return value.model(); }
    }
    public static OpenModel open(Path path, String checksum, long maxBytes, String context) throws IOException {
        return open(path,checksum,maxBytes,context,true);
    }
    private static OpenModel open(Path path, String checksum, long maxBytes, String context, boolean lazySyntax) throws IOException {
        RecordArchive records=RecordArchive.open(path,maxBytes,MAX_ROWS,checksum);
        try {
            if(records.size()<2) throw new IOException("MODEL_ARCHIVE_TRUNCATED");
            Map<String,Object> header=Json.map(records.get(0));
            if(!"header".equals(header.get("kind")) || Json.number(header,"version")!=VERSION
                    || !context.equals(header.get("context")) || !SECTIONS.equals(header.get("sections")))
                throw new IOException("MODEL_ARCHIVE_HEADER");
            Map<String,Object> model=Json.object("version",VERSION,"context",context);
            for(String section:SECTIONS) model.put(section,new ArrayList<Object>());
            ModelGraphs.Table types=new ModelGraphs.Table();
            Map<String,List<Integer>> lazyPositions=new LinkedHashMap<String,List<Integer>>();
            if(lazySyntax)for(String section:Arrays.asList("declarations","observations"))lazyPositions.put(section,new ArrayList<Integer>());
            int[] syntaxPositions=new int[256]; int syntaxCount=0;
            int sectionIndex=0;
            long typeBytes=0;
            for(int i=1;i<records.size()-1;i++) {
                Map<String,Object> record=Json.map(records.get(i));
                String kind=Json.string(record,"kind");
                if(kind.equals("type")) {
                    if(Json.number(record,"id")!=types.size()+1 || types.size()>=100000)
                        throw new IOException("MODEL_TYPE_SEQUENCE");
                    typeBytes+=Json.write(record.get("value")).getBytes(StandardCharsets.UTF_8).length;
                    if(typeBytes>MAX_TYPE_BYTES) throw new IOException("MODEL_TYPE_TABLE_BUDGET");
                    types.add(new ImmutableType(Json.map(immutable(unpack(record.get("value"),types,0),0))));
                } else if(kind.equals("graph_node")) {
                    types.graphs().add(record);
                } else if(kind.equals("row")) {
                    String section=Json.string(record,"section");
                    int current=SECTIONS.indexOf(section);
                    if(current<sectionIndex || current<0) throw new IOException("MODEL_SECTION_SEQUENCE");
                    sectionIndex=current;
                    boolean lazy=lazySyntax && (section.equals("syntax") || lazyPositions.containsKey(section));
                    if(lazy) validatePacked(record.get("value"),types.size(),types.graphs(),0);
                    Object value=lazy ? null : unpack(record.get("value"),types,0);
                    if(lazySyntax && lazyPositions.containsKey(section)) lazyPositions.get(section).add(i);
                    else if(lazySyntax && section.equals("syntax")) {
                        if(syntaxCount==syntaxPositions.length) syntaxPositions=Arrays.copyOf(syntaxPositions,syntaxCount*2);
                        syntaxPositions[syntaxCount++]=i;
                    } else Json.list(model.get(section)).add(value);
                } else throw new IOException("MODEL_RECORD_KIND");
            }
            Map<String,Object> end=Json.map(records.get(records.size()-1));
            if(!"end".equals(end.get("kind")) || Json.number(end,"types")!=types.size())
                throw new IOException("MODEL_ARCHIVE_END");
            if(!Json.write(end.getOrDefault("graphs",Collections.emptyList())).equals(Json.write(types.graphs().counts())))
                throw new IOException("MODEL_GRAPH_COUNTS");
            types.graphs().seal();
            Map<String,Object> counts=Json.map(end.get("counts"));
            if(!counts.keySet().equals(new HashSet<String>(SECTIONS))) throw new IOException("MODEL_SECTION_COUNTS");
            for(Map.Entry<String,List<Integer>> entry:lazyPositions.entrySet()) {
                int[] positions=new int[entry.getValue().size()];for(int i=0;i<positions.length;i++)positions[i]=entry.getValue().get(i);
                model.put(entry.getKey(),new ArchiveRows(records,types,positions));
            }
            if(lazySyntax) model.put("syntax",new SyntaxRows(records,types,Arrays.copyOf(syntaxPositions,syntaxCount)));
            for(String section:SECTIONS) if(Json.number(counts,section)!=Json.list(model.get(section)).size())
                throw new IOException("MODEL_SECTION_COUNTS");
            return new OpenModel(records,model);
        } catch (IOException | RuntimeException | Error failed) {
            try { records.close(); } catch (IOException cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
    }
}
