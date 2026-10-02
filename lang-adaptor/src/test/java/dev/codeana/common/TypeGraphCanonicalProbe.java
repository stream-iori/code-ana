package dev.codeana.common;
import java.util.*;
public final class TypeGraphCanonicalProbe {
 static Map<String,Object> node(String kind,Object... data) {Map<String,Object> n=Json.object("kind",kind,"complete",true);for(int i=0;i<data.length;i+=2)n.put(data[i].toString(),data[i+1]);return n;}
 public static void main(String[] args) {
  TypeGraph a=new TypeGraph(Arrays.asList(node("TYPEVAR","name","T","owner","Box","upper",1,"lower",2),node("DECLARED","element","Comparable","arguments",Arrays.asList(0),"enclosing",-1),node("NULL")));
  TypeGraph b=new TypeGraph(Arrays.asList(node("INT"),node("NULL"),node("DECLARED","enclosing",-1,"arguments",Arrays.asList(3),"element","Comparable"),node("TYPEVAR","lower",1,"upper",2,"owner","Box","name","T")));
  String first=Json.write(a.canonical(Arrays.asList(0,-1,0))),second=Json.write(b.canonical(Arrays.asList(3,-1,3)));
  if(!first.equals(second))throw new AssertionError("visit order or unreachable nodes changed public closure");
  Map<String,Object> payload=a.canonical(Arrays.asList(0));TypeGraph roundtrip=TypeGraph.decode(payload);
  if(!Json.write(roundtrip.canonical(Arrays.asList(0))).equals(Json.write(payload)))throw new AssertionError("canonical round trip");
  if(!TypeGraph.isView(roundtrip.view(0)) || !Boolean.TRUE.equals(Json.map(Json.list(Json.map(roundtrip.view(0).get("upper")).get("arguments")).get(0)).get("recursive")))throw new AssertionError("closure lost recursive edges");
  if(Json.list(b.canonical(Arrays.asList(-1)).get("nodes")).size()!=0)throw new AssertionError("absent root imported types");
  System.out.println("per-fact graph closure stable across node permutations, unreachable inputs, cycles, repeated and absent roots; round trip passed");
 }
}
