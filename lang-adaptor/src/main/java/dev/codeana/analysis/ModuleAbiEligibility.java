package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.util.*;

/** Match the frozen module boundary before attributing generated auxiliary signatures. */
final class ModuleAbiEligibility {
    private static final Set<String> TYPES = Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE");

    private ModuleAbiEligibility() {}

    static Set<String> worlds(Map<String, Object> context, Map<String, Object> model,
            List<Map<String, Object>> declarations) {
        Map<String, String> worlds = new HashMap<>();
        Set<String> eligible = new HashSet<>();
        for (Object raw : Json.list(context.get("sources"))) {
            var source = Json.map(raw);
            String world = Json.string(source, "world");
            worlds.put(Json.string(source, "path"), world);
            if (!world.equals(context.get("world")) && "MAIN".equals(source.get("scope")))
                eligible.add(world);
        }
        for (Object raw : Json.list(model.get("diagnostics"))) {
            var diagnostic = Json.map(raw);
            if ("SOURCE_ENCODING_INVALID".equals(diagnostic.get("code"))
                    || "JAVAC_PARSE_FAILED".equals(diagnostic.get("code"))
                    || "ERROR".equals(diagnostic.get("severity")) && "parse".equals(diagnostic.get("phase")))
                eligible.remove(worlds.get(Json.string(diagnostic, "source")));
        }
        Map<String, Map<String, Object>> bySignature = new HashMap<>();
        for (var declaration : declarations)
            bySignature.put(Json.string(declaration, "signature"), declaration);
        for (var declaration : declarations) {
            var attributes = Json.map(declaration.get("attributes"));
            if ("lombok_model".equals(attributes.get("binding_origin"))) continue;
            List<Object> annotations = ModelAnnotations.declared(declaration);
            boolean lombok = annotations.stream().anyMatch(raw -> {
                String name = Json.string(Json.map(raw), "type");
                return name.startsWith("lombok.") && !name.equals("lombok.Generated");
            });
            if (!lombok) continue;
            var owner = declaration;
            Set<String> seen = new HashSet<>();
            while (owner != null && !TYPES.contains(Json.map(owner.get("attributes")).get("element_kind"))) {
                if (!seen.add(Json.string(owner, "signature"))) {
                    owner = null;
                    break;
                }
                owner = bySignature.get(Json.string(owner, "parent"));
            }
            Map<String, Object> summary = owner == null ? Map.of() : LombokModel.summary(owner);
            if (owner != null && Json.map(owner.get("attributes")).get("lombok_summary") instanceof Map)
                summary = Json.map(Json.map(owner.get("attributes")).get("lombok_summary"));
            if (!"complete".equals(summary.get("coverage")))
                eligible.remove(worlds.get(Json.string(declaration, "source")));
        }
        Map<String, List<Map<String, Object>>> groups = new HashMap<>();
        for (var declaration : declarations)
            groups.computeIfAbsent(worlds.get(Json.string(declaration, "source")), ignored -> new ArrayList<>())
                    .add(declaration);
        SupplementalAbi abi = new SupplementalAbi(declarations);
        for (String world : new TreeSet<>(eligible)) {
            try {
                List<Map<String, Object>> headers = new ArrayList<>();
                for (var declaration : groups.getOrDefault(world, List.of()))
                    headers.add(abi.project(declaration));
                if (SignatureProjection.project(headers).isEmpty()) eligible.remove(world);
            } catch (IllegalArgumentException incomplete) {
                // Keep this world's sealed SOURCE; an unavailable neighbor does not poison others.
                eligible.remove(world);
            }
        }
        return eligible;
    }
}
