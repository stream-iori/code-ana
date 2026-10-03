package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.analysis.extension.ExtensionPoint;
import dev.codeana.common.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Task-local proof of MAIN annotation declarations under that module's own captured classpath. */
final class ModuleAnnotationEvidence {
    private static final long MAX_BYTES = 32L << 20;
    private record Proof(Map<String,String> sources, String context, String classpath,
                         Map<String,Map<String,Object>> declarations, Map<String,LombokConfiguration.Effective> configurations) {}
    private final Map<String,Proof> proofs = new HashMap<>();
    private final Map<Path,String> inputHashes = new HashMap<>();
    private long bytes;
    private final Set<String> cyclic;

    ModuleAnnotationEvidence(List<Object> contexts) {
        Map<String,Set<String>> dependencies = new TreeMap<>();
        for (Object raw : contexts) {
            var context=Json.map(raw);String world=Json.string(context,"world");Set<String> parents=new TreeSet<>();
            for (Object input : Json.list(context.get("sources"))) {
                String parent=Json.string(Json.map(input),"world");if (!world.equals(parent)) parents.add(parent);
            }
            dependencies.put(world,parents);
        }
        while (!dependencies.isEmpty()) {
            List<String> ready=dependencies.entrySet().stream()
                    .filter(entry -> Collections.disjoint(entry.getValue(),dependencies.keySet()))
                    .map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) break;
            for (String world : ready) dependencies.remove(world);
        }
        cyclic=Set.copyOf(dependencies.keySet());
    }

    static List<Map<String,Object>> expand(List<Map<String,Object>> work, List<Object> contexts) {
        Map<String,Map<String,Object>> available = new TreeMap<>(), expanded = new LinkedHashMap<>();
        for (Object raw : contexts) {
            var context = Json.map(raw);
            if ("MAIN".equals(context.get("scope"))) available.put(Json.string(context,"world"),context);
        }
        for (var context : work) expanded.put(Json.string(context,"world"),context);
        ArrayDeque<Map<String,Object>> pending = new ArrayDeque<>(work);
        while (!pending.isEmpty()) {
            var consumer = pending.removeFirst();
            if (!"model".equals(consumer.get("lombok"))) continue;
            for (Object raw : Json.list(consumer.get("sources"))) {
                var source = Json.map(raw); String world = Json.string(source,"world");
                var parent = available.get(world);
                if (parent == null || expanded.containsKey(world)
                        || world.equals(consumer.get("world"))) continue;
                var prerequisite = new LinkedHashMap<>(parent);
                prerequisite.put("prerequisite",true);
                List<Object> inputs = new ArrayList<>();
                for (Object unit : Json.list(parent.get("sources"))) {
                    var hidden = new LinkedHashMap<>(Json.map(unit));
                    hidden.put("publish",false);hidden.put("auxiliary",true);inputs.add(hidden);
                }
                prerequisite.put("sources",inputs);
                expanded.put(world,prerequisite);pending.add(prerequisite);
            }
        }
        return new ArrayList<>(expanded.values());
    }

    private synchronized String classpath(Map<String,Object> context) throws IOException {
        List<Object> inputs = new ArrayList<>();
        for (Object raw : Json.list(context.get("classpath"))) {
            Path path = Path.of((String) raw);String hash = inputHashes.get(path);
            if (hash == null) {hash=Json.sha(path);inputHashes.put(path,hash);}
            inputs.add(List.of(path.getFileName().toString(),hash));
        }
        return Json.sha(Json.write(inputs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    synchronized void publish(Map<String,Object> context, Map<String,Object> model) throws IOException {
        if (!"MAIN".equals(context.get("scope")) || !"model".equals(context.get("lombok"))) return;
        String world = Json.string(context,"world");
        if (cyclic.contains(world)) return;
        List<Map<String,Object>> declarations = Json.list(model.get("declarations")).stream().map(Json::map).toList();
        var candidate = new LinkedHashMap<>(context);candidate.put("world","");
        if (!ModuleAbiEligibility.worlds(candidate,model,declarations).contains(world)) return;
        Map<String,String> sources = new TreeMap<>();
        for (Object raw : Json.list(context.get("sources"))) {
            var unit = Json.map(raw);
            if (world.equals(unit.get("world"))) sources.put(Json.string(unit,"path"),Json.string(unit,"hash"));
        }
        Map<String,Map<String,Object>> headers = new TreeMap<>();Set<String> ambiguous = new HashSet<>();
        Map<String,LombokConfiguration.Effective> configurations=new HashMap<>();
        LombokConfiguration configuration=new LombokConfiguration(context);
        for (var declaration : declarations) {
            String source = Json.string(declaration,"source"), signature=Json.string(declaration,"signature");
            var attributes = Json.map(declaration.get("attributes"));
            if (!sources.containsKey(source) || "lombok_model".equals(attributes.get("binding_origin"))) continue;
            List<Object> annotations = ModelAnnotations.declared(declaration).stream()
                    .filter(raw -> Json.string(Json.map(raw),"type").startsWith("lombok.")).toList();
            if (annotations.isEmpty()) continue;
            configurations.put(source,configuration.forSource(source));
            var header = ExtensionContext.freezeMap(Json.object("source",source,"source_hash",sources.get(source),
                    "signature",signature,"annotations",Json.parse(Json.write(annotations).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            if (headers.putIfAbsent(signature,header)!=null) ambiguous.add(signature);
        }
        if (!ambiguous.isEmpty() || headers.isEmpty()) return;
        long incoming = Json.write(headers).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (incoming > MAX_BYTES-bytes) throw new ExtensionPoint.GateFailure("MODULE_ANNOTATION_EVIDENCE_BUDGET");
        proofs.put(world,new Proof(Map.copyOf(sources),Json.string(context,"context"),classpath(context),Map.copyOf(headers),Map.copyOf(configurations)));
        bytes += incoming;
    }

    synchronized void apply(Map<String,Object> context, Map<String,Object> model) throws IOException {
        String own=Json.string(context,"world"), currentClasspath=null;
        LombokConfiguration configuration=new LombokConfiguration(context);
        Map<String,Map<String,Object>> inputs = new HashMap<>();
        Map<String,Map<String,String>> inventory = new HashMap<>();
        for (Object raw : Json.list(context.get("sources"))) {
            var source=Json.map(raw);String world=Json.string(source,"world"), path=Json.string(source,"path");
            inputs.put(path,source);inventory.computeIfAbsent(world,ignored -> new TreeMap<>()).put(path,Json.string(source,"hash"));
        }
        int applied=0;
        for (Object raw : Json.list(model.get("declarations"))) {
            var declaration=Json.map(raw);var source=inputs.get(Json.string(declaration,"source"));
            if (source==null || own.equals(source.get("world")) || !"MAIN".equals(source.get("scope"))) continue;
            Proof proof=proofs.get(source.get("world"));
            if (proof==null || !proof.sources.equals(inventory.get(source.get("world")))) continue;
            if (currentClasspath==null) currentClasspath=classpath(context);
            if (proof.classpath.equals(currentClasspath)) continue;
            var header=proof.declarations.get(Json.string(declaration,"signature"));
            if (header==null || !Objects.equals(source.get("hash"),header.get("source_hash"))) continue;
            if (!Objects.equals(proof.configurations.get(source.get("path")),configuration.forSource(Json.string(source,"path")))) continue;
            var attributes=Json.map(declaration.get("attributes"));
            // An already resolved consumer annotation remains authoritative in its own world.
            boolean conflicting=Json.list(attributes.get("annotations")).stream().map(Json::map)
                    .anyMatch(a -> Boolean.TRUE.equals(a.get("complete")) && Json.string(a,"type").startsWith("lombok."));
            if (conflicting) continue;
            Json.map(declaration.get("attributes")).put("prerequisite_annotations",ExtensionContext.freezeMap(
                    Json.object("annotations",header.get("annotations"),"source",source.get("path"),
                    "source_hash",source.get("hash"),"world",source.get("world"),"context",proof.context,
                    "classpath_sha256",proof.classpath,"configuration_inputs",proof.configurations.get(source.get("path")).inputs(),"original_evidence_retained",true)));
            applied++;
        }
        if (applied>0) model.put("prerequisite_annotation_report",Json.object("declarations",applied,"original_evidence_retained",true));
    }
}
