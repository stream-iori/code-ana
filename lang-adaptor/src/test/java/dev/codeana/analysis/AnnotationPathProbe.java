package dev.codeana.analysis;
import dev.codeana.common.Json;
import java.util.*;

/** Repeated misses, mutable selectors, bounded cache and independent source budgets. */
public final class AnnotationPathProbe {
 private static Map<String,Object> annotation(String name,String value) {
  return Json.object("type",name,"effective",Json.object("value",value),"explicit",Json.object("value",value),"complete",true);
 }
 public static void main(String[] args) {
  Map<String,Object> use=annotation("Root","one");
  Map<String,Object> model=Json.object("annotation_types",List.of(Json.object("name","Root","complete",true,"annotations",List.of(),"members",List.of())),
    "declarations",List.of(Json.object("signature","A#f","source","A.java","attributes",Json.object("annotations",List.of(use))),
      Json.object("signature","B#f","source","B.java","attributes",Json.object("annotations",List.of(annotation("Root","two"))))),"external",List.of());
  ModelAnnotations matches=new ModelAnnotations(model);
  Set<String> roots=new HashSet<>(Set.of("Missing"));
  for(int i=0;i<70000;i++)if(matches.first("A#f",roots).isPresent())throw new AssertionError("negative path changed");
  if(matches.limited() || matches.counters().cacheHits()<69999)throw new AssertionError("repeated misses charged again");
  roots.add("Root");
  if(!"one".equals(matches.first("A#f",roots).orElseThrow().values().get("value")))throw new AssertionError("mutable selector polluted key");
  if(!"two".equals(matches.first("B#f",roots).orElseThrow().values().get("value")))throw new AssertionError("instance attributes cached");
  for(int i=0;i<5000;i++)matches.firstOn(List.of(annotation("Unknown"+i,"")),Set.of("Missing"));
  long visits=matches.counters().metaPathVisits();
  matches.first("A#f",Set.of("Missing"));
  if(matches.counters().metaPathVisits()==visits)throw new AssertionError("cache did not release old paths");
  for(int i=0;i<1000010;i++)matches.first("A#f",Set.of("Root"));
  if(!matches.limitedSources().equals(Set.of("A.java")))throw new AssertionError("budget source attribution lost");
  var healthy=matches.first("B#f",Set.of("Root")).orElseThrow();
  if(!healthy.complete() || !"two".equals(healthy.values().get("value")))throw new AssertionError("other source poisoned");
  System.out.println("annotation path cache/negative lookups/selectors/attributes/source budgets: passed");
 }
}
