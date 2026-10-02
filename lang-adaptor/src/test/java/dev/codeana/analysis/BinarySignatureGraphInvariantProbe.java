package dev.codeana.analysis;
import dev.codeana.common.*;
import java.util.*;
public final class BinarySignatureGraphInvariantProbe {
 static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static Map<String,Object> shape(Map<String,Object> result,String key){return Json.map(Json.map(Json.map(result.get("methods")).get(key)).get("type_model"));}
 static void rejects(Runnable action,String reason){try{action.run();throw new AssertionError(reason);}catch(IllegalArgumentException expected){System.out.println(reason+" rejected "+expected.getMessage());}}
 public static void main(String[]args){
  BinarySignatureGraph outer=new BinarySignatureGraph("Outer","<T:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;","java.lang.Object",List.of(),Map.of());
  outer.field("Outer#value","TT;","Ljava/lang/Comparable;");outer.method("Outer#read()","<T:Ljava/lang/Number;>(TT;)TT;","(Ljava/lang/Number;)Ljava/lang/Number;");
  Map<String,Object> result=outer.finish();Map<String,Object> declaration=Json.map(result.get("declaration"));
  Map<String,Object> formal=Json.map(Json.list(declaration.get("type_parameters")).getFirst());Map<String,Object> classRoot=Json.map(formal.get("type_model"));
  Map<String,Object> method=shape(result,"Outer#read()");TypeGraph graph=TypeGraph.owner(method);int executable=TypeGraph.reference(method);
  int argument=((Number)Json.list(graph.node(executable).get("parameters")).getFirst()).intValue();int returns=((Number)graph.node(executable).get("return")).intValue();
  require(argument==returns,"method formal shared");require(graph.node(argument).get("owner").equals("Outer#read()"),"method owner");require(TypeGraph.reference(classRoot)!=argument,"shadowing separate identities");
  Map<String,Object> field=Json.map(Json.map(result.get("fields")).get("Outer#value"));require(TypeGraph.reference(field)==TypeGraph.reference(classRoot),"field class formal shared");
  BinarySignatureGraph inner=new BinarySignatureGraph("Outer$Inner",null,"java.lang.Object",List.of(),Map.of("T",formal));
  inner.field("Outer$Inner#value","TT;","Ljava/lang/Comparable;");inner.method("Outer$Inner#read()","(TT;)TT;","(Ljava/lang/Comparable;)Ljava/lang/Comparable;");
  Map<String,Object> nested=inner.finish();Map<String,Object> nestedField=Json.map(Json.map(nested.get("fields")).get("Outer$Inner#value"));TypeGraph nestedGraph=TypeGraph.owner(nestedField);int root=TypeGraph.reference(nestedField);
  require(nestedGraph.node(root).get("owner").equals("Outer"),"imported enclosing owner");int upper=((Number)nestedGraph.node(root).get("upper")).intValue();require(root==((Number)Json.list(nestedGraph.node(upper).get("arguments")).getFirst()).intValue(),"imported recursive identity");
  Map<String,Object> nestedMethod=shape(nested,"Outer$Inner#read()");require(root==((Number)Json.list(nestedGraph.node(TypeGraph.reference(nestedMethod)).get("parameters")).getFirst()).intValue(),"enclosing method/field shared");
  TypeEvidence.decode(TypeEvidence.encode(nested));System.out.println("class/method identity, imported enclosing recursion and public graph encoding passed");
  BinarySignatureGraph missing=new BinarySignatureGraph("Missing",null,"java.lang.Object",List.of(),Map.of());missing.field("Missing#value","Ljava/util/List<TT;>;","Ljava/util/List;");Map<String,Object> absent=Json.map(Json.map(missing.finish().get("fields")).get("Missing#value"));require(Boolean.FALSE.equals(absent.get("complete")),"unknown formal propagates incompleteness");Map<String,Object> unknown=Json.map(Json.list(absent.get("arguments")).getFirst());require(unknown.get("owner").equals("")&&unknown.get("upper")==null,"no invented owner or Object bound");System.out.println("unavailable formal is explicit and propagates");
  rejects(()->new BinarySignatureGraph("Bad",null,"java.lang.Object",List.of(),Map.of()).field("Bad#value","Ljava/lang/String;X","Ljava/lang/String;"),"trailing field signature");
  rejects(()->new BinarySignatureGraph("Bad",null,"java.lang.Object",List.of(),Map.of()).method("Bad#read()","(I)Ljava/lang/String;X","(I)Ljava/lang/String;"),"trailing method signature");
  String deep="Ljava/lang/String;";for(int i=0;i<65;i++)deep="Ljava/util/List<"+deep+">;";final String signature=deep;
  rejects(()->new BinarySignatureGraph("Bad",null,"java.lang.Object",List.of(),Map.of()).field("Bad#value",signature,"Ljava/util/List;"),"depth budget");
 }
}
