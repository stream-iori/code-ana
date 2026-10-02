package dev.codeana.common;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TypeEvidenceProbe {
    public static void main(String[] args) {
        List<Map<String,Object>> nodes=new ArrayList<Map<String,Object>>();
        for(int i=0;i<10000;i++)nodes.add(Json.object("kind","ARRAY","complete",true,"component",i+1));
        nodes.add(Json.object("kind","INT","complete",true));
        TypeGraph graph=new TypeGraph(nodes);
        Map<String,Object> input=Json.object("declared",graph.view(0),"parameters",Arrays.asList(graph.view(0),graph.view(5)),
                "literal",Json.object("$java_type",Arrays.asList(99,99)));
        Map<String,Object> encoded=TypeEvidence.encode(input);
        byte[] bytes=Json.write(encoded).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>2L<<20)throw new AssertionError("per-root graph copies: "+bytes.length);
        Map<String,Object> parsed=Json.map(Json.parse(bytes));
        List<Object> graphs=Json.list(parsed.get(TypeEvidence.TABLE));
        if(graphs.size()!=1 || Json.list(Json.map(graphs.get(0)).get("roots")).size()!=2)
            throw new AssertionError("roots not interned within fact");
        Map<String,Object> decoded=TypeEvidence.decode(parsed);
        Map<String,Object> declared=Json.map(decoded.get("declared"));
        if(!TypeGraph.isView(declared) || TypeGraph.owner(declared).size()!=10001)throw new AssertionError("wire closure lost");
        if(!Json.write(decoded.get("literal")).equals(Json.write(input.get("literal"))))throw new AssertionError("literal interpreted as reference");
        TypeGraph cycle=new TypeGraph(Arrays.asList(
                Json.object("kind","TYPEVAR","complete",true,"name","T","upper",1,"lower",2),
                Json.object("kind","DECLARED","complete",true,"element","Comparable","arguments",Arrays.asList(0)),
                Json.object("kind","NULL","complete",true)));
        Map<String,Object> cycleInput=Json.object("declared",cycle.view(0));
        Map<String,Object> cycleEncoded=TypeEvidence.encode(cycleInput);
        Map<String,Object> cycleDecoded=TypeEvidence.decode(Json.map(Json.parse(Json.write(cycleEncoded).getBytes(StandardCharsets.UTF_8))));
        Map<String,Object> cycleRoot=Json.map(cycleDecoded.get("declared"));
        if(!Json.write(cycle.canonical(Arrays.asList(0))).equals(Json.write(TypeGraph.owner(cycleRoot).canonical(Arrays.asList(TypeGraph.reference(cycleRoot))))))
            throw new AssertionError("cycle changed at public boundary");
        // A different compiler visit order and unreachable node must not change published bytes.
        TypeGraph reordered=new TypeGraph(Arrays.asList(
                Json.object("kind","INT","complete",true),
                Json.object("kind","NULL","complete",true),
                Json.object("kind","DECLARED","complete",true,"element","Comparable","arguments",Arrays.asList(3)),
                Json.object("kind","TYPEVAR","complete",true,"name","T","upper",2,"lower",1)));
        if(!Json.write(cycleEncoded).equals(Json.write(TypeEvidence.encode(Json.object("declared",reordered.view(3))))))
            throw new AssertionError("compiler IDs leaked into wire identity");
        Map<String,Object> separate=TypeEvidence.encode(Json.object("declared",cycle.view(0),"selected",reordered.view(3)));
        Map<String,Object> shared=TypeEvidence.encode(Json.object("declared",cycle.view(0),"selected",cycle.view(0)));
        if(!Json.write(separate).equals(Json.write(shared)))throw new AssertionError("compiler context sharing leaked into public identity");
        TypeGraph captures=new TypeGraph(Arrays.asList(
                Json.object("kind","TYPEVAR","complete",true,"name","T","owner","capture:a","upper",2,"lower",3),
                Json.object("kind","TYPEVAR","complete",true,"name","T","owner","capture:b","upper",2,"lower",3),
                Json.object("kind","DECLARED","complete",true,"element","Object","arguments",Collections.emptyList()),
                Json.object("kind","NULL","complete",true)));
        Map<String,Object> distinct=TypeEvidence.encode(Json.object("left",captures.view(0),"right",captures.view(1)));
        if(Json.map(distinct.get("left")).equals(Json.map(distinct.get("right"))))throw new AssertionError("distinct captures merged");
        Map<String,Object> missing=new LinkedHashMap<String,Object>(Json.map(cycle.node(1)));
        missing.put("arguments",Collections.emptyList());missing.put("complete",false);missing.put("reason","missing argument");
        Map<String,Object> incomplete=TypeEvidence.encode(Json.object("known",cycle.view(1),"missing",missing));
        if(Json.map(incomplete.get("known")).equals(Json.map(incomplete.get("missing"))))throw new AssertionError("incomplete type merged with known evidence");
        for(Object pair:Arrays.asList(Arrays.asList(9,0),Arrays.asList(0,9),Arrays.asList(0,.5),Arrays.asList("0",0),Arrays.asList(0))) {
            Map<String,Object> invalid=new TreeMap<String,Object>(cycleEncoded);invalid.put("declared",Json.object("$java_type",pair));
            try {TypeEvidence.decode(invalid);throw new AssertionError("accepted malformed public type reference");}
            catch(IllegalArgumentException expected) {
                if(!"TYPE_EVIDENCE_REFERENCE".equals(expected.getMessage()))throw expected;
            }
        }
        System.out.println("public flat graph: shared roots, 10001 nodes, cycles, stable IDs and malformed references passed");
    }
}
