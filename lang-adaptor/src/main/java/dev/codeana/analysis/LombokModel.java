package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.util.*;

/**
 * Declaration-only Lombok overlay. Does not run Lombok and never invents a generated method body.
 */
public final class LombokModel {
    private LombokModel() {}

    public static void augment(Map<String, Object> model, Map<String, Object> context) {
        augment(model, context, ignored -> {});
    }

    public static void augment(Map<String, Object> model, Map<String, Object> context,
            java.util.function.Consumer<String> progress) {
        if (!"model".equals(context.get("lombok"))) return;
        progress.accept("inventory");
        new LombokDeclarations(model, context, progress).apply();
    }

    /** Anatomist AST eligibility is per source unit, including auxiliary binding sources. */
    public static Set<String> pendingSources(Map<String, Object> model) {
        Set<String> seen = new HashSet<>(), pending = new HashSet<>();
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            Map<String, Object> attributes = Json.map(declaration.get("attributes"));
            String source = Json.string(declaration, "source");
            if (attributes.containsKey("lombok_summary") || !summary(declaration).isEmpty())
                seen.add(source);
            if ("lombok_model".equals(attributes.get("binding_origin"))) continue;
            if (ModelAnnotations.declared(declaration).stream().map(Json::map)
                    .anyMatch(a -> Json.string(a, "type").startsWith("lombok.")))
                pending.add(source);
        }
        pending.removeAll(seen);
        return Set.copyOf(pending);
    }

    public static void recover(Map<String, Object> model, Map<String, Object> context) {
        if (!"model".equals(context.get("lombok"))) return;
        Map<String, Map<String, Object>> known = new HashMap<>();
        Set<String> altered = new HashSet<>();
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> d = Json.map(raw); known.put(Json.string(d, "signature"), d);
            if (Boolean.TRUE.equals(summary(d).get("constructor_replaced"))) altered.add(Json.string(d, "signature"));
        }
        Json.list(model.get("observations")).removeIf(raw -> {
            String parent = Json.string(Json.map(raw), "parent");
            return parent.contains("#<init>(") && !known.containsKey(parent)
                    && altered.contains(parent.substring(0, parent.indexOf('#')));
        });
        for (Object raw : Json.list(model.get("observations"))) {
            Map<String, Object> n = Json.map(raw); String target = Json.string(n, "target");
            Map<String, Object> d = known.get(target);
            boolean modeled = d != null && "lombok_model".equals(Json.map(d.get("attributes")).get("binding_origin"));
            boolean removed = target.contains("#<init>(") && d == null && altered.contains(target.substring(0, target.indexOf('#')));
            if (!modeled && !removed) continue;
            n.put("resolved", false); n.put("complete", false); n.put("target", "");
            Map<String, Object> attrs = Json.map(n.get("attributes"));
            attrs.put("binding_origin", "lombok_model"); attrs.put("declaration_resolved", false);
            attrs.put("evidence_complete", false); attrs.put("reason", "static_declaration_overlay");
        }
    }

    public static Map<String, Object> summary(Map<String, Object> declaration) {
        Object metadata = Json.map(declaration.get("attributes")).get("model_extensions");
        if (!(metadata instanceof Map)) return Map.of();
        Object lombok = Json.map(metadata).get("lombok-model-v1");
        if (!(lombok instanceof Map)) return Map.of();
        Object summary = Json.map(lombok).get("summary");
        return summary instanceof Map ? Json.map(summary) : Map.of();
    }

}
