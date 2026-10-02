package dev.codeana.analysis.extension;

import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;
import java.util.*;

/** Compare observer semantics and prove rejected headers do not decode a large call graph. */
public final class CallHeaderProbe {
    private static final String SOURCE="src/Use.java";
    private static Map<String,Object> configuration() {
        return Json.object("world","w","context","c","lombok","model","resources",List.of(),
                "fact_limit",300000,"sources",List.of(Json.object("path",SOURCE,"world","w","publish",true)));
    }
    private static Map<String,Object> call(int index,String receiver) {
        return Json.object("id","call"+index,"kind","call_site","source",SOURCE,"name","run","signature","",
                "owner",Json.object("world","w","kind","source","id",SOURCE,"producer","javac-java8-v1"),
                "span",null,"parent","","target","","attributes",Json.object("receiver_type",receiver));
    }
    private static JavaCallSiteEvidenceProvider delegate(boolean filtered) {
        JavaCallSiteEvidenceProvider builtin=(JavaCallSiteEvidenceProvider)BuiltinExtensions.REGISTRY.enabled(configuration())
                .stream().filter(e -> e.id().equals("lombok")).findFirst().orElseThrow();
        return new JavaCallSiteEvidenceProvider() {
            public String id(){return "observer";}
            public String producer(){return "observer-v1";}
            public Map<String,Integer> facets(){return Map.of("java.lombok",1);}
            public boolean acceptsCallSite(ExtensionContext context,Map<String,Object> header) throws Exception {
                return !filtered || builtin.acceptsCallSite(context,header);
            }
            public Optional<Evidence> observe(ExtensionContext context,Map<String,Object> fact) throws Exception {
                return builtin.observe(context,fact);
            }
        };
    }
    private static List<Object> run(JavaCallSiteEvidenceProvider provider,Map<String,Object> model,List<Object> facts) throws Exception {
        return new ExtensionRunner(configuration(),new ExtensionRegistry(List.of(provider))).analyzeJava(model,facts);
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> model=Json.object("declarations",List.of(
                Json.object("signature","Bean","attributes",Json.object("annotations",List.of(Json.object("type","lombok.Getter")))),
                Json.object("signature","Plain","attributes",Json.object("annotations",List.of()))));
        List<Object> facts=List.of(call(0,"Bean"),call(1,"Plain"),call(2,""),call(3,"Missing"));
        List<Object> selected=run(delegate(true),model,facts),baseline=run(delegate(false),model,facts);
        if(!Json.write(selected).equals(Json.write(baseline)) || selected.size()!=5)
            throw new AssertionError("FILTER_CHANGED_EVIDENCE");
        int[] decoded={0};
        FactHeaders.View archive=new FactHeaders.View() {
            public int size(){return 200000;}
            public Object get(int index){decoded[0]++;throw new AssertionError("REJECTED_CALL_DECODED");}
            public Map<String,Object> factHeader(int index){return FactHeaders.project(call(index,"Missing"));}
        };
        if(run(delegate(true),model,archive).size()!=archive.size() || decoded[0]!=0)
            throw new AssertionError("HEADER_FILTER_DROPPED_CORE_OR_READ_GRAPH");
        JavaCallSiteEvidenceProvider failing=new JavaCallSiteEvidenceProvider() {
            public String id(){return "failing";}
            public String producer(){return "failing-v1";}
            public boolean acceptsCallSite(ExtensionContext c,Map<String,Object> h){throw new IllegalStateException("SELECTOR_FAILURE");}
            public Optional<Evidence> observe(ExtensionContext c,Map<String,Object> f){throw new AssertionError("FAILED_SELECTOR_OBSERVED");}
        };
        if(run(failing,model,List.of(call(0,"Missing"))).size()!=3)
            throw new AssertionError("SELECTOR_FAILURE_NOT_ISOLATED");
        JavaCallSiteEvidenceProvider cancelled=new JavaCallSiteEvidenceProvider() {
            public String id(){return "cancelled";}
            public String producer(){return "cancelled-v1";}
            public boolean acceptsCallSite(ExtensionContext c,Map<String,Object> h){throw new java.util.concurrent.CancellationException("CALL_SELECTOR_CANCELLED");}
            public Optional<Evidence> observe(ExtensionContext c,Map<String,Object> f){return Optional.empty();}
        };
        try {run(cancelled,model,facts);throw new AssertionError("SELECTOR_CANCEL_IGNORED");}
        catch(java.util.concurrent.CancellationException expected) {}
        FactHeaders.View corrupt=new FactHeaders.View() {
            public int size(){return 1;}
            public Map<String,Object> factHeader(int index){return FactHeaders.project(call(0,"Missing"));}
            public Object get(int index){throw new IllegalArgumentException("MALFORMED_INPUT");}
        };
        try {run(delegate(false),model,corrupt);throw new AssertionError("CORRUPT_CALL_DEGRADED");}
        catch(ExtensionPoint.GateFailure expected) {
            if(!"EXTENSION_CALL_INPUT_CORRUPT".equals(expected.getMessage())
                    || !"MALFORMED_INPUT".equals(expected.getCause().getMessage())) throw expected;
        }
        List<String> phases=new ArrayList<>();
        ExtensionRunner runner=new ExtensionRunner(configuration(),new ExtensionRegistry(List.of(delegate(true))),phases::add);
        if(!Json.write(runner.analyzeJava(model,facts)).equals(Json.write(selected))
                || !phases.equals(List.of("call.observer"))) throw new AssertionError("PROGRESS_CHANGED_FACTS_OR_ORDER");
        System.out.println("call header: filtered/unfiltered evidence equal; 200000 rejected graphs never decoded; failure/cancel isolated");
    }
}
