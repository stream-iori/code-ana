package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** Tests the real row/model codec with shared graphs, rather than a second serializer. */
public final class ModelGraphProbe {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("model-graph-");
        try {
            List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();
            for(int i=0;i<10000;i++)nodes.add(Json.object("kind","ARRAY","complete",true,"component",i+1));
            nodes.add(Json.object("kind","INT","complete",true));
            TypeGraph graph=new TypeGraph(nodes);
            String context="app@MAIN";
            Map<String,Object> model=Json.object("version",ModelArchive.VERSION,"context",context);
            for(String section:Arrays.asList("sources","declarations","observations","syntax","external","annotation_types","diagnostics"))
                model.put(section,new ArrayList<Object>());
            try(ModelArchive.RowSpool spool=new ModelArchive.RowSpool(root,"rows",8L<<20)) {
                for(int i=0;i<100;i++)spool.add(Json.object("attributes",Json.object("type_model",graph.view(0)),"ordinal",i));
                // Literal marker names are user data and cannot be interpreted as graph references.
                spool.add(Json.object("literal",Json.object("$graph",Arrays.asList(99,99))));
                spool.seal();model.put("declarations",spool);
                Path path=root.resolve("model.bin");
                long bytes=ModelArchive.write(path,model,8L<<20);
                if(bytes>3L<<20)throw new AssertionError("shared graph expanded per root: "+bytes);
                try(ModelArchive.OpenModel decoded=ModelArchive.open(path,Json.sha(path),8L<<20,context)) {
                    List<Object> declarations=Json.list(decoded.model().get("declarations"));
                    if(declarations.size()!=101)throw new AssertionError("model rows changed");
                    for(int i=0;i<100;i++) {
                        Map<String,Object> type=Json.map(Json.map(Json.map(declarations.get(i)).get("attributes")).get("type_model"));
                        if(!TypeGraph.isView(type) || TypeGraph.owner(type).size()!=10001)
                            throw new AssertionError("flat model graph lost");
                        if(!"ARRAY".equals(type.get("kind")))throw new AssertionError("root changed");
                    }
                    if(!Json.write(Json.map(declarations.get(100)).get("literal")).equals("{\"$graph\":[99,99]}"))
                        throw new AssertionError("literal graph marker interpreted");
                }
            }
            TypeGraph cycle=new TypeGraph(Arrays.asList(
                Json.object("kind","TYPEVAR","complete",true,"name","T","upper",1,"lower",2),
                Json.object("kind","DECLARED","complete",true,"element","Comparable","arguments",Arrays.asList(0)),
                Json.object("kind","NULL","complete",true)));
            model.put("declarations",Arrays.asList(Json.object("type_model",cycle.view(0))));
            Path cycleFile=root.resolve("cycle.bin");ModelArchive.write(cycleFile,model,65536);
            try(ModelArchive.OpenModel decoded=ModelArchive.open(cycleFile,Json.sha(cycleFile),65536,context)) {
                Map<String,Object> type=Json.map(Json.map(Json.list(decoded.model().get("declarations")).get(0)).get("type_model"));
                if(!Json.write(cycle.canonical(Arrays.asList(0))).equals(Json.write(TypeGraph.owner(type).canonical(Arrays.asList(TypeGraph.reference(type))))))
                    throw new AssertionError("cycle changed in model archive");
            }
            List<Object> valid;
            try(RecordArchive records=RecordArchive.open(cycleFile,65536,1000,Json.sha(cycleFile))) {
                valid=new ArrayList<Object>(records);
            }
            rejected(root,context,valid,"root",record->{
                if("row".equals(record.get("kind")))
                    Json.map(record.get("value")).put("type_model",Json.object("$graph",Arrays.asList(0,99)));
            });
            rejected(root,context,valid,"fractional-root",record->{
                if("row".equals(record.get("kind")))
                    Json.map(record.get("value")).put("type_model",Json.object("$graph",Arrays.asList(0,.5)));
            });
            rejected(root,context,valid,"dangling-child",record->{
                if("graph_node".equals(record.get("kind")) && Json.number(record,"id")==0)
                    Json.map(record.get("value")).put("upper",99);
            });
            rejected(root,context,valid,"fractional-node",record->{
                if("graph_node".equals(record.get("kind")) && Json.number(record,"id")==0)record.put("id",.5);
            });
            rejected(root,context,valid,"node-sequence",record->{
                if("graph_node".equals(record.get("kind")) && Json.number(record,"id")==0)record.put("id",1);
            });
            rejected(root,context,valid,"counts",record->{
                if("end".equals(record.get("kind")))record.put("graphs",Arrays.asList(99));
            });
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception failure){throw new RuntimeException(failure);}});
            }
        }
        System.out.println("shared flat graph model and RowSpool round trip under bounded archive: passed");
    }

    private static void rejected(Path root,String context,List<Object> valid,String name,
            java.util.function.Consumer<Map<String,Object>> mutation) throws Exception {
        Path file=root.resolve("invalid-"+name+".bin");
        try(RecordArchive records=RecordArchive.create(file,65536,1000)) {
            for(Object raw:valid) {
                Map<String,Object> copy=Json.map(Json.parse(Json.write(raw).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                mutation.accept(copy);records.add(copy);
            }
            records.seal();
        }
        for(boolean lazy:Arrays.asList(false,true)) {
            try {
                if(lazy)try(ModelArchive.OpenModel ignored=ModelArchive.open(file,Json.sha(file),65536,context)) {}
                else ModelArchive.read(file,Json.sha(file),65536,context);
                throw new AssertionError("accepted malformed model graph: "+name+", lazy="+lazy);
            }catch(java.io.IOException expected) {
                if(!expected.getMessage().startsWith("MODEL_GRAPH_"))throw expected;
            }
        }
    }
}
