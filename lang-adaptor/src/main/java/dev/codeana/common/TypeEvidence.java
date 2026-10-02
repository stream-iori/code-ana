package dev.codeana.common;

import java.util.*;

/** Public per-fact graph closures. Neither compiler node IDs nor recursive type trees cross the wire. */
public final class TypeEvidence {
    public static final String TABLE = "java_type_graphs";
    private TypeEvidence() {}

    private static final class Node {
        final TypeGraph graph;final int reference;final Map<String,Object> value;
        Node(TypeGraph graph,int reference,Map<String,Object> value) {this.graph=graph;this.reference=reference;this.value=value;}
    }
    private static final class Encoder {
        final IdentityHashMap<TypeGraph,Map<Integer,Integer>> identities=new IdentityHashMap<TypeGraph,Map<Integer,Integer>>();
        final IdentityHashMap<Map<String,Object>,Integer> copies=new IdentityHashMap<Map<String,Object>,Integer>();
        final List<Node> nodes=new ArrayList<Node>();
        final List<Integer> roots=new ArrayList<Integer>();
        final List<List<Integer>> references=new ArrayList<List<Integer>>();
        private int view(TypeGraph graph,int reference) {
            if(reference<0)return -1;graph.requireSealed();
            Map<Integer,Integer> ids=identities.computeIfAbsent(graph,ignored->new HashMap<Integer,Integer>());
            Integer id=ids.get(reference);
            if(id==null) {id=reserve(new Node(graph,reference,null));ids.put(reference,id);}
            return id;
        }
        private int reserve(Node node) {
            if(nodes.size()>=TypeGraph.MAX_NODES)throw new IllegalArgumentException("TYPE_EVIDENCE_NODE_BUDGET");
            int id=nodes.size();nodes.add(node);return id;
        }
        private int model(Map<String,Object> value) {
            if(TypeGraph.isView(value))return view(TypeGraph.owner(value),TypeGraph.reference(value));
            Integer id=copies.get(value);
            if(id==null) {id=reserve(new Node(null,-1,value));copies.put(value,id);}
            return id;
        }
        Map<String,Object> payload() {
            List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();
            for(int cursor=0;cursor<nodes.size();cursor++) {
                Node source=nodes.get(cursor);Map<String,Object> raw=source.graph==null?source.value:source.graph.node(source.reference);
                Map<String,Object> copy=new TreeMap<String,Object>();
                for(Map.Entry<String,Object> entry:raw.entrySet()) {
                    String key=entry.getKey();Object value=entry.getValue();
                    if(CanonicalTypes.SINGLE.contains(key)) {
                        if(source.graph!=null)value=view(source.graph,((Number)value).intValue());
                        else if(value instanceof Map)value=model(Json.map(value));
                        else if(value==null)value=-1;
                        else throw new IllegalArgumentException("TYPE_EVIDENCE_MODEL_EDGE");
                    } else if(CanonicalTypes.MANY.contains(key)) {
                        List<Integer> children=new ArrayList<Integer>();
                        for(Object child:Json.list(value))children.add(source.graph==null?model(Json.map(child)):view(source.graph,((Number)child).intValue()));
                        value=children;
                    }
                    copy.put(key,value);
                }
                values.add(copy);
            }
            Map<String,Object> payload=CanonicalTypes.encode(new TypeGraph(values),roots);
            List<Object> canonicalRoots=Json.list(payload.get("roots"));List<Integer> unique=new ArrayList<Integer>();Map<Integer,Integer> slots=new LinkedHashMap<Integer,Integer>();
            for(int i=0;i<canonicalRoots.size();i++) {
                int root=((Number)canonicalRoots.get(i)).intValue();Integer slot=slots.get(root);
                if(slot==null) {slot=unique.size();slots.put(root,slot);unique.add(root);}
                references.get(i).set(1,slot);
            }
            payload.put("roots",unique);return payload;
        }
        Object pack(Object raw,int depth) {
            if(depth>Json.MAX_DEPTH)throw new IllegalArgumentException("TYPE_EVIDENCE_DEPTH_BUDGET");
            if(TypeGraph.isView(raw) || raw instanceof Map && Json.map(raw).get("version") instanceof Number
                    && Json.number(Json.map(raw),"version")==3 && Json.map(raw).get("kind") instanceof String
                    && Json.map(raw).get("complete") instanceof Boolean) {
                int root=model(Json.map(raw));List<Integer> reference=new ArrayList<Integer>(Arrays.asList(0,roots.size()));
                roots.add(root);references.add(reference);return Json.object("$java_type",reference);
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
        if(encoder.roots.isEmpty())return attributes;
        packed.put(TABLE,Arrays.asList(encoder.payload()));return packed;
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
