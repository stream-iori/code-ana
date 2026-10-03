package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.common.Json;
import java.util.*;

/** Separate javac proof for primitive expressions over generated auxiliary signatures. */
final class SupplementalExpressions {
    private static final Set<String> PRIMITIVES =
            Set.of("BOOLEAN", "BYTE", "SHORT", "CHAR", "INT", "LONG", "FLOAT", "DOUBLE");

    private static String key(Map<String, Object> node) {
        return Json.write(List.of(node.get("source"), node.get("start"), node.get("end"), node.get("kind")));
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? Json.map(value) : Map.of();
    }

    private static Map<String, Map<String, Object>> unique(List<Object> syntax) {
        Map<String, Map<String, Object>> output = new TreeMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Object value : syntax) {
            Map<String, Object> node = Json.map(value);
            if (!node.containsKey("expression_type_model")) continue;
            String identity = key(node);
            if (output.putIfAbsent(identity, node) != null) ambiguous.add(identity);
        }
        ambiguous.forEach(output::remove);
        return output;
    }

    static void derive(Map<String, Object> original, Map<String, Object> supplemental,
            Set<String> auxiliary, String signatureHash, String inputHash) {
        Map<String, Map<String, Object>> candidates = unique(Json.list(original.get("syntax")));
        Map<String, Object> proofs = new TreeMap<>();
        for (var entry : unique(Json.list(supplemental.get("syntax"))).entrySet()) {
            Map<String, Object> previous = candidates.get(entry.getKey()), node = entry.getValue();
            if (previous == null || auxiliary.contains(node.get("source"))) continue;
            Map<String, Object> oldType = map(previous.get("expression_type_model"));
            Map<String, Object> type = map(node.get("expression_type_model"));
            if (!Set.of("ERROR", "UNKNOWN", "NONE").contains(oldType.get("kind"))
                    || !PRIMITIVES.contains(type.get("kind"))
                    || !Boolean.TRUE.equals(type.get("complete"))) continue;
            proofs.put(entry.getKey(), ExtensionContext.freezeMap(Json.object(
                    "source", node.get("source"), "start", node.get("start"), "end", node.get("end"),
                    "node_kind", node.get("kind"), "original_type_kind", oldType.get("kind"),
                    "type_model", type, "binding_origin", "javac_generated_signatures",
                    "signature_sha256", signatureHash, "auxiliary_inputs_sha256", inputHash,
                    "original_evidence_retained", true)));
        }
        // Syntax archive rows are immutable. Keep additional proof outside them.
        if (!proofs.isEmpty()) original.put("supplemental_expressions", proofs);
    }

    static Map<String, Object> type(Map<String, Object> model, Map<String, Object> node) {
        return map(map(map(model.get("supplemental_expressions")).get(key(node))).get("type_model"));
    }
}
