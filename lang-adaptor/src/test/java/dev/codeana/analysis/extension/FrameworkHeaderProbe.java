package dev.codeana.analysis.extension;

import dev.codeana.analysis.FrameworkAnalysis;
import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;
import java.util.*;

/** Negative framework scans must retain headers through immutable extension views. */
public final class FrameworkHeaderProbe {
    private static Map<String,Object> fact(int index, String kind, String world) {
        return Json.object("id","d"+index,"kind",kind,"signature","Bean","source","src/Use.java",
                "parent","","target","","name","Bean","relation","",
                "owner",Json.object("world",world),
                "attributes",Json.object("receiver_type","Missing","generated",false));
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> config=Json.object("world","w","context","c","lombok","model","spring",false,
                "sofa",false,"spring_xml",false,"resources",List.of(),"fact_limit",300000,
                "sources",List.of(Json.object("path","src/Use.java","world","w","publish",true)));
        Map<String,Object> model=Json.object("declarations",List.of(),"observations",List.of(),
                "annotation_types",List.of(),"external",List.of(),"sources",List.of(),"syntax",List.of(),"diagnostics",List.of());
        String[] kinds={"declaration","call_target","call_site"};
        FactHeaders.View large=new FactHeaders.View() {
            public int size(){return 200000;}
            public Object get(int index){throw new AssertionError("UNUSED_FRAMEWORK_GRAPH_DECODED");}
            public String factKind(int index){return kinds[index%3];}
            public Map<String,Object> factHeader(int index){return FactHeaders.project(fact(index,factKind(index),"w"));}
        };
        List<Object> output=new ExtensionRunner(config,BuiltinExtensions.REGISTRY).analyzeJava(model,large);
        if(output.size()!=large.size()) throw new AssertionError("FRAMEWORK_DROPPED_CORE");
        Map<String,Object> spring=new LinkedHashMap<>(config);
        spring.put("spring",true);
        if(!FrameworkAnalysis.extract(spring,model,large,"java").isEmpty())
            throw new AssertionError("UNEXPECTED_SPRING_FACTS");
        ExtensionContext context=new ExtensionContext(config,model,large,List.of());
        if(!(context.facts() instanceof FactHeaders) || !FactHeaders.kind(context.facts(),1).equals("call_target"))
            throw new AssertionError("IMMUTABLE_VIEW_LOST_METADATA");
        try {context.facts().add(Map.of());throw new AssertionError("MUTABLE_FACT_VIEW");}
        catch(UnsupportedOperationException expected) {}
        Map<String,Object> declaration=Json.object("source","src/Use.java","signature","Bean",
                "parent","", "name","Bean", "attributes",Json.object("element_kind","CLASS","model_extensions",Json.object("lombok-model-v1",
                        Json.object("summary",Json.object("coverage","complete")))));
        Map<String,Object> nonempty=new LinkedHashMap<>(model);
        nonempty.put("declarations",List.of(declaration));
        List<Object> rows=List.of(fact(0,"external_symbol","w"),fact(1,"declaration","other"),
                fact(2,"declaration","w"),fact(3,"declaration","w"),fact(4,"external_symbol","w"));
        List<Integer> reads=new ArrayList<>();
        FactHeaders.View headers=new FactHeaders.View() {
            public int size(){return rows.size();}
            public Object get(int index){reads.add(index);return rows.get(index);}
            public Map<String,Object> factHeader(int index){return FactHeaders.project(rows.get(index));}
        };
        List<Object> baseline=FrameworkAnalysis.extract(config,nonempty,rows,"lombok");
        List<Object> selected=FrameworkAnalysis.extract(config,nonempty,headers,"lombok");
        if(!Json.write(baseline).equals(Json.write(selected)) || selected.size()!=2
                || !"d2".equals(Json.map(selected.getFirst()).get("parent")) || !reads.equals(List.of(2)))
            throw new AssertionError("DECLARATION_RANK_OR_LAZY_SELECTION_CHANGED: "+reads);
        // A file callback must preserve the old per-source IDs and global target ranking.
        Map<String,Object> multi = new LinkedHashMap<>(config);
        multi.put("sources",List.of(Json.object("path","src/Use.java","world","w","publish",true),
                Json.object("path","src/Other.java","world","w","publish",true)));
        Map<String,Object> other = Json.object("source","src/Other.java","signature","Other",
                "parent","", "name","Other", "attributes",declaration.get("attributes"));
        Map<String,Object> multiModel = new LinkedHashMap<>(model);
        multiModel.put("declarations",List.of(declaration,other));
        Map<String,Object> otherFact = new LinkedHashMap<>(fact(5,"declaration","w"));
        otherFact.put("signature","Other"); otherFact.put("source","src/Other.java");
        List<Object> multiCore = new ArrayList<>(rows); multiCore.add(otherFact);
        List<Object> whole = FrameworkAnalysis.extract(multi,multiModel,multiCore,"lombok");
        List<Object> files = new ArrayList<>();
        var session = new FrameworkAnalysis.UnitSession(multi,multiModel,multiCore,"lombok");
        try (var environment = new dev.codeana.analysis.model.JavaSemanticModel(multiModel)) {
            for (var unit : environment.units()) {
                List<Object> selectedFile = session.extract(unit.evidence(),unit.source());
                if (selectedFile.size()!=2 || selectedFile.stream().map(Json::map)
                        .anyMatch(f -> !unit.source().equals(f.get("source"))))
                    throw new AssertionError("FRAMEWORK_UNIT_OWNERSHIP");
                files.addAll(selectedFile);
            }
        }
        Comparator<Object> byId = Comparator.comparing(raw -> Json.string(Json.map(raw),"id"));
        whole.sort(byId); files.sort(byId);
        if (!Json.write(whole).equals(Json.write(files))) throw new AssertionError("FRAMEWORK_UNIT_FACT_DRIFT");
        ExtensionContext multiContext = new ExtensionContext(multi,multiModel,multiCore,List.of());
        ExtensionPoint producer = new ExtensionPoint() { public String id(){return "probe";} public String producer(){return "probe";} };
        FrameworkFacts fileFacts = new FrameworkFacts(producer,multiContext,new FactSink(),"src/Other.java");
        if (!"d2".equals(fileFacts.declarations.get("Bean").get("id")))
            throw new AssertionError("FRAMEWORK_UNIT_LOST_CONTEXT_TARGET");
        List<String> signatures = new ArrayList<>();
        fileFacts.published().forEach(f -> signatures.add(Json.string(f,"signature")));
        if (!signatures.equals(List.of("Other")) || multiContext.frameworkIndex()!=multiContext.frameworkIndex())
            throw new AssertionError("FRAMEWORK_UNIT_INDEX");
        List<Object> manifest = BuiltinExtensions.REGISTRY.manifest(Json.object("lombok","model","spring",true,"sofa",true));
        long fileAnalyzers = manifest.stream().map(Json::map)
                .filter(e -> Json.list(e.get("points")).contains("java-unit")).count();
        if (fileAnalyzers!=5) throw new AssertionError("BUILTIN_UNIT_ANALYZER_MISSING");
        System.out.println("framework header: 200000 mixed facts never decoded; immutable metadata retained; world/source/tie ranking unchanged");
    }
}
