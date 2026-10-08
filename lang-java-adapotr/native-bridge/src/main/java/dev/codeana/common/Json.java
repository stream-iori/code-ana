package dev.codeana.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Small bounded, reflection-free transport codec, compiled for both Java 8 and native Java 25. */
public final class Json {
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    /** Container depth includes both a type node and its argument list. */
    public static final int MAX_DEPTH = 256;

    private Json() {}

    public static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("OBJECT_REQUIRED");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("ARRAY_REQUIRED");
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> value, String field) {
        Object v = value.get(field);
        if (!(v instanceof String)) throw new IllegalArgumentException("STRING_REQUIRED: " + field);
        return (String) v;
    }

    public static long number(Map<String, Object> value, String field) {
        Object v = value.get(field);
        if (!(v instanceof Number)) throw new IllegalArgumentException("NUMBER_REQUIRED: " + field);
        try {
            return new java.math.BigDecimal(v.toString()).longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("INTEGER_REQUIRED: " + field, invalid);
        }
    }

    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(
                    () -> {
                        try {
                            return MessageDigest.getInstance("SHA-256");
                        } catch (java.security.NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                    });

    public static String sha(byte[] bytes) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        return hex(digest.digest(bytes));
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 15];
        }
        return new String(result);
    }

    public static String key(String namespace, String... parts) {
        return namespace + "_" + sha(write(Arrays.asList(parts)).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IOException("INPUT_BYTE_BUDGET");
        try (java.io.InputStream in = Files.newInputStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() > MAX_BYTES - n) throw new IOException("INPUT_BYTE_BUDGET");
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static Object parse(byte[] bytes) {
        return parse(bytes, null);
    }

    /** Validate the entire JSON record while retaining only fact navigation metadata. */
    public static Object factProjection(byte[] bytes) {
        return parse(
                bytes,
                object(
                        "id",
                        true,
                        "signature",
                        true,
                        "kind",
                        true,
                        "source",
                        true,
                        "parent",
                        true,
                        "target",
                        true,
                        "owner",
                        object("world", true),
                        "attributes",
                        object("generated", true, "receiver_type", true)));
    }

    private static Object parse(byte[] bytes, Map<String, Object> fields) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("JSON_BYTE_BUDGET");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            Parser p = new Parser(text);
            Object result = p.value(0, true, fields);
            p.space();
            if (p.at != text.length()) throw new IllegalArgumentException("JSON_TRAILING_DATA");
            return result;
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("INVALID_UTF8", e);
        }
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        try {
            append(out, value, 0);
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (out.length() > MAX_BYTES) throw new IllegalArgumentException("JSON_BUDGET");
        return out.toString();
    }

    /** Serialize directly to an owned file, charging encoded bytes before writing them. */
    public static long writeTo(Path path, Object value, final long maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("JSON_BYTE_BUDGET");
        final long[] written = {0};
        try (final java.io.OutputStream file =
                        Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW);
                java.io.OutputStream bounded =
                        new java.io.FilterOutputStream(file) {
                            private void charge(int bytes) throws IOException {
                                if (Thread.currentThread().isInterrupted())
                                    throw new IOException("CANCELLED");
                                if (bytes > maxBytes - written[0])
                                    throw new IOException("JSON_BYTE_BUDGET");
                                written[0] += bytes;
                            }

                            @Override
                            public void write(int value) throws IOException {
                                charge(1);
                                out.write(value);
                            }

                            @Override
                            public void write(byte[] bytes, int offset, int length)
                                    throws IOException {
                                charge(length);
                                out.write(bytes, offset, length);
                            }
                        };
                java.io.Writer writer =
                        new java.io.BufferedWriter(
                                new java.io.OutputStreamWriter(bounded, StandardCharsets.UTF_8),
                                65536)) {
            append(writer, value, 0);
        }
        return written[0];
    }

    public static String sha(Path path) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = in.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Validate every byte/node, but do not materialize an already encoded archive row. */
    static void appendRecord(Appendable out, byte[] bytes, int depth) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("JSON_BYTE_BUDGET");
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            Parser parser = new Parser(text);
            parser.value(depth, false, null);
            parser.space();
            if (parser.at != text.length())
                throw new IllegalArgumentException("JSON_TRAILING_DATA");
            if (out instanceof StringBuilder
                    && text.length() > MAX_BYTES - ((StringBuilder) out).length())
                throw new IllegalArgumentException("JSON_BUDGET");
            if (parser.escapedSurrogate) append(out, parse(bytes), depth);
            else out.append(text);
        } catch (java.nio.charset.CharacterCodingException malformed) {
            throw new IllegalArgumentException("INVALID_UTF8", malformed);
        }
    }

    private static void append(Appendable out, Object v, int depth) throws IOException {
        if (depth > MAX_DEPTH
                || out instanceof StringBuilder && ((StringBuilder) out).length() > MAX_BYTES)
            throw new IllegalArgumentException("JSON_BUDGET");
        if (v instanceof Double && !Double.isFinite((Double) v)
                || v instanceof Float && !Float.isFinite((Float) v))
            throw new IllegalArgumentException("JSON_NON_FINITE");
        if (v == null || v instanceof Boolean || v instanceof Number) out.append(String.valueOf(v));
        else if (v instanceof String) {
            out.append('"');
            String s = (String) v;
            int chunk = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 32 && c != '"' && c != '\\') continue;
                out.append(s, chunk, i);
                chunk = i + 1;
                switch (c) {
                    case '"':
                        out.append("\\\"");
                        break;
                    case '\\':
                        out.append("\\\\");
                        break;
                    case '\n':
                        out.append("\\n");
                        break;
                    case '\r':
                        out.append("\\r");
                        break;
                    case '\t':
                        out.append("\\t");
                        break;
                    case '\b':
                        out.append("\\b");
                        break;
                    case '\f':
                        out.append("\\f");
                        break;
                    default:
                        if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            out.append(s, chunk, s.length());
            out.append('"');
        } else if (v instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) out.append(',');
                first = false;
                append(out, e.getKey(), depth + 1);
                out.append(':');
                append(out, e.getValue(), depth + 1);
            }
            out.append('}');
        } else if (v instanceof FactArchive) {
            FactArchive records = (FactArchive) v;
            out.append('[');
            for (int index = 0; index < records.size(); index++) {
                if (index != 0) out.append(',');
                appendRecord(out, records.encoded(index), depth + 1);
            }
            out.append(']');
        } else if (v instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) v) {
                if (!first) out.append(',');
                first = false;
                append(out, e, depth + 1);
            }
            out.append(']');
        } else throw new IllegalArgumentException("JSON_TYPE: " + v.getClass());
    }

    private static final class Parser {
        final String text;
        int at;
        int nodes;
        boolean escapedSurrogate;

        Parser(String text) {
            this.text = text;
        }

        void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        }

        char take() {
            if (at >= text.length()) throw new IllegalArgumentException("JSON_EOF");
            return text.charAt(at++);
        }

        Object value(int depth) {
            return value(depth, true, null);
        }

        Object value(int depth, boolean keep, Map<String, Object> fields) {
            if (depth > MAX_DEPTH || ++nodes > 1000000)
                throw new IllegalArgumentException("JSON_ALLOCATION_BUDGET");
            space();
            char c = take();
            if (c == '"') return string(keep);
            if (c == '{') {
                Map<String, Object> out = keep ? new LinkedHashMap<String, Object>() : null;
                Set<String> seen = !keep || fields != null ? new HashSet<String>() : null;
                space();
                if (at < text.length() && text.charAt(at) == '}') {
                    at++;
                    return out;
                }
                do {
                    space();
                    if (take() != '"') throw new IllegalArgumentException("JSON_KEY");
                    String k = string();
                    space();
                    if (take() != ':') throw new IllegalArgumentException("JSON_COLON");
                    if (seen != null ? !seen.add(k) : out.containsKey(k))
                        throw new IllegalArgumentException("JSON_DUPLICATE_KEY");
                    Object selection = fields == null ? null : fields.get(k);
                    boolean childKeep = fields == null ? keep : selection != null;
                    Map<String, Object> childFields =
                            selection instanceof Map ? map(selection) : null;
                    Object child = value(depth + 1, childKeep, childFields);
                    if (childKeep) out.put(k, child);
                    space();
                    c = take();
                } while (c == ',');
                if (c != '}') throw new IllegalArgumentException("JSON_OBJECT_END");
                return out;
            }
            if (c == '[') {
                List<Object> out = keep ? new ArrayList<Object>() : null;
                space();
                if (at < text.length() && text.charAt(at) == ']') {
                    at++;
                    return out;
                }
                do {
                    Object child = value(depth + 1, keep, null);
                    if (keep) out.add(child);
                    space();
                    c = take();
                } while (c == ',');
                if (c != ']') throw new IllegalArgumentException("JSON_ARRAY_END");
                return out;
            }
            at--;
            for (String token : Arrays.asList("true", "false", "null"))
                if (text.startsWith(token, at)) {
                    at += token.length();
                    return token.equals("null") ? null : Boolean.valueOf(token);
                }
            int start = at;
            if (at < text.length() && text.charAt(at) == '-') at++;
            if (at >= text.length() || !digit(text.charAt(at)))
                throw new IllegalArgumentException("JSON_NUMBER");
            if (text.charAt(at) == '0') at++;
            else while (at < text.length() && digit(text.charAt(at))) at++;
            boolean decimal = false;
            if (at < text.length() && text.charAt(at) == '.') {
                decimal = true;
                at++;
                int fraction = at;
                while (at < text.length() && digit(text.charAt(at))) at++;
                if (at == fraction) throw new IllegalArgumentException("JSON_NUMBER");
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                decimal = true;
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                int exponent = at;
                while (at < text.length() && digit(text.charAt(at))) at++;
                if (at == exponent) throw new IllegalArgumentException("JSON_NUMBER");
            }
            String number = text.substring(start, at);
            if (number.length() > 1024) throw new IllegalArgumentException("JSON_NUMBER_BUDGET");
            try {
                if (decimal) return new java.math.BigDecimal(number);
                try {
                    return Long.valueOf(number);
                } catch (NumberFormatException wider) {
                    return new java.math.BigInteger(number);
                }
            } catch (NumberFormatException malformed) {
                throw new IllegalArgumentException("JSON_NUMBER", malformed);
            }
        }

        boolean digit(char value) {
            return value >= '0' && value <= '9';
        }

        String string() {
            return string(true);
        }

        String string(boolean keep) {
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return keep ? text.substring(start, at - 1) : null;
                if (c < 32) throw new IllegalArgumentException("JSON_CONTROL");
                if (c == '\\') {
                    at--;
                    break;
                }
            }
            StringBuilder out = keep ? new StringBuilder() : null;
            if (keep) out.append(text, start, at);
            int chunk = at;
            for (; ; ) {
                char c = take();
                if (c == '"') {
                    if (keep) out.append(text, chunk, at - 1);
                    return keep ? out.toString() : null;
                }
                if (c < 32) throw new IllegalArgumentException("JSON_CONTROL");
                if (c == '\\') {
                    if (keep) out.append(text, chunk, at - 1);
                    c = take();
                    switch (c) {
                        case '"':
                        case '\\':
                        case '/':
                            break;
                        case 'b':
                            c = '\b';
                            break;
                        case 'f':
                            c = '\f';
                            break;
                        case 'n':
                            c = '\n';
                            break;
                        case 'r':
                            c = '\r';
                            break;
                        case 't':
                            c = '\t';
                            break;
                        case 'u':
                            if (at + 4 > text.length())
                                throw new IllegalArgumentException("JSON_ESCAPE");
                            c = (char) Integer.parseInt(text.substring(at, at + 4), 16);
                            if (Character.isSurrogate(c)) escapedSurrogate = true;
                            at += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("JSON_ESCAPE");
                    }
                    if (keep) out.append(c);
                    chunk = at;
                }
            }
        }
    }
}
