package dev.codeana.analysis;

import static dev.codeana.analysis.TypeOperations.*;
import dev.codeana.common.Json;
import java.util.*;
import java.util.function.Consumer;

/** Declaration signatures and explicit written types, with separate body navigation. */
final class SourceTypeReferences {
    record Use(Map<String,Object> owner, String target, Map<String,Object> position,
            String context, int depth, int ordinal, boolean recovered, Map<String,Object> proof) {
        Use(Map<String,Object> owner, String target, Map<String,Object> position,
            String context, int depth, int ordinal, boolean recovered) {
            this(owner,target,position,context,depth,ordinal,recovered,Map.of());
        }
    }
    private record Key(String source, long id) {}
    private record SignatureUse(String target, String context, int depth, boolean recovered) {}
    private final List<Object> syntax;
    private final Map<Key,Integer> nodes = new HashMap<>();
    private final Map<String,Integer> declarations = new HashMap<>();
    private final Map<String,Integer> anonymousDeclarations = new HashMap<>();
    private final Set<String> sourceTypes = new HashSet<>();
    private final Set<Key> unavailableTypeSyntax = new HashSet<>();
    private final List<Object> declared;
    private final Map<String,List<Integer>> callbackParameters = new HashMap<>();
    private final List<Map<String,Object>> diagnostics = new ArrayList<>();
    private final boolean supplementalBaseline;
    private int work;
    private String source;

    SourceTypeReferences(Map<String,Object> model) { this(model,false); }
    SourceTypeReferences(Map<String,Object> model, boolean supplementalBaseline) {
        this.supplementalBaseline=supplementalBaseline;
        syntax = list(model.get("syntax")); declared = list(model.get("declarations"));
        for (int i=0;i<syntax.size();i++) {
            Map<String,Object> n=map(syntax.get(i));
            nodes.put(new Key(text(n.get("source")), id(n.get("id"))),i);
            if (Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE","METHOD","VARIABLE").contains(n.get("kind")))
                declarations.put(text(n.get("source"))+"\0"+text(n.get("symbol")),i);
        }
        for (Object raw:list(model.get("observations"))) {
            Map<String,Object> observation=map(raw), attrs=map(observation.get("attributes"));
            if ("reference".equals(observation.get("kind")) && "type".equals(attrs.get("reference_kind"))
                    && Boolean.FALSE.equals(observation.get("resolved")))
                unavailableTypeSyntax.add(new Key(text(observation.get("source")),id(attrs.get("tree_id"))));
        }
        for (int index=0;index<declared.size();index++) {
            Map<String,Object> d=map(declared.get(index));
            if("ANONYMOUS".equals(map(d.get("attributes")).get("nesting_kind")))
                anonymousDeclarations.put(text(d.get("source"))+"\0"+text(d.get("signature")),index);
            if("PARAMETER".equals(map(d.get("attributes")).get("element_kind")))
                callbackParameters.computeIfAbsent(text(d.get("source"))+"\0"+text(d.get("parent")),ignored->new ArrayList<>()).add(index);
            if (Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(map(d.get("attributes")).get("element_kind")))
                sourceTypes.add(text(d.get("signature")));
        }
    }

    static void emit(Map<String,Object> model, Consumer<Use> output) {
        new SourceTypeReferences(model).emit(output);
    }

    private static long id(Object value) { return value instanceof Number n ? n.longValue() : -1; }

    private Map<String,Object> node(long id) {
        Integer index=nodes.get(new Key(source,id));
        return index==null?Map.of():map(syntax.get(index));
    }
    private Map<String,Object> child(Map<String,Object> node,String role) {
        return node(id(map(node.get("roles")).get(role)));
    }
    List<Map<String,Object>> diagnostics() { return List.copyOf(diagnostics); }
    private void step(int depth) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CANCELLED");
        if (depth>=128 || ++work>100000) throw new TypeOperations.Budget();
    }
    boolean excluded(String name) {
        if (sourceTypes.contains(name)) return false;
        for (String prefix:List.of("java.lang.","java.io.","java.net.","java.nio.","java.math.","sun.","com.sun.","jdk."))
            if(name.startsWith(prefix))return true;
        return false;
    }
    private void signature(Map<String,Object> type,String context,int depth,int nesting,List<SignatureUse> output) {
        step(nesting);
        if(depth>5)return;
        String kind=kind(type);
        if(kind.equals("ARRAY")) { signature(map(type.get("component")),context,depth,nesting+1,output);return; }
        if(!kind.equals("DECLARED"))return;
        String target=text(type.get("element"));
        if(!target.isEmpty() && !excluded(target))output.add(new SignatureUse(target,context,depth,false));
        for(Object argument:list(type.get("arguments")))signature(map(argument),"generic_arg",depth+1,nesting+1,output);
    }
    private void written(Map<String,Object> tree,List<Map<String,Object>> output,int depth) {
        step(depth);
        switch(text(tree.get("kind"))) {
            case "ARRAY_TYPE", "ANNOTATED_TYPE" -> written(child(tree,"type"),output,depth+1);
            case "PARAMETERIZED_TYPE" -> {
                written(child(tree,"type"),output,depth+1);
                for(Object argument:list(map(tree.get("roles")).get("type_arguments")))written(node(id(argument)),output,depth+1);
            }
            case "EXTENDS_WILDCARD","SUPER_WILDCARD" -> written(child(tree,"bound"),output,depth+1);
            case "INTERSECTION_TYPE", "UNION_TYPE" -> {
                String role=tree.get("kind").equals("INTERSECTION_TYPE")?"bounds":"alternatives";
                for(Object bound:list(map(tree.get("roles")).get(role)))written(node(id(bound)),output,depth+1);
            }
            case "IDENTIFIER", "MEMBER_SELECT" -> {
                String symbol=text(tree.get("symbol"));
                if(!symbol.isEmpty() && !symbol.contains("#")
                        && id(tree.get("start"))>=0 && id(tree.get("end"))>=0
                        && !unavailableTypeSyntax.contains(new Key(source,id(tree.get("id")))))output.add(tree);
            }
        }
    }
    private void bounds(Map<String,Object> tree,List<Map<String,Object>> output) {
        for(Object p:list(map(tree.get("roles")).get("type_parameters")))
            for(Object bound:list(map(node(id(p)).get("roles")).get("bounds")))written(node(id(bound)),output,0);
    }
    private void recovered(Map<String,Object> type,String context,int depth,List<SignatureUse> output) {
        step(depth);
        if("ARRAY".equals(kind(type))) { recovered(map(type.get("component")),context,depth+1,output);return; }
        if(!"DECLARED".equals(kind(type)))return;
        String target=text(type.get("element"));
        if(target.isEmpty() || excluded(target))return;
        if(output.stream().noneMatch(use->use.target().equals(target) && Objects.equals(use.context(),context)))
            output.add(new SignatureUse(target,context,0,true));
    }
    Map<String,Object> hierarchyPosition(Map<String,Object> observation) {
        String relation=text(observation.get("relation"));
        if(!Set.of("extends","implements").contains(relation))return Map.of();
        source=text(observation.get("source"));
        Integer anonymous=anonymousDeclarations.get(source+"\0"+text(observation.get("parent")));
        if(anonymous!=null)return map(declared.get(anonymous));
        Integer index=declarations.get(source+"\0"+text(observation.get("parent")));
        if(index==null)return Map.of();
        Map<String,Object> declaration=map(syntax.get(index));
        List<Map<String,Object>> parents=new ArrayList<>();
        parents.add(child(declaration,"extends"));
        for(Object parent:list(map(declaration.get("roles")).get("implements")))parents.add(node(id(parent)));
        for(Map<String,Object> parent:parents) {
            for(int depth=0;depth<128 && Set.of("PARAMETERIZED_TYPE","ANNOTATED_TYPE").contains(text(parent.get("kind")));depth++)
                parent=child(parent,"type");
            if(text(observation.get("target")).equals(parent.get("symbol")))return parent;
        }
        return Map.of();
    }

    void emit(Consumer<Use> output) {
        for(Object raw:declared) {
            Map<String,Object> d=map(raw),a=map(d.get("attributes"));
            if(a.containsKey("extension_producer") || "ANONYMOUS".equals(a.get("nesting_kind")))continue;
            source=text(d.get("source"));work=0;
            String k=text(a.get("element_kind")),signature=text(d.get("signature"));
            Integer index=declarations.get(source+"\0"+signature);
            Map<String,Object> tree=index==null?Map.of():map(syntax.get(index));
            List<Use> collected = new ArrayList<>();
            try {
            List<SignatureUse> uses=new ArrayList<>(),supplementalUses=new ArrayList<>();
            List<Map<String,Object>> written=new ArrayList<>();
            if(Set.of("FIELD","ENUM_CONSTANT","RECORD_COMPONENT").contains(k)) {
                signature(map(a.get("type_model")),"field_type",0,0,uses);
                written(child(tree,"type"),written,0);
            } else if(Set.of("METHOD","CONSTRUCTOR").contains(k)) {
                if(k.equals("METHOD")) {
                    signature(map(a.get("return_type_model")),"return_type",0,0,uses);
                    written(child(tree,"return_type"),written,0);
                }
                for(Object parameter:list(a.get("parameter_type_models")))signature(map(parameter),"parameter_type",0,0,uses);
                for(Object parameter:list(map(tree.get("roles")).get("parameters")))written(child(node(id(parameter)),"type"),written,0);
                for(Object thrown:list(map(tree.get("roles")).get("throws")))written(node(id(thrown)),written,0);
                bounds(tree,written);
            } else if(Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(k)) {
                bounds(tree,written);
                // Root hierarchy identifiers are INHERITS/IMPLEMENTS; only their generic
                // arguments are references owned by the source type.
                List<Map<String,Object>> parents=new ArrayList<>();parents.add(child(tree,"extends"));
                for(Object parent:list(map(tree.get("roles")).get("implements")))parents.add(node(id(parent)));
                for(Map<String,Object> parent:parents) {
                    if("PARAMETERIZED_TYPE".equals(parent.get("kind")))
                        for(Object argument:list(map(parent.get("roles")).get("type_arguments")))written(node(id(argument)),written,0);
                }
            } else if(k.equals("LAMBDA")) {
                Map<String,Object> supplemental=map(a.get("supplemental_callback"));
                if(supplementalBaseline && !supplemental.isEmpty()) {
                    signature(map(supplemental.get("functional_type_model")),"functional_interface",0,0,uses);
                    for(Object parameter:list(supplemental.get("parameter_type_models")))
                        signature(map(parameter),"parameter_type",0,0,uses);
                } else {
                signature(map(a.get("functional_type_model")),"functional_interface",0,0,uses);
                for(int parameter:callbackParameters.getOrDefault(source+"\0"+signature,List.of()))
                    signature(map(map(map(declared.get(parameter)).get("attributes")).get("type_model")),"parameter_type",0,0,uses);
                Map<String,Object> recovery=map(a.get("recovered_callback"));
                if (!recovery.isEmpty()) {
                    recovered(map(recovery.get("functional_type_model")),"functional_interface",0,uses);
                    for(Object parameter:list(recovery.get("parameter_type_models")))
                        recovered(map(parameter),"parameter_type",0,uses);
                }
                }
                if(!supplementalBaseline && !supplemental.isEmpty()) {
                    signature(map(supplemental.get("functional_type_model")),"functional_interface",0,0,supplementalUses);
                    for(Object parameter:list(supplemental.get("parameter_type_models")))
                        signature(map(parameter),"parameter_type",0,0,supplementalUses);
                }
            } else continue;
            Map<String,ArrayDeque<Map<String,Object>>> positions=new HashMap<>();
            for(Map<String,Object> n:written)positions.computeIfAbsent(text(n.get("symbol")),ignored->new ArrayDeque<>()).add(n);
            Set<Long> used=new HashSet<>();int ordinal=0;
            for(SignatureUse use:uses) {
                ArrayDeque<Map<String,Object>> queue=positions.get(use.target());
                Map<String,Object> position=queue==null || queue.isEmpty()?d:queue.removeFirst();
                if(position!=d)used.add(id(position.get("id")));
                collected.add(new Use(d,use.target(),position,use.context(),use.depth(),ordinal++,use.recovered(),
                        k.equals("LAMBDA") && supplementalBaseline ? map(a.get("supplemental_callback")) : Map.of()));
            }
            for(SignatureUse use:supplementalUses) {
                Map<String,Object> proof=new TreeMap<>(map(a.get("supplemental_callback")));
                proof.put("baseline_projection",false);
                collected.add(new Use(d,use.target(),d,use.context(),use.depth(),ordinal++,false,proof));
            }
            for(Map<String,Object> n:written) {
                if(used.contains(id(n.get("id"))))continue;
                collected.add(new Use(d,text(n.get("symbol")),n,null,0,ordinal++,false));
            }
            } catch (TypeOperations.Budget exhausted) {
                diagnostics.add(Json.object("source",source,"owner",signature,
                        "code","SOURCE_TYPE_REFERENCE_BUDGET","severity","WARNING",
                        "start",d.get("start"),"end",d.get("end"),"work",work));
                continue;
            }
            collected.forEach(output);
        }
    }
}
