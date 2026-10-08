package com.anatomist.core.javac;

import com.anatomist.core.java.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Data-only protocol. No object deserialization or compiler classes in the host process. */
final class JavacProtocol {
    static final int MAGIC = 0x414A4341;
    static final int MAX_BYTES = 32 * 1024 * 1024;
    static final int MAX_COUNT = 1_000_000;
    static final long MAX_RESPONSE_BYTES = 128L * 1024 * 1024;

    record Result(
            String version,
            boolean compileSucceeded,
            String failure,
            int units,
            List<JavaDiagnostic> diagnostics,
            long bytes,
            Map<String, Long> timingsNanos,
            JavacDecodeBudget.Usage decodeUsage) {
        Result(
                String version,
                boolean compileSucceeded,
                String failure,
                int units,
                List<JavaDiagnostic> diagnostics,
                long bytes,
                Map<String, Long> timingsNanos) {
            this(
                    version,
                    compileSucceeded,
                    failure,
                    units,
                    diagnostics,
                    bytes,
                    timingsNanos,
                    JavacDecodeBudget.Usage.EMPTY);
        }
    }

    static void request(
            OutputStream output,
            JavacToolchain toolchain,
            List<Path> files,
            List<Path> classpath,
            boolean compile,
            Path processor,
            Path destination,
            Path spool,
            JavacDeclarationCache.Lease declarations)
            throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeInt(toolchain.release());
        out.writeBoolean(compile);
        string(
                out,
                String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        string(out, processor == null ? "" : processor.toString());
        string(out, destination == null ? "" : destination.toString());
        string(out, spool.toString());
        out.writeInt(files.size());
        for (Path file : files) {
            string(out, file.toString());
            out.writeBoolean(declarations.declarations(file));
            string(out, compile ? "" : declarations.token(file));
        }
        out.flush();
    }

    static Result response(
            JavacBatchProtocol.Result result,
            java.util.function.Consumer<JavaUnit> consumer,
            JavacDeclarationCache.Lease cache)
            throws IOException {
        List<JavaUnit> units = new ArrayList<>();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            Result decoded;
            try (InputStream input =
                    new BufferedInputStream(
                            new java.security.DigestInputStream(
                                    java.nio.file.Files.newInputStream(result.request().result()),
                                    digest),
                            65536)) {
                decoded = response(input, units::add, cache);
            }
            if (decoded.bytes() != result.bytes()
                    || !HexFormat.of().formatHex(digest.digest()).equals(result.sha256()))
                throw new IOException("JAVAC_PROTOCOL_INVALID: result checksum or length mismatch");
            units.forEach(consumer);
            return decoded;
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    static Result response(InputStream input, java.util.function.Consumer<JavaUnit> consumer)
            throws IOException {
        return response(input, consumer, null);
    }

    static Result response(
            InputStream input,
            java.util.function.Consumer<JavaUnit> consumer,
            JavacDeclarationCache.Lease cache)
            throws IOException {
        return response(input, consumer, cache, 0);
    }

    static Result response(
            InputStream input,
            java.util.function.Consumer<JavaUnit> consumer,
            JavacDeclarationCache.Lease cache,
            long launchedNanos)
            throws IOException {
        return response(input, consumer, cache, launchedNanos, true);
    }

    static Result response(
            InputStream input,
            java.util.function.Consumer<JavaUnit> consumer,
            JavacDeclarationCache.Lease cache,
            long launchedNanos,
            boolean requireEof)
            throws IOException {
        long receiveStarted = System.nanoTime(), consumerNanos = 0;
        ResponseInput measured =
                new ResponseInput(
                        input instanceof BufferedInputStream
                                ? input
                                : new BufferedInputStream(input, 65536));
        var budget = new JavacDecodeBudget(JavacDecodeBudget.DEFAULT_BYTES);
        DataInputStream in = new JavacDecodeBudget.Input(measured, budget);
        if (in.readInt() != MAGIC)
            throw new IOException("JAVAC_PROTOCOL_INVALID: worker protocol mismatch");
        String version = string(in);
        long readyNanos = launchedNanos == 0 ? -1 : System.nanoTime() - launchedNanos;
        int count = count(in, MAX_COUNT);
        int unitCount = count;
        Set<Path> paths = new HashSet<>();
        for (int i = 0; i < count; i++) {
            budget.section(JavacDecodeBudget.Section.METADATA, "");
            Path path = Path.of(string(in)).toAbsolutePath().normalize();
            if (!paths.add(path)) throw new IOException("duplicate compilation unit path");
            String sourceHash = string(in);
            if (!sourceHash.matches("[a-f0-9]{64}"))
                throw new IOException("invalid source snapshot hash");
            boolean declarations = in.readBoolean();
            if (declarations && (cache == null || !cache.declarations(path)))
                throw new IOException("JAVAC_PROTOCOL_INVALID: unexpected declaration view");
            budget.section(JavacDecodeBudget.Section.SOURCE_TREE, path.toString());
            JavaNode tree = readFrame(in, path, declarations ? cache : null);
            if (tree == null) throw new IOException("missing source tree");
            JavaProcessingState processing =
                    switch (in.readUnsignedByte()) {
                        case 0 -> JavaProcessingState.NONE;
                        case 1 -> JavaProcessingState.LOMBOK;
                        default -> throw new IOException("invalid processor execution state");
                    };
            budget.section(JavacDecodeBudget.Section.SEMANTICS, path.toString());
            Map<Integer, JavaBinding> bindings = new LinkedHashMap<>();
            List<String> dictionary = strings(in);
            JavaTypes types = types(in, dictionary);
            int bindingCount = count(in, MAX_COUNT);
            for (int j = 0; j < bindingCount; j++) {
                int id = in.readInt();
                if (id < 0) throw new IOException("negative syntax identity");
                String kind = reference(in, dictionary),
                        owner = reference(in, dictionary),
                        name = reference(in, dictionary);
                List<String> params = new ArrayList<>();
                int paramsCount = count(in, MAX_COUNT);
                for (int p = 0; p < paramsCount; p++) params.add(reference(in, dictionary));
                JavaBinding binding =
                        new JavaBinding(
                                id,
                                kind,
                                owner,
                                name,
                                List.copyOf(params),
                                reference(in, dictionary),
                                reference(in, dictionary),
                                in.readBoolean(),
                                references(in, dictionary),
                                references(in, dictionary),
                                reference(in, dictionary),
                                in.readInt(),
                                in.readInt(),
                                in.readInt(),
                                in.readBoolean(),
                                reference(in, dictionary),
                                reference(in, dictionary),
                                in.readBoolean());
                if (bindings.putIfAbsent(id, binding) != null)
                    throw new IOException("duplicate binding id");
            }
            budget.section(JavacDecodeBudget.Section.DECLARATIONS, path.toString());
            JavaUnit unit;
            try {
                unit =
                        new JavaUnit(
                                path,
                                tree,
                                processing,
                                bindings,
                                types,
                                declarations,
                                JavacDeclarations.readFrame(in),
                                sourceHash);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("invalid unit semantics", invalid);
            }
            long consumerStarted = System.nanoTime();
            consumer.accept(unit);
            consumerNanos += System.nanoTime() - consumerStarted;
        }
        budget.section(JavacDecodeBudget.Section.DIAGNOSTICS, "");
        boolean compileSucceeded = in.readBoolean();
        String failure = string(in);
        List<JavaDiagnostic> diagnostics = new ArrayList<>();
        count = count(in, MAX_COUNT);
        for (int i = 0; i < count; i++) {
            String path = string(in);
            diagnostics.add(
                    new JavaDiagnostic(
                            path.isEmpty() ? null : Path.of(path),
                            string(in),
                            string(in),
                            in.readLong(),
                            in.readLong(),
                            string(in)));
        }
        Map<String, Long> timings = new LinkedHashMap<>();
        int phases = count(in, 32);
        for (int i = 0; i < phases; i++) {
            String phase = string(in);
            long elapsed = in.readLong();
            if (elapsed < 0 || timings.putIfAbsent(phase, elapsed) != null)
                throw new IOException("invalid worker timing");
        }
        if (in.readInt() != 0x414A4544) throw new IOException("missing worker terminal result");
        if (requireEof && in.read() != -1)
            throw new IOException("unexpected worker output after result");
        if (readyNanos >= 0) timings.put("javac_worker_ready", readyNanos);
        timings.put(
                "javac_receive_wait_decode", System.nanoTime() - receiveStarted - consumerNanos);
        timings.put("javac_model_consume", consumerNanos);
        timings.put("javac_model_decode", timings.get("javac_receive_wait_decode"));
        return new Result(
                version,
                compileSucceeded,
                failure,
                unitCount,
                List.copyOf(diagnostics),
                MAX_RESPONSE_BYTES - measured.remaining,
                Collections.unmodifiableMap(timings),
                budget.usage());
    }

    private static JavaNode readFrame(
            DataInputStream in, Path path, JavacDeclarationCache.Lease cache) throws IOException {
        int length = in.readInt();
        if (length == -1) {
            if (cache == null) throw new IOException("unexpected declaration cache reference");
            return readTree(cache.reference(path, string(in)), in);
        }
        if (length < 0 || length > MAX_BYTES)
            throw new IOException("protocol length exceeds budget");
        if (length == 0) return null;
        JavacDecodeBudget.take(in, length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        JavaNode tree = readTree(bytes, in);
        if (cache != null) cache.remember(path, bytes);
        return tree;
    }

    private static JavaNode readTree(byte[] bytes, DataInputStream parent) throws IOException {
        if (bytes.length < 8) throw new IOException("invalid syntax frame");
        int bodyLength;
        try (var header = new DataInputStream(new ByteArrayInputStream(bytes))) {
            bodyLength = header.readInt();
        }
        if (bodyLength < 0 || bodyLength > bytes.length - 8)
            throw new IOException("invalid syntax dictionary offset");
        List<String> dictionary;
        try (var input =
                JavacDecodeBudget.frame(
                        new ByteArrayInputStream(
                                bytes, 4 + bodyLength, bytes.length - 4 - bodyLength),
                        parent)) {
            dictionary = strings(input);
            if (input.read() != -1) throw new IOException("trailing syntax dictionary bytes");
        }
        try (var input =
                JavacDecodeBudget.frame(new ByteArrayInputStream(bytes, 4, bodyLength), parent)) {
            Object value = value(input, dictionary, 0);
            if (!(value instanceof JavaNode tree) || input.read() != -1)
                throw new IOException("invalid unit tree");
            return tree;
        }
    }

    private static final class ResponseInput extends FilterInputStream {
        private long remaining = MAX_RESPONSE_BYTES;

        ResponseInput(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && --remaining < 0)
                throw new IOException("worker response exceeds total byte budget");
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0 && (remaining -= count) < 0)
                throw new IOException("worker response exceeds total byte budget");
            return count;
        }
    }

    private static Object value(DataInputStream in, List<String> dictionary, int depth)
            throws IOException {
        JavacDecodeBudget.take(in, 64);
        if (depth > 512) throw new IOException("protocol nesting exceeds budget");
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> {
                int id = in.readInt();
                if (id < 0) throw new IOException("negative syntax identity");
                String kind = reference(in, dictionary);
                long start = in.readLong(), end = in.readLong();
                int count = count(in, MAX_COUNT);
                Map<String, Object> fields = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = reference(in, dictionary);
                    if (fields.containsKey(key)) throw new IOException("duplicate tree field");
                    fields.put(key, value(in, dictionary, depth + 1));
                }
                yield new JavaNode(id, kind, start, end, Collections.unmodifiableMap(fields));
            }
            case 2 -> {
                int count = count(in, MAX_COUNT);
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) values.add(value(in, dictionary, depth + 1));
                yield Collections.unmodifiableList(values);
            }
            case 3 -> reference(in, dictionary);
            default -> throw new IOException("unknown protocol value");
        };
    }

    private static JavaTypes types(DataInputStream in, List<String> dictionary) throws IOException {
        int count = count(in, 100000);
        List<JavaType> values = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                values.add(
                        new JavaType(
                                JavaType.Kind.valueOf(reference(in, dictionary)),
                                reference(in, dictionary),
                                reference(in, dictionary),
                                in.readInt(),
                                typeReferences(in),
                                typeReferences(in),
                                in.readInt(),
                                in.readInt(),
                                typeReferences(in),
                                in.readInt(),
                                typeReferences(in),
                                JavacDeclarations.annotations(in),
                                reference(in, dictionary)));
            }
            return new JavaTypes(values);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid compiler type graph", invalid);
        }
    }

    private static List<Integer> typeReferences(DataInputStream in) throws IOException {
        int count = count(in, 100000);
        List<Integer> references = new ArrayList<>(count);
        for (int i = 0; i < count; i++) references.add(in.readInt());
        return references;
    }

    private static String reference(DataInputStream in, List<String> dictionary)
            throws IOException {
        int index = in.readInt();
        if (index < 0 || index >= dictionary.size())
            throw new IOException("JAVAC_PROTOCOL_INVALID: unknown binding string reference");
        return dictionary.get(index);
    }

    private static List<String> references(DataInputStream in, List<String> dictionary)
            throws IOException {
        int count = count(in, MAX_COUNT);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(reference(in, dictionary));
        return List.copyOf(values);
    }

    private static List<String> strings(DataInputStream in) throws IOException {
        int count = count(in, MAX_COUNT);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(string(in));
        return List.copyOf(values);
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int value = length(in, maximum);
        JavacDecodeBudget.take(in, 64L * value);
        return value;
    }

    private static int length(DataInputStream in, int maximum) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > maximum) throw new IOException("protocol length exceeds budget");
        return value;
    }

    private static String string(DataInputStream in) throws IOException {
        int length = length(in, MAX_BYTES);
        JavacDecodeBudget.take(in, 64L + 4L * length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("protocol string exceeds budget");
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
