package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** Explicit archive budgets must apply to disk-backed graphs in both transfer directions. Java 8. */
public final class ModelGraphBudgetProbe {
    private static void budgetFailure(Action action) throws Exception {
        try {action.run();throw new AssertionError("accepted undersized archive budget");}
        catch(IllegalArgumentException|java.io.UncheckedIOException|java.io.IOException failure) {
            if(!failure.toString().contains("BUDGET"))throw failure;
        }
    }
    private interface Action {void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("model-graph-budget-");
        try {
            char[] chars=new char[1024];Arrays.fill(chars,'x');String display=new String(chars);
            List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();
            for(int i=0;i<65536;i++)nodes.add(Json.object("version",3,"kind","DECLARED","complete",true,
                "element","p.Type"+i,"display",display,"enclosing",-1,"arguments",Arrays.asList((i+1)%65536)));
            TypeGraph graph=new TypeGraph(nodes);
            Map<String,Object> model=Json.object("version",ModelArchive.VERSION,"context","large@TEST");
            for(String section:Arrays.asList("sources","declarations","observations","syntax","external","annotation_types","diagnostics"))
                model.put(section,section.equals("declarations")?Arrays.asList(Json.object("type",graph.view(0))):Collections.emptyList());
            Path output=root.resolve("model.bin");long limit=96L<<20;
            long bytes=ModelArchive.write(output,model,limit);
            if(bytes<=(64L<<20)||bytes>limit)throw new AssertionError("large graph witness did not cross the old limit");
            budgetFailure(()->ModelArchive.write(root.resolve("small.bin"),model,64L<<20));
            try(ModelArchive.OpenModel opened=ModelArchive.open(output,Json.sha(output),limit,"large@TEST")) {
                Map<String,Object> type=Json.map(Json.map(Json.list(opened.model().get("declarations")).get(0)).get("type"));
                TypeGraph decoded=TypeGraph.owner(type);
                if(decoded.size()!=65536)throw new AssertionError("large graph nodes lost");
                for(int i=0;i<decoded.size();i++) {
                    Map<String,Object> node=decoded.node(i);
                    if(!("p.Type"+i).equals(node.get("element"))||!display.equals(node.get("display"))
                        ||((Number)Json.list(node.get("arguments")).get(0)).intValue()!=(i+1)%65536)
                        throw new AssertionError("large graph changed at "+i);
                }
            }
            System.out.println("explicit 96MiB graph archive round trip passed; default 64MiB rejection retained");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception e){throw new RuntimeException(e);}});
            }
        }
    }
}
