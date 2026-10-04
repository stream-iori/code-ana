package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** Exercise actual model reads while two large graph dictionaries coexist. */
public final class ModelGraphPagingProbe {
    private static final List<String> SECTIONS=Arrays.asList("sources","declarations","observations","syntax","external","annotation_types","diagnostics");
    private static Path create(Path root, String name, boolean invalid) throws Exception {
        Path path=root.resolve(name+".bin");
        try(RecordArchive rows=RecordArchive.create(path,64L<<20,100000)) {
            rows.add(Json.object("kind","header","version",7,"context","app@MAIN","sections",SECTIONS));
            for(int graph=0;graph<500;graph++)for(int node=0;node<100;node++)
                rows.add(Json.object("kind","graph_node","graph",graph,"id",node,"value",Json.object(
                    "kind","DECLARED","complete",true,"enclosing",-1,
                    "arguments",Arrays.asList(invalid && graph==499 && node==99 ? 100 : (node+1)%100),
                    "element","p.Box","element_kind","CLASS","display","p.Box<T>","name","Box",
                    "descriptor","Lp/Box;","signature","Lp/Box<TT;>;","version",3,"annotations",Collections.emptyList())));
            for(int graph=0;graph<500;graph++)rows.add(Json.object("kind","row","section","declarations",
                "value",Json.object("ordinal",graph,"attributes",Json.object("type_model",Json.object("$graph",Arrays.asList(graph,0))))));
            Map<String,Object> counts=new LinkedHashMap<String,Object>();
            for(String section:SECTIONS)counts.put(section,section.equals("declarations")?500:0);
            rows.add(Json.object("kind","end","types",0,"graphs",Collections.nCopies(500,100),"counts",counts));
            rows.seal();
        }
        return path;
    }
    private static Map<String,Object> type(List<Object> declarations,int index) {
        return Json.map(Json.map(Json.map(declarations.get(index)).get("attributes")).get("type_model"));
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("model-paging-");
        try {
            Path a=create(root,"original",false),b=create(root,"supplemental",false);
            Map<String,Object> held;String detachedElement;
            try(ModelArchive.OpenModel original=ModelArchive.open(a,Json.sha(a),64L<<20,"app@MAIN");
                ModelArchive.OpenModel supplemental=ModelArchive.open(b,Json.sha(b),64L<<20,"app@MAIN")) {
                List<Object> left=Json.list(original.model().get("declarations")),right=Json.list(supplemental.model().get("declarations"));
                if(left.size()!=500 || right.size()!=500)throw new AssertionError("row count drift");
                held=type(left,0);TypeGraph pinned=TypeGraph.owner(held);
                detachedElement=(String)held.get("element");
                for(int i=0;i<500;i++) {
                    Map<String,Object> l=type(left,i),r=type(right,i);
                    if(!TypeGraph.owner(l).canonical(Arrays.asList(0)).equals(TypeGraph.owner(r).canonical(Arrays.asList(0))))
                        throw new AssertionError("cyclic graph drift");
                }
                System.gc();
                if(TypeGraph.owner(type(left,0))!=pinned)throw new AssertionError("live graph identity changed");
                try{held.put("element","changed");throw new AssertionError("mutable graph");}catch(UnsupportedOperationException expected){}
            }
            if(!"p.Box".equals(detachedElement))throw new AssertionError("detached metadata changed on close");
            try{held.get("element");throw new AssertionError("closed archive view remained readable");}
            catch(IllegalStateException expected) {
                if(!"RECORD_ARCHIVE_CLOSED".equals(expected.getMessage()))throw expected;
            }
            Path invalid=create(root,"invalid-unread",true);
            try(ModelArchive.OpenModel ignored=ModelArchive.open(invalid,Json.sha(invalid),64L<<20,"app@MAIN")) {
                throw new AssertionError("unread final graph was not validated");
            }catch(java.io.IOException expected) {
                if(!expected.getMessage().equals("MODEL_GRAPH_INVALID"))throw expected;
            }
            System.out.println("two 50000-node model archives, cyclic roots, pinned identity and unread corruption passed");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception failure){throw new RuntimeException(failure);}});
            }
        }
    }
}
