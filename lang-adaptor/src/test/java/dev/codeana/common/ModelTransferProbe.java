package dev.codeana.common;
import java.nio.file.*;import java.util.*;
public final class ModelTransferProbe {
 static final List<String> SECTIONS=Arrays.asList("sources","declarations","observations","syntax","external","annotation_types","diagnostics");
 static Map<String,Object> model(){Map<String,Object> m=Json.object("version",ModelArchive.VERSION,"context","transfer");for(String s:SECTIONS)m.put(s,new ArrayList<Object>());return m;}
 static void closed(ModelArchive.RowSpool rows){try{rows.get(0);throw new AssertionError("consumed spool still readable");}catch(IllegalStateException expected){if(!"MODEL_SPOOL_CLOSED".equals(expected.getMessage()))throw expected;}}
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("transfer-proof-");
  try {
   List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();for(int i=0;i<10000;i++)nodes.add(Json.object("kind","ARRAY","complete",true,"component",i+1));nodes.add(Json.object("kind","INT","complete",true));TypeGraph graph=new TypeGraph(nodes);
   try(ModelArchive.RowSpool declarations=new ModelArchive.RowSpool(root,"declarations",8L<<20);ModelArchive.RowSpool observations=new ModelArchive.RowSpool(root,"observations",8L<<20)) {
    Map<String,Object> m=model();m.put("declarations",declarations);m.put("observations",observations);
    for(int i=0;i<100;i++){declarations.add(Json.object("type_model",graph.view(0),"ordinal",i));observations.add(Json.object("type_model",graph.view(0),"ordinal",i));}
    Path ordinary=root.resolve("ordinary.bin");ModelArchive.write(ordinary,m,8L<<20);
    if(!"ARRAY".equals(Json.map(Json.map(declarations.get(99)).get("type_model")).get("kind")))throw new AssertionError("ordinary write consumed caller rows");
    Path transfer=root.resolve("transfer.bin");ModelArchive.writeAndReleaseRows(transfer,m,8L<<20);closed(declarations);closed(observations);
    try(ModelArchive.OpenModel old=ModelArchive.open(ordinary,Json.sha(ordinary),8L<<20,"transfer");ModelArchive.OpenModel newer=ModelArchive.open(transfer,Json.sha(transfer),8L<<20,"transfer")) {
     for(String s:Arrays.asList("declarations","observations"))for(int i=0;i<100;i++) {
      Map<String,Object> a=Json.map(Json.map(Json.list(old.model().get(s)).get(i)).get("type_model")),b=Json.map(Json.map(Json.list(newer.model().get(s)).get(i)).get("type_model"));
      if(TypeGraph.reference(a)!=TypeGraph.reference(b) || TypeGraph.owner(a).size()!=TypeGraph.owner(b).size())throw new AssertionError("transfer changed root");
      if(i==0)for(int n=0;n<TypeGraph.owner(a).size();n++)if(!Json.write(TypeGraph.owner(a).node(n)).equals(Json.write(TypeGraph.owner(b).node(n))))throw new AssertionError("transfer changed node");
     }
    }
   }
   // The compiler retains cleanup ownership on budget/cancellation failure.
   for(boolean cancel:Arrays.asList(false,true)) {
    try(ModelArchive.RowSpool rows=new ModelArchive.RowSpool(root,"failure",8L<<20)) {
     Map<String,Object> m=model();m.put("declarations",rows);char[] payload=new char[1024];Arrays.fill(payload,'x');for(int i=0;i<100;i++)rows.add(Json.object("payload",new String(payload)));
     if(cancel)Thread.currentThread().interrupt();
     try{ModelArchive.writeAndReleaseRows(root.resolve(cancel?"cancel.bin":"limit.bin"),m,65536);throw new AssertionError("failure was swallowed");}
     catch(java.io.IOException|RuntimeException expected) {}
     finally{Thread.interrupted();}
    }
   }
   try(java.util.stream.Stream<Path> files=Files.list(root)){if(files.anyMatch(p->p.getFileName().toString().startsWith("model-rows-")))throw new AssertionError("compiler cleanup leaked row spool");}
  }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
  System.out.println("ordinary/consuming graph model equivalence, ownership and failed transfer cleanup: passed");
 }
}
