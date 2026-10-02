package dev.codeana.analysis;

import static dev.codeana.analysis.TypeOperations.*;
import dev.codeana.common.Json;
import org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.util.*;

/** Detached override recovery adapted from Anatomist; never replaces javac override evidence. */
final class ModeledOverrides {
    private record Parent(Map<String, Object> type, Set<String> packages) {}
    private record Target(String signature, Map<String, Object> attributes) {}
    private final Map<String, Map<String, Object>> sources;
    private final Map<String, List<Map<String, Object>>> members;
    private final Map<String, Map<String, Object>> known;
    private final TypeOperations types;
    private final BinaryIndex binary;
    private final Map<String, Object> model;
    private final Set<String> modeledNames = new HashSet<>(), existing = new HashSet<>();
    private final Set<String> external = new HashSet<>(), published = new HashSet<>();
    private int work;

    ModeledOverrides(Map<String, Object> model, Map<String, Object> context, Map<String, Map<String, Object>> sources,
            Map<String, List<Map<String, Object>>> members, Map<String, Map<String, Object>> known,
            TypeOperations types, BinaryIndex binary) {
        this.model = model; this.sources = sources; this.members = members; this.known = known;
        this.types = types; this.binary = binary;
        for(Object raw:list(context.get("sources"))) if(Boolean.TRUE.equals(map(raw).get("publish"))) published.add(text(map(raw).get("path")));
        for(Object raw:list(model.get("external"))) external.add(text(map(raw).get("signature")));
        for (var values : members.values()) for (var method : values)
            if (modeled(method) && "METHOD".equals(attrs(method).get("element_kind")))
                modeledNames.add(text(method.get("name")));
        for (Object raw : list(model.get("observations"))) {
            var observation = map(raw);
            if ("overrides".equals(observation.get("relation")))
                existing.add(text(observation.get("parent")) + '\0' + text(observation.get("target")));
        }
    }

    private static Map<String, Object> attrs(Map<String, Object> declaration) { return map(declaration.get("attributes")); }
    private static boolean modeled(Map<String, Object> declaration) { return "lombok_model".equals(attrs(declaration).get("binding_origin")); }
    private void step() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if (++work > 100000) throw new Budget();
    }
    private static String packageName(String name) { int dot=name.lastIndexOf('.'); return dot<0 ? "" : name.substring(0,dot); }
    private static int visibility(Set<String> modifiers) {
        return modifiers.contains("public") ? 3 : modifiers.contains("protected") ? 2 : modifiers.contains("private") ? 0 : 1;
    }
    private static Set<String> modifiers(Map<String, Object> method) {
        Set<String> out = new HashSet<>(); for(Object raw:list(attrs(method).get("modifiers"))) out.add(text(raw)); return out;
    }

    void generate() throws IOException {
        if (modeledNames.isEmpty()) return;
        // A binary target can be added to external rows later; never iterate that mutable index.
        List<Map<String, Object>> methods = members.values().stream().flatMap(Collection::stream).toList();
        for (var method : methods) {
            if (!published.contains(text(method.get("source")))) continue;
            if (!"METHOD".equals(attrs(method).get("element_kind"))) continue;
            Set<String> own = modifiers(method);
            if (own.contains("private") || own.contains("static")) continue;
            if (!modeled(method) && !modeledNames.contains(text(method.get("name")))) continue;
            work=0; types.reset();
            try {
                if (!modeled(method) && !modeledAncestor(method)) continue;
                List<Target> targets = targets(method, own);
                for (Target target : targets) emit(method, target);
            } catch (Budget exhausted) {
                gap(method, "modeled-override-budget");
            }
        }
    }

    private boolean modeledAncestor(Map<String, Object> method) {
        ArrayDeque<String> pending=new ArrayDeque<>();
        pending.add(text(method.get("parent"))); Set<String> seen=new HashSet<>();
        while(!pending.isEmpty()) {
            step(); if(seen.size()>=256) throw new Budget();
            String name=pending.removeFirst(); if(!seen.add(name)) continue;
            Map<String,Object> parent=sources.get(name); if(parent==null) continue;
            if(!name.equals(method.get("parent"))) for(var member:members.getOrDefault(name,List.of())) {
                step(); if(modeled(member) && Objects.equals(method.get("name"),member.get("name"))) return true;
            }
            Map<String,Object> a=attrs(parent);
            String superclass=text(map(a.get("superclass_type_model")).get("element"));
            if(!superclass.isEmpty()) pending.add(superclass);
            for(Object raw:list(a.get("interface_type_models"))) {
                String inherited=text(map(raw).get("element")); if(!inherited.isEmpty()) pending.add(inherited);
            }
        }
        return false;
    }

    private List<Target> targets(Map<String,Object> method, Set<String> own) throws IOException {
        Map<String,Object> owner=sources.get(text(method.get("parent")));
        if(owner==null) { gap(method,"modeled-override-owner-unavailable"); return List.of(); }
        String name=text(method.get("name"));
        List<Map<String,Object>> ownParameters=list(attrs(method).get("parameter_type_models")).stream().map(TypeOperations::map).toList();
        Map<String,Object> ownReturns=map(attrs(method).get("return_type_model"));
        ArrayDeque<Parent> pending=new ArrayDeque<>();
        Set<String> ownPackage=Set.of(packageName(text(owner.get("signature"))));
        for(var parent:types.parents(map(attrs(owner).get("type_model")),"override-root")) pending.add(new Parent(parent,ownPackage));
        Set<String> seen=new HashSet<>(); List<Target> selected=new ArrayList<>();
        while(!pending.isEmpty()) {
            step(); if(seen.size()>=256) throw new Budget();
            Parent current=pending.removeFirst(); String parentName=text(current.type.get("element"));
            if(parentName.isEmpty() || !kind(current.type).equals("DECLARED")) { gap(method,"modeled-override-hierarchy-incomplete"); continue; }
            String identity=Json.write(current.type)+Json.write(new TreeSet<>(current.packages));
            if(!seen.add(identity)) continue;
            Map<String,Object> parent=sources.get(parentName);
            Map<String,Map<String,Object>> bindings=types.bindings(current.type,"modeled-override");
            // A raw inherited class uses the erasure of its formals, not invented concrete arguments.
            if(list(current.type.get("arguments")).isEmpty()) for(Object raw:list(types.attributes(parentName).get("type_parameter_models"))) {
                Map<String,Object> formal=map(raw); List<Object> bounds=list(formal.get("bounds"));
                bindings.putIfAbsent(variable(formal),erase(bounds.isEmpty()?named("java.lang.Object"):map(bounds.getFirst()),0));
            }
            List<Map<String,Object>> candidates;
            if(parent!=null) candidates=members.getOrDefault(parentName,List.of());
            else {
                Map<String,Object> found=binary.readClass(parentName);
                if(found==null) { gap(method,"modeled-override-hierarchy-incomplete"); continue; }
                candidates=new ArrayList<>();
                for(var entry:map(found.get("members")).entrySet()) {
                    step(); Map<String,Object> member=map(entry.getValue());
                    if(!"METHOD".equals(member.get("kind"))) continue;
                    String signature=entry.getKey(), memberName=signature.substring(signature.indexOf('#')+1,signature.indexOf('('));
                    if(!name.equals(memberName)) continue;
                    int access=((Number)member.get("access")).intValue();
                    if((access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_BRIDGE))!=0) continue;
                    Set<String> flags=new HashSet<>();
                    if((access&Opcodes.ACC_PUBLIC)!=0) flags.add("public");
                    if((access&Opcodes.ACC_PROTECTED)!=0) flags.add("protected");
                    if((access&Opcodes.ACC_STATIC)!=0) flags.add("static");
                    if((access&Opcodes.ACC_FINAL)!=0) flags.add("final");
                    Map<String,Object> shape=map(member.get("type_model"));
                    if(!list(shape.get("type_parameters")).isEmpty()) continue; // Same binary generic-method guard as the reference.
                    candidates.add(Json.object("signature",signature,"name",memberName,"attributes",Json.object(
                            "element_kind","METHOD","modifiers",new ArrayList<>(flags),"parameter_type_models",shape.get("parameters"),
                            "return_type_model",shape.get("return"),"type_parameter_models",List.of(),"binary_member",member,
                            "binary_class",found.get("declaration"),"artifact_origin",found.get("artifact_origin"),
                            "binding_origin","binary_metadata","extension_producer","modeled-override-v1")));
                }
            }
            String parentPackage=packageName(parentName);
            for(var candidate:candidates) {
                step(); if(!"METHOD".equals(attrs(candidate).get("element_kind")) || !name.equals(candidate.get("name"))) continue;
                Set<String> inherited=modifiers(candidate);
                boolean visible=!inherited.contains("private") && (inherited.contains("public") || inherited.contains("protected")
                        || current.packages.stream().allMatch(parentPackage::equals));
                if(!visible) continue;
                List<Map<String,Object>> parentParameters=list(attrs(candidate).get("parameter_type_models")).stream()
                        .map(v -> types.substitute(map(v),bindings)).toList();
                Map<String,Object> parentReturn=types.substitute(map(attrs(candidate).get("return_type_model")),bindings);
                Match parameters=parameters(ownParameters,parentParameters);
                boolean erasureClash=parameters==Match.NO && ownParameters.size()==parentParameters.size();
                for(int i=0;erasureClash && i<ownParameters.size();i++)
                    erasureClash=types.same(erase(ownParameters.get(i),0),erase(parentParameters.get(i),0),0)==Match.YES;
                Match returns=returns(ownReturns,parentReturn);
                if(erasureClash || parameters==Match.YES && (inherited.contains("final") || inherited.contains("static")
                        || visibility(own)<visibility(inherited) || returns==Match.NO)) {
                    gap(method,"illegal-modeled-override"); return List.of(); // Discard all provisional edges for this method.
                }
                if(parameters==Match.YES && returns==Match.YES && (modeled(method)||modeled(candidate)))
                    selected.add(new Target(text(candidate.get("signature")),attrs(candidate)));
                else if(parameters==Match.UNKNOWN || parameters==Match.YES && returns==Match.UNKNOWN)
                    gap(method,"modeled-override-signature-incomplete");
            }
            Set<String> packages=new HashSet<>(current.packages);packages.add(parentPackage);
            for(var ancestor:types.parents(current.type,"modeled-override")) pending.add(new Parent(types.substitute(ancestor,bindings),Set.copyOf(packages)));
        }
        return selected;
    }

    private Map<String,Object> erase(Map<String,Object> type,int depth) {
        step(); if(depth>=64) throw new Budget();
        if(kind(type).equals("TYPEVAR")) return erase(map(type.getOrDefault("upper",named("java.lang.Object"))),depth+1);
        if(kind(type).equals("INTERSECTION")) { List<Object> bounds=list(type.get("bounds")); return erase(bounds.isEmpty()?named("java.lang.Object"):map(bounds.getFirst()),depth+1); }
        if(kind(type).equals("DECLARED")) return named(text(type.get("element")));
        if(kind(type).equals("ARRAY")) return Json.object("kind","ARRAY","complete",true,"component",erase(map(type.get("component")),depth+1));
        return type;
    }
    private Match parameters(List<Map<String,Object>> own,List<Map<String,Object>> parent) throws IOException {
        if(own.size()!=parent.size()) return Match.NO;
        Match result=Match.YES;
        for(int i=0;i<own.size();i++) {
            Match match=types.same(own.get(i),parent.get(i),0);
            if(match!=Match.YES && kind(own.get(i)).equals("DECLARED") && kind(parent.get(i)).equals("DECLARED")
                    && list(own.get(i).get("arguments")).isEmpty() && Objects.equals(own.get(i).get("element"),parent.get(i).get("element"))) match=Match.YES;
            result=and(result,match);
        }
        return result;
    }
    private Match returns(Map<String,Object> own,Map<String,Object> parent) throws IOException {
        if(primitive(kind(own)) || primitive(kind(parent)) || kind(own).equals("VOID") || kind(parent).equals("VOID"))
            return kind(own).equals(kind(parent))?Match.YES:Match.NO;
        return types.convert(own,parent,false);
    }

    private void gap(Map<String,Object> method,String reason) {
        Map<String,Object> a=attrs(method);
        @SuppressWarnings("unchecked") List<Object> reasons=(List<Object>)a.computeIfAbsent("modeled_override_incomplete",ignored -> new ArrayList<>());
        if(reasons.contains(reason)) return; reasons.add(reason);
        Json.list(model.get("diagnostics")).add(Json.object("source",method.get("source"),"start",method.get("start"),"end",method.get("end"),
                "code","MODELED_OVERRIDE_INCOMPLETE","severity","WARNING","phase","modeled_overrides","message",reason,"count",1,
                "signature",method.get("signature")));
    }
    private void emit(Map<String,Object> method,Target target) {
        String signature=text(method.get("signature"));
        if(!existing.add(signature+'\0'+target.signature)) return;
        if(!known.containsKey(target.signature) && external.add(target.signature)) {
            Json.list(model.get("external")).add(Json.object("signature",target.signature,
                    "name",target.signature.substring(target.signature.indexOf('#')+1,target.signature.indexOf('(')),"attributes",target.attributes));
        }
        Json.list(model.get("observations")).add(Json.object("kind","relation","source",method.get("source"),"start",method.get("start"),
                "end",method.get("end"),"parent",signature,"target",target.signature,"relation","overrides","resolved",false,"complete",false,
                "attributes",Json.object("binding_origin","modeled_override","modeled_input",true,"evidence_complete",false,
                        "declaration_resolved",false,"generated_from",attrs(method).get("generated_from"))));
    }
}
