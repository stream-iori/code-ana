package dev.codeana.adapter;

import com.anatomist.core.java.*;
import dev.codeana.common.Json;
import java.util.*;

/** Finite graph encoding at the Fact IR boundary; cycles retain identity. */
final class TypeGraphProjection {
    private final JavaTypes types;
    private final Map<Integer, Integer> slots = new LinkedHashMap<>();
    private final List<Integer> pending = new ArrayList<>(), roots = new ArrayList<>();
    private final List<Map<String, Object>> nodes = new ArrayList<>();
    private final List<List<Integer>> edges = new ArrayList<>();
    private static final java.util.regex.Pattern CAPTURE =
            java.util.regex.Pattern.compile("capture#\\d+");
    private final Map<String, Integer> captures = new LinkedHashMap<>();

    // javac's display numbers are local to a compiler process. Canonicalize only
    // type spelling; annotation values and source text must remain byte-for-byte data.
    String spelling(String text) {
        var matcher = CAPTURE.matcher(text);
        if (!matcher.find()) return text;
        var out = new StringBuilder();
        int scanned = 0;
        char quote = 0;
        boolean escaped = false;
        do {
            for (int i = scanned; i < matcher.start(); i++) {
                char ch = text.charAt(i);
                if (escaped) escaped = false;
                else if (quote != 0 && ch == '\\') escaped = true;
                else if (ch == quote) quote = 0;
                else if (quote == 0 && (ch == '"' || ch == '\'')) quote = ch;
            }
            String replacement = quote != 0 ? matcher.group() :
                    "capture#" + captures.computeIfAbsent(matcher.group(), ignored -> captures.size());
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
            scanned = matcher.end();
        } while (matcher.find());
        return matcher.appendTail(out).toString();
    }

    TypeGraphProjection(JavaTypes types) {
        this.types = types;
    }

    Object reference(int ref) {
        if (ref < 0) return null;
        int slot = include(ref), root = roots.indexOf(slot);
        if (root < 0) {
            root = roots.size();
            roots.add(slot);
        }
        return Json.object("$java_type", List.of(0, root));
    }

    private int include(int ref) {
        if (ref < 0) return -1;
        Integer known = slots.get(ref);
        if (known != null) return known;
        types.validateReference(ref);
        int slot = pending.size();
        if (slot >= 100000) throw new IllegalArgumentException("JAVA_TYPE_GRAPH_BUDGET");
        slots.put(ref, slot);
        pending.add(ref);
        return slot;
    }

    private List<Integer> many(List<Integer> refs, List<Integer> children) {
        List<Integer> out = new ArrayList<>();
        for (int ref : refs) {
            int id = include(ref);
            out.add(id);
            children.add(id);
        }
        return out;
    }

    private int one(int ref, List<Integer> children) {
        int id = include(ref);
        if (id >= 0) children.add(id);
        return id;
    }

    void attach(Map<String, Object> attributes) {
        for (int cursor = 0; cursor < pending.size(); cursor++) {
            JavaType t = types.get(pending.get(cursor));
            List<Integer> children = new ArrayList<>();
            String kind =
                    switch (t.kind()) {
                        case PACKAGE, MODULE -> "OTHER";
                        default -> t.kind().name();
                    };
            Map<String, Object> n =
                    Json.object(
                            "version",
                            3,
                            "kind",
                            kind,
                            "name",
                            spelling(t.name()),
                            "display",
                            spelling(t.display()),
                            "complete",
                            t.kind() != JavaType.Kind.ERROR && t.kind() != JavaType.Kind.OTHER);
            if (!t.binaryName().isEmpty()) n.put("binary_name", t.binaryName());
            if (!t.annotations().isEmpty())
                n.put(
                        "annotations",
                        t.annotations().stream().map(TypeGraphProjection::annotation).toList());
            switch (t.kind()) {
                case ARRAY -> n.put("component", one(t.component(), children));
                case DECLARED, ERROR -> {
                    n.put("element", spelling(t.name()));
                    n.put("enclosing", one(t.enclosing(), children));
                    n.put("arguments", many(t.arguments(), children));
                    if (t.name().isEmpty()) n.put("complete", false);
                }
                case TYPEVAR -> {
                    n.put(
                            "upper",
                            one(
                                    t.upperBounds().isEmpty() ? -1 : t.upperBounds().getFirst(),
                                    children));
                    n.put("lower", one(t.lowerBound(), children));
                    if (t.upperBounds().size() != 1 || t.lowerBound() < 0) n.put("complete", false);
                }
                case WILDCARD -> {
                    n.put(
                            "extends",
                            one(
                                    t.upperBounds().isEmpty() ? -1 : t.upperBounds().getFirst(),
                                    children));
                    n.put("super", one(t.lowerBound(), children));
                }
                case INTERSECTION -> n.put("bounds", many(t.upperBounds(), children));
                case UNION -> n.put("alternatives", many(t.arguments(), children));
                case EXECUTABLE -> {
                    n.put("receiver", one(t.enclosing(), children));
                    n.put("return", one(t.returns(), children));
                    n.put("parameters", many(t.parameters(), children));
                    n.put("throws", many(t.exceptions(), children));
                    n.put("type_variables", many(t.arguments(), children));
                    if (t.returns() < 0) n.put("complete", false);
                }
                default -> {}
            }
            if (t.kind() == JavaType.Kind.ARRAY && t.component() < 0) n.put("complete", false);
            nodes.add(n);
            edges.add(children);
        }
        // Propagate incomplete descendants with a reverse worklist, including recursive bounds.
        List<List<Integer>> reverse = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) reverse.add(new ArrayList<>());
        for (int i = 0; i < edges.size(); i++)
            for (int child : edges.get(i)) reverse.get(child).add(i);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < nodes.size(); i++)
            if (Boolean.FALSE.equals(nodes.get(i).get("complete"))) queue.add(i);
        while (!queue.isEmpty())
            for (int parent : reverse.get(queue.removeFirst()))
                if (Boolean.TRUE.equals(nodes.get(parent).put("complete", false)))
                    queue.add(parent);
        attributes.put(
                "java_type_graphs",
                List.of(Json.object("version", 3, "nodes", nodes, "roots", roots)));
    }

    private static Map<String, Object> annotation(JavaAnnotation a) {
        Map<String, Object> values = new TreeMap<>();
        a.values().forEach((k, v) -> values.put(k, value(v)));
        return Json.object(
                "descriptor",
                a.descriptor(),
                "retention",
                a.retention().name(),
                "values",
                literal(values));
    }

    private static Object literal(Map<String, Object> values) {
        if (!values.containsKey("$java_type") && !values.containsKey("$java_object")) return values;
        return Json.object(
                "$java_object",
                values.entrySet().stream()
                        .map(e -> Arrays.asList(e.getKey(), e.getValue()))
                        .toList());
    }

    private static Object value(JavaBinaryType.Value v) {
        return switch (v) {
            case JavaBinaryType.Scalar s ->
                    s.value() instanceof Character c ? c.toString() : s.value();
            case JavaBinaryType.ClassLiteral c ->
                    Json.object("kind", "class", "descriptor", c.descriptor());
            case JavaBinaryType.EnumValue e ->
                    Json.object("kind", "enum", "descriptor", e.descriptor(), "name", e.name());
            case JavaBinaryType.ArrayValue a ->
                    a.values().stream().map(TypeGraphProjection::value).toList();
            case JavaBinaryType.UnavailableValue u ->
                    Json.object("kind", "unavailable", "reason", u.reason());
            case JavaBinaryType.NestedAnnotation a -> {
                Map<String, Object> fields = new TreeMap<>();
                a.annotation().values().forEach((k, x) -> fields.put(k, value(x)));
                yield Json.object(
                        "kind",
                        "annotation",
                        "descriptor",
                        a.annotation().descriptor(),
                        "values",
                        literal(fields));
            }
        };
    }
}
