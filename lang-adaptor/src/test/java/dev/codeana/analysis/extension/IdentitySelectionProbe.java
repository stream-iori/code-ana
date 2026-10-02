package dev.codeana.analysis.extension;

import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;
import java.util.*;

/** Targeted identity validation must never equate a hash candidate with an exact ID. */
public final class IdentitySelectionProbe {
    private static Map<String,Object> emitted(String id,String parent) {
        return Json.object("id",id,"kind","facet","source","src/A.java","parent",parent,"target","",
                "owner",Json.object("world","w","kind","source","id","src/A.java","producer","probe"),
                "relation","probe","declaration_resolved",false,"attributes",Json.object("schema_version",1));
    }
    public static void main(String[] args) {
        ExtensionPoint producer=new ExtensionPoint() {
            public String id(){return "probe";}
            public Map<String,Integer> facets(){return Map.of("probe",1);}
        };
        Map<String,Object> config=Json.object("world","w","context","c","fact_limit",300000,
                "sources",List.of(Json.object("path","src/A.java","world","w","publish",true)),"resources",List.of());
        int[] headers={0};
        FactHeaders.View rows=new FactHeaders.View() {
            public int size(){return 200000;}
            public Object get(int i){throw new AssertionError("IDENTITY_GRAPH_DECODED");}
            public boolean mightHaveId(int i,Set<Long> wanted){return i==7;}
            public Map<String,Object> factHeader(int i){headers[0]++;return Map.of("id",i==7?"parent":"other"+i);}
        };
        ExtensionContext context=new ExtensionContext(config,Map.of(),rows,List.of());
        FactSink sink=new FactSink();sink.add(emitted("new","parent"));
        if(sink.validate(producer,context,rows).size()!=1 || headers[0]!=1)
            throw new AssertionError("TARGETED_VALIDATION_SCANNED_ALL_HEADERS: "+headers[0]);
        FactHeaders.View collision=new FactHeaders.View() {
            public int size(){return 1;}
            public Object get(int i){throw new AssertionError("COLLISION_GRAPH_DECODED");}
            public boolean mightHaveId(int i,Set<Long> wanted){return true;}
            public Map<String,Object> factHeader(int i){return Map.of("id","unrelated");}
        };
        sink=new FactSink();sink.add(emitted("new","missing"));
        try {sink.validate(producer,context,collision);throw new AssertionError("HASH_COLLISION_ACCEPTED_AS_ID");}
        catch(IllegalArgumentException expected){if(!expected.getMessage().equals("EXTENSION_DANGLING_LINK"))throw expected;}
        sink=new FactSink();sink.add(emitted("parent",""));
        try {sink.validate(producer,context,rows);throw new AssertionError("DUPLICATE_ID_ACCEPTED");}
        catch(IllegalArgumentException expected){if(!expected.getMessage().equals("EXTENSION_FACT_OWNERSHIP_OR_EVIDENCE"))throw expected;}
        List<Object> regular=List.of(emitted("parent",""),emitted("other",""));
        sink=new FactSink();sink.add(emitted("new","parent"));
        if(sink.validate(producer,context,regular).size()!=1)throw new AssertionError("PLAIN_LIST_FALLBACK");
        Thread.currentThread().interrupt();
        sink=new FactSink();sink.add(emitted("new","parent"));
        try {sink.validate(producer,context,rows);throw new AssertionError("IDENTITY_CANCEL_IGNORED");}
        catch(java.util.concurrent.CancellationException expected){}
        finally {Thread.interrupted();}
        System.out.println("identity selection: 200000 rows / one header; collision, duplicate, dangling, plain-list and cancellation guards passed");
    }
}
