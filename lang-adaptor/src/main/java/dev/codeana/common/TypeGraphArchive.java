package dev.codeana.common;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Flat unit graph spool: physical frames never contain recursive type objects. */
public final class TypeGraphArchive {
    private TypeGraphArchive() {}
    public static final class Decoded {
        public final TypeGraph graph;
        public final List<Integer> roots;
        private Decoded(TypeGraph graph,List<Integer> roots) {
            this.graph=graph;this.roots=Collections.unmodifiableList(roots);
        }
    }
    public static long write(Path path,TypeGraph graph,List<Integer> roots,long maxBytes) throws IOException {
        graph.requireSealed();
        for(Integer root:roots)graph.validateReference(root);
        try(RecordArchive records=RecordArchive.create(path,maxBytes,TypeGraph.MAX_NODES+2)) {
            records.add(Json.object("kind","type_graph_header","version",TypeGraph.VERSION,
                    "nodes",graph.size(),"roots",new ArrayList<Integer>(roots)));
            for(int i=0;i<graph.size();i++) {
                check();records.add(Json.object("kind","type_graph_node","id",i,"value",graph.node(i)));
            }
            records.add(Json.object("kind","type_graph_end","nodes",graph.size()));
            records.seal();return records.bytes();
        }
    }
    public static Decoded read(Path path,String checksum,long maxBytes) throws IOException {
        try(RecordArchive records=RecordArchive.open(path,maxBytes,TypeGraph.MAX_NODES+2,checksum)) {
            if(records.size()<2)throw new IOException("TYPE_GRAPH_ARCHIVE_TRUNCATED");
            Map<String,Object> header=Json.map(records.get(0));
            if(!"type_graph_header".equals(header.get("kind")) || Json.number(header,"version")!=TypeGraph.VERSION)
                throw new IOException("TYPE_GRAPH_ARCHIVE_HEADER");
            long count=Json.number(header,"nodes");
            if(count<0 || count>TypeGraph.MAX_NODES || records.size()!=count+2)
                throw new IOException("TYPE_GRAPH_ARCHIVE_NODE_COUNT");
            List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();
            for(int i=0;i<count;i++) {
                check();Map<String,Object> record=Json.map(records.get(i+1));
                if(!"type_graph_node".equals(record.get("kind")) || Json.number(record,"id")!=i)
                    throw new IOException("TYPE_GRAPH_ARCHIVE_NODE_SEQUENCE");
                nodes.add(Json.map(record.get("value")));
            }
            Map<String,Object> end=Json.map(records.get(records.size()-1));
            if(!"type_graph_end".equals(end.get("kind")) || Json.number(end,"nodes")!=count)
                throw new IOException("TYPE_GRAPH_ARCHIVE_END");
            TypeGraph graph;
            try {graph=new TypeGraph(nodes);}catch(IllegalArgumentException invalid) {throw new IOException("TYPE_GRAPH_ARCHIVE_INVALID",invalid);}
            List<Integer> roots=new ArrayList<Integer>();
            for(Object root:Json.list(header.get("roots"))) {
                try {
                    if(!(root instanceof Number))throw new IllegalArgumentException("root");
                    long id=new java.math.BigDecimal(root.toString()).longValueExact();
                    if(id<-1 || id>=count)throw new IllegalArgumentException("root");
                    roots.add((int)id);
                }catch(RuntimeException invalid) {throw new IOException("TYPE_GRAPH_ARCHIVE_ROOT",invalid);}
            }
            return new Decoded(graph,roots);
        }
    }
    private static void check() throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
    }
}
