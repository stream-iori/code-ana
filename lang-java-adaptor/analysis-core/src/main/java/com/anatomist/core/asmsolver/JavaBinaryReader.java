package com.anatomist.core.asmsolver;

import com.anatomist.core.java.JavaBinaryType;
import com.anatomist.core.java.JavaBinaryType.*;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Reads declarations without loading classes, interpreting code or retaining ASM visitors. */
public final class JavaBinaryReader {
    private JavaBinaryReader() {}

    public static JavaBinaryType read(byte[] bytes) {
        if (bytes == null
                || bytes.length < 10
                || bytes[0] != (byte) 0xca
                || bytes[1] != (byte) 0xfe
                || bytes[2] != (byte) 0xba
                || bytes[3] != (byte) 0xbe)
            throw new IllegalArgumentException("invalid class-file header");
        var builder = new TypeBuilder();
        // MethodParameters is declaration data: do not SKIP_DEBUG (which also drops that
        // attribute).
        new ClassReader(bytes).accept(builder, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return builder.build();
    }

    private static String binaryName(String internal) {
        return internal == null ? null : internal.replace('/', '.');
    }

    private static List<String> names(String[] internal) {
        return internal == null
                ? List.of()
                : Arrays.stream(internal).map(JavaBinaryReader::binaryName).toList();
    }

    private static final class TypeBuilder extends ClassVisitor {
        private int version;
        private int access;
        private String name;
        private String signature;
        private String superclass;
        private List<String> interfaces = List.of();
        private final List<Field> fields = new ArrayList<>();
        private final List<Method> methods = new ArrayList<>();
        private final List<Annotation> annotations = new ArrayList<>();
        private final List<TypeAnnotation> typeAnnotations = new ArrayList<>();
        private final List<InnerType> innerTypes = new ArrayList<>();
        private final List<RecordComponent> recordComponents = new ArrayList<>();
        private final List<String> permitted = new ArrayList<>();
        private String nestHost;
        private final List<String> nestMembers = new ArrayList<>();
        private EnclosingMethod enclosing;

        private TypeBuilder() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces) {
            this.version = version;
            this.access = access;
            this.name = binaryName(name);
            this.signature = signature;
            this.superclass = binaryName(superName);
            this.interfaces = names(interfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor, visible, annotations::add);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int ref, TypePath path, String descriptor, boolean visible) {
            return typeAnnotation(ref, path, descriptor, visible, typeAnnotations);
        }

        @Override
        public void visitInnerClass(String name, String outer, String simple, int access) {
            innerTypes.add(new InnerType(binaryName(name), binaryName(outer), simple, access));
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            enclosing = new EnclosingMethod(binaryName(owner), name, descriptor);
        }

        @Override
        public void visitNestHost(String name) {
            nestHost = binaryName(name);
        }

        @Override
        public void visitNestMember(String name) {
            nestMembers.add(binaryName(name));
        }

        @Override
        public void visitPermittedSubclass(String name) {
            permitted.add(binaryName(name));
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object constant) {
            return new FieldVisitor(Opcodes.ASM9) {
                private final List<Annotation> annotations = new ArrayList<>();
                private final List<TypeAnnotation> typeAnnotations = new ArrayList<>();

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return annotation(desc, visible, annotations::add);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int ref, TypePath path, String desc, boolean visible) {
                    return typeAnnotation(ref, path, desc, visible, typeAnnotations);
                }

                @Override
                public void visitEnd() {
                    fields.add(
                            new Field(
                                    name,
                                    descriptor,
                                    signature,
                                    access,
                                    constant == null ? null : value(constant),
                                    annotations,
                                    typeAnnotations));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                private final List<Parameter> parameters = new ArrayList<>();
                private final List<Annotation> annotations = new ArrayList<>();
                private final List<TypeAnnotation> typeAnnotations = new ArrayList<>();
                private final List<ParameterAnnotation> parameterAnnotations = new ArrayList<>();
                private int visibleCount = -1;
                private int invisibleCount = -1;
                private Value defaultValue;

                @Override
                public void visitParameter(String name, int access) {
                    parameters.add(new Parameter(name, access));
                }

                @Override
                public void visitAnnotableParameterCount(int count, boolean visible) {
                    if (visible) visibleCount = count;
                    else invisibleCount = count;
                }

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return annotation(desc, visible, annotations::add);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int ref, TypePath path, String desc, boolean visible) {
                    return typeAnnotation(ref, path, desc, visible, typeAnnotations);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(
                        int parameter, String desc, boolean visible) {
                    return annotation(
                            desc,
                            visible,
                            a -> parameterAnnotations.add(new ParameterAnnotation(parameter, a)));
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return values((ignored, v) -> defaultValue = v, () -> {});
                }

                @Override
                public void visitEnd() {
                    methods.add(
                            new Method(
                                    name,
                                    descriptor,
                                    signature,
                                    access,
                                    names(exceptions),
                                    parameters,
                                    annotations,
                                    typeAnnotations,
                                    parameterAnnotations,
                                    visibleCount,
                                    invisibleCount,
                                    defaultValue));
                }
            };
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
                String name, String descriptor, String signature) {
            return new RecordComponentVisitor(Opcodes.ASM9) {
                private final List<Annotation> annotations = new ArrayList<>();
                private final List<TypeAnnotation> typeAnnotations = new ArrayList<>();

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return annotation(desc, visible, annotations::add);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int ref, TypePath path, String desc, boolean visible) {
                    return typeAnnotation(ref, path, desc, visible, typeAnnotations);
                }

                @Override
                public void visitEnd() {
                    recordComponents.add(
                            new RecordComponent(
                                    name, descriptor, signature, annotations, typeAnnotations));
                }
            };
        }

        private JavaBinaryType build() {
            return new JavaBinaryType(
                    version,
                    access,
                    name,
                    signature,
                    superclass,
                    interfaces,
                    fields,
                    methods,
                    annotations,
                    typeAnnotations,
                    innerTypes,
                    recordComponents,
                    permitted,
                    nestHost,
                    nestMembers,
                    enclosing);
        }
    }

    private static AnnotationVisitor typeAnnotation(
            int ref,
            TypePath path,
            String descriptor,
            boolean visible,
            List<TypeAnnotation> target) {
        return annotation(
                descriptor,
                visible,
                a -> target.add(new TypeAnnotation(ref, path == null ? "" : path.toString(), a)));
    }

    private static AnnotationVisitor annotation(
            String descriptor, boolean visible, Consumer<Annotation> done) {
        Map<String, Value> attributes = new LinkedHashMap<>();
        return values(
                attributes::put,
                () -> done.accept(new Annotation(descriptor, visible, attributes)));
    }

    private static AnnotationVisitor values(
            java.util.function.BiConsumer<String, Value> sink, Runnable done) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                sink.accept(name, value(value));
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                sink.accept(name, new EnumValue(descriptor, value));
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                return annotation(
                        descriptor, false, a -> sink.accept(name, new NestedAnnotation(a)));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                List<Value> items = new ArrayList<>();
                return values(
                        (ignored, item) -> items.add(item),
                        () -> sink.accept(name, new ArrayValue(items)));
            }

            @Override
            public void visitEnd() {
                done.run();
            }
        };
    }

    private static Value value(Object value) {
        if (value instanceof Type type) return new ClassLiteral(type.getDescriptor());
        if (value.getClass().isArray()) {
            List<Value> items = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) items.add(value(Array.get(value, i)));
            return new ArrayValue(items);
        }
        return new Scalar(value);
    }
}
