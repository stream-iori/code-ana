package dev.codeana.common;

import java.util.*;

/** Public per-fact graph closures. Neither compiler node IDs nor recursive type trees cross the wire. */
public final class TypeEvidence {
    public static final String TABLE = "java_type_graphs";
    private static final Set<String> MODEL_KINDS = new HashSet<String>(Arrays.asList(
        "ARRAY","DECLARED","ERROR","TYPEVAR","CAPTURE","WILDCARD","INTERSECTION","UNION","EXECUTABLE",
        "BOOLEAN","BYTE","SHORT","INT","LONG","CHAR","FLOAT","DOUBLE","VOID","NONE","NULL","OTHER","UNKNOWN","POLY"));
    private TypeEvidence() {}
    private static final ThreadLocal<EncodingSession> SESSION=new ThreadLocal<EncodingSession>();
    /** Context-local canonical closure reuse. Cached bytes never share mutable output maps. */
    public static final class EncodingSession implements AutoCloseable {
        private final EncodingSession previous;
        private final long limit;
        private final LinkedHashMap<ClosureKey,CachedClosure> entries=new LinkedHashMap<ClosureKey,CachedClosure>(64,.75f,true);
        private long bytes,hits,misses,evictions;
        private boolean closed;
        private EncodingSession(long limit) {
            if(limit<0 || limit>(64L<<20))throw new IllegalArgumentException("TYPE_CLOSURE_CACHE_BUDGET");
            this.limit=limit;previous=SESSION.get();SESSION.set(this);
        }
        public Map<String,Object> metrics() {return Json.object("stage","type_closure_cache","hits",hits,
                "misses",misses,"evictions",evictions,"bytes",bytes,"entries",entries.size());}
        public void close() {
            if(closed)return;
            if(SESSION.get()!=this)throw new IllegalStateException("TYPE_CLOSURE_CACHE_SCOPE");
            closed=true;entries.clear();bytes=0;
            if(previous==null)SESSION.remove();else SESSION.set(previous);
        }
    }
    public static EncodingSession encodingSession(long bytes) {return new EncodingSession(bytes);}
    private static final class ClosureKey {
        final Object[] graphs;final int[] roots;final int hash;
        ClosureKey(Object[] graphs,int[] roots) {
            this.graphs=graphs;this.roots=roots;int value=1;
            for(int i=0;i<graphs.length;i++)value=31*(31*value+System.identityHashCode(graphs[i]))+roots[i];
            hash=value;
        }
        public int hashCode(){return hash;}
        public boolean equals(Object raw) {
            if(!(raw instanceof ClosureKey))return false;
            ClosureKey other=(ClosureKey)raw;if(graphs.length!=other.graphs.length)return false;
            for(int i=0;i<graphs.length;i++)if(graphs[i]!=other.graphs[i] || roots[i]!=other.roots[i])return false;
            return true;
        }
        long weight(){return 128L+16L*graphs.length;}
    }
    private static final class CachedClosure {
        final byte[] payload;final int[] slots;final long weight;
        CachedClosure(byte[] payload,int[] slots,long weight){this.payload=payload;this.slots=slots;this.weight=weight;}
    }

    private static final class Node {
        final TypeGraph graph;final int reference;final Map<String,Object> value;
        Node(TypeGraph graph,int reference,Map<String,Object> value) {this.graph=graph;this.reference=reference;this.value=value;}
    }
    private static final class Encoder {
        final IdentityHashMap<TypeGraph,Map<Integer,Integer>> identities=new IdentityHashMap<TypeGraph,Map<Integer,Integer>>();
        final IdentityHashMap<Map<String,Object>,Integer> copies=new IdentityHashMap<Map<String,Object>,Integer>();
        final List<Node> nodes=new ArrayList<Node>();
        final List<Integer> roots=new ArrayList<Integer>();
        final List<List<Integer>> references=new ArrayList<List<Integer>>();
        private ClosureKey key() {
            Object[] graphs=new Object[roots.size()];int[] ids=new int[roots.size()];
            for(int i=0;i<roots.size();i++) {
                Node node=nodes.get(roots.get(i));
                if(node.graph==null)return null;
                graphs[i]=node.graph.storageIdentity();ids[i]=node.reference;
            }
            return new ClosureKey(graphs,ids);
        }
        Map<String,Object> cachedPayload(EncodingSession session) {
            ClosureKey key=key();
            if(key==null || session.limit==0)return payload();
            CachedClosure cached=session.entries.get(key);
            if(cached!=null) {
                session.hits++;
                for(int i=0;i<references.size();i++)references.get(i).set(1,cached.slots[i]);
                try {return Json.map(BinaryModelCodec.decode(cached.payload));}
                catch(java.io.IOException failed){throw new java.io.UncheckedIOException(failed);}
            }
            session.misses++;Map<String,Object> value=payload();
            byte[] encoded;
            try {encoded=BinaryModelCodec.encode(value);}
            catch(java.io.IOException failed){throw new java.io.UncheckedIOException(failed);}
            catch(IllegalArgumentException budget) {
                if("MODEL_FRAME_BYTES_BUDGET".equals(budget.getMessage()))return value;
                throw budget;
            }
            int[] slots=new int[references.size()];for(int i=0;i<slots.length;i++)slots[i]=references.get(i).get(1);
            long weight=key.weight()+encoded.length+64L+4L*slots.length;
            if(weight<=session.limit) {
                while(!session.entries.isEmpty() && (session.bytes>session.limit-weight || session.entries.size()>=16384)) {
                    Iterator<Map.Entry<ClosureKey,CachedClosure>> oldest=session.entries.entrySet().iterator();
                    session.bytes-=oldest.next().getValue().weight;oldest.remove();session.evictions++;
                }
                session.entries.put(key,new CachedClosure(encoded,slots,weight));session.bytes+=weight;
            }
            return value;
        }
        private int view(TypeGraph graph,int reference) {
            if(reference<0)return -1;graph.requireSealed();
            Map<Integer,Integer> ids=identities.computeIfAbsent(graph,ignored->new HashMap<Integer,Integer>());
            Integer id=ids.get(reference);
            if(id==null) {id=reserve(new Node(graph,reference,null));ids.put(reference,id);}
            return id;
        }
        private int reserve(Node node) {
            if(nodes.size()>=TypeGraph.MAX_NODES)throw new IllegalArgumentException("TYPE_EVIDENCE_NODE_BUDGET");
            int id=nodes.size();nodes.add(node);return id;
        }
        private int model(Map<String,Object> value) {
            if(TypeGraph.isView(value))return view(TypeGraph.owner(value),TypeGraph.reference(value));
            Integer id=copies.get(value);
            if(id==null) {id=reserve(new Node(null,-1,value));copies.put(value,id);}
            return id;
        }
        Map<String,Object> payload() {
            List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();
            for(int cursor=0;cursor<nodes.size();cursor++) {
                Node source=nodes.get(cursor);Map<String,Object> raw=source.graph==null?source.value:source.graph.node(source.reference);
                Map<String,Object> copy=new TreeMap<String,Object>();
                boolean captured="CAPTURE".equals(raw.get("kind"));
                if(captured) {
                    raw=new TreeMap<String,Object>(raw);
                    raw.put("kind","TYPEVAR");raw.put("version",3);
                    raw.put("capture_identity",raw.get("capture"));raw.put("capture",true);
                    raw.put("owner_unavailable",true);
                    if(!raw.containsKey("lower") || raw.get("lower")==null)
                        raw.put("lower",Json.object("version",3,"kind","NULL","complete",true));
                }
                if(source.graph==null && "TYPEVAR".equals(raw.get("kind"))) {
                    raw=new TreeMap<String,Object>(raw);
                    if(!raw.containsKey("lower") || raw.get("lower")==null)
                        raw.put("lower",Json.object("version",3,"kind","NULL","complete",true));
                    if(!raw.containsKey("upper") || raw.get("upper")==null) {
                        raw.put("complete",false);raw.put("bounds_unavailable",true);
                    }
                }
                if(source.graph==null && ("DECLARED".equals(raw.get("kind")) || "ERROR".equals(raw.get("kind")))) {
                    raw=new TreeMap<String,Object>(raw);
                    // Legacy named/NoCode models represent absent arguments as the empty list.
                    raw.putIfAbsent("arguments",Collections.emptyList());
                }
                if(source.graph==null && "WILDCARD".equals(raw.get("kind"))) {
                    raw=new TreeMap<String,Object>(raw);
                    // An absent bound is an absent graph edge, never an invented Object bound.
                    raw.putIfAbsent("extends",null);raw.putIfAbsent("super",null);
                }
                for(Map.Entry<String,Object> entry:raw.entrySet()) {
                    String key=entry.getKey();Object value=entry.getValue();
                    if(CanonicalTypes.SINGLE.contains(key)) {
                        if(source.graph!=null)value=view(source.graph,((Number)value).intValue());
                        else if(value instanceof Map)value=model(Json.map(value));
                        else if(value==null)value=-1;
                        else throw new IllegalArgumentException("TYPE_EVIDENCE_MODEL_EDGE");
                    } else if(CanonicalTypes.MANY.contains(key)) {
                        List<Integer> children=new ArrayList<Integer>();
                        for(Object child:Json.list(value))children.add(source.graph==null?model(Json.map(child)):view(source.graph,((Number)child).intValue()));
                        value=children;
                    }
                    copy.put(key,value);
                }
                values.add(copy);
            }
            TypeGraph.Builder builder=new TypeGraph.Builder();
            for(Map<String,Object> row:values) {
                Map<String,Object> metadata=new TreeMap<String,Object>(row);
                for(String role:CanonicalTypes.SINGLE)metadata.remove(role);
                for(String role:CanonicalTypes.MANY)metadata.remove(role);
                builder.reserve(metadata);
            }
            for(int id=0;id<values.size();id++)for(Map.Entry<String,Object> entry:values.get(id).entrySet())
                if(CanonicalTypes.SINGLE.contains(entry.getKey()) || CanonicalTypes.MANY.contains(entry.getKey()))
                    builder.edit(id).put(entry.getKey(),entry.getValue());
            Map<String,Object> payload=CanonicalTypes.encode(builder.seal(),roots);
            List<Object> canonicalRoots=Json.list(payload.get("roots"));List<Integer> unique=new ArrayList<Integer>();Map<Integer,Integer> slots=new LinkedHashMap<Integer,Integer>();
            for(int i=0;i<canonicalRoots.size();i++) {
                int root=((Number)canonicalRoots.get(i)).intValue();Integer slot=slots.get(root);
                if(slot==null) {slot=unique.size();slots.put(root,slot);unique.add(root);}
                references.get(i).set(1,slot);
            }
            payload.put("roots",unique);return payload;
        }
        Object pack(Object raw,int depth) {
            if(depth>Json.MAX_DEPTH)throw new IllegalArgumentException("TYPE_EVIDENCE_DEPTH_BUDGET");
            if(TypeGraph.isView(raw) || raw instanceof Map && (!Json.map(raw).containsKey("version")
                    || Json.map(raw).get("version") instanceof Number
                        && (Json.number(Json.map(raw),"version")==3 || Json.number(Json.map(raw),"version")==2))
                    && MODEL_KINDS.contains(Json.map(raw).get("kind"))
                    && Json.map(raw).get("complete") instanceof Boolean) {
                int root=model(Json.map(raw));List<Integer> reference=new ArrayList<Integer>(Arrays.asList(0,roots.size()));
                roots.add(root);references.add(reference);return Json.object("$java_type",reference);
            }
            if(raw instanceof Map) {
                Map<String,Object> input=Json.map(raw),copy=new TreeMap<String,Object>();
                for(Map.Entry<String,Object> entry:new TreeMap<String,Object>(input).entrySet())
                    copy.put(entry.getKey(),pack(entry.getValue(),depth+1));
                if(input.containsKey("$java_type") || input.containsKey("$java_object")) {
                    List<Object> entries=new ArrayList<Object>();
                    for(Map.Entry<String,Object> entry:copy.entrySet())entries.add(Arrays.asList(entry.getKey(),entry.getValue()));
                    return Json.object("$java_object",entries);
                }
                return copy;
            }
            if(raw instanceof List) {
                List<Object> copy=new ArrayList<Object>();
                for(Object value:Json.list(raw))copy.add(pack(value,depth+1));
                return copy;
            }
            return raw;
        }
    }

    public static Map<String,Object> encode(Map<String,Object> attributes) {
        if(attributes.containsKey(TABLE))throw new IllegalArgumentException("TYPE_EVIDENCE_RESERVED_TABLE");
        Encoder encoder=new Encoder();Map<String,Object> packed=Json.map(encoder.pack(attributes,0));
        if(encoder.roots.isEmpty())return attributes;
        EncodingSession session=SESSION.get();
        packed.put(TABLE,Arrays.asList(session==null?encoder.payload():encoder.cachedPayload(session)));return packed;
    }

    public static Map<String,Object> decode(Map<String,Object> attributes) {
        if(!attributes.containsKey(TABLE))return attributes;
        if(!(attributes.get(TABLE) instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_TABLE");
        List<TypeGraph> graphs=new ArrayList<TypeGraph>();List<List<Object>> roots=new ArrayList<List<Object>>();
        for(Object value:Json.list(attributes.get(TABLE))) {
            Map<String,Object> payload=Json.map(value);graphs.add(TypeGraph.decode(payload));roots.add(Json.list(payload.get("roots")));
        }
        Map<String,Object> input=new TreeMap<String,Object>(attributes);input.remove(TABLE);
        return Json.map(unpack(input,graphs,roots,0));
    }
    private static int index(Object raw,int size) {
        try {
            if(!(raw instanceof Number))throw new IllegalArgumentException();
            int value=new java.math.BigDecimal(raw.toString()).intValueExact();
            if(value<0 || value>=size)throw new IllegalArgumentException();return value;
        }catch(RuntimeException failure){throw new IllegalArgumentException("TYPE_EVIDENCE_REFERENCE",failure);}
    }
    private static Object unpack(Object raw,List<TypeGraph> graphs,List<List<Object>> roots,int depth) {
        if(depth>Json.MAX_DEPTH)throw new IllegalArgumentException("TYPE_EVIDENCE_DEPTH_BUDGET");
        if(raw instanceof Map) {
            Map<String,Object> input=Json.map(raw),output=new TreeMap<String,Object>();
            if(input.containsKey("$java_type")) {
                if(input.size()!=1 || !(input.get("$java_type") instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_REFERENCE");
                List<Object> pair=Json.list(input.get("$java_type"));
                if(pair.size()!=2)throw new IllegalArgumentException("TYPE_EVIDENCE_REFERENCE");
                int graph=index(pair.get(0),graphs.size()),slot=index(pair.get(1),roots.get(graph).size());
                return graphs.get(graph).view(index(roots.get(graph).get(slot),graphs.get(graph).size()));
            }
            if(input.containsKey("$java_object")) {
                if(input.size()!=1 || !(input.get("$java_object") instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_ESCAPE");
                for(Object value:Json.list(input.get("$java_object"))) {
                    if(!(value instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_ESCAPE");
                    List<Object> pair=Json.list(value);
                    if(pair.size()!=2 || !(pair.get(0) instanceof String) || output.containsKey(pair.get(0)))throw new IllegalArgumentException("TYPE_EVIDENCE_ESCAPE");
                    output.put((String)pair.get(0),unpack(pair.get(1),graphs,roots,depth+1));
                }
            } else for(Map.Entry<String,Object> entry:input.entrySet())output.put(entry.getKey(),unpack(entry.getValue(),graphs,roots,depth+1));
            return output;
        }
        if(raw instanceof List) {
            List<Object> output=new ArrayList<Object>();for(Object value:Json.list(raw))output.add(unpack(value,graphs,roots,depth+1));return output;
        }
        return raw;
    }
}
