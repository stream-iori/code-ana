package dev.codeana.analysis.extension;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import java.util.*;

/** Pending overlays must isolate mutable headers while sharing immutable compiler types. */
public final class OverlaySharingProbe {
 public static void main(String[] args) {
  Map<String,Object> type=Json.object("version",2,"kind","DECLARED","element","Leaf","complete",true);
  for(int depth=0;depth<35;depth++)type=Json.object("version",2,"kind","DECLARED","element","Box","complete",true,"arguments",List.of(type));
  Map<String,Object> shared=ModelArchive.freezeType(type);
  Map<String,Object> original=Json.object("kind","declaration","signature","Owner","parent","","name","Owner","source","Owner.java","start",0,"end",1,"generated",false,"attributes",Json.object("element_kind","CLASS"));
  List<Object> declarations=new ArrayList<>();declarations.add(original);
  Map<String,Object> model=Json.object("declarations",declarations);
  ModelOverlay overlay=new ModelOverlay();
  int count=8000;
  List<Object> notes=new ArrayList<>(List.of("original"));
  for(int i=0;i<count;i++) {
   Map<String,Object> header=Json.object("kind","declaration","signature","Owner#f"+i,"parent","Owner","name","f"+i,"source","Owner.java","start",-1,"end",-1,"generated",true,"attributes",Json.object("element_kind","FIELD","generated_from","Owner","body_available",false,"type_model",shared,"notes",notes));
   overlay.add(header);
  }
  notes.add("producer-edited-after-add");
  overlay.commit(model,"sharing-proof",20000);
  if(declarations.size()!=count+1)throw new AssertionError("lost declarations");
  for(int i=1;i<declarations.size();i++) {
   Map<String,Object> attrs=Json.map(Json.map(declarations.get(i)).get("attributes"));
   if(attrs.get("type_model")!=shared)throw new AssertionError("immutable type expanded or copied");
   if(Json.list(attrs.get("notes")).size()!=1)throw new AssertionError("pending header aliases producer");
   try {Json.map(attrs.get("type_model")).put("element","Edited");throw new AssertionError("mutable compiler evidence");}catch(UnsupportedOperationException expected){}
  }
  Map<String,Object> first=Json.map(Json.map(declarations.get(1)).get("attributes"));
  Json.list(first.get("notes")).add("consumer-edit");
  if(Json.list(Json.map(Json.map(declarations.get(2)).get("attributes")).get("notes")).size()!=1)throw new AssertionError("headers alias each other");
  if(Json.map(original.get("attributes")).containsKey("type_model"))throw new AssertionError("original header changed");
  if(!"Box".equals(shared.get("element")))throw new AssertionError("compiler evidence changed");
  System.out.println("8000 generated headers / 35 generic levels: immutable compiler type shared, mutable headers isolated, complete overlay committed under 64 MiB");
 }
}
