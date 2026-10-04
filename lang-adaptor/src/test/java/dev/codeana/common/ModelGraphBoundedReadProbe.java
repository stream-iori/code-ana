package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** A large archive and all retained roots must fit in a heap smaller than its decoded graph. */
public final class ModelGraphBoundedReadProbe {
    private static final int COUNT=65536;
    private static final long LIMIT=128L<<20;
    private static final List<String> SECTIONS=Arrays.asList("sources","declarations","observations","syntax","external","annotation_types","diagnostics");
    public static void main(String[] args) throws Exception {
        Path path=Paths.get(args[1]);
        if(args[0].equals("write")) {
            char[] text=new char[1024];Arrays.fill(text,'x');String display=new String(text);
            try(RecordArchive rows=RecordArchive.create(path,LIMIT,COUNT+3)) {
                rows.add(Json.object("kind","header","version",7,"context","large@TEST","sections",SECTIONS));
                for(int i=0;i<COUNT;i++)rows.add(Json.object("kind","graph_node","graph",0,"id",i,"value",
                    Json.object("kind","DECLARED","complete",true,"enclosing",-1,"arguments",Arrays.asList((i+1)%COUNT),
                        "element","p.Type"+i,"display",display)));
                rows.add(Json.object("kind","row","section","declarations","value",Json.object("type",Json.object("$graph",Arrays.asList(0,0)))));
                Map<String,Object> counts=new LinkedHashMap<String,Object>();
                for(String section:SECTIONS)counts.put(section,section.equals("declarations")?1:0);
                rows.add(Json.object("kind","end","types",0,"graphs",Arrays.asList(COUNT),"counts",counts));rows.seal();
            }
            return;
        }
        try(ModelArchive.OpenModel model=ModelArchive.open(path,Json.sha(path),LIMIT,"large@TEST")) {
            Map<String,Object> root=Json.map(Json.map(Json.list(model.model().get("declarations")).get(0)).get("type"));
            TypeGraph graph=TypeGraph.owner(root);
            List<Map<String,Object>> held=new ArrayList<Map<String,Object>>();
            for(int i=0;i<COUNT;i++)held.add(graph.view(i));
            for(int i=COUNT-1;i>=0;i--) {
                Map<String,Object> node=graph.node(i);
                if(!("p.Type"+i).equals(held.get(i).get("element"))
                    ||((String)node.get("display")).length()!=1024
                    ||((Number)Json.list(node.get("arguments")).get(0)).intValue()!=(i+1)%COUNT)
                    throw new AssertionError("node drift "+i);
                try{node.put("element","changed");throw new AssertionError("mutable node");}
                catch(UnsupportedOperationException expected){}
            }
        }
        System.out.println("65536 retained cyclic roots and >64MiB archive read in 32MiB heap passed");
    }
}
