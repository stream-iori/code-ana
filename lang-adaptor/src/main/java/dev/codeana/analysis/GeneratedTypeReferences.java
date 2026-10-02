package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.util.*;
import java.util.function.Consumer;

/** Signature references absent from source syntax after a detached declaration overlay. */
final class GeneratedTypeReferences {
    record Use(String target, String context, int genericDepth) {}

    static void emit(Map<String, Object> declaration, Consumer<Use> output) {
        Map<String, Object> attrs = Json.map(declaration.get("attributes"));
        if (!attrs.containsKey("extension_producer")) return;
        String kind = Json.string(attrs, "element_kind");
        int[] work = {0};
        if (Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT").contains(kind))
            references(attrs.get("type_model"), "field_type", 0, 0, work, output);
        else if (Set.of("METHOD", "CONSTRUCTOR").contains(kind)) {
            if (kind.equals("METHOD"))
                references(attrs.get("return_type_model"), "return_type", 0, 0, work, output);
            for (Object parameter : TypeOperations.list(attrs.get("parameter_type_models")))
                references(parameter, "parameter_type", 0, 0, work, output);
        }
    }

    private static void references(Object raw, String context, int depth, int nesting,
            int[] work, Consumer<Use> output) {
        if (!(raw instanceof Map) || depth > 5) return;
        if (++work[0] > 100_000 || nesting >= 64)
            throw new IllegalArgumentException("GENERATED_TYPE_REFERENCE_BUDGET");
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        Map<String, Object> type = Json.map(raw);
        String kind = TypeOperations.kind(type);
        if (kind.equals("ARRAY")) {
            references(type.get("component"), context, depth, nesting + 1, work, output);
            return;
        }
        // Match the frozen reference's declaration-signature projection: formal/capture
        // bounds and wildcard bounds are not extra signature occurrences.
        if (!kind.equals("DECLARED")) return;
        String target = TypeOperations.text(type.get("element"));
        if (!target.isEmpty()) output.accept(new Use(target, context, depth));
        for (Object argument : TypeOperations.list(type.get("arguments")))
            references(argument, "generic_arg", depth + 1, nesting + 1, work, output);
    }
}
