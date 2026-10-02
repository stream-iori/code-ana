package dev.codeana.common;

import java.io.IOException;
import java.util.*;

/** Model-local flat graph dictionaries. Roots never recursively serialize their shared children. */
final class ModelGraphs {
    private final List<List<Map<String,Object>>> nodes = new ArrayList<List<Map<String,Object>>>();
    private final List<TypeGraph> decoded = new ArrayList<TypeGraph>();
    private final IdentityHashMap<TypeGraph,Integer> identities = new IdentityHashMap<TypeGraph,Integer>();
    private long bytes;
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    interface Types { ModelGraphs graphs(); }
    static final class Table extends ArrayList<Object> implements Types {
        private final ModelGraphs graphs = new ModelGraphs();
        public ModelGraphs graphs() { return graphs; }
    }

    Object pack(Map<String,Object> view, RecordArchive records) {
        TypeGraph graph=TypeGraph.owner(view);
        Integer id=identities.get(graph);
        if(id==null) {
            if(nodes.size()>=100000)throw new IllegalArgumentException("MODEL_GRAPH_COUNT_BUDGET");
            id=nodes.size();identities.put(graph,id);
            nodes.add(new ArrayList<Map<String,Object>>());decoded.add(null);
        }
        List<Map<String,Object>> values=nodes.get(id);
        while(values.size()<graph.size()) {
            int index=values.size();
            Map<String,Object> node=new LinkedHashMap<String,Object>(graph.node(index));
            account(node);
            records.add(Json.object("kind","graph_node","graph",id,"id",index,"value",node));
            values.add(node);decoded.set(id,null);
        }
        return Json.object("$graph",Arrays.asList(id,TypeGraph.reference(view)));
    }

    void add(Map<String,Object> record) throws IOException {
        long graph=integer(record.get("graph")),node=integer(record.get("id"));
        if(graph<0 || graph>nodes.size() || graph>=100000)throw new IOException("MODEL_GRAPH_SEQUENCE");
        if(graph==nodes.size()) {nodes.add(new ArrayList<Map<String,Object>>());decoded.add(null);}
        List<Map<String,Object>> values=nodes.get((int)graph);
        if(node!=values.size() || node>=TypeGraph.MAX_NODES)throw new IOException("MODEL_GRAPH_NODE_SEQUENCE");
        Map<String,Object> value=Json.map(record.get("value"));
        try {account(value);}catch(IllegalArgumentException failure){throw new IOException(failure.getMessage(),failure);}
        values.add(value);decoded.set((int)graph,null);
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
        if(graph==null) {
            try {graph=new TypeGraph(nodes.get(pair[0]));}
            catch(IllegalArgumentException failure){throw new IOException("MODEL_GRAPH_INVALID",failure);}
            decoded.set(pair[0],graph);
        }
        return graph.view(pair[1]);
    }

    List<Integer> counts() {
        List<Integer> counts=new ArrayList<Integer>();
        for(List<Map<String,Object>> values:nodes)counts.add(values.size());
        return counts;
    }
    void seal() throws IOException {
        for(int i=0;i<nodes.size();i++)if(!nodes.get(i).isEmpty())view(Arrays.asList(i,0));
        identities.clear();
    }
    void close() {identities.clear();nodes.clear();decoded.clear();}
}
