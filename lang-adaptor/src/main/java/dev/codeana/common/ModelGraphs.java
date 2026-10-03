package dev.codeana.common;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.*;

/** Model-local flat graph dictionaries. Roots never recursively serialize their shared children. */
final class ModelGraphs {
    private final boolean encodingOnly;
    private final RecordArchive archive;
    ModelGraphs() {this(false);}
    ModelGraphs(boolean encodingOnly) {this.encodingOnly=encodingOnly;this.archive=null;}
    ModelGraphs(RecordArchive archive) {this.encodingOnly=false;this.archive=archive;}
    void retireEncoderIdentities() {identities.clear();}
    private final List<List<Map<String,Object>>> nodes = new ArrayList<List<Map<String,Object>>>();
    private final List<TypeGraph> decoded = new ArrayList<TypeGraph>();
    private final Map<Integer,WeakReference<TypeGraph>> borrowed = new HashMap<Integer,WeakReference<TypeGraph>>();
    private final IdentityHashMap<Object,Integer> identities = new IdentityHashMap<Object,Integer>();
    private final List<Object> storageIdentities = new ArrayList<Object>();
    private long bytes;
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    interface Types { ModelGraphs graphs(); }
    static final class Table extends ArrayList<Object> implements Types {
        private final ModelGraphs graphs;
        Table(RecordArchive archive) {graphs=new ModelGraphs(archive);}
        public ModelGraphs graphs() { return graphs; }
    }

    /** Retain positions, not a second context's decoded type dictionary. */
    private final class ArchivedNodes extends AbstractList<Map<String,Object>> {
        private int[] positions = new int[16];
        private int count;
        void append(int position) {
            if(count==positions.length)positions=Arrays.copyOf(positions,count*2);
            positions[count++]=position;
        }
        public int size() { return count; }
        public Map<String,Object> get(int index) {
            if(index<0 || index>=count)throw new IndexOutOfBoundsException();
            return Json.map(Json.map(archive.get(positions[index])).get("value"));
        }
    }

    Object pack(Map<String,Object> view, RecordArchive records) {
        TypeGraph graph=TypeGraph.owner(view);
        Object identity=graph.storageIdentity();
        Integer id=identities.get(identity);
        if(id==null) {
            if(nodes.size()>=100000)throw new IllegalArgumentException("MODEL_GRAPH_COUNT_BUDGET");
            id=nodes.size();identities.put(identity,id);
            nodes.add(archive==null ? new ArrayList<Map<String,Object>>() : new ArchivedNodes());decoded.add(null);
            storageIdentities.add(new Object());
        }
        List<Map<String,Object>> values=nodes.get(id);
        while(values.size()<graph.size()) {
            int index=values.size();
            Map<String,Object> node=new LinkedHashMap<String,Object>(graph.node(index));
            account(node);
            records.add(Json.object("kind","graph_node","graph",id,"id",index,"value",node));
            if(archive==null)values.add(encodingOnly?null:node);
            else {
                if(records!=archive)throw new IllegalArgumentException("MODEL_GRAPH_ARCHIVE_OWNER");
                ((ArchivedNodes)values).append(records.size()-1);
            }
            decoded.set(id,null);borrowed.remove(id);
        }
        return Json.object("$graph",Arrays.asList(id,TypeGraph.reference(view)));
    }

    void add(Map<String,Object> record) throws IOException {
        add(record,-1);
    }
    void add(Map<String,Object> record, int position) throws IOException {
        long graph=integer(record.get("graph")),node=integer(record.get("id"));
        if(graph<0 || graph>nodes.size() || graph>=100000)throw new IOException("MODEL_GRAPH_SEQUENCE");
        if(graph==nodes.size()) {nodes.add(archive==null ? new ArrayList<Map<String,Object>>() : new ArchivedNodes());decoded.add(null);storageIdentities.add(new Object());}
        List<Map<String,Object>> values=nodes.get((int)graph);
        if(node!=values.size() || node>=TypeGraph.MAX_NODES)throw new IOException("MODEL_GRAPH_NODE_SEQUENCE");
        Map<String,Object> value=Json.map(record.get("value"));
        try {account(value);}catch(IllegalArgumentException failure){throw new IOException(failure.getMessage(),failure);}
        if(archive==null)values.add(value);
        else {
            if(position<0)throw new IOException("MODEL_GRAPH_ARCHIVE_POSITION");
            ((ArchivedNodes)values).append(position);
        }
        decoded.set((int)graph,null);borrowed.remove((int)graph);
    }

    private void account(Map<String,Object> value) {
        bytes=Math.addExact(bytes,Json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if(bytes>MAX_BYTES)throw new IllegalArgumentException("MODEL_GRAPH_TABLE_BUDGET");
    }

    private int[] reference(Object raw) throws IOException {
        if(!(raw instanceof List) || Json.list(raw).size()!=2)throw new IOException("MODEL_GRAPH_REFERENCE");
        List<Object> pair=Json.list(raw);
        long graph,node;
        graph=integer(pair.get(0));node=integer(pair.get(1));
        if(graph<0 || graph>=nodes.size() || node<0 || node>=nodes.get((int)graph).size())
            throw new IOException("MODEL_GRAPH_REFERENCE");
        return new int[]{(int)graph,(int)node};
    }

    private static long integer(Object value) throws IOException {
        try {
            if(!(value instanceof Number))throw new IllegalArgumentException();
            return new java.math.BigDecimal(value.toString()).longValueExact();
        }catch(RuntimeException failure){throw new IOException("MODEL_GRAPH_REFERENCE",failure);}
    }

    void validate(Object raw) throws IOException { reference(raw); }
    Map<String,Object> view(Object raw) throws IOException {
        int[] pair=reference(raw);
        TypeGraph graph=decoded.get(pair[0]);
        if(archive!=null) {
            WeakReference<TypeGraph> reference=borrowed.get(pair[0]);
            graph=reference==null ? null : reference.get();
        }
        if(graph==null) {
            try {graph=new TypeGraph(nodes.get(pair[0]),archive==null ? null : storageIdentities.get(pair[0]));}
            catch(IllegalArgumentException failure){throw new IOException("MODEL_GRAPH_INVALID",failure);}
            if(archive==null)decoded.set(pair[0],graph);
            else borrowed.put(pair[0],new WeakReference<TypeGraph>(graph));
        }
        return graph.view(pair[1]);
    }

    List<Integer> counts() {
        List<Integer> counts=new ArrayList<Integer>();
        for(List<Map<String,Object>> values:nodes)counts.add(values.size());
        return counts;
    }
    void seal() throws IOException {
        for(int i=0;i<nodes.size();i++)if(!nodes.get(i).isEmpty()) {
            view(Arrays.asList(i,0));
            if(archive!=null)continue; // Every graph was checked, but only live views pin its nodes.
            final TypeGraph graph=decoded.get(i);
            // The decoder has validated and frozen these rows. Keeping the packed mutable
            // maps as well doubles the entire graph dictionary while supplemental javac
            // reads a second model. Retain a view of the same immutable nodes instead.
            nodes.set(i,new AbstractList<Map<String,Object>>() {
                public int size() { return graph.size(); }
                public Map<String,Object> get(int index) { return graph.node(index); }
            });
        }
        identities.clear();
    }
    void close() {identities.clear();nodes.clear();decoded.clear();borrowed.clear();storageIdentities.clear();}
}
