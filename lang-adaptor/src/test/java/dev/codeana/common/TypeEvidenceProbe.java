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
