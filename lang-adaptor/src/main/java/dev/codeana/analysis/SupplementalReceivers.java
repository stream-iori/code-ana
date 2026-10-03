package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.common.Json;
import java.util.*;

/** Independent receiver evidence; a generated ABI does not confirm an upstream call. */
final class SupplementalReceivers {
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? Json.map(value) : Map.of();
    }

    private static String key(Map<String, Object> call) {
        var attributes = map(call.get("attributes"));
        return Json.write(List.of(call.get("source"), call.get("start"), call.get("end"),
                Json.string(attributes, "lookup_name"), Json.string(attributes, "mechanism")));
    }

    private static Map<String, Map<String, Object>> unique(List<Object> observations) {
        Map<String, Map<String, Object>> calls = new TreeMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Object raw : observations) {
            var call = Json.map(raw);
            if (!"call".equals(call.get("kind"))) continue;
            String identity = key(call);
            if (calls.putIfAbsent(identity, call) != null) ambiguous.add(identity);
        }
        ambiguous.forEach(calls::remove);
        return calls;
    }

    static void derive(Map<String, Object> original, Map<String, Object> supplemental,
            Set<String> auxiliary, String signatureHash, String inputHash) {
        Map<String, Map<String, Object>> generated = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Object raw : Json.list(original.get("declarations"))) {
            var declaration = Json.map(raw);
            if (!auxiliary.contains(declaration.get("source"))) continue;
            String signature = Json.string(declaration, "signature");
            if (generated.putIfAbsent(signature, declaration) != null) ambiguous.add(signature);
        }
        ambiguous.forEach(generated::remove);
        generated.values().removeIf(d -> !"lombok_model".equals(map(d.get("attributes")).get("binding_origin")));
        var previous = unique(Json.list(original.get("observations")));
        Map<String, Object> proofs = new TreeMap<>();
        for (var entry : unique(Json.list(supplemental.get("observations"))).entrySet()) {
            var call = entry.getValue(); var before = previous.get(entry.getKey());
            if (before == null || auxiliary.contains(call.get("source"))
                    || !Boolean.TRUE.equals(call.get("resolved"))
                    || !Boolean.TRUE.equals(call.get("complete"))) continue;
            var attributes = map(call.get("attributes"));
            var receiver = map(attributes.get("receiver_type_model"));
            var oldReceiver = map(map(before.get("attributes")).get("receiver_type_model"));
            if (!oldReceiver.isEmpty() && !Set.of("ERROR", "UNKNOWN", "NONE").contains(oldReceiver.get("kind"))) continue;
            if (!"DECLARED".equals(receiver.get("kind")) || !SupplementalTypes.complete(receiver, 0)) continue;
            var owner = generated.get(Json.string(receiver, "element"));
            var selected = generated.get(Json.string(attributes, "javac_selected_declaration"));
            if (owner == null || selected == null
                    || !"CLASS".equals(map(owner.get("attributes")).get("element_kind"))
                    || !"METHOD".equals(map(selected.get("attributes")).get("element_kind"))) continue;
            proofs.put(entry.getKey(), ExtensionContext.freezeMap(Json.object(
                    "source", call.get("source"), "start", call.get("start"), "end", call.get("end"),
                    "receiver_type_model", receiver, "selected_declaration", selected.get("signature"),
                    "binding_origin", "javac_generated_signatures", "signature_sha256", signatureHash,
                    "auxiliary_inputs_sha256", inputHash, "original_evidence_retained", true)));
        }
        if (!proofs.isEmpty()) original.put("supplemental_receivers", proofs);
    }

    static Map<String, Object> receiver(Map<String, Object> model, Map<String, Object> call) {
        return map(map(map(model.get("supplemental_receivers")).get(key(call))).get("receiver_type_model"));
    }
}
