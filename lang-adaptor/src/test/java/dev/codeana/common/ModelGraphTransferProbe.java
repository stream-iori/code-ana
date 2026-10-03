package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** Compiler spool transfer must not pin a second decoded copy of every graph. Java 8. */
public final class ModelGraphTransferProbe {
    private static final List<String> SECTIONS=Arrays.asList("sources","declarations","observations",
            "syntax","external","annotation_types","diagnostics");
    private static Map<String,Object> model(String context,Object rows) {
        Map<String,Object> model=Json.object("version",ModelArchive.VERSION,"context",context);
        for(String section:SECTIONS)model.put(section,section.equals("declarations")?rows:Collections.emptyList());
        return model;
    }
    private static TypeGraph graph(String name,int count) {
        List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();
        for(int i=0;i<count;i++)nodes.add(Json.object("kind","DECLARED","complete",true,
                "enclosing",-1,"arguments",Arrays.asList((i+1)%count),"element",name,
                "display",name,"version",3));
        return new TypeGraph(nodes);
    }
    private static Map<String,Object> type(Object row) {return Json.map(Json.map(row).get("type"));}
    private static void counts(Path path,int graphs,int nodes) throws Exception {
        try(RecordArchive records=RecordArchive.open(path,64L<<20,2000000,Json.sha(path))) {
            List<Object> counts=Json.list(Json.map(records.get(records.size()-1)).get("graphs"));
            if(counts.size()!=graphs)throw new AssertionError("graph identities drifted: "+counts.size());
            for(Object count:counts)if(((Number)count).intValue()!=nodes)throw new AssertionError("graph nodes lost");
        }
    }
    private static void transfer(Path root) throws Exception {
        Path output=root.resolve("transfer.bin");
        try(ModelArchive.RowSpool rows=new ModelArchive.RowSpool(root,"transfer",64L<<20)) {
            for(int g=0;g<500;g++) {
                TypeGraph graph=graph("p.Box"+g,150);
                rows.add(Json.object("ordinal",g,"type",graph.view(0),"last",graph.view(149)));
                rows.add(Json.object("ordinal",g,"type",graph.view(0),"last",graph.view(149)));
                rows.retireCompilerIdentities();
            }
            ModelArchive.writeAndReleaseRows(output,model("transfer",rows),64L<<20);
        }
        counts(output,500,150);
        try(ModelArchive.OpenModel opened=ModelArchive.open(output,Json.sha(output),64L<<20,"transfer")) {
            List<Object> rows=Json.list(opened.model().get("declarations"));
            if(rows.size()!=1000)throw new AssertionError("rows lost");
            for(int i=0;i<1000;i++) {
                Map<String,Object> type=type(rows.get(i));
                if(!("p.Box"+(i/2)).equals(type.get("element")))throw new AssertionError("graph alias collision");
                TypeGraph graph=TypeGraph.owner(type);
                if(graph.size()!=150 || ((Number)Json.list(graph.node(149).get("arguments")).get(0)).intValue()!=0)
                    throw new AssertionError("cycle changed");
            }
        }
    }
    private static void collectedIdentity(Path root) throws Exception {
        Path output=root.resolve("collected.bin");
        try(final ModelArchive.RowSpool a=new ModelArchive.RowSpool(root,"a");
            final ModelArchive.RowSpool b=new ModelArchive.RowSpool(root,"b")) {
            for(ModelArchive.RowSpool spool:Arrays.asList(a,b)) {
                TypeGraph graph=graph(spool==a?"p.Left":"p.Right",64);
                for(int i=0;i<12;i++)spool.add(Json.object("type",graph.view(0)));
                spool.seal();
            }
            List<Object> rows=new AbstractList<Object>() {
                public int size() {return 24;}
                public Object get(int i) {System.gc();return (i%2==0?a:b).get(i/2);}
            };
            ModelArchive.write(output,model("collected",rows),64L<<20);
        }
        counts(output,2,64);
        try(ModelArchive.OpenModel opened=ModelArchive.open(output,Json.sha(output),64L<<20,"collected")) {
            List<Object> rows=Json.list(opened.model().get("declarations"));
            for(int i=0;i<24;i++)if(!(i%2==0?"p.Left":"p.Right").equals(type(rows.get(i)).get("element")))
                throw new AssertionError("archive identities collided");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("model-transfer-");
        try {
            transfer(root);collectedIdentity(root);
            System.out.println("75000-node spool transfer, cyclic roots, collected identities and independent dictionaries passed");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception e){throw new RuntimeException(e);}});
            }
        }
    }
}
