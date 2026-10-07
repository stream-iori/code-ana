package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.common.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Supplemental javac proof over modeled auxiliary signatures. Original evidence stays intact. */
final class SupplementalTypes {
    private record Callback(String source, long start, long end, String signature) {}
    private static Map<String,Object> map(Object raw) { return raw instanceof Map ? Json.map(raw) : Map.of(); }
    private static String text(Object raw) { return raw instanceof String ? (String)raw : ""; }
    private static Callback key(Map<String,Object> declaration) {
        return new Callback(text(declaration.get("source")), Json.number(declaration,"start"),
                Json.number(declaration,"end"),text(declaration.get("signature")));
    }

    static boolean complete(Map<String,Object> type, int depth) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if (depth >= 64 || type.isEmpty() || !Boolean.TRUE.equals(type.get("complete"))) return false;
        if (Set.of("ERROR","UNKNOWN","CAPTURE").contains(text(type.get("kind")))) return false;
        for (String field : List.of("component","enclosing","return","extends","super"))
            if (type.get(field) instanceof Map && !complete(map(type.get(field)),depth+1)) return false;
        for (String field : List.of("arguments","parameters"))
            for (Object value : type.get(field) instanceof List ? Json.list(type.get(field)) : List.of())
                if (!complete(map(value),depth+1)) return false;
        return true;
    }

    static void derive(Map<String,Object> context, Map<String,Object> original, CompilerSession session) throws Exception {
        session.mark(Json.string(context,"context"),"supplemental_eligibility");
        try(var environment=new dev.codeana.analysis.model.JavaSemanticModel(original,64L*1024*1024)) {
            derive(context,original,session,environment.declarationRows());
        }
    }

    private static void derive(Map<String,Object> context, Map<String,Object> original, CompilerSession session,
            List<Map<String,Object>> all) throws Exception {
        Set<String> auxiliary = new HashSet<>();
        List<Object> primary = new ArrayList<>(), sourceProof = new ArrayList<>();
        boolean potential=false;
        for(var declaration:all) {
            var attributes=map(declaration.get("attributes"));
            if("LAMBDA".equals(attributes.get("element_kind"))
                    || "LOCAL_VARIABLE".equals(attributes.get("element_kind"))
                    && Set.of("ERROR","UNKNOWN").contains(text(map(attributes.get("type_model")).get("kind")))) {
                potential=true;break;
            }
        }
        if(!potential)return;
        var projected=ModuleAbiEligibility.projections(context,original,all);
        Set<String> eligible=projected.keySet();
        session.mark(Json.string(context,"context"),"supplemental_projection");
        for (Object raw : Json.list(context.get("sources"))) {
            var unit=Json.map(raw);
            if (eligible.contains(unit.get("world"))) {
                auxiliary.add(Json.string(unit,"path"));
                sourceProof.add(List.of(unit.get("path"),unit.get("hash"),unit.get("world")));
            } else primary.add(unit);
        }
        if (auxiliary.isEmpty() || primary.isEmpty()) return;
        boolean generated=false;
        Map<Callback,Map<String,Object>> wanted=new HashMap<>(), variables=new HashMap<>();
        Map<String,Map<String,Object>> generatedTypes=new HashMap<>();
        for(var declaration:all) {
            var attributes=map(declaration.get("attributes"));
            generated |= auxiliary.contains(declaration.get("source")) && "lombok_model".equals(attributes.get("binding_origin"));
            if ("LAMBDA".equals(attributes.get("element_kind")) && !auxiliary.contains(declaration.get("source")))
                wanted.put(key(declaration),declaration);
            if ("LOCAL_VARIABLE".equals(attributes.get("element_kind")) && !auxiliary.contains(declaration.get("source"))
                    && Set.of("ERROR","UNKNOWN").contains(text(map(attributes.get("type_model")).get("kind"))))
                variables.put(key(declaration),declaration);
            if ("CLASS".equals(attributes.get("element_kind")) && auxiliary.contains(declaration.get("source"))
                    && "lombok_model".equals(attributes.get("binding_origin")))generatedTypes.put(text(declaration.get("signature")),declaration);
        }
        if (!generated || wanted.isEmpty() && variables.isEmpty()) return;
        Map<String,byte[]> classes=new TreeMap<>();
        try {
            for(var world:projected.values())for(var entry:world.entrySet())
                if(classes.putIfAbsent(entry.getKey(),entry.getValue())!=null)
                    throw new IllegalArgumentException("SIGNATURE_TYPE_IDENTITY");
        } catch(IllegalArgumentException unsupported) {
            original.put("supplemental_signature_report",Json.object("used",false,"reason",unsupported.getMessage()));
            return;
        }
        session.mark(Json.string(context,"context"),"supplemental_signature_prepare");
        Path directory=session.newInputDirectory("supplemental-signatures-");
        Path jar=directory.resolve("signatures.jar");
        try {
            long size=0;
            try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar,StandardOpenOption.CREATE_NEW))) {
                for(var entry:classes.entrySet()) {
                    session.check();size=Math.addExact(size,entry.getValue().length);
                    if(size>64L*1024*1024)throw new IOException("SUPPLEMENTAL_SIGNATURE_DISK_BUDGET");
                    JarEntry name=new JarEntry(entry.getKey().replace('.','/')+".class");name.setTime(0);
                    out.putNextEntry(name);out.write(entry.getValue());out.closeEntry();
                }
            }
            String checksum=Json.sha(jar), inputs=Json.sha(Json.write(sourceProof).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var supplementalContext=new LinkedHashMap<>(context);
            supplementalContext.put("analysis_purpose","supplemental");
            List<Object> classpath=new ArrayList<>();classpath.add(jar.toString());classpath.addAll(Json.list(context.get("classpath")));
            supplementalContext.put("sources",primary);supplementalContext.put("classpath",classpath);
            session.mark(Json.string(context,"context"),"supplemental_javac");
            var supplemental=session.analyze(supplementalContext);
            int accepted=0;
            try {
                SupplementalExpressions.derive(original, supplemental, auxiliary, checksum, inputs);
                SupplementalReceivers.derive(original, supplemental, auxiliary, checksum, inputs);
                for(Object raw:Json.list(supplemental.get("declarations"))) {
                    var declaration=Json.map(raw); var attributes=map(declaration.get("attributes"));
                    if("LOCAL_VARIABLE".equals(attributes.get("element_kind"))) {
                        var destination=variables.get(key(declaration));var type=map(attributes.get("type_model"));
                        if(destination!=null && "DECLARED".equals(type.get("kind")) && complete(type,0)
                                && generatedTypes.containsKey(type.get("element")))
                            map(destination.get("attributes")).put("supplemental_variable",ExtensionContext.freezeMap(
                                    Json.object("type_model",type,"binding_origin","javac_generated_signatures",
                                            "signature_sha256",checksum,"auxiliary_inputs_sha256",inputs,
                                            "original_evidence_retained",true)));
                    }
                    if(!"LAMBDA".equals(attributes.get("element_kind")))continue;
                    var destination=wanted.get(key(declaration));
                    var functional=map(attributes.get("functional_type_model")); var sam=map(attributes.get("sam"));
                    if(destination==null || !complete(functional,0) || !Boolean.TRUE.equals(sam.get("complete")))continue;
                    List<Object> parameters=new ArrayList<>();
                    boolean valid=true;
                    var descriptor=map(sam.get("instantiated_type"));
                    for(Object parameter:Json.list(descriptor.get("parameters"))) {
                        if(!complete(map(parameter),0)){valid=false;break;}parameters.add(parameter);
                    }
                    if(!valid || !complete(descriptor,0))continue;
                    var previous=map(destination.get("attributes"));
                    if (Json.write(functional).equals(Json.write(map(previous.get("functional_type_model"))))
                            && Json.write(sam).equals(Json.write(map(previous.get("sam"))))) continue;
                    map(destination.get("attributes")).put("supplemental_callback",ExtensionContext.freezeMap(
                            Json.object("functional_type_model",functional,"parameter_type_models",parameters,"sam",sam,
                                    "binding_origin","javac_generated_signatures","signature_sha256",checksum,
                                    "auxiliary_inputs_sha256",inputs,"original_evidence_retained",true)));
                    accepted++;
                }
            } finally {session.releaseModel(supplemental);}
            original.put("supplemental_signature_report",Json.object("used",true,"callbacks",accepted,
                    "classes",classes.size(),"signature_sha256",checksum));
        } finally {
            Files.deleteIfExists(jar);Files.deleteIfExists(directory);
        }
    }
}
