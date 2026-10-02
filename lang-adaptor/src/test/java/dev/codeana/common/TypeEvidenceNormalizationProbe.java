package dev.codeana.common;
import java.util.*;
public final class TypeEvidenceNormalizationProbe {
    private static void require(boolean value,String reason) {if(!value)throw new AssertionError(reason);}
    private static Map<String,Object> root(Map<String,Object> type) {
        return Json.map(TypeEvidence.decode(TypeEvidence.encode(Json.object("type",type))).get("type"));
    }
    public static void main(String[] args) {
        Map<String,Object> named=root(Json.object("version",2,"kind","DECLARED","element","p.Api","complete",true));
        require(TypeGraph.isView(named) && Json.list(named.get("arguments")).isEmpty(),"legacy named arguments");
        Map<String,Object> wildcard=root(Json.object("version",2,"kind","WILDCARD","complete",true));
        require(wildcard.get("extends")==null && wildcard.get("super")==null,"absent wildcard bounds");
        Map<String,Object> capture=root(Json.object("kind","CAPTURE","capture","query-1","upper",Json.object("version",2,"kind","DECLARED","element","java.lang.Object","complete",true),"complete",true));
        require("TYPEVAR".equals(capture.get("kind")) && Boolean.TRUE.equals(capture.get("capture")) && "query-1".equals(capture.get("capture_identity")),"capture identity");
        TypeGraph graph=TypeGraph.owner(capture);int id=((Number)graph.node(TypeGraph.reference(capture)).get("lower")).intValue();
        require("NULL".equals(graph.node(id).get("kind")),"real NULL lower edge");
        Map<String,Object> unknown=root(Json.object("kind","TYPEVAR","name","T","owner_unavailable",true,"complete",true));
        require(Boolean.FALSE.equals(unknown.get("complete")) && Boolean.TRUE.equals(unknown.get("bounds_unavailable")) && unknown.get("upper")==null,"no invented unknown upper");
        Map<String,Object> parent=root(Json.object("version",2,"kind","DECLARED","element","p.Api","arguments",Arrays.asList(Json.object("version",2,"kind","UNKNOWN","complete",false)),"complete",true));
        require(Boolean.FALSE.equals(parent.get("complete")),"child incompleteness propagates");
        Map<String,Object> other=Json.object("kind","CLASS","complete",true,"annotation",Json.object("kind","annotation","complete",true));
        require(TypeEvidence.encode(other)==other,"declaration metadata untouched");
        Map<String,Object> primitive=root(Json.object("version",2,"kind","INT","complete",true));
        require("INT".equals(primitive.get("kind")) && Json.number(primitive,"version")==3,"scalar root v3");
        try {root(Json.object("version",2,"kind","ARRAY","complete",true));throw new AssertionError("invalid complete ARRAY accepted");}
        catch(IllegalArgumentException expected){require(expected.getMessage().startsWith("TYPE_GRAPH_REQUIRED_REFERENCE"),"invalid shape remains rejected");}
        System.out.println("named, wildcard, capture, unknown, scalar and metadata roots verified");
    }
}
