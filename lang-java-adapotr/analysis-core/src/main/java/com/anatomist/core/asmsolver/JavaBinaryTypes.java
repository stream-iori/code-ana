package com.anatomist.core.asmsolver;

import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.*;
import java.util.function.IntConsumer;

/** Bounded class-file signature projection into the same type graph used by source declarations. */
public final class JavaBinaryTypes {
    public record MethodShape(
            JavaTypes types,
            int executable,
            List<String> classParameters,
            List<String> methodParameters,
            List<Integer> methodParameterTypes) {}

    public record ClassShape(
            JavaTypes types, List<String> parameters, int superclass, List<Integer> interfaces) {}

    private int superclass = -1;
    private final List<Integer> interfaces = new ArrayList<>();
    private final List<JavaType> graph = new ArrayList<>();
    private final Map<String, Integer> variables = new LinkedHashMap<>();
    private final List<String> classParameters = new ArrayList<>();
    private final List<String> methodParameters = new ArrayList<>();
    private final List<Integer> methodParameterTypes = new ArrayList<>();
    private final List<Integer> parameters = new ArrayList<>();
    private final List<Integer> exceptions = new ArrayList<>();
    private int returns = -1;

    private JavaBinaryTypes() {}

    public static ClassShape hierarchy(JavaBinaryType type) {
        var builder = new JavaBinaryTypes();
        String signature = type.signature();
        if (signature == null)
            signature =
                    (type.superclass() == null
                                    ? "Ljava/lang/Object;"
                                    : "L" + type.superclass().replace('.', '/') + ";")
                            + String.join(
                                    "",
                                    type.interfaces().stream()
                                            .map(name -> "L" + name.replace('.', '/') + ";")
                                            .toList());
        new SignatureReader(signature).accept(builder.header(builder.classParameters, false));
        return new ClassShape(
                new JavaTypes(builder.graph),
                List.copyOf(builder.classParameters),
                type.superclass() == null ? -1 : builder.superclass,
                List.copyOf(builder.interfaces));
    }

    public static MethodShape method(JavaBinaryType owner, JavaBinaryType.Method method) {
        var builder = new JavaBinaryTypes();
        if (owner.signature() != null)
            new SignatureReader(owner.signature())
                    .accept(builder.header(builder.classParameters, false));
        new SignatureReader(method.signature() == null ? method.descriptor() : method.signature())
                .accept(builder.header(builder.methodParameters, true));
        if (builder.returns < 0)
            throw new IllegalArgumentException("binary method has no return type");
        int executable =
                builder.add(
                        new JavaType(
                                JavaType.Kind.EXECUTABLE,
                                "",
                                method.descriptor(),
                                -1,
                                List.of(),
                                List.of(),
                                -1,
                                -1,
                                builder.parameters,
                                builder.returns,
                                builder.exceptions));
        return new MethodShape(
                new JavaTypes(builder.graph),
                executable,
                List.copyOf(builder.classParameters),
                List.copyOf(builder.methodParameters),
                List.copyOf(builder.methodParameterTypes));
    }

    private SignatureVisitor header(List<String> formals, boolean method) {
        return new SignatureVisitor(Opcodes.ASM9) {
            private int variable = -1;

            @Override
            public void visitFormalTypeParameter(String name) {
                variable =
                        add(
                                new JavaType(
                                        JavaType.Kind.TYPEVAR,
                                        name,
                                        name,
                                        -1,
                                        List.of(),
                                        List.of(),
                                        -1,
                                        -1,
                                        List.of(),
                                        -1,
                                        List.of()));
                variables.put(name, variable);
                formals.add(name);
                if (method) methodParameterTypes.add(variable);
            }

            private SignatureVisitor bound() {
                int reference = variable;
                return type(
                        value -> {
                            JavaType old = graph.get(reference);
                            var bounds = new ArrayList<>(old.upperBounds());
                            bounds.add(value);
                            graph.set(
                                    reference,
                                    new JavaType(
                                            old.kind(),
                                            old.name(),
                                            old.display(),
                                            -1,
                                            List.of(),
                                            bounds,
                                            -1,
                                            -1,
                                            List.of(),
                                            -1,
                                            List.of()));
                        },
                        0);
            }

            @Override
            public SignatureVisitor visitClassBound() {
                return bound();
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return bound();
            }

            @Override
            public SignatureVisitor visitSuperclass() {
                return type(
                        value -> {
                            if (!method) superclass = value;
                        },
                        0);
            }

            @Override
            public SignatureVisitor visitInterface() {
                return type(
                        value -> {
                            if (!method) interfaces.add(value);
                        },
                        0);
            }

            @Override
            public SignatureVisitor visitParameterType() {
                return type(
                        value -> {
                            if (method) parameters.add(value);
                        },
                        0);
            }

            @Override
            public SignatureVisitor visitReturnType() {
                return type(
                        value -> {
                            if (method) returns = value;
                        },
                        0);
            }

            @Override
            public SignatureVisitor visitExceptionType() {
                return type(
                        value -> {
                            if (method) exceptions.add(value);
                        },
                        0);
            }
        };
    }

    private SignatureVisitor type(IntConsumer result, int depth) {
        if (depth > 64) throw new IllegalArgumentException("binary type depth exceeds budget");
        return new SignatureVisitor(Opcodes.ASM9) {
            private String binary;
            private int enclosing = -1;
            private final List<Integer> arguments = new ArrayList<>();

            @Override
            public void visitBaseType(char descriptor) {
                JavaType.Kind kind =
                        switch (descriptor) {
                            case 'Z' -> JavaType.Kind.BOOLEAN;
                            case 'B' -> JavaType.Kind.BYTE;
                            case 'S' -> JavaType.Kind.SHORT;
                            case 'I' -> JavaType.Kind.INT;
                            case 'J' -> JavaType.Kind.LONG;
                            case 'C' -> JavaType.Kind.CHAR;
                            case 'F' -> JavaType.Kind.FLOAT;
                            case 'D' -> JavaType.Kind.DOUBLE;
                            case 'V' -> JavaType.Kind.VOID;
                            default ->
                                    throw new IllegalArgumentException("invalid binary primitive");
                        };
                result.accept(
                        add(
                                new JavaType(
                                        kind,
                                        kind.name().toLowerCase(Locale.ROOT),
                                        "",
                                        -1,
                                        List.of(),
                                        List.of(),
                                        -1,
                                        -1,
                                        List.of(),
                                        -1,
                                        List.of())));
            }

            @Override
            public void visitTypeVariable(String name) {
                Integer reference = variables.get(name);
                if (reference == null) {
                    reference =
                            add(
                                    new JavaType(
                                            JavaType.Kind.TYPEVAR,
                                            name,
                                            name,
                                            -1,
                                            List.of(),
                                            List.of(),
                                            -1,
                                            -1,
                                            List.of(),
                                            -1,
                                            List.of()));
                    variables.put(name, reference);
                }
                result.accept(reference);
            }

            @Override
            public SignatureVisitor visitArrayType() {
                return type(
                        component ->
                                result.accept(
                                        add(
                                                new JavaType(
                                                        JavaType.Kind.ARRAY,
                                                        "",
                                                        "",
                                                        -1,
                                                        List.of(),
                                                        List.of(),
                                                        -1,
                                                        component,
                                                        List.of(),
                                                        -1,
                                                        List.of()))),
                        depth + 1);
            }

            @Override
            public void visitClassType(String name) {
                binary = name.replace('/', '.');
            }

            @Override
            public void visitInnerClassType(String name) {
                enclosing = declared();
                arguments.clear();
                binary += "$" + name;
            }

            @Override
            public void visitTypeArgument() {
                arguments.add(
                        add(
                                new JavaType(
                                        JavaType.Kind.WILDCARD,
                                        "",
                                        "?",
                                        -1,
                                        List.of(),
                                        List.of(),
                                        -1,
                                        -1,
                                        List.of(),
                                        -1,
                                        List.of())));
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                return type(
                        value ->
                                arguments.add(
                                        wildcard == '='
                                                ? value
                                                : add(
                                                        new JavaType(
                                                                JavaType.Kind.WILDCARD,
                                                                "",
                                                                "?",
                                                                -1,
                                                                List.of(),
                                                                wildcard == '+'
                                                                        ? List.of(value)
                                                                        : List.of(),
                                                                wildcard == '-' ? value : -1,
                                                                -1,
                                                                List.of(),
                                                                -1,
                                                                List.of()))),
                        depth + 1);
            }

            private int declared() {
                if (binary == null)
                    throw new IllegalArgumentException("binary name is unavailable");
                return add(
                        new JavaType(
                                JavaType.Kind.DECLARED,
                                binary.replace('$', '.'),
                                binary,
                                enclosing,
                                arguments,
                                List.of(),
                                -1,
                                -1,
                                List.of(),
                                -1,
                                List.of(),
                                List.of(),
                                binary));
            }

            @Override
            public void visitEnd() {
                result.accept(declared());
            }
        };
    }

    private int add(JavaType type) {
        if (graph.size() >= 100_000)
            throw new IllegalArgumentException("binary type graph exceeds budget");
        graph.add(type);
        return graph.size() - 1;
    }
}
