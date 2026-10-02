package dev.codeana.compiler;
import dev.codeana.common.*;
import com.sun.source.util.*;
import java.lang.reflect.*;
import java.net.URI;
import java.util.*;
import javax.tools.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Frozen worker class runs in the same compiler context; its type nodes are never product-derived. */
public final class CompilerTypeGraphProbe {
 static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
 static Map<String,Object> reference(Object value)throws Exception {
  String kind=(String)field(value,"kind");Map<String,Object> out=Json.object("kind",kind,"complete",!kind.equals("ERROR"));
  if(kind.equals("ARRAY"))out.put("component",field(value,"component"));
  if(kind.equals("DECLARED")||kind.equals("ERROR")){out.put("element",((String)field(value,"binaryName")).isEmpty()?field(value,"name"):field(value,"binaryName"));out.put("enclosing",field(value,"enclosing"));out.put("arguments",field(value,"arguments"));}
  if(kind.equals("TYPEVAR")){out.put("name",field(value,"name"));out.put("upper",((List<?>)field(value,"upper")).get(0));out.put("lower",field(value,"lower"));}
  if(kind.equals("WILDCARD")){List<?> upper=(List<?>)field(value,"upper");out.put("extends",upper.isEmpty()?-1:upper.get(0));out.put("super",field(value,"lower"));}
  if(kind.equals("INTERSECTION"))out.put("bounds",field(value,"upper"));
  if(kind.equals("UNION"))out.put("alternatives",field(value,"arguments"));
  if(kind.equals("EXECUTABLE")){out.put("receiver",field(value,"enclosing"));out.put("parameters",field(value,"parameters"));out.put("return",field(value,"returns"));out.put("throws",field(value,"exceptions"));out.put("type_variables",field(value,"arguments"));}
  return out;
 }
 static void compare(TypeGraph product,List<Map<String,Object>> reference,int a,int b)throws Exception {
  ArrayDeque<int[]> pending=new ArrayDeque<int[]>();pending.add(new int[]{a,b});Set<String> visited=new HashSet<String>();
  Set<String> singles=new HashSet<String>(Arrays.asList("enclosing","component","extends","super","upper","lower","return","receiver"));
  Set<String> lists=new HashSet<String>(Arrays.asList("arguments","bounds","alternatives","parameters","throws","type_variables"));
  while(!pending.isEmpty()) {
   int[] pair=pending.removeFirst();if(!visited.add(pair[0]+":"+pair[1]))continue;
   if(pair[0]==-1||pair[1]==-1){if(pair[0]!=pair[1])throw new AssertionError("absent type mismatch");continue;}
   Map<String,Object> left=product.node(pair[0]),right=reference.get(pair[1]);
   for(Map.Entry<String,Object> entry:right.entrySet()) {
    String key=entry.getKey();if(key.equals("complete"))continue;
    Object wanted=entry.getValue(),actual=left.get(key);
    if(singles.contains(key))pending.add(new int[]{((Number)actual).intValue(),((Number)wanted).intValue()});
    else if(lists.contains(key)) {
     List<Object> x=Json.list(actual),y=Json.list(wanted);if(x.size()!=y.size())throw new AssertionError("edge count "+key);
     for(int i=0;i<x.size();i++)pending.add(new int[]{((Number)x.get(i)).intValue(),((Number)y.get(i)).intValue()});
    }else if(!Objects.equals(actual,wanted))throw new AssertionError("node "+pair[0]+" key "+key+" "+actual+" != "+wanted);
   }
  }
 }
 public static void main(String[] args)throws Exception {
  String nested="Leaf";for(int i=0;i<80;i++)nested="Box<"+nested+">";
  final String source="class Leaf {} class Box<T> {} class Outer<T extends Comparable<T> & java.io.Serializable>{class Inner<U>{T inherited;U local;}} class Use<X extends Comparable<X>> {"+nested+" deep;Outer<LeafHolder>.Inner<? super Leaf[]> nested;X recursive;Missing absent;<T extends Comparable<T>> T call(T[] values)throws java.io.IOException{return values[0];}} class LeafHolder implements Comparable<LeafHolder>,java.io.Serializable{public int compareTo(LeafHolder other){return 0;}}";
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<JavaFileObject>();
  StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8);
  try {
   JavaFileObject unit=new SimpleJavaFileObject(URI.create("string:///Types.java"),JavaFileObject.Kind.SOURCE){public CharSequence getCharContent(boolean ignore){return source;}};
   JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,Arrays.asList("-proc:none","-source","8","-target","8"),null,Arrays.asList(unit));
   Iterable<? extends com.sun.source.tree.CompilationUnitTree> parsed=task.parse();task.analyze();List<Element> elements=new ArrayList<Element>();Trees trees=Trees.instance(task);for(com.sun.source.tree.CompilationUnitTree ast:parsed)for(com.sun.source.tree.Tree declaration:ast.getTypeDecls()){Element e=trees.getElement(new TreePath(new TreePath(ast),declaration));if(e!=null)elements.add(e);}
   CompilerTypeGraph product=new CompilerTypeGraph(task.getElements(),type->Collections.<String,Object>emptyMap());
   Class<?> clazz=Class.forName("com.anatomist.worker.WorkerTypes");Constructor<?> construct=clazz.getDeclaredConstructor(javax.lang.model.util.Elements.class);construct.setAccessible(true);Object frozen=construct.newInstance(task.getElements());
   Method add=clazz.getDeclaredMethod("add",TypeMirror.class);add.setAccessible(true);
   List<int[]> roots=new ArrayList<int[]>();ArrayDeque<Element> queue=new ArrayDeque<Element>();for(Element e:elements)queue.add(e);
   while(!queue.isEmpty()) {
    Element element=queue.removeFirst();TypeMirror type=element.asType();roots.add(new int[]{product.add(type),((Integer)add.invoke(frozen,type)).intValue()});
    for(Element child:element.getEnclosedElements())if(child instanceof TypeElement || child instanceof VariableElement || child instanceof ExecutableElement)queue.add(child);
   }
   List<?> entries=(List<?>)field(frozen,"entries");List<Map<String,Object>> original=new ArrayList<Map<String,Object>>();for(Object entry:entries)original.add(reference(entry));
   TypeGraph graph=product.snapshot();for(int[] root:roots)compare(graph,original,root[0],root[1]);
   String wire=Json.write(graph.encoded(Arrays.asList(roots.get(0)[0])));TypeGraph decoded=TypeGraph.decode(Json.map(Json.parse(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
   for(int[] root:roots)compare(decoded,original,root[0],root[1]);
   long errors=0;for(int i=0;i<graph.size();i++)if(!Boolean.TRUE.equals(graph.node(i).get("complete")))errors++;
   if(errors==0)throw new AssertionError("unknown did not propagate");
   System.out.println("frozen graph equal: "+roots.size()+" roots, "+graph.size()+" nodes, 80 generic levels; incomplete propagated to "+errors+" nodes");
  }finally{manager.close();}
 }
}
