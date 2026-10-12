package com.anatomist.core.asmsolver;

import com.anatomist.core.java.*;
import com.anatomist.core.java.JavaBinaryType.*;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypeReference;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.*;

/** Source ABI projection for index-only archives. Missing declaration evidence fails closed. */
public final class JavaSignatureTypes {
    public sealed interface Result permits Complete, Incomplete {}

    public record Complete(List<JavaBinaryType> types) implements Result {
        public Complete {
            types = List.copyOf(types);
        }
    }

    public record Incomplete(List<String> reasons) implements Result {
        public Incomplete {
            reasons = List.copyOf(reasons);
        }
    }

    private record Entry(
            JavaUnit unit, JavaDeclaration declaration, JavaDeclaration parent, String root) {}

    private static final Set<String> TYPE_KINDS =
            Set.of("CLASS", "INTERFACE", "ANNOTATION_TYPE", "ENUM", "RECORD");
    private final int release;
    private final Map<String, Entry> entries = new TreeMap<>();

    private JavaSignatureTypes(int release) {
        if (!Set.of(8, 17, 25).contains(release))
            throw new IllegalArgumentException("unsupported signature release");
        this.release = release;
    }

    public static Result project(List<JavaUnit> units, int release) {
        var projection = new JavaSignatureTypes(release);
        List<String> reasons = new ArrayList<>();
        for (JavaUnit unit : units) {
            if (Set.of("module-info.java", "package-info.java")
                    .contains(unit.path().getFileName().toString())) {
                reasons.add(unit.path() + ": package/module descriptor requires source input");
                continue;
            }
            for (JavaDeclaration declaration : unit.declarationInventory()) {
                if (declaration.canonicalName().isEmpty()) continue;
                if (!declaration.complete())
                    reasons.add(
                            unit.path()
                                    + ": incomplete declaration "
                                    + declaration.canonicalName());
                else {
                    try {
                        projection.collect(unit, declaration, null, declaration.binaryName());
                    } catch (IllegalArgumentException incomplete) {
                        reasons.add(unit.path() + ": " + incomplete.getMessage());
                    }
                }
            }
        }
        if (!reasons.isEmpty()) return new Incomplete(reasons);
        try {
            projection.linkOwners();
            return new Complete(
                    projection.entries.values().stream().map(projection::type).toList());
        } catch (IllegalArgumentException unsupported) {
            return new Incomplete(
                    List.of("signature projection unavailable: " + unsupported.getMessage()));
        }
    }

    private void linkOwners() {
        Map<String, Entry> canonical = new HashMap<>();
        entries.values()
                .forEach(entry -> canonical.put(entry.declaration().canonicalName(), entry));
        for (Entry entry : List.copyOf(entries.values())) {
            Entry parent = canonical.get(entry.declaration().symbol().owner());
            Entry root = entry;
            for (int depth = 0; depth <= 128; depth++) {
                Entry next = canonical.get(root.declaration().symbol().owner());
                if (next == null) break;
                if (depth == 128) throw new IllegalArgumentException("declaration owner cycle");
                root = next;
            }
            entries.put(
                    entry.declaration().binaryName(),
                    new Entry(
                            entry.unit(),
                            entry.declaration(),
                            parent == null ? null : parent.declaration(),
                            root.declaration().binaryName()));
        }
    }

    private void collect(
            JavaUnit unit, JavaDeclaration declaration, JavaDeclaration parent, String root) {
        if (!TYPE_KINDS.contains(declaration.symbol().kind())
                || declaration.canonicalName().isEmpty()) return;
        if (declaration.binaryName().isEmpty())
            throw new IllegalArgumentException("binary declaration name unavailable");
        if (entries.putIfAbsent(
                        declaration.binaryName(), new Entry(unit, declaration, parent, root))
                != null)
            throw new IllegalArgumentException(
                    "duplicate binary declaration: " + declaration.binaryName());
        for (JavaDeclaration member : declaration.members())
            if (TYPE_KINDS.contains(member.symbol().kind()))
                collect(unit, member, declaration, root);
    }

    private JavaBinaryType type(Entry entry) {
        JavaDeclaration declaration = entry.declaration();
        JavaTypes graph = entry.unit().types();
        List<Field> fields = new ArrayList<>();
        List<Method> methods = new ArrayList<>();
        List<RecordComponent> components = new ArrayList<>();
        List<TypeAnnotation> annotations = new ArrayList<>();
        if (declaration.superclass() >= 0)
            uses(
                    graph,
                    declaration.superclass(),
                    TypeReference.newSuperTypeReference(-1).getValue(),
                    "",
                    annotations,
                    0);
        for (int i = 0; i < declaration.interfaces().size(); i++)
            uses(
                    graph,
                    declaration.interfaces().get(i),
                    TypeReference.newSuperTypeReference(i).getValue(),
                    "",
                    annotations,
                    0);
        parameters(graph, declaration, false, annotations);
        for (JavaDeclaration member : declaration.members()) {
            JavaSymbol symbol = member.symbol();
            if (Set.of("FIELD", "ENUM_CONSTANT").contains(symbol.kind())) {
                List<TypeAnnotation> fieldTypes = new ArrayList<>();
                uses(
                        graph,
                        symbol.declaredType(),
                        TypeReference.newTypeReference(TypeReference.FIELD).getValue(),
                        "",
                        fieldTypes,
                        0);
                fields.add(
                        new Field(
                                symbol.name(),
                                symbol.descriptor(),
                                generic(member.signature(), symbol.descriptor()),
                                flags(symbol.modifiers())
                                        | deprecated(member)
                                        | (symbol.kind().equals("ENUM_CONSTANT")
                                                ? Opcodes.ACC_ENUM
                                                : 0),
                                member.constant(),
                                annotations(member.annotations()),
                                fieldTypes));
            } else if (symbol.kind().equals("RECORD_COMPONENT")) {
                List<TypeAnnotation> componentTypes = new ArrayList<>();
                uses(
                        graph,
                        symbol.declaredType(),
                        TypeReference.newTypeReference(TypeReference.FIELD).getValue(),
                        "",
                        componentTypes,
                        0);
                components.add(
                        new RecordComponent(
                                symbol.name(),
                                symbol.descriptor(),
                                generic(member.signature(), symbol.descriptor()),
                                annotations(member.annotations()),
                                componentTypes));
            } else if (Set.of("METHOD", "CONSTRUCTOR").contains(symbol.kind()))
                methods.add(method(entry, member));
        }
        List<InnerType> inner =
                entries.values().stream()
                        .filter(
                                value ->
                                        value.parent() != null
                                                && (value.declaration()
                                                                .binaryName()
                                                                .equals(declaration.binaryName())
                                                        || value.parent()
                                                                .binaryName()
                                                                .equals(declaration.binaryName())))
                        .map(
                                value ->
                                        new InnerType(
                                                value.declaration().binaryName(),
                                                value.parent().binaryName(),
                                                value.declaration().symbol().name(),
                                                innerFlags(value.declaration())))
                        .toList();
        String superclass =
                declaration.superclass() < 0
                        ? "java.lang.Object"
                        : binary(graph, declaration.superclass());
        int access = classFlags(declaration);
        return new JavaBinaryType(
                44 + release,
                access,
                declaration.binaryName(),
                generic(
                        declaration.signature(),
                        "L"
                                + superclass.replace('.', '/')
                                + ";"
                                + String.join(
                                        "",
                                        declaration.interfaces().stream()
                                                .map(
                                                        reference ->
                                                                "L"
                                                                        + binary(graph, reference)
                                                                                .replace('.', '/')
                                                                        + ";")
                                                .toList())),
                superclass,
                declaration.interfaces().stream()
                        .map(reference -> binary(graph, reference))
                        .toList(),
                fields,
                methods,
                annotations(declaration.annotations()),
                annotations,
                inner,
                components,
                declaration.permitted().stream()
                        .map(reference -> binary(graph, reference))
                        .toList(),
                release >= 17 && entry.parent() != null ? entry.root() : null,
                release >= 17 && entry.parent() == null
                        ? entries.values().stream()
                                .filter(
                                        value ->
                                                value.root().equals(entry.root())
                                                        && value.parent() != null)
                                .map(value -> value.declaration().binaryName())
                                .toList()
                        : List.of(),
                null);
    }

    private Method method(Entry owner, JavaDeclaration declaration) {
        JavaSymbol symbol = declaration.symbol();
        JavaTypes graph = owner.unit().types();
        JavaType executable = graph.get(symbol.declaredType());
        List<TypeAnnotation> types = new ArrayList<>();
        List<Parameter> parameters = new ArrayList<>();
        List<ParameterAnnotation> parameterAnnotations = new ArrayList<>();
        List<JavaDeclaration> sourceParameters =
                declaration.members().stream()
                        .filter(member -> member.symbol().kind().equals("PARAMETER"))
                        .toList();
        String prefix = "";
        if (symbol.kind().equals("CONSTRUCTOR")) {
            if (owner.declaration().symbol().kind().equals("ENUM")) {
                prefix = "Ljava/lang/String;I";
                parameters.add(new Parameter("$enum$name", Opcodes.ACC_SYNTHETIC));
                parameters.add(new Parameter("$enum$ordinal", Opcodes.ACC_SYNTHETIC));
            } else if (owner.parent() != null
                    && !owner.declaration().symbol().modifiers().contains("static")) {
                prefix = "L" + owner.parent().binaryName().replace('.', '/') + ";";
                parameters.add(new Parameter("this$0", Opcodes.ACC_FINAL | Opcodes.ACC_MANDATED));
            }
        }
        for (int i = 0; i < sourceParameters.size(); i++) {
            var parameter = sourceParameters.get(i);
            parameters.add(
                    new Parameter(
                            parameter.symbol().name(),
                            flags(parameter.symbol().modifiers()) & Opcodes.ACC_FINAL));
            for (Annotation annotation : annotations(parameter.annotations()))
                parameterAnnotations.add(new ParameterAnnotation(i, annotation));
        }
        uses(
                graph,
                executable.returns(),
                TypeReference.newTypeReference(TypeReference.METHOD_RETURN).getValue(),
                "",
                types,
                0);
        uses(
                graph,
                executable.enclosing(),
                TypeReference.newTypeReference(TypeReference.METHOD_RECEIVER).getValue(),
                "",
                types,
                0);
        for (int i = 0; i < executable.parameters().size(); i++)
            uses(
                    graph,
                    executable.parameters().get(i),
                    TypeReference.newFormalParameterReference(i).getValue(),
                    "",
                    types,
                    0);
        for (int i = 0; i < executable.exceptions().size(); i++)
            uses(
                    graph,
                    executable.exceptions().get(i),
                    TypeReference.newExceptionReference(i).getValue(),
                    "",
                    types,
                    0);
        parameters(graph, declaration, true, types);
        String descriptor =
                prefix.isEmpty()
                        ? symbol.descriptor()
                        : "(" + prefix + symbol.descriptor().substring(1);
        boolean visible =
                parameterAnnotations.stream()
                        .anyMatch(annotation -> annotation.annotation().visible());
        boolean invisible =
                parameterAnnotations.stream()
                        .anyMatch(annotation -> !annotation.annotation().visible());
        return new Method(
                symbol.kind().equals("CONSTRUCTOR") ? "<init>" : symbol.name(),
                descriptor,
                generic(declaration.signature(), symbol.descriptor()),
                flags(symbol.modifiers())
                        | deprecated(declaration)
                        | (symbol.variadic() ? Opcodes.ACC_VARARGS : 0),
                executable.exceptions().stream()
                        .map(reference -> erasedBinary(graph, reference))
                        .toList(),
                parameters,
                annotations(declaration.annotations()),
                types,
                parameterAnnotations,
                visible ? sourceParameters.size() : -1,
                invisible ? sourceParameters.size() : -1,
                declaration.annotationDefault());
    }

    private static void parameters(
            JavaTypes graph,
            JavaDeclaration declaration,
            boolean method,
            List<TypeAnnotation> annotations) {
        List<JavaDeclaration> parameters =
                declaration.members().stream()
                        .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                        .toList();
        List<List<Integer>> boundSlots = bounds(declaration.signature());
        for (int i = 0; i < parameters.size(); i++) {
            var parameter = parameters.get(i);
            int target =
                    TypeReference.newTypeParameterReference(
                                    method
                                            ? TypeReference.METHOD_TYPE_PARAMETER
                                            : TypeReference.CLASS_TYPE_PARAMETER,
                                    i)
                            .getValue();
            for (Annotation annotation : annotations(parameter.annotations()))
                annotations.add(new TypeAnnotation(target, "", annotation));
            JavaType variable = graph.get(parameter.symbol().declaredType());
            List<Integer> upper = variable.upperBounds();
            if (upper.size() == 1
                    && graph.get(upper.getFirst()).kind() == JavaType.Kind.INTERSECTION)
                upper = graph.get(upper.getFirst()).upperBounds();
            if (i >= boundSlots.size() || upper.size() != boundSlots.get(i).size())
                throw new IllegalArgumentException("generic bound slots disagree with type graph");
            for (int j = 0; j < upper.size(); j++)
                uses(
                        graph,
                        upper.get(j),
                        TypeReference.newTypeParameterBoundReference(
                                        method
                                                ? TypeReference.METHOD_TYPE_PARAMETER_BOUND
                                                : TypeReference.CLASS_TYPE_PARAMETER_BOUND,
                                        i,
                                        boundSlots.get(i).get(j))
                                .getValue(),
                        "",
                        annotations,
                        0);
        }
    }

    private static List<List<Integer>> bounds(String signature) {
        List<List<Integer>> result = new ArrayList<>();
        new SignatureReader(signature)
                .accept(
                        new SkipSignature() {
                            private List<Integer> current;
                            private int interfaceSlot;

                            @Override
                            public void visitFormalTypeParameter(String name) {
                                current = new ArrayList<>();
                                result.add(current);
                                interfaceSlot = 1;
                            }

                            @Override
                            public SignatureVisitor visitClassBound() {
                                current.add(0);
                                return new SkipSignature();
                            }

                            @Override
                            public SignatureVisitor visitInterfaceBound() {
                                current.add(interfaceSlot++);
                                return new SkipSignature();
                            }
                        });
        return result;
    }

    private static class SkipSignature extends SignatureVisitor {
        private SkipSignature() {
            super(Opcodes.ASM9);
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return this;
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return this;
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return this;
        }

        @Override
        public SignatureVisitor visitInterface() {
            return this;
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return this;
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return this;
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            return this;
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return this;
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return this;
        }
    }

    private static void uses(
            JavaTypes graph,
            int reference,
            int target,
            String path,
            List<TypeAnnotation> annotations,
            int depth) {
        if (reference < 0) return;
        if (depth > 128)
            throw new IllegalArgumentException("type annotation nesting exceeds budget");
        JavaType type = graph.get(reference);
        if (type.kind() == JavaType.Kind.ERROR)
            throw new IllegalArgumentException("error type in signature");
        if (type.kind() == JavaType.Kind.DECLARED) {
            List<JavaType> chain = new ArrayList<>();
            JavaType current = type;
            while (true) {
                chain.add(current);
                if (chain.size() > 128)
                    throw new IllegalArgumentException("enclosing type exceeds budget");
                if (current.enclosing() < 0
                        || graph.get(current.enclosing()).kind() != JavaType.Kind.DECLARED) break;
                current = graph.get(current.enclosing());
            }
            Collections.reverse(chain);
            for (JavaType nested : chain) {
                for (Annotation annotation : annotations(nested.annotations()))
                    annotations.add(new TypeAnnotation(target, path, annotation));
                for (int i = 0; i < nested.arguments().size(); i++)
                    uses(
                            graph,
                            nested.arguments().get(i),
                            target,
                            path + i + ";",
                            annotations,
                            depth + 1);
                path += ".";
            }
            return;
        }
        for (Annotation annotation : annotations(type.annotations()))
            annotations.add(new TypeAnnotation(target, path, annotation));
        if (type.kind() == JavaType.Kind.ARRAY)
            uses(graph, type.component(), target, path + "[", annotations, depth + 1);
        else if (type.kind() == JavaType.Kind.WILDCARD) {
            for (int bound : type.upperBounds())
                uses(graph, bound, target, path + "*", annotations, depth + 1);
            uses(graph, type.lowerBound(), target, path + "*", annotations, depth + 1);
        }
    }

    private static List<Annotation> annotations(List<JavaAnnotation> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.retention() != JavaAnnotation.Retention.SOURCE)
                .map(
                        annotation ->
                                new Annotation(
                                        annotation.descriptor(),
                                        annotation.retention() == JavaAnnotation.Retention.RUNTIME,
                                        annotation.values()))
                .toList();
    }

    private static String binary(JavaTypes graph, int reference) {
        JavaType type = graph.get(reference);
        if (type.kind() != JavaType.Kind.DECLARED || type.binaryName().isEmpty())
            throw new IllegalArgumentException("binary type unavailable");
        return type.binaryName();
    }

    private static String erasedBinary(JavaTypes graph, int reference) {
        JavaType type = graph.get(reference);
        if (type.kind() == JavaType.Kind.DECLARED) return binary(graph, reference);
        if (type.kind() == JavaType.Kind.TYPEVAR && !type.upperBounds().isEmpty())
            return erasedBinary(graph, type.upperBounds().getFirst());
        if (type.kind() == JavaType.Kind.INTERSECTION && !type.upperBounds().isEmpty())
            return erasedBinary(graph, type.upperBounds().getFirst());
        throw new IllegalArgumentException("exception erasure unavailable");
    }

    private static String generic(String signature, String descriptor) {
        return signature.isEmpty() || signature.equals(descriptor) ? null : signature;
    }

    private static int classFlags(JavaDeclaration declaration) {
        int flags =
                flags(declaration.symbol().modifiers())
                        & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT);
        // The class header has no protected flag; javac emits public there and preserves
        // protected access in InnerClasses, which is what Java lookup consumes.
        if (declaration.symbol().modifiers().contains("protected")) flags |= Opcodes.ACC_PUBLIC;
        return flags
                | deprecated(declaration)
                | switch (declaration.symbol().kind()) {
                    case "INTERFACE" -> Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
                    case "ANNOTATION_TYPE" ->
                            Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION;
                    case "ENUM" -> Opcodes.ACC_ENUM | Opcodes.ACC_SUPER;
                    case "RECORD" -> Opcodes.ACC_RECORD | Opcodes.ACC_SUPER;
                    default -> Opcodes.ACC_SUPER;
                };
    }

    private static int innerFlags(JavaDeclaration declaration) {
        return (classFlags(declaration)
                        & ~(Opcodes.ACC_SUPER | Opcodes.ACC_PUBLIC | Opcodes.ACC_DEPRECATED))
                | (flags(declaration.symbol().modifiers())
                        & (Opcodes.ACC_PUBLIC
                                | Opcodes.ACC_PRIVATE
                                | Opcodes.ACC_PROTECTED
                                | Opcodes.ACC_STATIC));
    }

    private static int flags(List<String> modifiers) {
        int flags = 0;
        for (String modifier : modifiers)
            flags |=
                    switch (modifier) {
                        case "public" -> Opcodes.ACC_PUBLIC;
                        case "private" -> Opcodes.ACC_PRIVATE;
                        case "protected" -> Opcodes.ACC_PROTECTED;
                        case "static" -> Opcodes.ACC_STATIC;
                        case "final" -> Opcodes.ACC_FINAL;
                        case "abstract" -> Opcodes.ACC_ABSTRACT;
                        case "native" -> Opcodes.ACC_NATIVE;
                        case "synchronized" -> Opcodes.ACC_SYNCHRONIZED;
                        case "transient" -> Opcodes.ACC_TRANSIENT;
                        case "volatile" -> Opcodes.ACC_VOLATILE;
                        case "strictfp" -> Opcodes.ACC_STRICT;
                        default -> 0;
                    };
        return flags;
    }

    private static int deprecated(JavaDeclaration declaration) {
        return declaration.deprecated()
                        || declaration.annotations().stream()
                                .anyMatch(
                                        annotation ->
                                                annotation
                                                        .descriptor()
                                                        .equals("Ljava/lang/Deprecated;"))
                ? Opcodes.ACC_DEPRECATED
                : 0;
    }
}
