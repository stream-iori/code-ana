package com.anatomist.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hand-written runtime JSON I/O, independent of Jackson's TOML configuration reader. Keeps semantic
 * codecs reflection-free under GraalVM native image.
 *
 * <p>Output format is a byte-for-byte match of Jackson's {@code DefaultPrettyPrinter} (space before
 * colon, inline empty containers, arrays of scalars on one line, 2-space indent). This is the
 * format every golden file under {@code tests/scenarios/} was captured with — do not change without
 * regenerating the goldens.
 *
 * <p>Supported value types when serialising:
 *
 * <ul>
 *   <li>{@code null}
 *   <li>{@link Boolean}, {@link Number}, {@link CharSequence}
 *   <li>{@link Map} → JSON object (key insertion order preserved)
 *   <li>{@link Iterable} → JSON array
 *   <li>Anything else with a registered {@link JsonCodec}; otherwise IAE
 * </ul>
 */
public final class Json {

    private Json() {}

    // ───────────────────────── write ─────────────────────────────────

    public static String writeCompact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValueCompact(sb, value);
        return sb.toString();
    }

    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValuePretty(sb, value, 0);
        return sb.toString();
    }

    /** Pretty + recursively sorted map keys — for golden-file canonicalisation. */
    public static String writeCanonical(Object value) {
        return writePretty(sortRecursively(value));
    }

    private static Object sortRecursively(Object v) {
        if (v instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sortRecursively(e.getValue()));
            }
            return sorted;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> list = new ArrayList<>();
            for (Object o : it) list.add(sortRecursively(o));
            return list;
        }
        return v;
    }

    // ── compact ──

    private static void writeValueCompact(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (v instanceof Number n) {
            writeNumber(sb, n);
            return;
        }
        if (v instanceof CharSequence s) {
            writeString(sb, s.toString());
            return;
        }
        if (v instanceof Map<?, ?> m) {
            writeMapCompact(sb, m);
            return;
        }
        if (v instanceof Iterable<?> it) {
            writeArrayCompact(sb, it);
            return;
        }
        JsonCodec<Object> codec = JsonCodecRegistry.lookup(v.getClass());
        if (codec != null) {
            Object tree = codec.toTree(v);
            writeValueCompact(sb, tree);
            return;
        }
        throw new IllegalArgumentException("no codec for type " + v.getClass());
    }

    private static void writeMapCompact(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValueCompact(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArrayCompact(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object o : it) {
            if (!first) sb.append(',');
            first = false;
            writeValueCompact(sb, o);
        }
        sb.append(']');
    }

    // ── pretty ──

    private static void writeValuePretty(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
            return;
        }
        if (v instanceof Number n) {
            writeNumber(sb, n);
            return;
        }
        if (v instanceof CharSequence s) {
            writeString(sb, s.toString());
            return;
        }
        if (v instanceof Map<?, ?> m) {
            writeMapPretty(sb, m, depth);
            return;
        }
        if (v instanceof Iterable<?> it) {
            writeArrayPretty(sb, it, depth);
            return;
        }
        JsonCodec<Object> codec = JsonCodecRegistry.lookup(v.getClass());
        if (codec != null) {
            Object tree = codec.toTree(v);
            writeValuePretty(sb, tree, depth);
            return;
        }
        throw new IllegalArgumentException("no codec for type " + v.getClass());
    }

    private static void writeMapPretty(StringBuilder sb, Map<?, ?> m, int depth) {
        if (m.isEmpty()) {
            sb.append("{ }");
            return;
        }
        sb.append('{');
        boolean first = true;
        int childDepth = depth + 1;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('\n');
            indent(sb, childDepth);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(" : ");
            writeValuePretty(sb, e.getValue(), childDepth);
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append('}');
    }

    private static void writeArrayPretty(StringBuilder sb, Iterable<?> it, int depth) {
        // Materialize so we can peek size + element kind.
        List<Object> list;
        if (it instanceof Collection<?> c) {
            list = new ArrayList<>(c);
        } else {
            list = new ArrayList<>();
            for (Object o : it) list.add(o);
        }
        if (list.isEmpty()) {
            sb.append("[ ]");
            return;
        }

        boolean inline = isAllScalar(list);
        if (inline) {
            sb.append("[ ");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                writeValuePretty(sb, list.get(i), depth);
            }
            sb.append(" ]");
            return;
        }

        // Jackson default for object/array elements: '[ {\n...\n}, {\n...\n} ]'
        // Element bodies indent from the CURRENT depth (not depth+1), which keeps
        // the "[ {" on the same line.
        sb.append("[ ");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            writeValuePretty(sb, list.get(i), depth);
        }
        sb.append(" ]");
    }

    private static boolean isAllScalar(List<Object> list) {
        for (Object o : list) {
            if (o == null
                    || o instanceof Boolean
                    || o instanceof Number
                    || o instanceof CharSequence) continue;
            return false;
        }
        return true;
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    // ── primitives ──

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                // Match Jackson default: 1.0 stays "1.0"
                sb.append(d);
            } else {
                sb.append(d);
            }
            return;
        }
        sb.append(n.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ───────────────────────── parse ─────────────────────────────────

    /**
     * Parse JSON into a generic tree. Object → LinkedHashMap, Array → ArrayList, integer → Long,
     * non-integer → Double, string → String, true/false → Boolean, null → null.
     */
    public static Object parseTree(String json) {
        return parseTree(json, 512);
    }

    /** Parse with an explicit container nesting limit for untrusted input. */
    public static Object parseTree(String json, int maxDepth) {
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be >= 1");
        Parser p = new Parser(json, maxDepth);
        Object v = p.readValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing content at offset " + p.pos);
        return v;
    }

    private static final class Parser {
        final String src;
        final int maxDepth;
        int pos;

        Parser(String s, int maxDepth) {
            this.src = s;
            this.maxDepth = maxDepth;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        Object readValue(int depth) {
            skipWhitespace();
            if (atEnd()) throw err("unexpected EOF");
            char c = src.charAt(pos);
            if ((c == '{' || c == '[') && depth >= maxDepth) {
                throw err("JSON nesting exceeds " + maxDepth);
            }
            if (c == '{') return readObject(depth + 1);
            if (c == '[') return readArray(depth + 1);
            if (c == '"') return readString();
            if (c == 't' || c == 'f') return readBool();
            if (c == 'n') return readNull();
            return readNumber();
        }

        Map<String, Object> readObject(int depth) {
            expect('{');
            skipWhitespace();
            Map<String, Object> m = new LinkedHashMap<>();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object val = readValue(depth);
                m.put(key, val);
                skipWhitespace();
                char d = next();
                if (d == ',') continue;
                if (d == '}') return m;
                throw err("expected , or } got " + d);
            }
        }

        List<Object> readArray(int depth) {
            expect('[');
            skipWhitespace();
            List<Object> list = new ArrayList<>();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object v = readValue(depth);
                list.add(v);
                skipWhitespace();
                char d = next();
                if (d == ',') continue;
                if (d == ']') return list;
                throw err("expected , or ] got " + d);
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw err("EOF in escape");
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > src.length()) throw err("short u escape");
                            int cp = Integer.parseInt(src.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default:
                            throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        Boolean readBool() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw err("expected true/false");
        }

        Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw err("expected null");
        }

        Number readNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && isNumChar(src.charAt(pos))) pos++;
            String s = src.substring(start, pos);
            if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.parseDouble(s);
            return Long.parseLong(s);
        }

        boolean isNumChar(char c) {
            return (c >= '0' && c <= '9')
                    || c == '.'
                    || c == 'e'
                    || c == 'E'
                    || c == '+'
                    || c == '-';
        }

        char peek() {
            return atEnd() ? '\0' : src.charAt(pos);
        }

        char next() {
            return src.charAt(pos++);
        }

        void expect(char c) {
            skipWhitespace();
            if (atEnd() || src.charAt(pos) != c) throw err("expected '" + c + "'");
            pos++;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " at offset " + pos);
        }
    }
}
