package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.util.*;

/** NoCode metadata, separate from the original compiler evidence. */
final class SupplementalAbi {
    private final Map<String, Map<String, Object>> declarations = new HashMap<>();
    private int work;

    SupplementalAbi(List<Map<String, Object>> declarations) {
        for (var declaration : declarations)
            this.declarations.put(Json.string(declaration, "signature"), declaration);
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? Json.map(value) : Map.of();
    }
    private static List<Object> list(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }
    private static String text(Object value) { return value instanceof String ? (String) value : ""; }
    private static String kind(Map<String, Object> type) { return text(type.get("kind")); }

    private void step(int depth) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if (++work > 100000 || depth >= 64) throw new IllegalArgumentException("SUPPLEMENTAL_ABI_BUDGET");
    }

    private String binary(Map<String, Object> type) {
        String element = text(type.get("element"));
        if (element.isEmpty()) throw new IllegalArgumentException("SUPPLEMENTAL_BINARY_NAME_MISSING");
        var declaration = declarations.get(element);
        String binary = declaration == null ? element : text(map(declaration.get("attributes")).get("binary_name"));
        if (binary.isEmpty()) binary = element;
        return binary.replace('.', '/');
    }

    private static String primitive(String kind) {
        return switch (kind) {
            case "VOID" -> "V"; case "BOOLEAN" -> "Z"; case "BYTE" -> "B";
            case "SHORT" -> "S"; case "CHAR" -> "C"; case "INT" -> "I";
            case "LONG" -> "J"; case "FLOAT" -> "F"; case "DOUBLE" -> "D";
            default -> "";
        };
    }

    private void complete(Map<String, Object> type) {
        boolean recursiveFormal = kind(type).equals("TYPEVAR")
                && Boolean.TRUE.equals(type.get("recursive"))
                && formal(type) != type && Boolean.TRUE.equals(formal(type).get("complete"));
        if (type.isEmpty() || !Boolean.TRUE.equals(type.get("complete")) && !recursiveFormal
                || Set.of("ERROR", "UNKNOWN", "CAPTURE").contains(kind(type)))
            throw new IllegalArgumentException("SUPPLEMENTAL_TYPE_INCOMPLETE");
    }

    private Map<String, Object> formal(Map<String, Object> type) {
        String owner = text(type.get("owner")), name = text(type.get("name"));
        var declaration = declarations.get(owner);
        if (declaration != null)
            for (Object raw : list(map(declaration.get("attributes")).get("type_parameter_models"))) {
                var candidate = map(raw);
                if (name.equals(candidate.get("name"))) return candidate;
            }
        return type;
    }

    String descriptor(Map<String, Object> type, Set<String> visiting, int depth) {
        step(depth); complete(type);
        String primitive = primitive(kind(type));
        if (!primitive.isEmpty()) return primitive;
        String existing = text(type.get("descriptor"));
        if (!existing.isEmpty()) return existing;
        return switch (kind(type)) {
            case "DECLARED" -> "L" + binary(type) + ";";
            case "ARRAY" -> "[" + descriptor(map(type.get("component")), visiting, depth + 1);
            case "TYPEVAR" -> {
                String key = text(type.get("owner")) + ':' + text(type.get("name"));
                if (!visiting.add(key)) throw new IllegalArgumentException("SUPPLEMENTAL_ERASURE_CYCLE");
                try {
                    List<Object> bounds = list(formal(type).get("bounds"));
                    if (bounds.isEmpty()) throw new IllegalArgumentException("SUPPLEMENTAL_ERASURE_BOUND_MISSING");
                    yield descriptor(map(bounds.getFirst()), visiting, depth + 1);
                } finally { visiting.remove(key); }
            }
            default -> throw new IllegalArgumentException("SUPPLEMENTAL_DESCRIPTOR_KIND");
        };
    }

    String signature(Map<String, Object> type, int depth) {
        step(depth); complete(type);
        String primitive = primitive(kind(type));
        if (!primitive.isEmpty()) return primitive;
        String existing = text(type.get("signature"));
        if (!existing.isEmpty()) return existing;
        return switch (kind(type)) {
            case "TYPEVAR" -> {
                String name = text(type.get("name"));
                if (name.isEmpty()) throw new IllegalArgumentException("SUPPLEMENTAL_FORMAL_NAME_MISSING");
                yield "T" + name + ";";
            }
            case "ARRAY" -> "[" + signature(map(type.get("component")), depth + 1);
            case "WILDCARD" -> type.get("super") instanceof Map
                    ? "-" + signature(map(type.get("super")), depth + 1)
                    : type.get("extends") instanceof Map
                            ? "+" + signature(map(type.get("extends")), depth + 1) : "*";
            case "DECLARED" -> {
                if (type.get("enclosing") instanceof Map)
                    throw new IllegalArgumentException("SUPPLEMENTAL_ENCLOSING_SIGNATURE_REQUIRES_SOURCE");
                StringBuilder value = new StringBuilder("L").append(binary(type));
                List<Object> arguments = list(type.get("arguments"));
                if (!arguments.isEmpty()) {
                    value.append('<');
                    for (Object argument : arguments) value.append(signature(map(argument), depth + 1));
                    value.append('>');
                }
                yield value.append(';').toString();
            }
            default -> throw new IllegalArgumentException("SUPPLEMENTAL_SIGNATURE_KIND");
        };
    }

    private boolean interfaceBound(Map<String, Object> bound) {
        String kind = text(bound.get("element_kind"));
        var declaration = declarations.get(text(bound.get("element")));
        if (kind.isEmpty() && declaration != null) kind = text(map(declaration.get("attributes")).get("element_kind"));
        if (kind.isEmpty()) throw new IllegalArgumentException("SUPPLEMENTAL_BOUND_KIND_MISSING");
        return Set.of("INTERFACE", "ANNOTATION_TYPE").contains(kind);
    }

    private String formals(List<Object> formals) {
        if (formals.isEmpty()) return "";
        StringBuilder signature = new StringBuilder("<");
        Set<String> names = new HashSet<>();
        for (Object raw : formals) {
            var formal = map(raw);
            String name = text(formal.get("name"));
            if (name.isEmpty() || !names.add(name)) throw new IllegalArgumentException("SUPPLEMENTAL_FORMAL_NAME");
            signature.append(name).append(':');
            List<Object> bounds = list(formal.get("bounds"));
            if (bounds.isEmpty()) throw new IllegalArgumentException("SUPPLEMENTAL_FORMAL_BOUND_MISSING");
            for (int i = 0; i < bounds.size(); i++) {
                var bound = map(bounds.get(i));
                if (i > 0 || interfaceBound(bound)) signature.append(':');
                signature.append(signature(bound, 0));
            }
        }
        return signature.append('>').toString();
    }

    Map<String, Object> project(Map<String, Object> declaration) {
        work = 0;
        var attributes = map(declaration.get("attributes"));
        if (!Boolean.TRUE.equals(declaration.get("generated"))
                || !"lombok_model".equals(attributes.get("binding_origin"))) return declaration;
        Map<String, Object> projected = new LinkedHashMap<>(attributes);
        String kind = text(attributes.get("element_kind"));
        StringBuilder descriptor = new StringBuilder(), signature = new StringBuilder();
        if (Set.of("METHOD", "CONSTRUCTOR").contains(kind)) {
            descriptor.append('('); signature.append(formals(list(attributes.get("type_parameter_models")))).append('(');
            for (Object raw : list(attributes.get("parameter_type_models"))) {
                descriptor.append(descriptor(map(raw), new HashSet<>(), 0));
                signature.append(signature(map(raw), 0));
            }
            descriptor.append(')').append(descriptor(map(attributes.get("return_type_model")), new HashSet<>(), 0));
            signature.append(')').append(signature(map(attributes.get("return_type_model")), 0));
        } else if (kind.equals("FIELD")) {
            descriptor.append(descriptor(map(attributes.get("type_model")), new HashSet<>(), 0));
            signature.append(signature(map(attributes.get("type_model")), 0));
        } else if (kind.equals("CLASS")) {
            projected.put("nesting_kind", "MEMBER");
            projected.put("enclosing_type", declaration.get("parent"));
            signature.append(formals(list(attributes.get("type_parameter_models"))));
            var superclass = map(attributes.get("superclass_type_model"));
            signature.append(superclass.isEmpty() || kind(superclass).equals("NONE") ? "Ljava/lang/Object;" : signature(superclass, 0));
            for (Object parent : list(attributes.get("interface_type_models"))) signature.append(signature(map(parent), 0));
        } else throw new IllegalArgumentException("SUPPLEMENTAL_DECLARATION_KIND");
        if (!descriptor.isEmpty()) projected.put("jvm_descriptor", descriptor.toString());
        if (!signature.toString().equals(descriptor.toString())) projected.put("jvm_signature", signature.toString());
        Map<String, Object> result = new LinkedHashMap<>(declaration);
        result.put("attributes", projected);
        return result;
    }
}
