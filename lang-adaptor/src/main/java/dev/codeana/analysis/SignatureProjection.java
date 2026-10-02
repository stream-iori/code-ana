package dev.codeana.analysis;

import dev.codeana.common.Json;

import org.objectweb.asm.*;

import java.util.*;

/** NoCode classfiles preserve the Java 8 declaration ABI, never executable project code. */
final class SignatureProjection {
    private SignatureProjection() {}

    private static List<Object> items(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }

    private static Map<String, Object> attrs(Map<String, Object> declaration) {
        return Json.map(declaration.get("attributes"));
    }

    private static boolean type(String kind) {
        return Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(kind);
    }

    static Map<String, byte[]> project(List<Map<String, Object>> declarations) {
        Map<String, Map<String, Object>> types = new TreeMap<>();
        Map<String, List<Map<String, Object>>> members = new TreeMap<>();
        for (Map<String, Object> declaration : declarations) {
            Map<String, Object> a = attrs(declaration);
            String kind = Json.string(a, "element_kind");
            if ((type(kind) || Set.of("METHOD", "CONSTRUCTOR", "FIELD", "ENUM_CONSTANT").contains(kind))
                    && Boolean.FALSE.equals(a.get("written_type_complete")))
                throw new IllegalArgumentException("SIGNATURE_WRITTEN_TYPE_INCOMPLETE");
            if (type(kind)) {
                if (Set.of("LOCAL", "ANONYMOUS").contains(a.get("nesting_kind"))) continue;
                String binary = Json.string(a, "binary_name");
                if (binary.isEmpty() || types.putIfAbsent(binary, declaration) != null)
                    throw new IllegalArgumentException("SIGNATURE_TYPE_IDENTITY");
                header(a.get("superclass_type_model"), 0);
                for (Object parent : items(a.get("interface_type_models"))) header(parent, 0);
            } else if (Set.of("METHOD", "CONSTRUCTOR", "FIELD", "ENUM_CONSTANT").contains(kind)) {
                members.computeIfAbsent(
                                Json.string(declaration, "parent"), ignored -> new ArrayList<>())
                        .add(declaration);
            }
        }
        Map<String, byte[]> output = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : types.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> declaration = entry.getValue(), a = attrs(declaration);
            String kind = Json.string(a, "element_kind");
            boolean iface = kind.equals("INTERFACE") || kind.equals("ANNOTATION_TYPE");
            int access =
                    flags(items(a.get("modifiers")))
                            & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
            access |= iface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_SUPER;
            if (kind.equals("ENUM")) access |= Opcodes.ACC_ENUM;
            if (kind.equals("ANNOTATION_TYPE")) access |= Opcodes.ACC_ANNOTATION;
            Map<String, Object> superclass = Json.map(a.get("superclass_type_model"));
            String parent = internal(superclass, "java/lang/Object");
            String[] interfaces =
                    items(a.get("interface_type_models")).stream()
                            .map(p -> internal(Json.map(p), null))
                            .toArray(String[]::new);
            ClassWriter writer = new ClassWriter(0);
            writer.visit(
                    Opcodes.V1_8,
                    access,
                    name.replace('.', '/'),
                    text(a, "jvm_signature"),
                    parent,
                    interfaces);
            for (Map.Entry<String, Map<String, Object>> nested : types.entrySet()) {
                Map<String, Object> inner = attrs(nested.getValue());
                // Unrelated entries can trigger recursive class completion in javac 8, e.g.
                // an annotation loading the annotated class before its own type is attributed.
                if ("MEMBER".equals(inner.get("nesting_kind"))
                        && (nested.getKey().equals(name)
                                || name.equals(inner.get("enclosing_type"))
                                || name.startsWith(nested.getKey() + "$")))
                    writer.visitInnerClass(
                            nested.getKey().replace('.', '/'),
                            text(inner, "enclosing_type").replace('.', '/'),
                            Json.string(nested.getValue(), "name"),
                            flags(items(inner.get("modifiers")))
                                    | ("INTERFACE".equals(inner.get("element_kind"))
                                            ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
                                            : 0)
                                    | ("ENUM".equals(inner.get("element_kind"))
                                            ? Opcodes.ACC_ENUM
                                            : 0)
                                    | ("ANNOTATION_TYPE".equals(inner.get("element_kind"))
                                            ? Opcodes.ACC_ANNOTATION
                                                    | Opcodes.ACC_INTERFACE
                                                    | Opcodes.ACC_ABSTRACT
                                            : 0));
            }
            annotations(items(a.get("annotations")), writer::visitAnnotation);
            formalAnnotations(
                    items(a.get("type_parameter_models")), false, writer::visitTypeAnnotation);
            typeAnnotations(
                    superclass,
                    TypeReference.newSuperTypeReference(-1).getValue(),
                    "",
                    writer::visitTypeAnnotation,
                    0);
            int parentIndex = 0;
            for (Object p : items(a.get("interface_type_models")))
                typeAnnotations(
                        Json.map(p),
                        TypeReference.newSuperTypeReference(parentIndex++).getValue(),
                        "",
                        writer::visitTypeAnnotation,
                        0);
            Set<String> names = new HashSet<>();
            for (Map<String, Object> member : members.getOrDefault(name, List.of())) {
                Map<String, Object> m = attrs(member);
                String memberKind = Json.string(m, "element_kind");
                header(m.get("type_model"), 0);
                String descriptor = text(m, "jvm_descriptor");
                if (descriptor == null || descriptor.isEmpty())
                    throw new IllegalArgumentException("INCOMPLETE_MEMBER_ABI");
                int memberAccess = flags(items(m.get("modifiers")));
                if (memberKind.equals("ENUM_CONSTANT")) memberAccess |= Opcodes.ACC_ENUM;
                if (memberKind.equals("FIELD") || memberKind.equals("ENUM_CONSTANT")) {
                    FieldVisitor field =
                            writer.visitField(
                                    memberAccess,
                                    Json.string(member, "name"),
                                    descriptor,
                                    text(m, "jvm_signature"),
                                    fieldConstant(m.get("constant_value"), descriptor));
                    annotations(items(m.get("annotations")), field::visitAnnotation);
                    typeAnnotations(
                            Json.map(m.get("type_model")),
                            TypeReference.newTypeReference(TypeReference.FIELD).getValue(),
                            "",
                            field::visitTypeAnnotation,
                            0);
                    field.visitEnd();
                } else {
                    String methodName =
                            memberKind.equals("CONSTRUCTOR")
                                    ? "<init>"
                                    : Json.string(member, "name");
                    String hidden = "";
                    if (memberKind.equals("CONSTRUCTOR")) {
                        if (kind.equals("ENUM")) hidden = "Ljava/lang/String;I";
                        else if ("MEMBER".equals(a.get("nesting_kind"))
                                && !items(a.get("modifiers")).contains("static"))
                            hidden = "L" + text(a, "enclosing_type").replace('.', '/') + ";";
                        descriptor = "(" + hidden + descriptor.substring(1);
                    }
                    Type.getMethodType(descriptor);
                    if (Boolean.TRUE.equals(m.get("varargs"))) memberAccess |= Opcodes.ACC_VARARGS;
                    String[] exceptions =
                            items(m.get("exceptions")).stream()
                                    .map(
                                            v -> {
                                                String d = (String) v;
                                                return d.substring(1, d.length() - 1);
                                            })
                                    .toArray(String[]::new);
                    MethodVisitor method =
                            writer.visitMethod(
                                    memberAccess,
                                    methodName,
                                    descriptor,
                                    text(m, "jvm_signature"),
                                    exceptions);
                    names.add(methodName + descriptor);
                    annotations(items(m.get("annotations")), method::visitAnnotation);
                    formalAnnotations(
                            items(m.get("type_parameter_models")),
                            true,
                            method::visitTypeAnnotation);
                    if (m.containsKey("annotation_default")) {
                        AnnotationVisitor value = method.visitAnnotationDefault();
                        value(value, null, m.get("annotation_default"));
                        value.visitEnd();
                    }
                    int offset = hidden.isEmpty() ? 0 : kind.equals("ENUM") ? 2 : 1;
                    int parameter = 0;
                    for (Object annotations : items(m.get("parameter_annotations"))) {
                        final int index = parameter++ + offset;
                        annotations(
                                items(annotations),
                                (d, visible) -> method.visitParameterAnnotation(index, d, visible));
                    }
                    parameter = 0;
                    for (Object parameterType : items(m.get("parameter_type_models")))
                        typeAnnotations(
                                Json.map(parameterType),
                                TypeReference.newFormalParameterReference(parameter++).getValue(),
                                "",
                                method::visitTypeAnnotation,
                                0);
                    if (m.get("return_type_model") instanceof Map)
                        typeAnnotations(
                                Json.map(m.get("return_type_model")),
                                TypeReference.newTypeReference(TypeReference.METHOD_RETURN)
                                        .getValue(),
                                "",
                                method::visitTypeAnnotation,
                                0);
                    method.visitEnd();
                }
            }
            if (kind.equals("ENUM")) {
                String d = "()[L" + name.replace('.', '/') + ";";
                if (!names.contains("values" + d))
                    writer.visitMethod(
                                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                    "values",
                                    d,
                                    null,
                                    null)
                            .visitEnd();
                d = "(Ljava/lang/String;)L" + name.replace('.', '/') + ";";
                if (!names.contains("valueOf" + d))
                    writer.visitMethod(
                                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                                    "valueOf",
                                    d,
                                    null,
                                    null)
                            .visitEnd();
            }
            writer.visitEnd();
            output.put(name, writer.toByteArray());
        }
        return output;
    }

    private static String text(Map<String, Object> map, String key) {
        return map.get(key) instanceof String ? (String) map.get(key) : null;
    }

    private static String internal(Map<String, Object> type, String fallback) {
        if ("NONE".equals(type.get("kind"))) return fallback;
        String name = text(type, "element");
        if (name == null || name.isEmpty())
            throw new IllegalArgumentException("INCOMPLETE_SUPERTYPE");
        return name.replace('.', '/');
    }

    private static void header(Object value, int depth) {
        if (!(value instanceof Map)) return;
        if (depth > 32) throw new IllegalArgumentException("SIGNATURE_TYPE_BUDGET");
        Map<String, Object> type = Json.map(value);
        if ("ERROR".equals(type.get("kind")) || "UNKNOWN".equals(type.get("kind")))
            throw new IllegalArgumentException("INCOMPLETE_SIGNATURE_TYPE");
        if (Boolean.TRUE.equals(type.get("recursive"))) return;
        for (String field :
                List.of("component", "return", "enclosing", "upper", "lower", "extends", "super"))
            if (type.containsKey(field)) header(type.get(field), depth + 1);
        for (String field : List.of("arguments", "parameters", "throws", "bounds"))
            for (Object child : items(type.get(field))) header(child, depth + 1);
    }

    private static Object fieldConstant(Object value, String descriptor) {
        if (value == null) return null;
        if (value instanceof Map) {
            String nonFinite = Json.string(Json.map(value), "non_finite");
            return descriptor.equals("F") ? Float.valueOf(nonFinite) : Double.valueOf(nonFinite);
        }
        if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? 1 : 0;
        if (value instanceof Number n)
            return switch (descriptor) {
                case "J" -> n.longValue();
                case "F" -> n.floatValue();
                case "D" -> n.doubleValue();
                default -> n.intValue();
            };
        return value;
    }

    private interface AnnotationSink {
        AnnotationVisitor visit(String descriptor, boolean visible);
    }

    private interface TypeAnnotationSink {
        AnnotationVisitor visit(int reference, TypePath path, String descriptor, boolean visible);
    }

    private static void annotations(List<Object> annotations, AnnotationSink sink) {
        for (Object raw : annotations) {
            Map<String, Object> annotation = Json.map(raw);
            if ("SOURCE".equals(annotation.get("retention"))) continue;
            AnnotationVisitor visitor =
                    sink.visit(
                            "L" + Json.string(annotation, "type").replace('.', '/') + ";",
                            "RUNTIME".equals(annotation.get("retention")));
            for (Map.Entry<String, Object> entry : Json.map(annotation.get("explicit")).entrySet())
                value(visitor, entry.getKey(), entry.getValue());
            visitor.visitEnd();
        }
    }

    private static void value(AnnotationVisitor visitor, String name, Object raw) {
        if (raw instanceof Map) {
            Map<String, Object> value = Json.map(raw);
            switch (Json.string(value, "kind")) {
                case "primitive" -> {
                    Object literal = value.get("value");
                    String type = Json.string(value, "type");
                    if (literal instanceof Map) {
                        String nonFinite = Json.string(Json.map(literal), "non_finite");
                        literal =
                                type.equals("float")
                                        ? Float.valueOf(nonFinite)
                                        : Double.valueOf(nonFinite);
                    } else if (literal instanceof Number n) {
                        literal =
                                switch (type) {
                                    case "byte" -> n.byteValue();
                                    case "short" -> n.shortValue();
                                    case "character" -> (char) n.intValue();
                                    case "long" -> n.longValue();
                                    case "float" -> n.floatValue();
                                    case "double" -> n.doubleValue();
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "ANNOTATION_PRIMITIVE_TYPE");
                                };
                    }
                    visitor.visit(name, literal);
                }
                case "class" -> visitor.visit(name, Type.getType(Json.string(value, "descriptor")));
                case "enum" ->
                        visitor.visitEnum(
                                name,
                                "L" + Json.string(value, "type").replace('.', '/') + ";",
                                Json.string(value, "name"));
                case "array" -> {
                    AnnotationVisitor array = visitor.visitArray(name);
                    for (Object item : items(value.get("values"))) value(array, null, item);
                    array.visitEnd();
                }
                case "annotation" -> {
                    Map<String, Object> annotation = Json.map(value.get("value"));
                    AnnotationVisitor child =
                            visitor.visitAnnotation(
                                    name,
                                    "L" + Json.string(annotation, "type").replace('.', '/') + ";");
                    for (Map.Entry<String, Object> entry :
                            Json.map(annotation.get("explicit")).entrySet())
                        value(child, entry.getKey(), entry.getValue());
                    child.visitEnd();
                }
                default -> throw new IllegalArgumentException("INCOMPLETE_ANNOTATION_VALUE");
            }
        } else {
            // Other primitive numeric values carry their tag in a typed map.
            // JSON integer tokens are Long even when the Java annotation member is int.
            if (raw instanceof Number n) raw = n.intValue();
            visitor.visit(name, raw);
        }
    }

    private static void typeAnnotations(
            Map<String, Object> type,
            int reference,
            String path,
            TypeAnnotationSink sink,
            int depth) {
        if (depth > 32) throw new IllegalArgumentException("TYPE_ANNOTATION_BUDGET");
        final String currentPath = path;
        annotations(
                items(type.get("annotation_models")),
                (d, visible) ->
                        sink.visit(reference, TypePath.fromString(currentPath), d, visible));
        if (type.get("component") instanceof Map)
            typeAnnotations(
                    Json.map(type.get("component")), reference, path + "[", sink, depth + 1);
        for (String bound : List.of("extends", "super"))
            if (type.get(bound) instanceof Map)
                typeAnnotations(Json.map(type.get(bound)), reference, path + "*", sink, depth + 1);
        int index = 0;
        for (Object argument : items(type.get("arguments")))
            typeAnnotations(Json.map(argument), reference, path + index++ + ";", sink, depth + 1);
        // Nested type-annotation paths need explicit enclosing segments; keep source until modeled.
        if (type.get("enclosing") instanceof Map && hasAnnotations(Json.map(type.get("enclosing"))))
            throw new IllegalArgumentException("ENCLOSING_TYPE_ANNOTATION_REQUIRES_SOURCE");
    }

    private static void formalAnnotations(
            List<Object> parameters, boolean method, TypeAnnotationSink sink) {
        int parameterIndex = 0;
        for (Object raw : parameters) {
            Map<String, Object> parameter = Json.map(raw);
            final int reference =
                    TypeReference.newTypeParameterReference(
                                    method
                                            ? TypeReference.METHOD_TYPE_PARAMETER
                                            : TypeReference.CLASS_TYPE_PARAMETER,
                                    parameterIndex)
                            .getValue();
            annotations(
                    items(parameter.get("annotations")),
                    (d, visible) -> sink.visit(reference, null, d, visible));
            List<Object> bounds = items(parameter.get("bounds"));
            int boundIndex = 0;
            // Interface-only bounds encode an empty class bound at slot zero.
            if (!bounds.isEmpty()
                    && "INTERFACE".equals(Json.map(bounds.getFirst()).get("element_kind")))
                boundIndex = 1;
            for (Object bound : bounds) {
                header(bound, 0);
                int ref =
                        TypeReference.newTypeParameterBoundReference(
                                        method
                                                ? TypeReference.METHOD_TYPE_PARAMETER_BOUND
                                                : TypeReference.CLASS_TYPE_PARAMETER_BOUND,
                                        parameterIndex,
                                        boundIndex++)
                                .getValue();
                typeAnnotations(Json.map(bound), ref, "", sink, 0);
            }
            parameterIndex++;
        }
    }

    private static boolean hasAnnotations(Map<String, Object> type) {
        if (!items(type.get("annotation_models")).isEmpty()) return true;
        for (Object argument : items(type.get("arguments")))
            if (hasAnnotations(Json.map(argument))) return true;
        return type.get("enclosing") instanceof Map
                && hasAnnotations(Json.map(type.get("enclosing")));
    }

    private static int flags(List<Object> modifiers) {
        int result = 0;
        for (Object raw : modifiers)
            result |=
                    switch ((String) raw) {
                        case "public" -> Opcodes.ACC_PUBLIC;
                        case "private" -> Opcodes.ACC_PRIVATE;
                        case "protected" -> Opcodes.ACC_PROTECTED;
                        case "static" -> Opcodes.ACC_STATIC;
                        case "final" -> Opcodes.ACC_FINAL;
                        case "abstract" -> Opcodes.ACC_ABSTRACT;
                        case "native" -> Opcodes.ACC_NATIVE;
                        case "synchronized" -> Opcodes.ACC_SYNCHRONIZED;
                        case "strictfp" -> Opcodes.ACC_STRICT;
                        case "transient" -> Opcodes.ACC_TRANSIENT;
                        case "volatile" -> Opcodes.ACC_VOLATILE;
                        default -> 0;
                    };
        return result;
    }
}
