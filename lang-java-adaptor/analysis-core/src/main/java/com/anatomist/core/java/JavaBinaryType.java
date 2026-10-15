package com.anatomist.core.java;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detached class-file declarations. Descriptors and signatures use JVM grammar, never display
 * names.
 */
public record JavaBinaryType(
        int version,
        int access,
        String name,
        String signature,
        String superclass,
        List<String> interfaces,
        List<Field> fields,
        List<Method> methods,
        List<Annotation> annotations,
        List<TypeAnnotation> typeAnnotations,
        List<InnerType> innerTypes,
        List<RecordComponent> recordComponents,
        List<String> permittedSubclasses,
        String nestHost,
        List<String> nestMembers,
        EnclosingMethod enclosingMethod) {
    public JavaBinaryType {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        annotations = List.copyOf(annotations);
        typeAnnotations = List.copyOf(typeAnnotations);
        innerTypes = List.copyOf(innerTypes);
        recordComponents = List.copyOf(recordComponents);
        permittedSubclasses = List.copyOf(permittedSubclasses);
        nestMembers = List.copyOf(nestMembers);
    }

    public record Field(
            String name,
            String descriptor,
            String signature,
            int access,
            Value constant,
            List<Annotation> annotations,
            List<TypeAnnotation> typeAnnotations) {
        public Field {
            annotations = List.copyOf(annotations);
            typeAnnotations = List.copyOf(typeAnnotations);
        }
    }

    public record Method(
            String name,
            String descriptor,
            String signature,
            int access,
            List<String> exceptions,
            List<Parameter> parameters,
            List<Annotation> annotations,
            List<TypeAnnotation> typeAnnotations,
            List<ParameterAnnotation> parameterAnnotations,
            int visibleParameterCount,
            int invisibleParameterCount,
            Value annotationDefault) {
        public Method {
            exceptions = List.copyOf(exceptions);
            parameters = List.copyOf(parameters);
            annotations = List.copyOf(annotations);
            typeAnnotations = List.copyOf(typeAnnotations);
            parameterAnnotations = List.copyOf(parameterAnnotations);
        }
    }

    public record Parameter(String name, int access) {}

    public record ParameterAnnotation(int parameter, Annotation annotation) {}

    public record InnerType(String name, String outerName, String simpleName, int access) {}

    public record EnclosingMethod(String owner, String name, String descriptor) {}

    public record RecordComponent(
            String name,
            String descriptor,
            String signature,
            List<Annotation> annotations,
            List<TypeAnnotation> typeAnnotations) {
        public RecordComponent {
            annotations = List.copyOf(annotations);
            typeAnnotations = List.copyOf(typeAnnotations);
        }
    }

    public record Annotation(String descriptor, boolean visible, Map<String, Value> values) {
        public Annotation {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** target is the JVMS type_reference encoding; path uses ASM's stable type-path grammar. */
    public record TypeAnnotation(int target, String path, Annotation annotation) {}

    public sealed interface Value
            permits Scalar,
                    ClassLiteral,
                    EnumValue,
                    NestedAnnotation,
                    ArrayValue,
                    UnavailableValue {}

    /** Source-only recovery value; never emitted by class-file decoding or signature archives. */
    public record UnavailableValue(String reason) implements Value {}

    public record Scalar(Object value) implements Value {
        public Scalar {
            if (!(value instanceof String
                    || value instanceof Boolean
                    || value instanceof Byte
                    || value instanceof Short
                    || value instanceof Character
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof Double)) {
                throw new IllegalArgumentException("not a class-file scalar: " + value);
            }
        }
    }

    public record ClassLiteral(String descriptor) implements Value {}

    public record EnumValue(String descriptor, String name) implements Value {}

    public record NestedAnnotation(Annotation annotation) implements Value {}

    public record ArrayValue(List<Value> values) implements Value {
        public ArrayValue {
            values = List.copyOf(values);
        }
    }
}
