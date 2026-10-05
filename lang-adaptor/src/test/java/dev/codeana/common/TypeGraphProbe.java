package dev.codeana.common;
import java.util.*;
public final class TypeGraphProbe {
 static Map<String,Object> node(String kind,Object... data) { Map<String,Object> n=Json.object("kind",kind,"complete",true);for(int i=0;i<data.length;i+=2)n.put(data[i].toString(),data[i+1]);return n; }
 static void rejected(Runnable task) { try {task.run();throw new AssertionError("accepted invalid graph");}catch(IllegalArgumentException expected){} }
 public static void main(String[] ignored) {
  List<Map<String,Object>> input=new ArrayList<Map<String,Object>>();
  input.add(node("TYPEVAR","owner","Box","name","T","upper",1,"lower",2));
  input.add(node("DECLARED","element","java.lang.Comparable","arguments",Arrays.asList(0),"enclosing",-1));
  input.add(node("NULL"));
  TypeGraph graph=new TypeGraph(input);input.clear();
  if(graph.size()!=3 || !Boolean.TRUE.equals(Json.map(Json.list(Json.map(graph.view(0).get("upper")).get("arguments")).get(0)).get("recursive")))throw new AssertionError("recursive graph");
  TypeGraph rebased=graph.rebaseOwners(Map.of(new TypeGraph.VariableIdentity("Box","T"),"BoxBuilder"));
  if(!"BoxBuilder".equals(rebased.node(0).get("owner")) || !"Box".equals(graph.node(0).get("owner")))throw new AssertionError("owner rebase aliases input");
  if(rebased.node(1)!=graph.node(1) || !Objects.equals(rebased.node(0).get("lower"),2))throw new AssertionError("edges or unchanged nodes copied");
  if(!Boolean.TRUE.equals(Json.map(Json.list(Json.map(rebased.view(0).get("upper")).get("arguments")).get(0)).get("recursive")))throw new AssertionError("rebased cycle lost");
  if(graph.rebaseOwners(Map.of(new TypeGraph.VariableIdentity("Other","T"),"Builder"))!=graph)throw new AssertionError("same-name variable captured");
  try {rebased.node(0).put("owner","Changed");throw new AssertionError("mutable rebase");}catch(UnsupportedOperationException expected){}
  graph.validateReference(-1);rejected(()->graph.view(-1));rejected(()->graph.validateReference(-2));
  rejected(()->new TypeGraph(Arrays.asList(node("ARRAY","component",1))));
  rejected(()->new TypeGraph(Arrays.asList(node("ARRAY","component",0.5))));
  rejected(()->new TypeGraph(Arrays.asList(node("DECLARED","arguments",Arrays.asList(-1)))));
  rejected(()->new TypeGraph(Arrays.asList(node("ARRAY"))));
  rejected(()->new TypeGraph(Arrays.asList(node("ARRAY","component",1),Json.object("kind","ERROR","complete",false))));
  rejected(()->new TypeGraph(Arrays.asList(node("TYPEVAR","upper",0,"lower",-1))));
  rejected(()->new TypeGraph(Arrays.asList(node("ERROR"))));
  rejected(()->new TypeGraph(Arrays.asList(node("INT","component",0))));
  rejected(()->new TypeGraph(Arrays.asList(node("not_a_type"))));
  rejected(()->new TypeGraph(Arrays.asList(node("INT","constant",new java.util.concurrent.atomic.AtomicInteger(1)))));
  rejected(()->new TypeGraph(Arrays.asList(node("INT","constant",Double.NaN))));
  rejected(()->new TypeGraph(Arrays.asList(node("INT","constant",Float.POSITIVE_INFINITY))));
  rejected(()->new TypeGraph(Arrays.asList(node("ARRAY","component",new java.util.concurrent.atomic.AtomicInteger(0)))));
  try {graph.node(0).put("name","X");throw new AssertionError("mutable");}catch(UnsupportedOperationException good){}
  String json=Json.write(graph.encoded(Arrays.asList(0,-1)));
  TypeGraph decoded=TypeGraph.decode(Json.map(Json.parse(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  if(!json.equals(Json.write(decoded.encoded(Arrays.asList(0,-1)))))throw new AssertionError("round trip");
  List<Map<String,Object>> deep=new ArrayList<Map<String,Object>>();
  for(int i=0;i<1024;i++)deep.add(node("ARRAY","component",i+1));deep.add(node("INT"));
  TypeGraph ladder=new TypeGraph(deep);String wire=Json.write(ladder.encoded(Arrays.asList(0)));
  if(TypeGraph.decode(Json.map(Json.parse(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).size()!=1025)throw new AssertionError("graph flattened");
  Map<String,Object> current=ladder.view(0);for(int i=0;i<129;i++)current=Json.map(current.get("component"));
  if(!"TYPE_GRAPH_VIEW_DEPTH_BUDGET".equals(current.get("reason")))throw new AssertionError("view budget");
  TypeGraph.Builder builder=new TypeGraph.Builder();
  int first=builder.reserve(node("TYPEVAR","name","T")),second=builder.reserve(node("DECLARED","element","Bound"));
  builder.edit(first).put("upper",second);builder.edit(second).put("arguments",Arrays.asList(first));
  builder.edit(first).put("complete",false);
  if(!Boolean.FALSE.equals(builder.graph().node(second).get("complete")))throw new AssertionError("cycle completeness");
  int discarded=builder.reserve(node("ARRAY"));builder.edit(discarded).put("component",first);builder.rollback(2);
  if(builder.graph().size()!=2)throw new AssertionError("rollback");
  if(builder.graph().isSealed())throw new AssertionError("live graph marked immutable");
  try {builder.graph().encoded(Arrays.asList(first));throw new AssertionError("published live graph");}catch(IllegalStateException expected){}
  try {builder.graph().canonical(Arrays.asList(first));throw new AssertionError("fingerprinted live graph");}catch(IllegalStateException expected){}
  TypeGraph.Builder retained=new TypeGraph.Builder();
  int leaf=retained.reserve(node("INT")),array=retained.reserve(node("ARRAY"));retained.edit(array).put("component",leaf);
  int suffix=retained.reserve(node("INT"));retained.edit(array).put("component",suffix);
  rejected(()->retained.rollback(2));
  if(retained.graph().size()!=3)throw new AssertionError("invalid rollback mutated state");
  retained.edit(array).put("component",leaf);retained.rollback(2);retained.seal();
  builder.seal();if(!builder.graph().isSealed())throw new AssertionError("sealed graph still live");try {builder.edit(first).put("complete",true);throw new AssertionError("unsealed graph");}catch(IllegalStateException expected){}
  System.out.println("cyclic/forward/absent/invalid/immutable/deep/round-trip graph checks passed; builder cycle completeness and rollback passed");
 }
}
