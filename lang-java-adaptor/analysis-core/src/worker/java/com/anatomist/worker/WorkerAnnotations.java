package com.anatomist.worker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/** Data-only annotation values. All retention modes are kept in the source model. */
final class WorkerAnnotations {
    private WorkerAnnotations() {}

    static void writeAnnotations(
            DataOutputStream out, List<? extends AnnotationMirror> annotations, Elements elements)
            throws IOException {
        out.writeInt(annotations.size());
        for (AnnotationMirror annotation : annotations) annotation(out, annotation, elements, 0);
    }

    private static void annotation(
            DataOutputStream out, AnnotationMirror annotation, Elements elements, int depth)
            throws IOException {
        depth(depth);
        TypeElement type = (TypeElement) annotation.getAnnotationType().asElement();
        string(out, "L" + elements.getBinaryName(type).toString().replace('.', '/') + ";");
        String retention = "CLASS";
        for (AnnotationMirror meta : type.getAnnotationMirrors()) {
            if (!"java.lang.annotation.Retention".equals(meta.getAnnotationType().toString()))
                continue;
            for (AnnotationValue value : meta.getElementValues().values()) {
                if (value.getValue() instanceof VariableElement)
                    retention = ((VariableElement) value.getValue()).getSimpleName().toString();
            }
        }
        string(out, retention);
        // Explicit values are retained; defaults belong to the annotation declaration inventory.
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                annotation.getElementValues();
        out.writeInt(values.size());
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                values.entrySet()) {
            string(out, entry.getKey().getSimpleName().toString());
            value(out, entry.getValue(), elements, depth + 1, entry.getKey().getReturnType());
        }
    }

    static void value(DataOutputStream out, Object value, Elements elements, int depth)
            throws IOException {
        value(out, value, elements, depth, null);
    }

    static void value(
            DataOutputStream out, Object value, Elements elements, int depth, TypeMirror expected)
            throws IOException {
        depth(depth);
        if (value instanceof AnnotationValue) value = ((AnnotationValue) value).getValue();
        if (value instanceof String
                && expected != null
                && !expected.toString().equals("java.lang.String")) {
            out.writeByte(14);
            string(out, "annotation-value-unavailable:" + expected);
            return;
        }
        if (value == null) out.writeByte(0);
        else if (value instanceof Boolean) {
            out.writeByte(1);
            out.writeBoolean(((Boolean) value).booleanValue());
        } else if (value instanceof Byte) {
            out.writeByte(2);
            out.writeByte(((Byte) value).byteValue());
        } else if (value instanceof Short) {
            out.writeByte(3);
            out.writeShort(((Short) value).shortValue());
        } else if (value instanceof Character) {
            out.writeByte(4);
            out.writeChar(((Character) value).charValue());
        } else if (value instanceof Integer) {
            out.writeByte(5);
            out.writeInt(((Integer) value).intValue());
        } else if (value instanceof Long) {
            out.writeByte(6);
            out.writeLong(((Long) value).longValue());
        } else if (value instanceof Float) {
            out.writeByte(7);
            out.writeFloat(((Float) value).floatValue());
        } else if (value instanceof Double) {
            out.writeByte(8);
            out.writeDouble(((Double) value).doubleValue());
        } else if (value instanceof String) {
            out.writeByte(9);
            string(out, (String) value);
        } else if (value instanceof TypeMirror) {
            String descriptor;
            try {
                descriptor = WorkerSignatures.descriptor((TypeMirror) value, elements);
            } catch (IllegalArgumentException unavailable) {
                out.writeByte(14);
                string(out, "type-literal-unavailable:" + value);
                return;
            }
            out.writeByte(10);
            string(out, descriptor);
        } else if (value instanceof VariableElement) {
            VariableElement constant = (VariableElement) value;
            out.writeByte(11);
            string(
                    out,
                    "L"
                            + elements.getBinaryName((TypeElement) constant.getEnclosingElement())
                                    .toString()
                                    .replace('.', '/')
                            + ";");
            string(out, constant.getSimpleName().toString());
        } else if (value instanceof AnnotationMirror) {
            out.writeByte(12);
            annotation(out, (AnnotationMirror) value, elements, depth + 1);
        } else if (value instanceof List<?>) {
            out.writeByte(13);
            List<?> items = (List<?>) value;
            out.writeInt(items.size());
            TypeMirror component =
                    expected != null && expected.getKind() == TypeKind.ARRAY
                            ? ((ArrayType) expected).getComponentType()
                            : null;
            for (Object item : items) value(out, item, elements, depth + 1, component);
        } else {
            out.writeByte(14);
            string(out, "annotation-value-unavailable:" + value.getClass().getName());
        }
    }

    static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32 * 1024 * 1024)
            throw new IOException("declaration string exceeds budget");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void depth(int depth) throws IOException {
        if (depth > 128) throw new IOException("compiler annotation value exceeds depth budget");
    }
}
