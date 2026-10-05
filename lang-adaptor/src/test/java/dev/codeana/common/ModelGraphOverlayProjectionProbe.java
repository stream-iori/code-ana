package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** Shared metadata overlays must not serialize unrelated base nodes for each generated owner. */
public final class ModelGraphOverlayProjectionProbe {
    public static void main(String[] args) throws Exception {
        List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();
        nodes.add(Json.object("kind","TYPEVAR","complete",true,"owner","A","name","T","upper",1,"lower",2));
        nodes.add(Json.object("kind","DECLARED","complete",true,"element","Comparable","arguments",Arrays.asList(0),"enclosing",-1));
        nodes.add(Json.object("kind","NULL","complete",true));
        nodes.add(Json.object("kind","TYPEVAR","complete",true,"owner","Other","name","T","upper",4,"lower",2));
        nodes.add(Json.object("kind","DECLARED","complete",true,"element","OtherBound","arguments",Arrays.asList(3),"enclosing",-1));
        for(int i=0;i<5000;i++)nodes.add(Json.object("kind","INT","complete",true,"display",String.join("",Collections.nCopies(1024,"x"))));
        TypeGraph original=new TypeGraph(nodes);
        TypeGraph rewritten=original.rebaseOwners(Collections.singletonMap(new TypeGraph.VariableIdentity("A","T"),"Builder"));
        try(ModelArchive.RowSpool spool=new ModelArchive.RowSpool(Paths.get(args[0]),"overlay",64L<<20)) {
            spool.add(Json.object("type",rewritten.view(0)));
            spool.add(Json.object("type",rewritten.view(3)));
            spool.seal();
            Map<String,Object> first=Json.map(Json.map(spool.get(0)).get("type"));
            Map<String,Object> second=Json.map(Json.map(spool.get(1)).get("type"));
            TypeGraph graph=TypeGraph.owner(first);
            if(graph.size()!=5 || !"Builder".equals(first.get("owner")) || !"Other".equals(second.get("owner")))
                throw new AssertionError("unrelated nodes exported or owner identities drifted");
            int lower=((Number)graph.node(TypeGraph.reference(first)).get("lower")).intValue();
            if(!"NULL".equals(graph.node(lower).get("kind")))throw new AssertionError("NULL lower lost");
            if(!Boolean.TRUE.equals(Json.map(Json.list(Json.map(first.get("upper")).get("arguments")).get(0)).get("recursive")))
                throw new AssertionError("cycle lost");
            if(!"A".equals(original.node(0).get("owner")))throw new AssertionError("original changed");
        }
        System.out.println("two overlay roots export 5 reachable nodes from 5005-node base; cycles, NULL and distinct owners preserved");
    }
}
