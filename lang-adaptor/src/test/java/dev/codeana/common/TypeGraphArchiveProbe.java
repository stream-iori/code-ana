package dev.codeana.common;
import java.nio.file.*;
import java.util.*;
public final class TypeGraphArchiveProbe {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("flat-type-archive-");
  try {
   List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>();
   for(int i=0;i<10000;i++)rows.add(Json.object("kind","ARRAY","complete",true,"component",i+1));
   rows.add(Json.object("kind","INT","complete",true));
   TypeGraph graph=new TypeGraph(rows);Path path=root.resolve("graph.bin");
   long bytes=TypeGraphArchive.write(path,graph,Arrays.asList(0,-1,0),4L*1024*1024);
   TypeGraphArchive.Decoded decoded=TypeGraphArchive.read(path,Json.sha(path),4L*1024*1024);
   if(decoded.graph.size()!=10001 || !decoded.roots.equals(Arrays.asList(0,-1,0)))throw new AssertionError("graph roots/count");
   if(!Json.write(graph.canonical(Arrays.asList(0))).equals(Json.write(decoded.graph.canonical(Arrays.asList(0)))))throw new AssertionError("flat archive drift");
   List<Map<String,Object>> cycle=Arrays.asList(Json.object("kind","TYPEVAR","complete",true,"name","T","upper",1,"lower",2),Json.object("kind","DECLARED","complete",true,"element","Comparable","arguments",Arrays.asList(0),"enclosing",-1),Json.object("kind","NULL","complete",true));
   String original=Json.sha(path);
   try{TypeGraphArchive.write(path,graph,Arrays.asList(0),4L*1024*1024);throw new AssertionError("existing archive overwritten");}catch(java.nio.file.FileAlreadyExistsException expected){}
   if(!original.equals(Json.sha(path)))throw new AssertionError("existing archive changed");
   path=root.resolve("cycle.bin");graph=new TypeGraph(cycle);TypeGraphArchive.write(path,graph,Arrays.asList(0),65536);
   decoded=TypeGraphArchive.read(path,Json.sha(path),65536);
   if(!Json.write(graph.canonical(Arrays.asList(0))).equals(Json.write(decoded.graph.canonical(Arrays.asList(0)))))throw new AssertionError("forward/cycle lost");
   String checksum=Json.sha(path);byte[] corrupt=Files.readAllBytes(path);corrupt[corrupt.length-1]^=1;Files.write(path,corrupt);
   try{TypeGraphArchive.read(path,checksum,65536);throw new AssertionError("checksum ignored");}catch(java.io.IOException expected){}
   Thread.currentThread().interrupt();
   try{TypeGraphArchive.write(root.resolve("cancel.bin"),graph,Arrays.asList(0),65536);throw new AssertionError("cancel ignored");}catch(java.io.IOException expected){}finally{Thread.interrupted();}
   System.out.println("10001 flat nodes, repeated/absent roots, forward cycles, checksums and cancellation passed; bytes="+bytes);
  }finally{try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}}
 }
}
