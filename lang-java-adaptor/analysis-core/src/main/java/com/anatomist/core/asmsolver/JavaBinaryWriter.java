package com.anatomist.core.asmsolver;

import com.anatomist.core.java.JavaBinaryType;
import com.anatomist.core.AnalysisContext;
import com.anatomist.core.java.JavaBinaryType.*;

import org.objectweb.asm.*;

import java.util.List;
import java.util.function.Function;

/**
 * Writes declaration-only class files for indexing. Concrete methods intentionally have no Code.
 */
public final class JavaBinaryWriter {
    private final AnalysisContext analysis;
    private JavaBinaryWriter(AnalysisContext analysis) { this.analysis=analysis; }
    private void check() {if(analysis!=null)analysis.check();}

    public static byte[] signature(JavaBinaryType type) {
        return signature(type,null);
    }
    public static byte[] signature(JavaBinaryType type,AnalysisContext analysis) {
        // ASM owns its internal byte vectors and constant-pool tables. Admit an
        // upper bound from the complete detached input before constructing ASM.
        if(analysis!=null) {
            analysis.charge(1,1,4096);
            new Admission(analysis).type(type);
        }
        return new JavaBinaryWriter(analysis).writeType(type);
    }
    private byte[] writeType(JavaBinaryType type) {
        check();
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
            check();
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
            check();
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
            check();
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
        check();
        return writer.toByteArray();
    }

    private static final class Admission {
        private final AnalysisContext analysis;
        Admission(AnalysisContext analysis) {this.analysis=analysis;}
        void record() {analysis.charge(1,1,1024);}
        void text(String value) {if(value!=null)analysis.charge(1,1,128L+32L*value.length());}
        void names(List<String> names) {for(String name:names)text(name);}
        void annotations(List<Annotation> values) {for(var value:values)annotation(value,1);}
        void uses(List<TypeAnnotation> values) {for(var value:values){record();text(value.path());annotation(value.annotation(),1);}}
        void annotation(Annotation value,int depth) {
            analysis.depth(depth,129);record();text(value.descriptor());
            for(var entry:value.values().entrySet()){text(entry.getKey());value(entry.getValue(),depth);}
        }
        void value(Value value,int depth) {
            if(value==null)return;
            analysis.depth(depth,129);record();
            switch(value) {
                case Scalar scalar -> {if(scalar.value() instanceof String text)text(text);}
                case ClassLiteral literal -> text(literal.descriptor());
                case EnumValue enumeration -> {text(enumeration.descriptor());text(enumeration.name());}
                case NestedAnnotation nested -> annotation(nested.annotation(),depth+1);
                case ArrayValue array -> {for(var child:array.values())value(child,depth+1);}
                case UnavailableValue unavailable -> text(unavailable.reason());
            }
        }
        void type(JavaBinaryType type) {
            record();text(type.name());text(type.signature());text(type.superclass());names(type.interfaces());
            text(type.nestHost());names(type.nestMembers());names(type.permittedSubclasses());
            if(type.enclosingMethod()!=null){record();text(type.enclosingMethod().owner());text(type.enclosingMethod().name());text(type.enclosingMethod().descriptor());}
            for(var inner:type.innerTypes()){record();text(inner.name());text(inner.outerName());text(inner.simpleName());}
            annotations(type.annotations());uses(type.typeAnnotations());
            for(var component:type.recordComponents()){record();text(component.name());text(component.descriptor());text(component.signature());annotations(component.annotations());uses(component.typeAnnotations());}
            for(var field:type.fields()){record();text(field.name());text(field.descriptor());text(field.signature());value(field.constant(),1);annotations(field.annotations());uses(field.typeAnnotations());}
            for(var method:type.methods()){
                record();text(method.name());text(method.descriptor());text(method.signature());names(method.exceptions());
                for(var parameter:method.parameters()){record();text(parameter.name());}
                annotations(method.annotations());uses(method.typeAnnotations());
                for(var parameter:method.parameterAnnotations()){record();annotation(parameter.annotation(),1);}
                value(method.annotationDefault(),1);
            }
        }
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

    private void annotations(
            List<Annotation> annotations, Function<Annotation, AnnotationVisitor> factory) {
        for (Annotation annotation : annotations) { check(); write(factory.apply(annotation), annotation); }
    }

    private void typeAnnotations(
            List<TypeAnnotation> annotations, Function<TypeAnnotation, AnnotationVisitor> factory) {
        for (TypeAnnotation annotation : annotations)
            write(factory.apply(annotation), annotation.annotation());
    }

    private void write(AnnotationVisitor visitor, Annotation annotation) {
        annotation.values().forEach((name, value) -> value(visitor, name, value, 0));
        visitor.visitEnd();
    }

    private void value(AnnotationVisitor visitor, String name, Value value, int depth) {
        check();
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
