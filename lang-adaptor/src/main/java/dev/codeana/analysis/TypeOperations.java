package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;

import java.io.IOException;
import java.util.*;

/** Detached Java 8 type operations. Unknown evidence never satisfies an applicability proof. */
final class TypeOperations {
    enum Match {
        YES,
        NO,
        UNKNOWN
    }

    static final class Budget extends IllegalArgumentException {
        Budget() {
            super("TYPE_OPERATION_BUDGET");
        }
    }

    private static final Map<String, String> BOXES =
            Map.of(
                    "BOOLEAN",
                    "java.lang.Boolean",
                    "BYTE",
                    "java.lang.Byte",
                    "SHORT",
                    "java.lang.Short",
                    "CHAR",
                    "java.lang.Character",
                    "INT",
                    "java.lang.Integer",
                    "LONG",
                    "java.lang.Long",
                    "FLOAT",
                    "java.lang.Float",
                    "DOUBLE",
                    "java.lang.Double");
    private final Map<String, Map<String, Object>> source;
    private final BinaryIndex binary;
    private int work;
    private final Map<String, Map<String, Object>> captures = new HashMap<>();

    private void rememberCapture(Map<String, Object> value) {
        String identity = text(value.get("capture"));
        if (!captures.containsKey(identity) && captures.size() >= 4096) throw new Budget();
        captures.put(identity, value);
    }

    private static Map<String, Object> captureIdentity(Map<String, Object> value) {
        return Json.object(
                "kind",
                "CAPTURE",
                "capture",
                value.get("capture"),
                "recursive",
                true,
                "complete",
                false);
    }

    TypeOperations(Map<String, Map<String, Object>> source, BinaryIndex binary) {
        this.source = source;
        this.binary = binary;
    }

    void reset() {
        work = 0;
    }

    private void step(int depth) {
        if (++work > 100_000 || depth >= 64 || Thread.currentThread().isInterrupted())
            throw new Budget();
    }

    static Map<String, Object> map(Object value) {
        return value instanceof Map ? Json.map(value) : Map.of();
    }

    static List<Object> list(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }

    static String text(Object value) {
        return value instanceof String ? (String) value : "";
    }

    static String kind(Map<String, Object> type) {
        return text(type.get("kind"));
    }

    static Map<String, Object> named(String name) {
        return Json.object(
                "version",
                2,
                "kind",
                "DECLARED",
                "element",
                name,
                "arguments",
                List.of(),
                "display",
                name,
                "complete",
                true);
    }

    static String variable(Map<String, Object> type) {
        String owner = text(type.get("owner")), name = text(type.get("name"));
        return owner.isEmpty() || name.isEmpty() ? "" : owner + '\0' + name;
    }

    Map<String, Object> attributes(String name) throws IOException {
        Map<String, Object> declaration = source.get(name);
        if (declaration != null) return map(declaration.get("attributes"));
        Map<String, Object> found = binary == null ? null : binary.readClass(name);
        if (found == null) return Map.of();
        Map<String, Object> raw = map(found.get("declaration")), types = map(raw.get("type_model"));
        return Json.object(
                "type_parameter_models",
                types.get("type_parameters"),
                "superclass_type_model",
                types.get("superclass"),
                "interface_type_models",
                types.get("interfaces"),
                "binary",
                raw);
    }

    Map<String, Map<String, Object>> bindings(Map<String, Object> receiver, String capture)
            throws IOException {
        Map<String, Map<String, Object>> result = new HashMap<>();
        collectBindings(receiver, capture, result, 0);
        return result;
    }

    private void collectBindings(
            Map<String, Object> receiver,
            String capture,
            Map<String, Map<String, Object>> result,
            int depth)
            throws IOException {
        step(depth);
        if (receiver.get("enclosing") instanceof Map)
            collectBindings(
                    map(receiver.get("enclosing")), capture + "/enclosing", result, depth + 1);
        List<Object> formals =
                list(attributes(text(receiver.get("element"))).get("type_parameter_models"));
        List<Object> actual = list(receiver.get("arguments"));
        if (formals.size() != actual.size())
            return; // Raw/missing evidence is not a concrete substitution.
        List<String> created = new ArrayList<>();
        for (int i = 0; i < formals.size(); i++) {
            Map<String, Object> formal = map(formals.get(i)), value = map(actual.get(i));
            if (kind(value).equals("WILDCARD")) {
                Object upper = value.get("extends");
                List<Object> bounds = list(formal.get("bounds"));
                if (upper == null)
                    upper =
                            bounds.isEmpty()
                                    ? named("java.lang.Object")
                                    : bounds.size() == 1
                                            ? bounds.getFirst()
                                            : Json.object(
                                                    "kind",
                                                    "INTERSECTION",
                                                    "bounds",
                                                    bounds,
                                                    "complete",
                                                    true);
                value =
                        Json.object(
                                "kind",
                                "CAPTURE",
                                "capture",
                                capture + "/" + text(formal.get("owner")) + "/" + i,
                                "upper",
                                upper,
                                "lower",
                                value.get("super"),
                                "complete",
                                true);
            }
            String key = variable(formal);
            if (!key.isEmpty()) {
                result.put(key, value);
                if (kind(map(actual.get(i))).equals("WILDCARD")) {
                    rememberCapture(value);
                    created.add(key);
                }
            }
        }
        // Resolve dependent formal bounds only after every capture identity exists.
        for (String key : created) {
            Map<String, Object> value = result.get(key), normalized = new TreeMap<>(value);
            Set<String> visiting = new HashSet<>();
            visiting.add(key);
            normalized.put(
                    "upper", substitute(map(value.get("upper")), result, visiting, depth + 1));
            rememberCapture(normalized);
            result.put(key, normalized);
        }
    }

    Map<String, Object> captureArgument(Map<String, Object> type, String scope) throws IOException {
        if (!kind(type).equals("DECLARED")) return type;
        List<Object> formals = list(attributes(text(type.get("element"))).get("type_parameter_models"));
        List<Object> arguments = list(type.get("arguments"));
        if (formals.size() != arguments.size() || arguments.stream()
                .noneMatch(value -> kind(map(value)).equals("WILDCARD"))) return type;
        Map<String, Map<String, Object>> variables = bindings(type, scope);
        List<Object> captured = new ArrayList<>(arguments);
        for (int i = 0; i < captured.size(); i++)
            if (kind(map(captured.get(i))).equals("WILDCARD")) {
                Map<String, Object> value = variables.get(variable(map(formals.get(i))));
                if (value == null) return type;
                captured.set(i, value);
            }
        Map<String, Object> result = new TreeMap<>(type);
        result.put("arguments", captured);
        result.remove("display");
        result.remove("signature");
        return result;
    }

    Map<String, Object> substitute(
            Map<String, Object> type, Map<String, Map<String, Object>> bindings) {
        return substitute(type, bindings, new HashSet<>(), 0);
    }

    private Map<String, Object> substitute(
            Map<String, Object> type,
            Map<String, Map<String, Object>> bindings,
            Set<String> visiting,
            int depth) {
        step(depth);
        if (kind(type).equals("TYPEVAR")) {
            String key = variable(type);
            Map<String, Object> bound = bindings.get(key);
            if (bound != null && visiting.contains(key) && kind(bound).equals("CAPTURE"))
                return captureIdentity(bound);
            if (bound != null && visiting.add(key)) {
                try {
                    return substitute(bound, bindings, visiting, depth + 1);
                } finally {
                    visiting.remove(key);
                }
            }
            return type;
        }
        // A declaration type can occur at thousands of recovered sites. Copy only the
        // changed path; archive nodes are already immutable and safe to share.
        Map<String, Object> result = null;
        for (String key :
                List.of("component", "enclosing", "extends", "super", "upper", "lower", "return"))
            if (type.get(key) instanceof Map) {
                Map<String, Object> original = map(type.get(key));
                Map<String, Object> replacement = substitute(original, bindings, visiting, depth + 1);
                if (replacement != original) {
                    if (result == null) result = new TreeMap<>(type);
                    result.put(key, replacement);
                }
            }
        for (String key : List.of("arguments", "parameters", "bounds", "alternatives", "throws"))
            if (type.get(key) instanceof List) {
                List<Object> original = list(type.get(key));
                List<Object> values = null;
                for (int i = 0; i < original.size(); i++) {
                    Map<String, Object> value = map(original.get(i));
                    Map<String, Object> replacement = substitute(value, bindings, visiting, depth + 1);
                    if (replacement != value) {
                        if (values == null) values = new ArrayList<>(original);
                        values.set(i, replacement);
                    }
                }
                if (values != null) {
                    if (result == null) result = new TreeMap<>(type);
                    result.put(key, values);
                }
            }
        return result == null ? type : ModelArchive.freezeType(result);
    }

    List<Map<String, Object>> parents(Map<String, Object> type, String capture) throws IOException {
        Map<String, Object> attrs = attributes(text(type.get("element")));
        Map<String, Map<String, Object>> substitutions = bindings(type, capture);
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> superclass = map(attrs.get("superclass_type_model"));
        if (!superclass.isEmpty() && !Set.of("NONE", "NULL").contains(kind(superclass)))
            result.add(substitute(superclass, substitutions));
        for (Object parent : list(attrs.get("interface_type_models")))
            result.add(substitute(map(parent), substitutions));
        return result;
    }

    record Projection(Match match, Map<String, Object> type) {}

    Projection project(Map<String, Object> type, String target) throws IOException {
        ArrayDeque<Map<String, Object>> pending = new ArrayDeque<>();
        pending.add(type);
        Set<String> seen = new HashSet<>();
        boolean unknown = false;
        Map<String, Object> selected = null;
        while (!pending.isEmpty()) {
            step(0);
            Map<String, Object> current = readable(pending.removeFirst(), 0);
            if (!kind(current).equals("DECLARED")) {
                unknown = true;
                continue;
            }
            String key =
                    Json.sha(Json.write(current).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!seen.add(key)) continue;
            if (seen.size() > 256) throw new Budget();
            String name = text(current.get("element"));
            if (name.equals(target)) {
                if (selected != null && same(selected, current, 0) != Match.YES)
                    return new Projection(Match.UNKNOWN, Map.of());
                selected = current;
                continue;
            }
            if (name.equals("java.lang.Object")) continue;
            if (attributes(name).isEmpty()) {
                unknown = true;
                continue;
            }
            pending.addAll(parents(current, "hierarchy:" + key));
        }
        if (selected != null) return new Projection(Match.YES, selected);
        return new Projection(unknown ? Match.UNKNOWN : Match.NO, Map.of());
    }

    Map<String, Object> readable(Map<String, Object> type, int depth) {
        step(depth);
        if (kind(type).equals("CAPTURE") && !(type.get("upper") instanceof Map)) {
            Map<String, Object> captured = captures.get(text(type.get("capture")));
            if (captured != null) return readable(captured, depth + 1);
        }
        if (Set.of("CAPTURE", "TYPEVAR").contains(kind(type)) && type.get("upper") instanceof Map)
            return readable(map(type.get("upper")), depth + 1);
        return type;
    }

    Match convert(Map<String, Object> actual, Map<String, Object> expected, boolean loose)
            throws IOException {
        return convert(actual, expected, loose, 0);
    }

    private Match convert(
            Map<String, Object> actual, Map<String, Object> expected, boolean loose, int depth)
            throws IOException {
        step(depth);
        String a = kind(actual), e = kind(expected);
        if (Set.of("ERROR", "UNKNOWN", "NONE", "VOID", "").contains(a)
                || Set.of("ERROR", "UNKNOWN", "NONE", "VOID", "").contains(e)) return Match.UNKNOWN;
        if (a.equals("NULL")) return primitive(e) ? Match.NO : Match.YES;
        if (e.equals("CAPTURE")) {
            if (a.equals("CAPTURE")
                    && Objects.equals(actual.get("capture"), expected.get("capture")))
                return Match.YES;
            return expected.get("lower") instanceof Map
                    ? convert(actual, map(expected.get("lower")), loose, depth + 1)
                    : Match.NO;
        }
        if (e.equals("TYPEVAR")) {
            String key = variable(expected);
            return a.equals("TYPEVAR") && !key.isEmpty() && key.equals(variable(actual))
                    ? Match.YES
                    : Match.UNKNOWN;
        }
        if (e.equals("WILDCARD"))
            return Match.UNKNOWN; // Only valid as a type argument, not a writable parameter.
        if (e.equals("INTERSECTION")) {
            Match result = Match.YES;
            for (Object bound : list(expected.get("bounds")))
                result = and(result, convert(actual, map(bound), loose, depth + 1));
            return result;
        }
        if (a.equals("INTERSECTION")) {
            Match result = Match.NO;
            for (Object bound : list(actual.get("bounds"))) {
                Match match = convert(map(bound), expected, loose, depth + 1);
                if (match == Match.YES) return match;
                if (match == Match.UNKNOWN) result = match;
            }
            return result;
        }
        if (a.equals("TYPEVAR") || a.equals("CAPTURE")) {
            Map<String, Object> bound = readable(actual, depth + 1);
            // Detached formals can lack a captured upper bound. Re-entering the
            // same type cannot establish a conversion and only consumes budget.
            return bound == actual ? Match.UNKNOWN : convert(bound, expected, loose, depth + 1);
        }
        if (primitive(a) || primitive(e)) {
            if (primitive(a) && primitive(e))
                return a.equals(e) || widens(a, e) ? Match.YES : Match.NO;
            if (!loose) return Match.NO;
            if (primitive(a)) return convert(named(BOXES.get(a)), expected, false, depth + 1);
            Object actualElement = actual.get("element");
            String unboxed =
                    BOXES.entrySet().stream()
                            .filter(v -> v.getValue().equals(actualElement))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse("");
            return unboxed.isEmpty()
                    ? Match.NO
                    : unboxed.equals(e) || widens(unboxed, e) ? Match.YES : Match.NO;
        }
        if (a.equals("ARRAY")) {
            if (e.equals("DECLARED"))
                return Set.of("java.lang.Object", "java.lang.Cloneable", "java.io.Serializable")
                                .contains(expected.get("element"))
                        ? Match.YES
                        : Match.NO;
            if (!e.equals("ARRAY")) return Match.NO;
            Map<String, Object> ac = map(actual.get("component")),
                    ec = map(expected.get("component"));
            return primitive(kind(ac)) || primitive(kind(ec))
                    ? same(ac, ec, depth + 1)
                    : convert(ac, ec, false, depth + 1);
        }
        if (e.equals("ARRAY")) return Match.NO;
        if (!a.equals("DECLARED") || !e.equals("DECLARED")) return Match.UNKNOWN;
        String target = text(expected.get("element"));
        if (target.equals("java.lang.Object")) return Match.YES;
        if (!Objects.equals(actual.get("element"), target)) {
            Projection projection = project(actual, target);
            if (projection.match != Match.YES) return projection.match;
            actual = projection.type;
        }
        List<Object> wanted = list(expected.get("arguments")),
                given = list(actual.get("arguments"));
        if (wanted.isEmpty()) return Match.YES;
        if (given.size() != wanted.size())
            return Match.UNKNOWN; // Unchecked raw conversion lacks concrete argument evidence.
        Match result = Match.YES;
        for (int i = 0; i < wanted.size(); i++)
            result = and(result, contained(map(given.get(i)), map(wanted.get(i)), depth + 1));
        if (expected.get("enclosing") instanceof Map)
            result =
                    and(
                            result,
                            convert(
                                    map(actual.get("enclosing")),
                                    map(expected.get("enclosing")),
                                    false,
                                    depth + 1));
        return result;
    }

    private Match contained(Map<String, Object> actual, Map<String, Object> expected, int depth)
            throws IOException {
        step(depth);
        if (kind(expected).equals("TYPEVAR")) {
            Match match = same(actual, expected, depth + 1);
            return match == Match.YES ? match : Match.UNKNOWN;
        }
        if (!kind(expected).equals("WILDCARD")) return same(actual, expected, depth + 1);
        if (expected.get("super") instanceof Map) {
            Map<String, Object> lower =
                    kind(actual).equals("WILDCARD")
                            ? map(actual.get("super"))
                            : kind(actual).equals("CAPTURE") ? map(actual.get("lower")) : actual;
            return lower.isEmpty()
                    ? Match.NO
                    : convert(map(expected.get("super")), lower, false, depth + 1);
        }
        if (!(expected.get("extends") instanceof Map)) return Match.YES;
        Map<String, Object> upper =
                kind(actual).equals("WILDCARD")
                        ? actual.get("extends") instanceof Map
                                ? map(actual.get("extends"))
                                : named("java.lang.Object")
                        : readable(actual, depth + 1);
        return convert(upper, map(expected.get("extends")), false, depth + 1);
    }

    Match same(Map<String, Object> first, Map<String, Object> second, int depth)
            throws IOException {
        step(depth);
        String a = kind(first), b = kind(second);
        if (!a.equals(b)) return Match.NO;
        if (a.equals("TYPEVAR"))
            return variable(first).isEmpty()
                    ? Match.UNKNOWN
                    : variable(first).equals(variable(second)) ? Match.YES : Match.NO;
        if (a.equals("CAPTURE"))
            return Objects.equals(first.get("capture"), second.get("capture"))
                    ? Match.YES
                    : Match.NO;
        if (primitive(a) || a.equals("NULL") || a.equals("VOID")) return Match.YES;
        if (a.equals("ARRAY"))
            return same(map(first.get("component")), map(second.get("component")), depth + 1);
        if (!a.equals("DECLARED")) return Match.UNKNOWN;
        if (!Objects.equals(first.get("element"), second.get("element"))) return Match.NO;
        List<Object> left = list(first.get("arguments")), right = list(second.get("arguments"));
        if (left.size() != right.size()) return Match.UNKNOWN;
        Match result = Match.YES;
        for (int i = 0; i < left.size(); i++)
            result = and(result, same(map(left.get(i)), map(right.get(i)), depth + 1));
        if (first.get("enclosing") instanceof Map || second.get("enclosing") instanceof Map)
            result =
                    and(
                            result,
                            same(
                                    map(first.get("enclosing")),
                                    map(second.get("enclosing")),
                                    depth + 1));
        return result;
    }

    static Match and(Match a, Match b) {
        return a == Match.NO || b == Match.NO
                ? Match.NO
                : a == Match.UNKNOWN || b == Match.UNKNOWN ? Match.UNKNOWN : Match.YES;
    }

    static boolean primitive(String kind) {
        return BOXES.containsKey(kind);
    }

    static Map<String, Object> boxed(Map<String, Object> type) {
        return primitive(kind(type)) ? named(BOXES.get(kind(type))) : type;
    }

    static boolean widens(String from, String to) {
        return switch (from) {
            case "BYTE" -> Set.of("SHORT", "INT", "LONG", "FLOAT", "DOUBLE").contains(to);
            case "SHORT", "CHAR" -> Set.of("INT", "LONG", "FLOAT", "DOUBLE").contains(to);
            case "INT" -> Set.of("LONG", "FLOAT", "DOUBLE").contains(to);
            case "LONG" -> Set.of("FLOAT", "DOUBLE").contains(to);
            case "FLOAT" -> to.equals("DOUBLE");
            default -> false;
        };
    }
}
