package dev.codeana.common;

import java.util.*;

/** Public per-fact graph closures. Neither compiler node IDs nor recursive type trees cross the wire. */
public final class TypeEvidence {
    public static final String TABLE = "java_type_graphs";
    private TypeEvidence() {}

    private static final class Group {
        final TypeGraph graph;
        final List<Integer> roots = new ArrayList<Integer>();
        final Map<Integer,Integer> slots = new LinkedHashMap<Integer,Integer>();
        Group(TypeGraph graph) { this.graph=graph; }
        int root(int reference) {
            Integer slot=slots.get(reference);
            if(slot==null) {slot=roots.size();slots.put(reference,slot);roots.add(reference);}
            return slot;
        }
    }
    private static final class Encoder {
        final IdentityHashMap<TypeGraph,Integer> identities=new IdentityHashMap<TypeGraph,Integer>();
        final List<Group> groups=new ArrayList<Group>();
        Object pack(Object raw,int depth) {
            if(depth>Json.MAX_DEPTH)throw new IllegalArgumentException("TYPE_EVIDENCE_DEPTH_BUDGET");
            if(TypeGraph.isView(raw)) {
                Map<String,Object> view=Json.map(raw);TypeGraph graph=TypeGraph.owner(view);
                Integer group=identities.get(graph);
                if(group==null) {group=groups.size();identities.put(graph,group);groups.add(new Group(graph));}
                return Json.object("$java_type",Arrays.asList(group,groups.get(group).root(TypeGraph.reference(view))));
            }
            if(raw instanceof Map) {
                Map<String,Object> input=Json.map(raw),copy=new TreeMap<String,Object>();
                for(Map.Entry<String,Object> entry:new TreeMap<String,Object>(input).entrySet())
                    copy.put(entry.getKey(),pack(entry.getValue(),depth+1));
                if(input.containsKey("$java_type") || input.containsKey("$java_object")) {
                    List<Object> entries=new ArrayList<Object>();
                    for(Map.Entry<String,Object> entry:copy.entrySet())entries.add(Arrays.asList(entry.getKey(),entry.getValue()));
                    return Json.object("$java_object",entries);
                }
                return copy;
            }
            if(raw instanceof List) {
                List<Object> copy=new ArrayList<Object>();
                for(Object value:Json.list(raw))copy.add(pack(value,depth+1));
                return copy;
            }
            return raw;
        }
    }

    public static Map<String,Object> encode(Map<String,Object> attributes) {
        if(attributes.containsKey(TABLE))throw new IllegalArgumentException("TYPE_EVIDENCE_RESERVED_TABLE");
        Encoder encoder=new Encoder();Map<String,Object> packed=Json.map(encoder.pack(attributes,0));
        if(encoder.groups.isEmpty())return attributes;
        List<Object> graphs=new ArrayList<Object>();
        for(Group group:encoder.groups)graphs.add(group.graph.canonical(group.roots));
        packed.put(TABLE,graphs);return packed;
    }

    public static Map<String,Object> decode(Map<String,Object> attributes) {
        if(!attributes.containsKey(TABLE))return attributes;
        if(!(attributes.get(TABLE) instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_TABLE");
        List<TypeGraph> graphs=new ArrayList<TypeGraph>();List<List<Object>> roots=new ArrayList<List<Object>>();
        for(Object value:Json.list(attributes.get(TABLE))) {
            Map<String,Object> payload=Json.map(value);graphs.add(TypeGraph.decode(payload));roots.add(Json.list(payload.get("roots")));
        }
        Map<String,Object> input=new TreeMap<String,Object>(attributes);input.remove(TABLE);
        return Json.map(unpack(input,graphs,roots,0));
    }
    private static int index(Object raw,int size) {
        try {
            if(!(raw instanceof Number))throw new IllegalArgumentException();
            int value=new java.math.BigDecimal(raw.toString()).intValueExact();
            if(value<0 || value>=size)throw new IllegalArgumentException();return value;
        }catch(RuntimeException failure){throw new IllegalArgumentException("TYPE_EVIDENCE_REFERENCE",failure);}
    }
    private static Object unpack(Object raw,List<TypeGraph> graphs,List<List<Object>> roots,int depth) {
        if(depth>Json.MAX_DEPTH)throw new IllegalArgumentException("TYPE_EVIDENCE_DEPTH_BUDGET");
        if(raw instanceof Map) {
            Map<String,Object> input=Json.map(raw),output=new TreeMap<String,Object>();
            if(input.containsKey("$java_type")) {
                if(input.size()!=1 || !(input.get("$java_type") instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_REFERENCE");
                List<Object> pair=Json.list(input.get("$java_type"));
                if(pair.size()!=2)throw new IllegalArgumentException("TYPE_EVIDENCE_REFERENCE");
                int graph=index(pair.get(0),graphs.size()),slot=index(pair.get(1),roots.get(graph).size());
                return graphs.get(graph).view(index(roots.get(graph).get(slot),graphs.get(graph).size()));
            }
            if(input.containsKey("$java_object")) {
                if(input.size()!=1 || !(input.get("$java_object") instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_ESCAPE");
                for(Object value:Json.list(input.get("$java_object"))) {
                    if(!(value instanceof List))throw new IllegalArgumentException("TYPE_EVIDENCE_ESCAPE");
                    List<Object> pair=Json.list(value);
                    if(pair.size()!=2 || !(pair.get(0) instanceof String) || output.containsKey(pair.get(0)))throw new IllegalArgumentException("TYPE_EVIDENCE_ESCAPE");
                    output.put((String)pair.get(0),unpack(pair.get(1),graphs,roots,depth+1));
                }
            } else for(Map.Entry<String,Object> entry:input.entrySet())output.put(entry.getKey(),unpack(entry.getValue(),graphs,roots,depth+1));
            return output;
        }
        if(raw instanceof List) {
            List<Object> output=new ArrayList<Object>();for(Object value:Json.list(raw))output.add(unpack(value,graphs,roots,depth+1));return output;
        }
        return raw;
    }
}
