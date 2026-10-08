package com.anatomist.core.java;

import com.anatomist.core.ContentHash;
import com.anatomist.json.Json;

import java.util.*;

/** Canonical compiler declaration contract, independent of source layout and executable bodies. */
public final class JavaContract {
    public static final String VERSION = "java-declarations-v3";

    private JavaContract() {}

    public static String fingerprint(JavaUnit unit) {
        Encoder encoder = new Encoder();
        List<Object> declarations =
                unit.declarationInventory().stream()
                        .filter(value -> !value.canonicalName().isEmpty())
                        .sorted(Comparator.comparing(JavaDeclaration::canonicalName))
                        .map(value -> encoder.declaration(unit.types(), value, 0))
                        .toList();
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("declarations", declarations);
        Map<String, Object> header = new TreeMap<>();
        for (String key :
                List.of("getPackageName", "getPackageAnnotations", "getPackage", "getModule")) {
            Object value = unit.tree().fields().get(key);
            if (value != null) header.put(key, encoder.syntax(value, 0));
        }
        contract.put("header", header);
        return ContentHash.sha256(VERSION + "\n" + Json.writeCompact(contract));
    }

    private static final class Encoder {
        private int work;

        private void visit(int depth) {
            if (depth > 128) throw new IllegalArgumentException("contract exceeds depth budget");
            if (++work > 100_000)
                throw new IllegalArgumentException("contract exceeds work budget");
        }

        private Object syntax(Object value, int depth) {
            visit(depth);
            if (value instanceof JavaNode node) {
                Map<String, Object> result = new TreeMap<>();
                result.put("kind", node.kind());
                node.fields().forEach((key, child) -> result.put(key, syntax(child, depth + 1)));
                return result;
            }
            if (value instanceof List<?> list)
                return list.stream().map(child -> syntax(child, depth + 1)).toList();
            return value;
        }

        private Object declaration(JavaTypes types, JavaDeclaration value, int depth) {
            visit(depth);
            JavaSymbol symbol = value.symbol();
            List<Object> members = new ArrayList<>();
            if (Set.of("METHOD", "CONSTRUCTOR").contains(symbol.kind())) {
                // Parameter and generic parameter order is positional.
                value.members()
                        .forEach(member -> members.add(declaration(types, member, depth + 1)));
            } else {
                // Record component and enum constant order is part of the contract.
                value.members().stream()
                        .filter(
                                member ->
                                        Set.of("RECORD_COMPONENT", "ENUM_CONSTANT")
                                                .contains(member.symbol().kind()))
                        .forEach(member -> members.add(declaration(types, member, depth + 1)));
                value.members().stream()
                        .filter(
                                member ->
                                        !Set.of("RECORD_COMPONENT", "ENUM_CONSTANT")
                                                .contains(member.symbol().kind()))
                        .sorted(
                                Comparator.comparing(
                                                (JavaDeclaration member) -> member.symbol().kind())
                                        .thenComparing(member -> member.symbol().name())
                                        .thenComparing(member -> member.symbol().descriptor()))
                        .forEach(member -> members.add(declaration(types, member, depth + 1)));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", symbol.kind());
            result.put("name", symbol.name());
            result.put("binary", value.binaryName());
            result.put("descriptor", symbol.descriptor());
            result.put("signature", value.signature());
            result.put("modifiers", symbol.modifiers().stream().sorted().toList());
            result.put("variadic", symbol.variadic());
            result.put("deprecated", value.deprecated());
            result.put("type", type(types, symbol.declaredType(), new ArrayList<>()));
            result.put("superclass", type(types, value.superclass(), new ArrayList<>()));
            result.put(
                    "interfaces",
                    value.interfaces().stream()
                            .map(reference -> type(types, reference, new ArrayList<>()))
                            .toList());
            result.put(
                    "permitted",
                    value.permitted().stream()
                            .map(reference -> type(types, reference, new ArrayList<>()))
                            .map(Json::writeCompact)
                            .sorted()
                            .toList());
            result.put("annotations", annotations(value.annotations()));
            result.put("constant", constant(value.constant()));
            result.put("default", constant(value.annotationDefault()));
            result.put("members", members);
            result.put("incomplete", value.incompleteReasons().stream().sorted().toList());
            return result;
        }

        private Object type(JavaTypes types, int reference, List<Integer> path) {
            visit(path.size());
            if (reference < 0) return null;
            int cycle = path.indexOf(reference);
            if (cycle >= 0) return Map.of("back", cycle);
            if (path.size() >= 128)
                throw new IllegalArgumentException("contract type exceeds depth budget");
            path.add(reference);
            try {
                JavaType value = types.get(reference);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", value.kind().name());
                result.put("name", value.name());
                result.put("binary", value.binaryName());
                result.put("annotations", annotations(value.annotations()));
                result.put("enclosing", type(types, value.enclosing(), path));
                result.put(
                        "arguments",
                        value.arguments().stream().map(child -> type(types, child, path)).toList());
                result.put(
                        "bounds",
                        value.upperBounds().stream()
                                .map(child -> type(types, child, path))
                                .toList());
                result.put("lower", type(types, value.lowerBound(), path));
                result.put("component", type(types, value.component(), path));
                result.put(
                        "parameters",
                        value.parameters().stream()
                                .map(child -> type(types, child, path))
                                .toList());
                result.put("returns", type(types, value.returns(), path));
                result.put(
                        "exceptions",
                        value.exceptions().stream()
                                .map(child -> type(types, child, path))
                                .toList());
                return result;
            } finally {
                path.removeLast();
            }
        }

        private List<Object> annotations(List<JavaAnnotation> annotations) {
            visit(0);
            return annotations.stream()
                    .map(
                            annotation -> {
                                visit(0);
                                return (Object)
                                        List.of(
                                                annotation.descriptor(),
                                                annotation.retention().name(),
                                                values(annotation.values(), 1));
                            })
                    .toList();
        }

        private Map<String, Object> values(Map<String, JavaBinaryType.Value> values, int depth) {
            visit(depth);
            Map<String, Object> result = new TreeMap<>();
            values.forEach((key, value) -> result.put(key, constant(value, depth + 1)));
            return result;
        }

        private Object constant(JavaBinaryType.Value value) {
            return constant(value, 0);
        }

        private Object constant(JavaBinaryType.Value value, int depth) {
            visit(depth);
            if (value == null) return null;
            return switch (value) {
                case JavaBinaryType.Scalar scalar ->
                        List.of(
                                "scalar",
                                scalar.value().getClass().getSimpleName(),
                                scalar.value().toString());
                case JavaBinaryType.ClassLiteral literal -> List.of("class", literal.descriptor());
                case JavaBinaryType.EnumValue enumeration ->
                        List.of("enum", enumeration.descriptor(), enumeration.name());
                case JavaBinaryType.NestedAnnotation nested ->
                        List.of(
                                "annotation",
                                nested.annotation().descriptor(),
                                values(nested.annotation().values(), depth + 1));
                case JavaBinaryType.ArrayValue array ->
                        List.of(
                                "array",
                                array.values().stream()
                                        .map(child -> constant(child, depth + 1))
                                        .toList());
                case JavaBinaryType.UnavailableValue unavailable ->
                        List.of("unavailable", unavailable.reason());
            };
        }
    }
}
