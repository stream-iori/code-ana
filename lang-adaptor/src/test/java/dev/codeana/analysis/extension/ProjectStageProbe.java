package dev.codeana.analysis.extension;
import dev.codeana.common.Json; import java.util.*;
/** Keep Java-only project analysis and conservative custom-extension behavior. */
public final class ProjectStageProbe {
 public static void main(String[] args) throws Exception {
  Map<String,Object> c=Json.object("spring",true,"spring_xml",false,"sofa",true,"lombok","off","resources",List.of(),"sources",List.of(),"world","w","context","x@MAIN");
  Map<String,Object> model=Json.object("declarations",List.of(Json.object("signature","Api")),"external",List.of(),"annotation_types",List.of(),"syntax",List.of("original tree"),"observations",List.of("original observation"),"diagnostics",List.of("original diagnostic"));
  ExtensionRunner builtins=new ExtensionRunner(c,BuiltinExtensions.REGISTRY);
  if(!builtins.hasProjectStages()) throw new AssertionError("Java DI stage lost");
  Map<String,Object> projection=builtins.projectModel(model);
  if(projection.get("declarations")!=model.get("declarations") || projection.get("annotation_types")!=model.get("annotation_types")) throw new AssertionError("header evidence lost");
  for(String section:List.of("syntax","observations","diagnostics"))
   if(!Json.list(projection.get(section)).isEmpty()) throw new AssertionError("unused body retained");
  if(Json.list(model.get("syntax")).isEmpty()) throw new AssertionError("original model changed");
  c.put("spring",false);
  if(new ExtensionRunner(c,BuiltinExtensions.REGISTRY).hasProjectStages()) throw new AssertionError("empty XML-only stage retained");
  int[] called={0}; ProjectResourceAnalyzer legacy=new ProjectResourceAnalyzer(){public String id(){return "legacy";} public Set<String> resourceKinds(){return Set.of("xml");} public void analyzeResources(ExtensionContext x,List<ProjectResource> r,FactSink out){called[0]++; if(!x.model().containsKey("syntax")) throw new AssertionError("legacy model narrowed");}};
  ExtensionRunner run=new ExtensionRunner(c,new ExtensionRegistry(List.of(legacy)));
  if(!run.hasProjectStages() || run.projectModel(model)!=model) throw new AssertionError("legacy empty callback/full model lost");
  run.analyzeProject(model,List.of());
  if(called[0]!=1) throw new AssertionError("legacy callback not called");
  c.put("resources",List.of(Json.object("unit",Json.object("path","beans.xml"))));
  if(!new ExtensionRunner(c,BuiltinExtensions.REGISTRY).hasProjectStages()) throw new AssertionError("nonempty resource stage lost");
  System.out.println("Java DI/header evidence retained; XML-only empty stage skipped; legacy full model/empty callback retained");
 }
}