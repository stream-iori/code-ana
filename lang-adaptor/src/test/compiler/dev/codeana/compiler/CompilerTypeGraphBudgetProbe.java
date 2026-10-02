package dev.codeana.compiler;
import dev.codeana.common.TypeGraph;
import java.util.*;
import java.lang.annotation.Annotation;
import javax.tools.ToolProvider;
import com.sun.source.util.JavacTask;
import javax.lang.model.type.*;
import javax.lang.model.element.AnnotationMirror;
/** A failed graph root must not poison healthy roots or cancellation. */
public final class CompilerTypeGraphBudgetProbe {
 static final class Array implements ArrayType {
  final TypeMirror component;
  Array(TypeMirror component){this.component=component;}
  public TypeMirror getComponentType(){return component;}
  public TypeKind getKind(){return TypeKind.ARRAY;}
  public <R,P> R accept(TypeVisitor<R,P> visitor,P argument){return visitor.visitArray(this,argument);}
  public List<? extends AnnotationMirror> getAnnotationMirrors(){return Collections.emptyList();}
  public <A extends Annotation>A getAnnotation(Class<A> kind){return null;}
  @SuppressWarnings("unchecked") public <A extends Annotation>A[] getAnnotationsByType(Class<A> kind){return (A[])java.lang.reflect.Array.newInstance(kind,0);}
  public String toString(){return component+"[]";}
 }
 public static void main(String[] args)throws Exception {
  JavacTask task=(JavacTask)ToolProvider.getSystemJavaCompiler().getTask(null,null,null,Arrays.asList("-proc:none"),null,Collections.emptyList());
  TypeMirror healthy=task.getTypes().getPrimitiveType(TypeKind.INT),excess=healthy;
  for(int i=0;i<131;i++)excess=new Array(excess);
  CompilerTypeGraph types=new CompilerTypeGraph(task.getElements(),type->Collections.emptyMap());
  try{types.add(excess);throw new AssertionError("accepted over-depth graph");}catch(CompilerTypeGraph.Budget expected){}
  if(types.add(healthy)!=0)throw new AssertionError("failed root leaked entries");
  Thread.currentThread().interrupt();
  try{types.add(healthy);throw new AssertionError("ignored cancellation");}catch(IllegalStateException expected){if(!"CANCELLED".equals(expected.getMessage()))throw expected;}finally{Thread.interrupted();}
  if(types.add(healthy)!=0)throw new AssertionError("cancellation poisoned healthy root");
  TypeGraph graph=types.snapshot();
  if(graph.size()!=1 || !Boolean.TRUE.equals(graph.node(0).get("complete")))throw new AssertionError("healthy graph incomplete");
  System.out.println("depth overflow rollback and cancellation preserve healthy graph roots: passed");
 }
}
