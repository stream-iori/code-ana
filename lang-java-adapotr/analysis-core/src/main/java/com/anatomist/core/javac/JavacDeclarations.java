package com.anatomist.core.javac;

import com.anatomist.core.java.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded decoding of the detached declaration inventory and compiler annotation values. */
final class JavacDeclarations {
    private JavacDeclarations() {}

    static List<JavaDeclaration> readFrame(DataInputStream input) throws IOException {
        int length = length(input, JavacProtocol.MAX_BYTES);
        JavacDecodeBudget.take(input, length);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try (var frame = JavacDecodeBudget.frame(new ByteArrayInputStream(bytes), input)) {
            int count = count(frame, 100000);
            List<JavaDeclaration> declarations = new ArrayList<>(count);
            for (int i = 0; i < count; i++) declarations.add(declaration(frame, 0));
            if (frame.read() != -1) throw new IOException("trailing declaration inventory data");
            return List.copyOf(declarations);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid declaration inventory", invalid);
        }
    }

    private static JavaDeclaration declaration(DataInputStream in, int depth) throws IOException {
        JavacDecodeBudget.take(in, 128);
        depth(depth);
        var symbol =
                new JavaSymbol(
                        string(in),
                        string(in),
                        string(in),
                        string(in),
                        in.readInt(),
                        strings(in),
                        in.readBoolean());
        String canonicalName = string(in), binaryName = string(in), signature = string(in);
        SourceSpan span = new SourceSpan(in.readLong(), in.readLong());
        JavaDeclaration.Origin origin = JavaDeclaration.Origin.valueOf(string(in));
        boolean deprecated = in.readBoolean();
        int superclass = in.readInt();
        List<Integer> interfaces = refs(in), permitted = refs(in);
        List<JavaAnnotation> annotations = annotations(in);
        JavaBinaryType.Value constant = value(in, 0), annotationDefault = value(in, 0);
        int count = count(in, 100000);
        List<JavaDeclaration> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) members.add(declaration(in, depth + 1));
        List<String> incompleteReasons = strings(in);
        int overridesCount = count(in, 100000);
        List<JavaOverrides.Target> overrides = new ArrayList<>(overridesCount);
        for (int i = 0; i < overridesCount; i++) {
            String owner = string(in),
                    binaryOwner = string(in),
                    name = string(in),
                    descriptor = string(in);
            overrides.add(
                    new JavaOverrides.Target(
                            new JavaSymbol(
                                    "METHOD",
                                    owner,
                                    name,
                                    descriptor,
                                    in.readInt(),
                                    strings(in),
                                    in.readBoolean()),
                            binaryOwner));
        }
        return new JavaDeclaration(
                symbol,
                canonicalName,
                binaryName,
                signature,
                span,
                origin,
                superclass,
                interfaces,
                permitted,
                annotations,
                constant,
                annotationDefault,
                members,
                incompleteReasons,
                new JavaOverrides(overrides, strings(in)),
                deprecated);
    }

    static List<JavaAnnotation> annotations(DataInputStream in) throws IOException {
        int count = count(in, 100000);
        List<JavaAnnotation> annotations = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) annotations.add(annotation(in, 0));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid compiler annotation", invalid);
        }
        return List.copyOf(annotations);
    }

    private static JavaAnnotation annotation(DataInputStream in, int depth) throws IOException {
        JavacDecodeBudget.take(in, 128);
        depth(depth);
        String descriptor = string(in);
        JavaAnnotation.Retention retention = JavaAnnotation.Retention.valueOf(string(in));
        int count = count(in, 100000);
        Map<String, JavaBinaryType.Value> values = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String name = string(in);
            if (values.containsKey(name)) throw new IOException("duplicate annotation attribute");
            JavaBinaryType.Value value = value(in, depth + 1);
            if (value == null) throw new IOException("null annotation attribute");
            values.put(name, value);
        }
        return new JavaAnnotation(descriptor, retention, values);
    }

    private static JavaBinaryType.Value value(DataInputStream in, int depth) throws IOException {
        JavacDecodeBudget.take(in, 128);
        depth(depth);
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> new JavaBinaryType.Scalar(in.readBoolean());
            case 2 -> new JavaBinaryType.Scalar(in.readByte());
            case 3 -> new JavaBinaryType.Scalar(in.readShort());
            case 4 -> new JavaBinaryType.Scalar(in.readChar());
            case 5 -> new JavaBinaryType.Scalar(in.readInt());
            case 6 -> new JavaBinaryType.Scalar(in.readLong());
            case 7 -> new JavaBinaryType.Scalar(in.readFloat());
            case 8 -> new JavaBinaryType.Scalar(in.readDouble());
            case 9 -> new JavaBinaryType.Scalar(string(in));
            case 10 -> new JavaBinaryType.ClassLiteral(string(in));
            case 11 -> new JavaBinaryType.EnumValue(string(in), string(in));
            case 12 -> {
                JavaAnnotation nested = annotation(in, depth + 1);
                yield new JavaBinaryType.NestedAnnotation(
                        new JavaBinaryType.Annotation(nested.descriptor(), false, nested.values()));
            }
            case 13 -> {
                int count = count(in, 100000);
                List<JavaBinaryType.Value> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    JavaBinaryType.Value value = value(in, depth + 1);
                    if (value == null) throw new IOException("null annotation array element");
                    values.add(value);
                }
                yield new JavaBinaryType.ArrayValue(values);
            }
            case 14 -> new JavaBinaryType.UnavailableValue(string(in));
            default -> throw new IOException("unknown compiler annotation value");
        };
    }

    private static List<String> strings(DataInputStream in) throws IOException {
        int count = count(in, 100000);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(string(in));
        return List.copyOf(values);
    }

    private static List<Integer> refs(DataInputStream in) throws IOException {
        int count = count(in, 100000);
        List<Integer> refs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) refs.add(in.readInt());
        return List.copyOf(refs);
    }

    private static String string(DataInputStream in) throws IOException {
        int length = length(in, JavacProtocol.MAX_BYTES);
        JavacDecodeBudget.take(in, 64L + 4L * length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int value = length(in, maximum);
        JavacDecodeBudget.take(in, 64L * value);
        return value;
    }

    private static int length(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum)
            throw new IOException("declaration length exceeds budget");
        return count;
    }

    private static void depth(int depth) throws IOException {
        if (depth > 128) throw new IOException("declaration nesting exceeds budget");
    }
}
