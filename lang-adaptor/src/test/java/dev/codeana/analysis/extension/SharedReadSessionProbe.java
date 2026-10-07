package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.*;

/** Append-only fact visibility and query-budget isolation across shared model indexes. */
public final class SharedReadSessionProbe {
    static Map<String,Object> declaration(String signature) {
        return Json.object("kind","declaration","parent","","id",signature,"signature",signature,"world","w",
                "source","A.java","generated",false,"owner",Json.object("world","w","kind","source","id","A.java","producer","test"),"attributes",Json.object("element_kind","CLASS"));
    }
    public static void main(String[] args) {
        var config=Json.object("world","w","sources",List.of(Json.object("path","A.java","publish",true)));
        var model=Json.object("sources",List.of(),"declarations",List.of(declaration("A")),
                "syntax",List.of(),"observations",List.of(),"external",List.of(),"annotation_types",List.of());
        var owner=new ExtensionContext(config,model,List.of(),List.of());
        var facts=new ArrayList<Object>();facts.add(declaration("A"));
        var first=owner.withFacts(facts);var index=first.frameworkIndex();
        var facet=new LinkedHashMap<>(declaration("facet"));facet.put("kind","facet");facts.add(facet);
        var second=owner.withFacts(facts);
        if(index!=second.frameworkIndex() || first.facts().size()!=1)
            throw new AssertionError("unchanged headers rebuilt or old fact view advanced");
        if(first.semanticModel()!=second.semanticModel()
                ||first.declaration("A").orElseThrow()!=second.declaration("A").orElseThrow())
            throw new AssertionError("model indexes not shared");
        if(first.javaModel()==second.javaModel())throw new AssertionError("closable navigation lease shared");
        if(first.annotations()==second.annotations())throw new AssertionError("analyzer budgets shared");
        first.annotations().firstOn(List.of(),Set.of("Missing"));
        if(second.annotations().counters().firstCalls()!=0)throw new AssertionError("query counters leaked");
        facts.add(declaration("B"));var third=owner.withFacts(facts);
        if(third.frameworkIndex()==index || third.frameworkIndex().declarations.get("B")==null
                ||index.declarations.get("B")!=null ||first.facts().size()!=1)
            throw new AssertionError("new header missing or old header snapshot changed");
        var empty=Json.object("sources",List.of(),"resources",List.of());
        if(!new ExtensionRunner(empty,new ExtensionRegistry(List.of())).headerOnlyContext())
            throw new AssertionError("empty publication not header-only");
        var legacy=new JavaModelAnalyzer() {
            public String id(){return "legacy";}
            public void analyze(ExtensionContext context,FactSink sink) {}
        };
        if(new ExtensionRunner(empty,new ExtensionRegistry(List.of(legacy))).headerOnlyContext())
            throw new AssertionError("legacy full-model analyzer narrowed");
        owner.closeModel();
        try {first.semanticModel().units();throw new AssertionError("shared model escaped stage");}
        catch(IllegalStateException expected) {}
        System.out.println("shared indexes, header snapshots, independent query budgets and legacy scope passed");
    }
}
