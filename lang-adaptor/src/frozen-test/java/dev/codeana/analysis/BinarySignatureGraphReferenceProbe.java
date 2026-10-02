package dev.codeana.analysis;
import dev.codeana.common.*;
import com.anatomist.core.asmsolver.*;
import com.anatomist.core.java.*;
import org.objectweb.asm.*;
import java.util.*;
public final class BinarySignatureGraphReferenceProbe {
 static Object reference(JavaTypes g,int id,Set<Integer> active){
  if(id<0)return null;JavaType t=g.get(id);String kind=t.kind().name();
  if(!active.add(id))return Json.object("cycle",kind,"name",t.name());
  try {Map<String,Object> out=Json.object("kind",kind);
   if(kind.equals("DECLARED")) {out.put("name",t.name());out.put("enclosing",reference(g,t.enclosing(),active));out.put("arguments",refs(g,t.arguments(),active));}
   if(kind.equals("TYPEVAR")){out.put("name",t.name());out.put("bounds",refs(g,t.upperBounds(),active));}
   if(kind.equals("WILDCARD")){out.put("extends",t.upperBounds().isEmpty()?null:reference(g,t.upperBounds().getFirst(),active));out.put("super",reference(g,t.lowerBound(),active));}
   if(kind.equals("ARRAY"))out.put("component",reference(g,t.component(),active));
   if(kind.equals("EXECUTABLE")){out.put("parameters",refs(g,t.parameters(),active));out.put("return",reference(g,t.returns(),active));out.put("throws",refs(g,t.exceptions(),active));}
   return out;
  }finally{active.remove(id);}
 }
 static List<Object> refs(JavaTypes g,List<Integer> ids,Set<Integer>a){return ids.stream().map(i->reference(g,i,a)).toList();}
 static Object product(TypeGraph g,int id,Set<Integer>active){
  if(id<0)return null;Map<String,Object> t=g.node(id);String kind=(String)t.get("kind");
  if(!active.add(id))return Json.object("cycle",kind,"name",t.get("name"));
  try{Map<String,Object>out=Json.object("kind",kind);
   if(kind.equals("DECLARED")){out.put("name",((String)t.get("element")).replace('$','.'));out.put("enclosing",product(g,edge(t,"enclosing"),active));out.put("arguments",products(g,t,"arguments",active));}
   if(kind.equals("TYPEVAR")){out.put("name",t.get("name"));int upper=edge(t,"upper");if(upper<0)out.put("bounds",List.of());else{Map<String,Object>u=g.node(upper);out.put("bounds",u.get("kind").equals("INTERSECTION")?products(g,u,"bounds",active):List.of(product(g,upper,active)));}}
   if(kind.equals("WILDCARD")){out.put("extends",product(g,edge(t,"extends"),active));out.put("super",product(g,edge(t,"super"),active));}
   if(kind.equals("ARRAY"))out.put("component",product(g,edge(t,"component"),active));
   if(kind.equals("EXECUTABLE")){out.put("parameters",products(g,t,"parameters",active));out.put("return",product(g,edge(t,"return"),active));out.put("throws",products(g,t,"throws",active));}
   return out;
  }finally{active.remove(id);}
 }
 static int edge(Map<String,Object>t,String k){Object v=t.get(k);return v instanceof Number n?n.intValue():-1;}
 static List<Object>products(TypeGraph g,Map<String,Object>t,String k,Set<Integer>a){return Json.list(t.get(k)).stream().map(v->product(g,((Number)v).intValue(),a)).toList();}
 static void check(String name,String classSig,String signature,String descriptor){
  ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC,"Box",classSig,"java/lang/Object",null);
  writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_ABSTRACT,"read",descriptor,signature,null).visitEnd();writer.visitEnd();
  JavaBinaryType binary=JavaBinaryReader.read(writer.toByteArray());JavaBinaryType.Method m=binary.methods().getFirst();
  JavaBinaryTypes.MethodShape ref=JavaBinaryTypes.method(binary,m);
  BinarySignatureGraph own=new BinarySignatureGraph("Box",classSig,"java.lang.Object",List.of(),Map.of());own.method("Box#read()",signature,descriptor);
  Map<String,Object> model=Json.map(Json.map(Json.map(own.finish().get("methods")).get("Box#read()")).get("type_model"));
  Object left=product(TypeGraph.owner(model),TypeGraph.reference(model),new HashSet<>()),right=reference(ref.types(),ref.executable(),new HashSet<>());
  if(!left.equals(right))throw new AssertionError(name+"\nPRODUCT "+Json.write(left)+"\nREFERENCE "+Json.write(right));
  System.out.println(name+" equal");
 }
 public static void main(String[]args){
  check("shadowed-method-formal","<T:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;","<T:Ljava/lang/Number;>(TT;)TT;^Ljava/lang/Exception;","(Ljava/lang/Number;)Ljava/lang/Number;");
  check("recursive-class-formal","<T:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;","()TT;","()Ljava/lang/Comparable;");
  check("intersection-method-formal",null,"<T:Ljava/lang/Number;:Ljava/lang/Comparable<TT;>;>(TT;)TT;","(Ljava/lang/Number;)Ljava/lang/Number;");
  check("wildcards-arrays-primitives",null,"(Ljava/util/List<+Ljava/lang/Number;>;Ljava/util/List<-Ljava/lang/String;>;[I)Ljava/util/List<*>;","(Ljava/util/List;Ljava/util/List;[I)Ljava/util/List;");
  check("unavailable-formal",null,"()TT;","()Ljava/lang/Object;");
  String deep="Ljava/lang/String;";for(int i=0;i<50;i++)deep="Ljava/util/List<"+deep+">;";check("fifty-level-signature",null,"()"+deep,"()Ljava/util/List;");
 }
}
