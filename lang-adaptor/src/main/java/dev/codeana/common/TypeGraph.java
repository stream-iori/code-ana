package dev.codeana.common;

import java.util.*;

/** Detached unit-local graph. References are validated even when the target is forward or cyclic. */
public final class TypeGraph {
    public static final int VERSION = 3;
    public static final int MAX_NODES = 100000;
    /** Editable root metadata; retain real edges hidden by the traversal compatibility view. */
    public static Map<String,Object> copyRoot(Map<String,Object> type) {
        Map<String,Object> copy = new LinkedHashMap<String,Object>(type);
        if (isView(type)) {
            TypeGraph graph = owner(type);
            Object lower = graph.node(reference(type)).get("lower");
            if (lower instanceof Number && ((Number)lower).intValue() >= 0)
                copy.put("lower", graph.view(((Number)lower).intValue()));
        }
        return copy;
    }
    private static final Set<String> SINGLE = new HashSet<String>(Arrays.asList(
            "enclosing", "component", "extends", "super", "upper", "lower", "return", "receiver"));
    private static final Set<String> MANY = new HashSet<String>(Arrays.asList(
            "arguments", "bounds", "alternatives", "parameters", "throws", "type_variables"));
    private final List<Map<String,Object>> nodes;
    // An archive dictionary entry has stable identity even when its decoded graph is reclaimed.
    // The token owns neither rows nor compiler objects.
    private final Object storageIdentity;
    private boolean sealed=true;

    public TypeGraph(List<Map<String,Object>> input) {
        this(input,null);
    }
    TypeGraph(List<Map<String,Object>> input, Object storageIdentity) {
        this.storageIdentity=storageIdentity;
        if (input.size() > MAX_NODES) throw new IllegalArgumentException("TYPE_GRAPH_NODE_BUDGET");
        List<Map<String,Object>> frozen = new ArrayList<Map<String,Object>>();
        for (Map<String,Object> raw : input) {
            if (!(raw.get("kind") instanceof String) || !(raw.get("complete") instanceof Boolean))
                throw new IllegalArgumentException("TYPE_GRAPH_NODE_SHAPE");
            Map<String,Object> row = new LinkedHashMap<String,Object>(raw);
            row.put("version", VERSION);
            for (String key : SINGLE) if (row.containsKey(key)) check(row.get(key), input.size(), true);
            for (String key : MANY) if (row.containsKey(key)) {
                Object value = row.get(key);
                if (!(value instanceof List)) throw new IllegalArgumentException("TYPE_GRAPH_REFERENCE_LIST");
                for (Object reference : (List<?>) value) check(reference, input.size(), false);
            }
            validateShape(row);
            frozen.add(Json.map(freeze(row,0)));
        }
        for (Map<String,Object> row : frozen) if(Boolean.TRUE.equals(row.get("complete"))) {
            for(String key:SINGLE) if(row.containsKey(key)) {
                int child=((Number)row.get(key)).intValue();
                if(child>=0 && Boolean.FALSE.equals(frozen.get(child).get("complete")))
                    throw new IllegalArgumentException("TYPE_GRAPH_COMPLETENESS_MISMATCH");
            }
            for(String key:MANY) if(row.containsKey(key)) for(Object value:Json.list(row.get(key)))
                if(Boolean.FALSE.equals(frozen.get(((Number)value).intValue()).get("complete")))
                    throw new IllegalArgumentException("TYPE_GRAPH_COMPLETENESS_MISMATCH");
        }
        nodes = Collections.unmodifiableList(frozen);
    }

    /** Rows belong to a validated, immutable archive; retain its bounded view, not a graph copy. */
    static TypeGraph archived(List<Map<String,Object>> input, Object storageIdentity) {
        if(input.size()>MAX_NODES)throw new IllegalArgumentException("TYPE_GRAPH_NODE_BUDGET");
        boolean[] complete=new boolean[input.size()];
        for(int i=0;i<input.size();i++)complete[i]=Boolean.TRUE.equals(input.get(i).get("complete"));
        for(int i=0;i<input.size();i++)if(complete[i]) {
            Map<String,Object> row=input.get(i);
            for(String key:SINGLE)if(row.containsKey(key)) {
                int child=((Number)row.get(key)).intValue();
                if(child>=0 && !complete[child])throw new IllegalArgumentException("TYPE_GRAPH_COMPLETENESS_MISMATCH");
            }
            for(String key:MANY)if(row.containsKey(key))for(Object value:Json.list(row.get(key)))
                if(!complete[((Number)value).intValue()])throw new IllegalArgumentException("TYPE_GRAPH_COMPLETENESS_MISMATCH");
        }
        return new TypeGraph(input,storageIdentity,true);
    }

    private TypeGraph(List<Map<String,Object>> input,Object storageIdentity,boolean archived) {
        this.nodes=Collections.unmodifiableList(input);this.storageIdentity=storageIdentity;
    }

    /** Freeze a single archive row after validating all numeric edges and metadata. */
    static Map<String,Object> archivedNode(Map<String,Object> raw,int size) {
        if(!(raw.get("kind") instanceof String) || !(raw.get("complete") instanceof Boolean))
            throw new IllegalArgumentException("TYPE_GRAPH_NODE_SHAPE");
        Map<String,Object> row=new LinkedHashMap<String,Object>(raw);row.put("version",VERSION);
        for(String key:SINGLE)if(row.containsKey(key))check(row.get(key),size,true);
        for(String key:MANY)if(row.containsKey(key)) {
            Object value=row.get(key);
            if(!(value instanceof List))throw new IllegalArgumentException("TYPE_GRAPH_REFERENCE_LIST");
            for(Object reference:(List<?>)value)check(reference,size,false);
        }
        validateShape(row);
        return Json.map(freeze(row,0));
    }

    private TypeGraph(List<Map<String,Object>> input, boolean live) {
        nodes=Collections.unmodifiableList(input);storageIdentity=null;sealed=false;
    }
    Object storageIdentity() { return storageIdentity==null ? this : storageIdentity; }
    public static boolean isView(Object value) { return value instanceof TypeGraph.View; }
    public static TypeGraph owner(Map<String,Object> value) {
        if (!(value instanceof TypeGraph.View)) throw new IllegalArgumentException("TYPE_GRAPH_VIEW");
        return ((TypeGraph.View)value).graph();
    }
    public static int reference(Map<String,Object> value) {
        if (!(value instanceof TypeGraph.View)) throw new IllegalArgumentException("TYPE_GRAPH_VIEW");
        return ((TypeGraph.View)value).reference;
    }

    /** Compiler-owned builder; consumers only receive immutable views and detached metadata. */
    public static final class Builder {
        private final List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();
        private final List<List<Integer>> parents=new ArrayList<List<Integer>>();
        private final TypeGraph graph=new TypeGraph(values,true);
        private boolean sealed;
        public int reserve(Map<String,Object> metadata) {
            if(sealed)throw new IllegalStateException("TYPE_GRAPH_SEALED");
            if(values.size()>=MAX_NODES)throw new IllegalArgumentException("TYPE_GRAPH_NODE_BUDGET");
            if(!(metadata.get("kind") instanceof String) || !(metadata.get("complete") instanceof Boolean))throw new IllegalArgumentException("TYPE_GRAPH_NODE_SHAPE");
            for(String key:metadata.keySet())if(SINGLE.contains(key)||MANY.contains(key))throw new IllegalArgumentException("TYPE_GRAPH_METADATA_REFERENCE");
            int id=values.size();Map<String,Object> raw=new LinkedHashMap<String,Object>(Json.map(freeze(metadata,0)));raw.put("version",VERSION);
            values.add(Collections.unmodifiableMap(raw));parents.add(new ArrayList<Integer>());
            edits.add(raw);return id;
        }
        private final List<Map<String,Object>> edits=new ArrayList<Map<String,Object>>();
        public Map<String,Object> edit(final int id) {
            check(id,values.size(),false);
            return new AbstractMap<String,Object>() {
                public Set<Map.Entry<String,Object>> entrySet() { return values.get(id).entrySet(); }
                public Object get(Object key) { return values.get(id).get(key); }
                public Object put(String key,Object value) { return Builder.this.put(id,key,value); }
            };
        }
        private List<Integer> children(String key,Object value) {
            List<Integer> refs=new ArrayList<Integer>();
            if(SINGLE.contains(key)) { int ref=check(value,values.size(),true);if(ref>=0)refs.add(ref); }
            else if(MANY.contains(key)) {
                if(!(value instanceof List))throw new IllegalArgumentException("TYPE_GRAPH_REFERENCE_LIST");
                for(Object ref:Json.list(value))refs.add(check(ref,values.size(),false));
            }
            return refs;
        }
        private Object put(int id,String key,Object value) {
            if(sealed)throw new IllegalStateException("TYPE_GRAPH_SEALED");
            List<Integer> refs=children(key,value);Object frozen=freeze(value,0);
            Map<String,Object> row=edits.get(id);Object previous=row.get(key);
            if(key.equals("complete")) {
                if(!(value instanceof Boolean))throw new IllegalArgumentException("TYPE_GRAPH_COMPLETE");
                if(Boolean.FALSE.equals(previous)&&Boolean.TRUE.equals(value))throw new IllegalArgumentException("TYPE_GRAPH_COMPLETENESS_REWRITE");
            }
            if(previous!=null)for(int child:children(key,previous))parents.get(child).remove(Integer.valueOf(id));
            row.put(key,frozen);
            for(int child:refs)parents.get(child).add(id);
            if(Boolean.FALSE.equals(row.get("complete")))invalidate(id);
            else for(int child:refs)if(Boolean.FALSE.equals(edits.get(child).get("complete"))) { invalidate(id);break; }
            return previous;
        }
        private void invalidate(int id) {
            ArrayDeque<Integer> pending=new ArrayDeque<Integer>();pending.add(id);edits.get(id).put("complete",false);
            Set<Integer> seen=new HashSet<Integer>();
            while(!pending.isEmpty()) {
                int child=pending.removeFirst();if(!seen.add(child))continue;
                for(int parent:parents.get(child))if(!Boolean.FALSE.equals(edits.get(parent).get("complete"))) {
                    edits.get(parent).put("complete",false);pending.add(parent);
                }
            }
        }
        public void rollback(int size) {
            if(sealed || size<0 || size>values.size())throw new IllegalArgumentException("TYPE_GRAPH_ROLLBACK");
            // Suffix rollback cannot silently leave a surviving node pointing into removed storage.
            for(int i=0;i<size;i++)for(Map.Entry<String,Object> entry:edits.get(i).entrySet())
                for(int child:children(entry.getKey(),entry.getValue()))if(child>=size)
                    throw new IllegalArgumentException("TYPE_GRAPH_ROLLBACK_EXISTING_REFERENCE");
            for(int i=0;i<size;i++)parents.get(i).removeIf(parent->parent>=size);
            values.subList(size,values.size()).clear();parents.subList(size,parents.size()).clear();edits.subList(size,edits.size()).clear();
        }
        public TypeGraph graph() { return graph; }
        public TypeGraph seal() {
            if(!sealed) { new TypeGraph(values);sealed=true;graph.sealed=true; }
            return graph;
        }
    }

    private static void validateShape(Map<String,Object> row) {
        String kind=(String)row.get("kind");
        Set<String> allowed=new HashSet<String>();
        switch(kind) {
            case "ARRAY":allowed.add("component");break;
            case "DECLARED":case "ERROR":allowed.addAll(Arrays.asList("enclosing","arguments"));break;
            case "TYPEVAR":allowed.addAll(Arrays.asList("upper","lower"));break;
            case "WILDCARD":allowed.addAll(Arrays.asList("extends","super"));break;
            case "INTERSECTION":allowed.add("bounds");break;
            case "UNION":allowed.add("alternatives");break;
            case "EXECUTABLE":allowed.addAll(Arrays.asList("receiver","return","parameters","throws","type_variables"));break;
            case "BOOLEAN":case "BYTE":case "SHORT":case "INT":case "LONG":case "CHAR":case "FLOAT":case "DOUBLE":
            case "VOID":case "NONE":case "NULL":case "OTHER":case "UNKNOWN":case "POLY":break;
            default:throw new IllegalArgumentException("TYPE_GRAPH_KIND");
        }
        for(String key:row.keySet())if((SINGLE.contains(key)||MANY.contains(key))&&!allowed.contains(key))
            throw new IllegalArgumentException("TYPE_GRAPH_FOREIGN_REFERENCE:"+key);
        if(!Boolean.TRUE.equals(row.get("complete")))return;
        if(kind.equals("ERROR") || kind.equals("UNKNOWN"))throw new IllegalArgumentException("TYPE_GRAPH_COMPLETE_UNKNOWN");
        List<String> singles=Collections.emptyList(),lists=Collections.emptyList();
        switch(kind) {
            case "ARRAY":singles=Arrays.asList("component");break;
            case "TYPEVAR":singles=Arrays.asList("upper","lower");break;
            case "DECLARED":
                if(!(row.get("element") instanceof String) || ((String)row.get("element")).isEmpty())
                    throw new IllegalArgumentException("TYPE_GRAPH_DECLARATION");
                lists=Arrays.asList("arguments");break;
            case "WILDCARD":
                if(!row.containsKey("extends") || !row.containsKey("super"))
                    throw new IllegalArgumentException("TYPE_GRAPH_WILDCARD_BOUNDS");
                break;
            case "INTERSECTION":lists=Arrays.asList("bounds");break;
            case "UNION":lists=Arrays.asList("alternatives");break;
            case "EXECUTABLE":singles=Arrays.asList("return");lists=Arrays.asList("parameters","throws","type_variables");break;
            default:break;
        }
        for(String key:singles)if(!row.containsKey(key)||((Number)row.get(key)).intValue()<0)
            throw new IllegalArgumentException("TYPE_GRAPH_REQUIRED_REFERENCE:"+key);
        for(String key:lists)if(!row.containsKey(key))throw new IllegalArgumentException("TYPE_GRAPH_REQUIRED_REFERENCES:"+key);
    }

    private static int check(Object value, int size, boolean absent) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("TYPE_GRAPH_REFERENCE");
        long number;
        try { number = new java.math.BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException("TYPE_GRAPH_REFERENCE",failure); }
        if (number == -1 && absent) return -1;
        if (number < 0 || number >= size) throw new IllegalArgumentException("TYPE_GRAPH_REFERENCE");
        return (int)number;
    }

    private static Object freeze(Object raw, int depth) {
        if (depth > 128) throw new IllegalArgumentException("TYPE_GRAPH_METADATA_DEPTH_BUDGET");
        if (raw instanceof Map) {
            Map<String,Object> copy = new LinkedHashMap<String,Object>();
            for (Map.Entry<?,?> entry : ((Map<?,?>)raw).entrySet()) {
                if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("TYPE_GRAPH_METADATA_KEY");
                copy.put((String)entry.getKey(),freeze(entry.getValue(),depth+1));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (raw instanceof List) {
            List<Object> copy = new ArrayList<Object>();
            for (Object child : Json.list(raw)) copy.add(freeze(child,depth+1));
            return Collections.unmodifiableList(copy);
        }
        if (raw == null || raw instanceof String || raw instanceof Boolean) return raw;
        if (raw instanceof Number) {
            // Number is extensible: AtomicInteger and custom Number instances are mutable.
            Class<?> kind=raw.getClass();
            if(kind!=Byte.class && kind!=Short.class && kind!=Integer.class && kind!=Long.class
                    && kind!=Float.class && kind!=Double.class && kind!=java.math.BigInteger.class
                    && kind!=java.math.BigDecimal.class)throw new IllegalArgumentException("TYPE_GRAPH_METADATA_NUMBER");
            if(raw instanceof Double && !Double.isFinite((Double)raw)
                    || raw instanceof Float && !Float.isFinite((Float)raw))
                throw new IllegalArgumentException("TYPE_GRAPH_METADATA_NUMBER");
            return raw;
        }
        throw new IllegalArgumentException("TYPE_GRAPH_METADATA_VALUE");
    }

    public boolean isSealed() { return sealed; }
    public void requireSealed() {
        if(!sealed)throw new IllegalStateException("TYPE_GRAPH_NOT_SEALED");
    }
    public int size() { return nodes.size(); }
    public Map<String,Object> node(int reference) { check(reference,nodes.size(),false);return nodes.get(reference); }
    public void validateReference(int reference) { check(reference,nodes.size(),true); }
    public Map<String,Object> view(int reference) {
        check(reference,nodes.size(),false);
        return new View(reference,new Path(reference,null));
    }

    /** Shallow compatibility view. The graph itself retains cycles and all bounded compiler nodes. */
    private final class View extends AbstractMap<String,Object> {
        final int reference;
        final Path path;
        View(int reference, Path path) { this.reference=reference;this.path=path; }
        TypeGraph graph() { return TypeGraph.this; }
        public boolean containsKey(Object key) { return node(reference).containsKey(key); }
        public int size() { return node(reference).size(); }
        public Object get(Object key) {
            Object value=node(reference).get(key);
            if (value == null) return null;
            if (SINGLE.contains(key)) {
                int next=((Number)value).intValue();
                if(next>=0 && ("enclosing".equals(key)||"receiver".equals(key)) && "NONE".equals(node(next).get("kind"))) return null;
                if(next>=0 && "lower".equals(key) && "NULL".equals(node(next).get("kind"))) return null;
                return follow(next);
            }
            if (MANY.contains(key)) {
                final List<Object> values=Json.list(value);
                return Collections.unmodifiableList(new AbstractList<Object>() {
                    public int size() { return values.size(); }
                    public Object get(int index) { return follow(((Number)values.get(index)).intValue()); }
                });
            }
            return value;
        }
        private Object follow(int next) {
            if (next == -1) return null;
            if (path.contains(next) || path.depth >= 128) {
                Map<String,Object> summary = new LinkedHashMap<String,Object>();
                for (Map.Entry<String,Object> e:node(next).entrySet())
                    if (!SINGLE.contains(e.getKey()) && !MANY.contains(e.getKey())) summary.put(e.getKey(),e.getValue());
                if (path.contains(next)) summary.put("recursive",true);
                else { summary.put("complete",false);summary.put("reason","TYPE_GRAPH_VIEW_DEPTH_BUDGET"); }
                return Collections.unmodifiableMap(summary);
            }
            return new View(next,new Path(next,path));
        }
        public Set<Map.Entry<String,Object>> entrySet() {
            return new AbstractSet<Map.Entry<String,Object>>() {
                public int size() { return View.this.size(); }
                public Iterator<Map.Entry<String,Object>> iterator() {
                    final Iterator<String> keys=node(reference).keySet().iterator();
                    return new Iterator<Map.Entry<String,Object>>() {
                        public boolean hasNext() { return keys.hasNext(); }
                        public Map.Entry<String,Object> next() {
                            String key=keys.next();return new SimpleImmutableEntry<String,Object>(key,View.this.get(key));
                        }
                        public void remove() { throw new UnsupportedOperationException(); }
                    };
                }
            };
        }
    }
    private static final class Path {
        final int id,depth;
        final Path parent;
        Path(int id,Path parent) { this.id=id;this.parent=parent;depth=parent==null?0:parent.depth+1; }
        boolean contains(int wanted) { for(Path p=this;p!=null;p=p.parent)if(p.id==wanted)return true;return false; }
    }

    /** No recursive type objects enter the wire representation. Original IDs stay unit-local. */
    public Map<String,Object> encoded(List<Integer> roots) {
        requireSealed();
        for (Integer root:roots) check(root,nodes.size(),true);
        return Json.object("version",VERSION,"roots",new ArrayList<Integer>(roots),"nodes",nodes);
    }
    /** Canonical per-fact closure; compiler visit order and unrelated unit types stay private. */
    public Map<String,Object> canonical(List<Integer> roots) {
        requireSealed();
        Map<Integer,Integer> identifiers = new LinkedHashMap<Integer,Integer>();
        List<Integer> pending = new ArrayList<Integer>();
        List<Integer> exportedRoots = new ArrayList<Integer>();
        for (Integer root : roots) {
            check(root,nodes.size(),true);
            exportedRoots.add(remap(root,identifiers,pending));
        }
        List<Map<String,Object>> exported = new ArrayList<Map<String,Object>>();
        for (int cursor=0;cursor<pending.size();cursor++) {
            Map<String,Object> copy = new TreeMap<String,Object>();
            for (Map.Entry<String,Object> entry : new TreeMap<String,Object>(node(pending.get(cursor))).entrySet()) {
                String key=entry.getKey();Object value=entry.getValue();
                if (SINGLE.contains(key)) value=remap(((Number)value).intValue(),identifiers,pending);
                else if (MANY.contains(key)) {
                    List<Integer> list=new ArrayList<Integer>();
                    for(Object raw:Json.list(value))list.add(remap(((Number)raw).intValue(),identifiers,pending));
                    value=list;
                }
                copy.put(key,value);
            }
            exported.add(copy);
        }
        return Json.object("version",VERSION,"roots",exportedRoots,"nodes",exported);
    }
    private static int remap(int original,Map<Integer,Integer> identifiers,List<Integer> pending) {
        if(original==-1)return -1;
        Integer result=identifiers.get(original);
        if(result==null) {result=pending.size();identifiers.put(original,result);pending.add(original);}
        return result;
    }

    public static TypeGraph decode(Map<String,Object> payload) {
        if (Json.number(payload,"version") != VERSION) throw new IllegalArgumentException("TYPE_GRAPH_VERSION");
        if (!(payload.get("nodes") instanceof List) || !(payload.get("roots") instanceof List))
            throw new IllegalArgumentException("TYPE_GRAPH_SHAPE");
        List<Map<String,Object>> input=new ArrayList<Map<String,Object>>();
        for(Object value:Json.list(payload.get("nodes"))) input.add(Json.map(value));
        TypeGraph graph=new TypeGraph(input);
        for(Object root:Json.list(payload.get("roots"))) check(root,graph.size(),true);
        return graph;
    }
}
