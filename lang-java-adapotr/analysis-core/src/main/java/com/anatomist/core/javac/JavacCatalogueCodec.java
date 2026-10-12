package com.anatomist.core.javac;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.java.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded, data-only persisted dependency declarations. No bodies or compiler objects. */
final class JavacCatalogueCodec {
    static final long MAX_BYTES = 64L * 1024 * 1024;

    static final class BudgetExceeded extends IOException {
        BudgetExceeded(String message) {
            super(message);
        }
    }

    static final int MAGIC = 0x41434432;
    private final DataInputStream input;
    private final DataOutputStream output;
    private int work;

    private JavacCatalogueCodec(DataInputStream input, DataOutputStream output) {
        this.input = input;
        this.output = output;
    }

    static void write(Path path, List<JavaUnit> units) throws IOException {
        try (var output =
                new DataOutputStream(
                        new BufferedOutputStream(
                                new LimitedOutput(Files.newOutputStream(path), MAX_BYTES)))) {
            var codec = new JavacCatalogueCodec(null, output);
            output.writeInt(MAGIC);
            output.writeInt(units.size());
            for (JavaUnit unit : units) codec.unit(unit);
        }
    }

    static List<JavaUnit> read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new BudgetExceeded("catalogue exceeds byte budget");
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC) throw new IOException("catalogue format mismatch");
            var codec = new JavacCatalogueCodec(input, null);
            List<JavaUnit> units = new ArrayList<>();
            int count = codec.count();
            for (int i = 0; i < count; i++) units.add(codec.unit());
            if (input.read() != -1) throw new IOException("trailing catalogue bytes");
            return List.copyOf(units);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid catalogue", invalid);
        }
    }

    private void unit(JavaUnit unit) throws IOException {
        visit(0);
        if (!unit.declarations()
                || unit.hasProcessorEvidence()
                || !unit.bindings().isEmpty()
                || !Set.of("getPackageName", "getPackageAnnotations", "getPackage", "getModule")
                        .containsAll(unit.tree().fields().keySet()))
            throw new IOException("catalogue contains executable analysis state");
        text(unit.path().toString());
        text(unit.sourceHash());
        data(unit.tree(), 0);
        output.writeInt(unit.types().entries().size());
        for (var type : unit.types().entries()) type(type);
        declarations(unit.declarationInventory(), 0);
        var extensions = unit.extensions();
        output.writeInt(extensions.generated().size());
        for (var entry : extensions.generated().entrySet()) {
            text(entry.getKey());
            var origin = entry.getValue();
            text(origin.producerId());
            text(origin.generator());
            text(origin.generatorMode());
            text(origin.generatedFrom());
            text(origin.confidence());
            output.writeBoolean(origin.bodyAvailable());
        }
        data(extensions.declarationMetadata(), 0);
        output.writeInt(extensions.diagnostics().size());
        for (var diagnostic : extensions.diagnostics()) diagnostic(diagnostic);
        data(extensions.counters(), 0);
    }

    private JavaUnit unit() throws IOException {
        visit(0);
        Path path = Path.of(text());
        String hash = text();
        if (!path.isAbsolute() || !hash.matches("[a-f0-9]{64}"))
            throw new IOException("invalid catalogue source identity");
        Object header = data(0);
        if (!(header instanceof JavaNode tree)
                || !tree.kind().equals("COMPILATION_UNIT")
                || !Set.of("getPackageName", "getPackageAnnotations", "getPackage", "getModule")
                        .containsAll(tree.fields().keySet()))
            throw new IOException("invalid catalogue header");
        List<JavaType> types = new ArrayList<>();
        int count = count();
        for (int i = 0; i < count; i++) types.add(type());
        var declarations = declarations(0);
        Map<String, JavaGeneratedOrigin> generated = new LinkedHashMap<>();
        count = count();
        for (int i = 0; i < count; i++) {
            String name = text();
            var origin =
                    new JavaGeneratedOrigin(
                            text(), text(), text(), text(), text(), input.readBoolean());
            if (generated.putIfAbsent(name, origin) != null)
                throw new IOException("duplicate generated declaration");
        }
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        for (var entry : map(data(0)).entrySet())
            metadata.put(entry.getKey(), map(entry.getValue()));
        List<IndexDiagnostic> diagnostics = new ArrayList<>();
        count = count();
        for (int i = 0; i < count; i++) diagnostics.add(diagnostic());
        Map<String, Long> counters = new LinkedHashMap<>();
        for (var entry : map(data(0)).entrySet()) {
            if (!(entry.getValue() instanceof Long value))
                throw new IOException("invalid catalogue counter");
            counters.put(entry.getKey(), value);
        }
        return new JavaUnit(
                path,
                tree,
                com.anatomist.core.java.JavaProcessingState.NONE,
                Map.of(),
                new JavaTypes(types),
                true,
                declarations,
                hash,
                new JavaExtensionData(generated, metadata, diagnostics, counters));
    }

    private void type(JavaType value) throws IOException {
        visit(0);
        text(value.kind().name());
        text(value.name());
        text(value.display());
        output.writeInt(value.enclosing());
        refs(value.arguments());
        refs(value.upperBounds());
        output.writeInt(value.lowerBound());
        output.writeInt(value.component());
        refs(value.parameters());
        output.writeInt(value.returns());
        refs(value.exceptions());
        annotations(value.annotations(), 0);
        text(value.binaryName());
    }

    private JavaType type() throws IOException {
        visit(0);
        return new JavaType(
                JavaType.Kind.valueOf(text()),
                text(),
                text(),
                input.readInt(),
                refs(),
                refs(),
                input.readInt(),
                input.readInt(),
                refs(),
                input.readInt(),
                refs(),
                annotations(0),
                text());
    }

    private void symbol(JavaSymbol value) throws IOException {
        text(value.kind());
        text(value.owner());
        text(value.name());
        text(value.descriptor());
        output.writeInt(value.declaredType());
        strings(value.modifiers());
        output.writeBoolean(value.variadic());
    }

    private JavaSymbol symbol() throws IOException {
        return new JavaSymbol(
                text(), text(), text(), text(), input.readInt(), strings(), input.readBoolean());
    }

    private void declarations(List<JavaDeclaration> values, int depth) throws IOException {
        output.writeInt(values.size());
        for (var value : values) declaration(value, depth + 1);
    }

    private List<JavaDeclaration> declarations(int depth) throws IOException {
        int count = count();
        List<JavaDeclaration> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add(declaration(depth + 1));
        return List.copyOf(values);
    }

    private void declaration(JavaDeclaration value, int depth) throws IOException {
        visit(depth);
        symbol(value.symbol());
        text(value.canonicalName());
        text(value.binaryName());
        text(value.signature());
        output.writeLong(value.span().start());
        output.writeLong(value.span().end());
        text(value.origin().name());
        output.writeBoolean(value.deprecated());
        output.writeInt(value.superclass());
        refs(value.interfaces());
        refs(value.permitted());
        annotations(value.annotations(), depth);
        value(value.constant(), depth);
        value(value.annotationDefault(), depth);
        declarations(value.members(), depth);
        strings(value.incompleteReasons());
        output.writeInt(value.overrides().targets().size());
        for (var target : value.overrides().targets()) {
            symbol(target.symbol());
            text(target.binaryOwner());
        }
        strings(value.overrides().incompleteReasons());
    }

    private JavaDeclaration declaration(int depth) throws IOException {
        visit(depth);
        var symbol = symbol();
        String canonical = text(), binary = text(), signature = text();
        var span = new SourceSpan(input.readLong(), input.readLong());
        var origin = JavaDeclaration.Origin.valueOf(text());
        boolean deprecated = input.readBoolean();
        int superclass = input.readInt();
        var interfaces = refs();
        var permitted = refs();
        var annotations = annotations(depth);
        var constant = value(depth);
        var defaultValue = value(depth);
        var members = declarations(depth);
        var reasons = strings();
        List<JavaOverrides.Target> targets = new ArrayList<>();
        int count = count();
        for (int i = 0; i < count; i++) targets.add(new JavaOverrides.Target(symbol(), text()));
        return new JavaDeclaration(
                symbol,
                canonical,
                binary,
                signature,
                span,
                origin,
                superclass,
                interfaces,
                permitted,
                annotations,
                constant,
                defaultValue,
                members,
                reasons,
                new JavaOverrides(targets, strings()),
                deprecated);
    }

    private void annotations(List<JavaAnnotation> values, int depth) throws IOException {
        output.writeInt(values.size());
        for (var value : values) {
            visit(depth);
            text(value.descriptor());
            text(value.retention().name());
            attributes(value.values(), depth);
        }
    }

    private List<JavaAnnotation> annotations(int depth) throws IOException {
        List<JavaAnnotation> values = new ArrayList<>();
        int count = count();
        for (int i = 0; i < count; i++) {
            visit(depth);
            values.add(
                    new JavaAnnotation(
                            text(), JavaAnnotation.Retention.valueOf(text()), attributes(depth)));
        }
        return List.copyOf(values);
    }

    private void attributes(Map<String, JavaBinaryType.Value> values, int depth)
            throws IOException {
        output.writeInt(values.size());
        for (var entry : values.entrySet()) {
            text(entry.getKey());
            value(entry.getValue(), depth + 1);
        }
    }

    private Map<String, JavaBinaryType.Value> attributes(int depth) throws IOException {
        Map<String, JavaBinaryType.Value> values = new LinkedHashMap<>();
        int count = count();
        for (int i = 0; i < count; i++) {
            String name = text();
            var value = value(depth + 1);
            if (value == null || values.putIfAbsent(name, value) != null)
                throw new IOException("invalid catalogue annotation attribute");
        }
        return values;
    }

    private void value(JavaBinaryType.Value value, int depth) throws IOException {
        visit(depth);
        switch (value) {
            case null -> output.writeByte(0);
            case JavaBinaryType.Scalar scalar -> {
                output.writeByte(1);
                data(scalar.value(), depth + 1);
            }
            case JavaBinaryType.ClassLiteral literal -> {
                output.writeByte(2);
                text(literal.descriptor());
            }
            case JavaBinaryType.EnumValue enumeration -> {
                output.writeByte(3);
                text(enumeration.descriptor());
                text(enumeration.name());
            }
            case JavaBinaryType.NestedAnnotation nested -> {
                output.writeByte(4);
                text(nested.annotation().descriptor());
                output.writeBoolean(nested.annotation().visible());
                attributes(nested.annotation().values(), depth + 1);
            }
            case JavaBinaryType.ArrayValue array -> {
                output.writeByte(5);
                output.writeInt(array.values().size());
                for (var item : array.values()) value(item, depth + 1);
            }
            case JavaBinaryType.UnavailableValue unavailable -> {
                output.writeByte(6);
                text(unavailable.reason());
            }
        }
    }

    private JavaBinaryType.Value value(int depth) throws IOException {
        visit(depth);
        return switch (input.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> new JavaBinaryType.Scalar(data(depth + 1));
            case 2 -> new JavaBinaryType.ClassLiteral(text());
            case 3 -> new JavaBinaryType.EnumValue(text(), text());
            case 4 ->
                    new JavaBinaryType.NestedAnnotation(
                            new JavaBinaryType.Annotation(
                                    text(), input.readBoolean(), attributes(depth + 1)));
            case 5 -> {
                List<JavaBinaryType.Value> values = new ArrayList<>();
                int count = count();
                for (int i = 0; i < count; i++) {
                    var value = value(depth + 1);
                    if (value == null) throw new IOException("null annotation element");
                    values.add(value);
                }
                yield new JavaBinaryType.ArrayValue(values);
            }
            case 6 -> new JavaBinaryType.UnavailableValue(text());
            default -> throw new IOException("invalid catalogue annotation tag");
        };
    }

    private void diagnostic(IndexDiagnostic value) throws IOException {
        text(value.severity());
        text(value.code());
        text(value.phase());
        text(value.sourceFile());
        text(value.module());
        text(value.scope());
        text(value.symbol());
        output.writeLong(value.count());
        text(value.sample());
        text(value.language());
        text(value.providerId());
        text(value.providerReason());
        text(value.reasonCode());
        text(value.sampleDetails());
    }

    private IndexDiagnostic diagnostic() throws IOException {
        return new IndexDiagnostic(
                text(),
                text(),
                text(),
                text(),
                text(),
                text(),
                text(),
                input.readLong(),
                text(),
                text(),
                text(),
                text(),
                text(),
                text());
    }

    private void data(Object value, int depth) throws IOException {
        visit(depth);
        switch (value) {
            case null -> output.writeByte(0);
            case String string -> {
                output.writeByte(1);
                text(string);
            }
            case Boolean flag -> {
                output.writeByte(2);
                output.writeBoolean(flag);
            }
            case Byte number -> {
                output.writeByte(3);
                output.writeByte(number);
            }
            case Short number -> {
                output.writeByte(4);
                output.writeShort(number);
            }
            case Character character -> {
                output.writeByte(5);
                output.writeChar(character);
            }
            case Integer number -> {
                output.writeByte(6);
                output.writeInt(number);
            }
            case Long number -> {
                output.writeByte(7);
                output.writeLong(number);
            }
            case Float number -> {
                output.writeByte(8);
                output.writeFloat(number);
            }
            case Double number -> {
                output.writeByte(9);
                output.writeDouble(number);
            }
            case Map<?, ?> map -> {
                output.writeByte(10);
                output.writeInt(map.size());
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String name))
                        throw new IOException("catalogue key is not text");
                    text(name);
                    data(entry.getValue(), depth + 1);
                }
            }
            case List<?> list -> {
                output.writeByte(11);
                output.writeInt(list.size());
                for (Object item : list) data(item, depth + 1);
            }
            case JavaNode node -> {
                output.writeByte(12);
                output.writeInt(node.id());
                text(node.kind());
                output.writeLong(node.start());
                output.writeLong(node.end());
                data(node.fields(), depth + 1);
            }
            default -> throw new IOException("unsupported catalogue data");
        }
    }

    private Object data(int depth) throws IOException {
        visit(depth);
        return switch (input.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> text();
            case 2 -> input.readBoolean();
            case 3 -> input.readByte();
            case 4 -> input.readShort();
            case 5 -> input.readChar();
            case 6 -> input.readInt();
            case 7 -> input.readLong();
            case 8 -> input.readFloat();
            case 9 -> input.readDouble();
            case 10 -> {
                Map<String, Object> values = new LinkedHashMap<>();
                int count = count();
                for (int i = 0; i < count; i++) {
                    String name = text();
                    if (name == null || values.containsKey(name))
                        throw new IOException("invalid catalogue map key");
                    values.put(name, data(depth + 1));
                }
                yield Collections.unmodifiableMap(values);
            }
            case 11 -> {
                List<Object> values = new ArrayList<>();
                int count = count();
                for (int i = 0; i < count; i++) values.add(data(depth + 1));
                yield Collections.unmodifiableList(values);
            }
            case 12 ->
                    new JavaNode(
                            input.readInt(),
                            text(),
                            input.readLong(),
                            input.readLong(),
                            map(data(depth + 1)));
            default -> throw new IOException("invalid catalogue data tag");
        };
    }

    private static Map<String, Object> map(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> source))
            throw new IOException("catalogue value is not a map");
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name))
                throw new IOException("catalogue map key is not text");
            result.put(name, entry.getValue());
        }
        return result;
    }

    private void refs(List<Integer> values) throws IOException {
        output.writeInt(values.size());
        for (int value : values) {
            visit(0);
            output.writeInt(value);
        }
    }

    private List<Integer> refs() throws IOException {
        int count = count();
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            visit(0);
            values.add(input.readInt());
        }
        return List.copyOf(values);
    }

    private void strings(List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) text(value);
    }

    private List<String> strings() throws IOException {
        int count = count();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) values.add(text());
        return List.copyOf(values);
    }

    private void text(String value) throws IOException {
        visit(0);
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > JavacProtocol.MAX_BYTES)
            throw new BudgetExceeded("catalogue text exceeds budget");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private String text() throws IOException {
        visit(0);
        int size = input.readInt();
        if (size == -1) return null;
        if (size < 0 || size > JavacProtocol.MAX_BYTES)
            throw new BudgetExceeded("catalogue text exceeds budget");
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException("truncated catalogue text");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private int count() throws IOException {
        int count = input.readInt();
        if (count < 0 || count > 100_000) throw new IOException("catalogue count exceeds budget");
        return count;
    }

    private void visit(int depth) throws IOException {
        if (depth > 128 || ++work > 1_000_000)
            throw new BudgetExceeded("catalogue work exceeds budget");
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private long remaining;

        LimitedOutput(OutputStream output, long maximum) {
            super(output);
            remaining = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            if (--remaining < 0) throw new BudgetExceeded("catalogue exceeds byte budget");
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > remaining) throw new BudgetExceeded("catalogue exceeds byte budget");
            remaining -= length;
            out.write(bytes, offset, length);
        }
    }
}
