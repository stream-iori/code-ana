package dev.codeana.adapter;

import com.anatomist.core.java.JavaBinaryType;
import dev.codeana.common.Json;
import java.util.*;

/** Detached classfile data only; member selection uses the original JVM descriptor. */
final class BinaryMemberProjection {
    private BinaryMemberProjection() {}

    static Map<String, Object> method(JavaBinaryType.Method member) {
        return Json.object("schema_version", 1, "kind", "METHOD", "name", member.name(),
                "descriptor", member.descriptor(), "signature", member.signature(), "access", member.access(),
                "exceptions", member.exceptions(),
                "parameters", member.parameters().stream().map(p -> Json.object("name", p.name(), "access", p.access())).toList(),
                "annotations", member.annotations().stream().map(BinaryMemberProjection::annotation).toList(),
                "type_annotations", member.typeAnnotations().stream().map(BinaryMemberProjection::typeAnnotation).toList(),
                "parameter_annotations", member.parameterAnnotations().stream()
                        .map(p -> Json.object("parameter", p.parameter(), "annotation", annotation(p.annotation()))).toList(),
                "visible_parameter_count", member.visibleParameterCount(),
                "invisible_parameter_count", member.invisibleParameterCount(),
                "annotation_default", value(member.annotationDefault()));
    }

    static Map<String, Object> field(JavaBinaryType.Field member) {
        return Json.object("schema_version", 1, "kind", "FIELD", "name", member.name(),
                "descriptor", member.descriptor(), "signature", member.signature(), "access", member.access(),
                "constant", value(member.constant()),
                "annotations", member.annotations().stream().map(BinaryMemberProjection::annotation).toList(),
                "type_annotations", member.typeAnnotations().stream().map(BinaryMemberProjection::typeAnnotation).toList());
    }

    private static Map<String, Object> annotation(JavaBinaryType.Annotation annotation) {
        return Json.object("descriptor", annotation.descriptor(), "visible", annotation.visible(),
                "values", annotation.values().entrySet().stream()
                        .map(e -> Json.object("name", e.getKey(), "value", value(e.getValue()))).toList());
    }

    private static Map<String, Object> typeAnnotation(JavaBinaryType.TypeAnnotation annotation) {
        return Json.object("target", annotation.target(), "path", annotation.path(),
                "annotation", annotation(annotation.annotation()));
    }

    static Object value(JavaBinaryType.Value value) {
        if (value == null) return null;
        return switch (value) {
            case JavaBinaryType.Scalar scalar -> {
                Object raw = scalar.value();
                String kind = raw.getClass().getSimpleName().toUpperCase(Locale.ROOT);
                // Decimal text plus bits preserve NaN, signed zero, and the original scalar kind.
                if (raw instanceof Float number)
                    yield Json.object("kind", kind, "value", number.toString(), "bits", Float.floatToRawIntBits(number));
                if (raw instanceof Double number)
                    yield Json.object("kind", kind, "value", number.toString(), "bits", Double.doubleToRawLongBits(number));
                if (raw instanceof String text && hasUnpairedSurrogate(text))
                    yield Json.object("kind", kind, "utf16_units", text.chars().boxed().toList());
                yield Json.object("kind", kind, "value", raw instanceof Character ch ? (int) ch : raw);
            }
            case JavaBinaryType.ClassLiteral literal -> Json.object("kind", "CLASS", "descriptor", literal.descriptor());
            case JavaBinaryType.EnumValue enumeration -> Json.object("kind", "ENUM", "descriptor", enumeration.descriptor(), "name", enumeration.name());
            case JavaBinaryType.NestedAnnotation nested -> Json.object("kind", "ANNOTATION", "annotation", annotation(nested.annotation()));
            case JavaBinaryType.ArrayValue array -> Json.object("kind", "ARRAY", "values", array.values().stream().map(BinaryMemberProjection::value).toList());
            case JavaBinaryType.UnavailableValue unavailable -> Json.object("kind", "UNAVAILABLE", "reason", unavailable.reason());
        };
    }

    static Object display(Object value) {
        if (value instanceof Character ch)
            return Character.isSurrogate(ch) ? (int) ch : ch.toString();
        if (value instanceof Float floatNumber && !Float.isFinite(floatNumber)
                || value instanceof Double doubleNumber && !Double.isFinite(doubleNumber)) return value.toString();
        if (value instanceof String text && hasUnpairedSurrogate(text))
            return Json.object("kind", "UTF16_STRING", "utf16_units", text.chars().boxed().toList());
        return value;
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }
}
