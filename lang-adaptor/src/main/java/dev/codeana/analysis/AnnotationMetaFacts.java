package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.util.*;
import java.util.function.Consumer;

/** Source-owned discovery of detached annotation metadata, including binary headers.
 * Adapted from Anatomist JavaAnnotationFacts.meta; no compiler or database objects. */
final class AnnotationMetaFacts {
    private static final Set<String> OWNERS=Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE",
            "METHOD","CONSTRUCTOR","FIELD","ENUM_CONSTANT","PARAMETER");
    private final Map<String,Map<String,Object>> headers=new HashMap<>(), declarations=new HashMap<>();
    private final Set<String> published=new HashSet<>(), emitted=new HashSet<>(), roots=new HashSet<>();
    private final Map<String,Integer> sourceWork=new HashMap<>();
    private final Map<String,Object> model;
    private final Consumer<Map<String,Object>> output;
    private String discovery;
    private int work;

    private AnnotationMetaFacts(Map<String,Object> context,Map<String,Object> model,
            Map<String,Map<String,Object>> verifiedSources,Consumer<Map<String,Object>> output) {
        this.model=model;this.output=output;
        for(Object raw:Json.list(context.get("sources"))) {
            var source=Json.map(raw);
            if(Boolean.TRUE.equals(source.get("publish"))) published.add(Json.string(source,"path"));
        }
        for(Object raw:Json.list(model.get("annotation_types"))) {
            var header=Json.map(raw);headers.put(Json.string(header,"name"),header);
        }
        for(Object raw:Json.list(model.get("declarations"))) {
            var declaration=Json.map(raw);declarations.put(Json.string(declaration,"signature"),declaration);
        }
        // File NoCode removes unchanged source declarations from the compiler
        // model. These original catalogues were checked against the published
        // baseline before use; binary annotations must not replace them.
        for(var entry:verifiedSources.entrySet())
            if(entry.getValue().get("source") instanceof String) declarations.put(entry.getKey(),entry.getValue());
    }

    static void emit(Map<String,Object> context,Map<String,Object> model,
            Map<String,Map<String,Object>> verifiedSources,Consumer<Map<String,Object>> output) {
        new AnnotationMetaFacts(context,model,verifiedSources,output).generate();
    }

    private void generate() {
        for(Object raw:Json.list(model.get("observations"))) {
            var use=Json.map(raw);
            if(!"annotation".equals(use.get("kind"))) continue;
            discovery=Json.string(use,"source");
            if(!published.contains(discovery)) continue;
            var owner=declarations.get(Json.string(use,"parent"));
            if(owner==null || !OWNERS.contains(Json.map(owner.get("attributes")).get("element_kind"))) continue;
            String name=Json.string(use,"target");
            // Unknown/import-only names are not confirmed annotation declarations.
            if(!headers.containsKey(name) || !roots.add(discovery+'\0'+name)) continue;
            work=0;
            List<Map<String,Object>> candidates=new ArrayList<>();
            try {
                walk(name,discovery,use,new HashSet<>(),0,candidates);
                for(var relation:candidates) {
                    var a=Json.map(relation.get("attributes"));
                    String identity=Json.write(List.of(discovery,a.get("annotation_fqn"),a.get("meta_annotation_fqn"),
                            Json.map(a.get("source_origin")).get("path")));
                    if(!emitted.add(identity)) continue;
                    relation.put("identity",identity);relation.put("source",discovery);
                    relation.put("parent",use.get("parent"));output.accept(relation);
                }
            } catch(Incomplete exhausted) {
                Json.list(model.get("diagnostics")).add(Json.object("source",discovery,"start",use.get("start"),"end",use.get("end"),
                    "code","ANNOTATION_META_INCOMPLETE","severity","WARNING","phase","annotation_meta",
                    "message",exhausted.getMessage(),"count",1,"annotation",name));
            }
        }
    }

    private void walk(String name,String originSource,Map<String,Object> position,Set<String> active,int depth,
            List<Map<String,Object>> candidates) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if(!active.add(name)) return;
        try {
            int total=sourceWork.merge(discovery,1,Integer::sum);
            if(++work>100000 || total>1000000) throw new Incomplete("annotation-meta-work-budget");
            var header=headers.get(name);
            var declaration=declarations.get(name);
            boolean sourceAnnotation=declaration!=null && "ANNOTATION_TYPE".equals(Json.map(declaration.get("attributes")).get("element_kind"));
            if(!sourceAnnotation && (header==null || !Boolean.TRUE.equals(header.get("complete"))))
                throw new Incomplete("annotation-meta-header-unavailable");
            if(declaration!=null) {
                originSource=Json.string(declaration,"source");position=declaration;
            }
            List<Object> annotations=!sourceAnnotation ? Json.list(header.get("annotations"))
                    : Json.list(Json.map(declaration.get("attributes")).get("annotations"));
            if(depth>=16 && !annotations.isEmpty()) throw new Incomplete("annotation-meta-depth-budget");
            for(Object raw:annotations) {
                String parent=Json.string(Json.map(raw),"type");
                if(parent.isEmpty()) throw new Incomplete("annotation-meta-name-unavailable");
                var evidence=Json.object("schema_version",1,"annotation_fqn",name,"meta_annotation_fqn",parent,
                        "raw_name",parent,"resolution_status","exact","mechanism","java.annotation.meta",
                        "source_origin",Json.object("path",originSource,"start",position.get("start"),"end",position.get("end")));
                candidates.add(Json.object("attributes",evidence));
                walk(parent,originSource,position,active,depth+1,candidates);
            }
        } finally { active.remove(name); }
    }

    private static final class Incomplete extends RuntimeException {
        Incomplete(String message) { super(message); }
    }
}
