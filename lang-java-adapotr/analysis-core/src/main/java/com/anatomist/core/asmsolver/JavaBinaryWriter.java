package com.anatomist.core.asmsolver;

import com.anatomist.core.java.JavaBinaryType;
import com.anatomist.core.java.JavaBinaryType.*;

import org.objectweb.asm.*;

import java.util.List;
import java.util.function.Function;

/**
 * Writes declaration-only class files for indexing. Concrete methods intentionally have no Code.
 */
public final class JavaBinaryWriter {
    private JavaBinaryWriter() {}

    public static byte[] signature(JavaBinaryType type) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                type.version(),
                type.access(),
                internal(type.name()),
                type.signature(),
                internal(type.superclass()),
                internals(type.interfaces()));
        if (type.nestHost() != null) writer.visitNestHost(internal(type.nestHost()));
        for (String member : type.nestMembers()) writer.visitNestMember(internal(member));
        if (type.enclosingMethod() != null) {
            var enclosing = type.enclosingMethod();
            writer.visitOuterClass(
                    internal(enclosing.owner()), enclosing.name(), enclosing.descriptor());
        }
        for (InnerType inner : type.innerTypes())
            writer.visitInnerClass(
                    internal(inner.name()),
                    internal(inner.outerName()),
                    inner.simpleName(),
                    inner.access());
        for (String permitted : type.permittedSubclasses())
            writer.visitPermittedSubclass(internal(permitted));
        annotations(
                type.annotations(),
                annotation ->
                        writer.visitAnnotation(annotation.descriptor(), annotation.visible()));
        typeAnnotations(
                type.typeAnnotations(),
                annotation ->
                        writer.visitTypeAnnotation(
                                annotation.target(),
                                path(annotation.path()),
                                annotation.annotation().descriptor(),
                                annotation.annotation().visible()));
        for (RecordComponent component : type.recordComponents()) {
            var visitor =
                    writer.visitRecordComponent(
                            component.name(), component.descriptor(), component.signature());
            annotations(
                    component.annotations(),
                    annotation ->
                            visitor.visitAnnotation(annotation.descriptor(), annotation.visible()));
            typeAnnotations(
                    component.typeAnnotations(),
                    annotation ->
                            visitor.visitTypeAnnotation(
                                    annotation.target(),
                                    path(annotation.path()),
                                    annotation.annotation().descriptor(),
                                    annotation.annotation().visible()));
            visitor.visitEnd();
        }
        for (Field field : type.fields()) {
            var visitor =
                    writer.visitField(
                            field.access(),
                            field.name(),
                            field.descriptor(),
                            field.signature(),
                            fieldConstant(field.constant()));
            annotations(
                    field.annotations(),
                    annotation ->
                            visitor.visitAnnotation(annotation.descriptor(), annotation.visible()));
            typeAnnotations(
                    field.typeAnnotations(),
                    annotation ->
                            visitor.visitTypeAnnotation(
                                    annotation.target(),
                                    path(annotation.path()),
                                    annotation.annotation().descriptor(),
                                    annotation.annotation().visible()));
            visitor.visitEnd();
        }
        for (Method method : type.methods()) {
            var visitor =
                    writer.visitMethod(
                            method.access(),
                            method.name(),
                            method.descriptor(),
                            method.signature(),
                            internals(method.exceptions()));
            for (Parameter parameter : method.parameters())
                visitor.visitParameter(parameter.name(), parameter.access());
            annotations(
                    method.annotations(),
                    annotation ->
                            visitor.visitAnnotation(annotation.descriptor(), annotation.visible()));
            typeAnnotations(
                    method.typeAnnotations(),
                    annotation ->
                            visitor.visitTypeAnnotation(
                                    annotation.target(),
                                    path(annotation.path()),
                                    annotation.annotation().descriptor(),
                                    annotation.annotation().visible()));
            if (method.visibleParameterCount() >= 0)
                visitor.visitAnnotableParameterCount(method.visibleParameterCount(), true);
            if (method.invisibleParameterCount() >= 0)
                visitor.visitAnnotableParameterCount(method.invisibleParameterCount(), false);
            for (ParameterAnnotation parameter : method.parameterAnnotations()) {
                var annotation = parameter.annotation();
                write(
                        visitor.visitParameterAnnotation(
                                parameter.parameter(),
                                annotation.descriptor(),
                                annotation.visible()),
                        annotation);
            }
            if (method.annotationDefault() != null) {
                var annotation = visitor.visitAnnotationDefault();
                value(annotation, null, method.annotationDefault(), 0);
                annotation.visitEnd();
            }
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Object fieldConstant(Value constant) {
        if (constant == null) return null;
        if (!(constant instanceof Scalar scalar))
            throw new IllegalArgumentException("unavailable or non-scalar field constant");
        return switch (scalar.value()) {
            case Boolean bool -> bool ? 1 : 0;
            case Character character -> (int) character;
            case Byte number -> number.intValue();
            case Short number -> number.intValue();
            default -> scalar.value();
        };
    }

    private static void annotations(
            List<Annotation> annotations, Function<Annotation, AnnotationVisitor> factory) {
        for (Annotation annotation : annotations) write(factory.apply(annotation), annotation);
    }

    private static void typeAnnotations(
            List<TypeAnnotation> annotations, Function<TypeAnnotation, AnnotationVisitor> factory) {
        for (TypeAnnotation annotation : annotations)
            write(factory.apply(annotation), annotation.annotation());
    }

    private static void write(AnnotationVisitor visitor, Annotation annotation) {
        annotation.values().forEach((name, value) -> value(visitor, name, value, 0));
        visitor.visitEnd();
    }

    private static void value(AnnotationVisitor visitor, String name, Value value, int depth) {
        if (depth > 128)
            throw new IllegalArgumentException("signature annotation exceeds depth budget");
        switch (value) {
            case Scalar scalar -> visitor.visit(name, scalar.value());
            case ClassLiteral literal -> visitor.visit(name, Type.getType(literal.descriptor()));
            case EnumValue enumeration ->
                    visitor.visitEnum(name, enumeration.descriptor(), enumeration.name());
            case NestedAnnotation nested -> {
                var child = visitor.visitAnnotation(name, nested.annotation().descriptor());
                nested.annotation()
                        .values()
                        .forEach((key, item) -> value(child, key, item, depth + 1));
                child.visitEnd();
            }
            case ArrayValue array -> {
                var child = visitor.visitArray(name);
                for (Value item : array.values()) value(child, null, item, depth + 1);
                child.visitEnd();
            }
            case UnavailableValue unavailable ->
                    throw new IllegalArgumentException(
                            "signature value unavailable: " + unavailable.reason());
        }
    }

    private static TypePath path(String path) {
        return path == null || path.isEmpty() ? null : TypePath.fromString(path);
    }

    private static String internal(String name) {
        return name == null ? null : name.replace('.', '/');
    }

    private static String[] internals(List<String> names) {
        return names.stream().map(JavaBinaryWriter::internal).toArray(String[]::new);
    }
}
