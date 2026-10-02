package dev.codeana.analysis;

import dev.codeana.common.Json;
import org.objectweb.asm.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.*;

/** Declaration metadata adapted from Anatomist's frozen JavaBinaryReader; never loads classes. */
final class BinaryAnnotations {
    private int nodes;

    private void check(int depth) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if (depth > 64 || ++nodes > 100000) throw new IllegalArgumentException("BINARY_ANNOTATION_BUDGET");
    }

    AnnotationVisitor annotation(String descriptor, boolean visible, Consumer<Object> done) {
        return annotation(descriptor, visible, done, 0);
    }

    private AnnotationVisitor annotation(String descriptor, boolean visible, Consumer<Object> done, int depth) {
        check(depth);
        Map<String, Object> values = Json.object();
        return values(values::put, () -> done.accept(Json.object("descriptor", descriptor,
                "type", Type.getType(descriptor).getClassName(), "runtime_visible", visible,
                "explicit", values)), depth);
    }

    AnnotationVisitor typeAnnotation(int reference, TypePath path, String descriptor,
                                    boolean visible, List<Object> target) {
        return annotation(descriptor, visible, a -> target.add(Json.object("reference", reference,
                "path", path == null ? "" : path.toString(), "annotation", a)));
    }

    AnnotationVisitor defaultValue(Consumer<Object> done) {
        return values((ignored, value) -> done.accept(value), () -> {}, 0);
    }

    private AnnotationVisitor values(BiConsumer<String, Object> sink, Runnable done, int depth) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { sink.accept(name, value(value, depth + 1)); }
            @Override public void visitEnum(String name, String descriptor, String value) {
                check(depth + 1);
                sink.accept(name, Json.object("kind", "enum", "type", Type.getType(descriptor).getClassName(), "name", value));
            }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                return annotation(descriptor, false, a -> sink.accept(name, Json.object("kind", "annotation", "value", a)), depth + 1);
            }
            @Override public AnnotationVisitor visitArray(String name) {
                check(depth + 1);
                List<Object> items = new ArrayList<>();
                return values((ignored, item) -> items.add(item), () -> sink.accept(name,
                        Json.object("kind", "array", "values", items)), depth + 1);
            }
            @Override public void visitEnd() { done.run(); }
        };
    }

    private Object value(Object value, int depth) {
        check(depth);
        if (value instanceof Type type) return Json.object("kind", "class", "name", type.getClassName(), "descriptor", type.getDescriptor(), "complete", true);
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) items.add(value(Array.get(value, i), depth + 1));
            return Json.object("kind", "array", "values", items);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Character
                || value instanceof Long || value instanceof Float || value instanceof Double) {
            Object scalar = value instanceof Character character ? (int) character.charValue() : value;
            if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f))
                scalar = Json.object("non_finite", value.toString());
            return Json.object("kind", "primitive", "type", value.getClass().getSimpleName().toLowerCase(Locale.ROOT), "value", scalar);
        }
        return value;
    }
}
