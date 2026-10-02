package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.util.*;

/** Bounded JVM signature decoder; binary type parameters retain names and separate bounds. */
public final class BinaryTypes {
    private final String input;
    private int position, nodes;
    private BinaryTypes(String input) {
        if (input == null || input.length() > 65536) throw new IllegalArgumentException("BINARY_SIGNATURE_BUDGET");
        this.input = input;
    }

    public static Map<String, Object> field(String signature, String descriptor) {
        BinaryTypes reader = new BinaryTypes(signature == null ? descriptor : signature);
        Map<String, Object> result = reader.type(0);
        reader.end();
        return result;
    }

    public static Map<String, Object> method(String signature, String descriptor) {
        BinaryTypes reader = new BinaryTypes(signature == null ? descriptor : signature);
        List<Object> variables = reader.formals();
        reader.expect('(');
        List<Object> parameters = new ArrayList<>();
        while (reader.peek() != ')') parameters.add(reader.type(0));
        reader.expect(')');
        Map<String, Object> returns = reader.type(0);
        List<Object> thrown = new ArrayList<>();
        while (reader.position < reader.input.length()) {
            reader.expect('^'); thrown.add(reader.type(0));
        }
        return Json.object("version", 2, "kind", "EXECUTABLE", "complete", true,
                "parameters", parameters, "return", returns, "throws", thrown,
                "type_parameters", variables, "origin", "binary_signature");
    }

    public static Map<String, Object> declaration(String signature, String superclass, String[] interfaces) {
        if (signature == null) {
            List<Object> parents = new ArrayList<>();
            for (String name : interfaces) parents.add(declared(name.replace('/', '.'), List.of(), null));
            return Json.object("type_parameters", List.of(), "superclass",
                    superclass == null ? Json.object("version", 2, "kind", "NONE", "complete", true)
                            : declared(superclass.replace('/', '.'), List.of(), null), "interfaces", parents);
        }
        BinaryTypes reader = new BinaryTypes(signature);
        List<Object> variables = reader.formals();
        Map<String, Object> parent = reader.type(0);
        List<Object> parents = new ArrayList<>();
        while (reader.position < reader.input.length()) parents.add(reader.type(0));
        return Json.object("type_parameters", variables, "superclass", parent, "interfaces", parents);
    }

    /** Resolve binary formal identities after decoding, including method shadowing. */
    static void owners(Map<String, Object> model, String owner, Map<String, String> enclosing) {
        Map<String, String> scope = new HashMap<>(enclosing);
        Object raw = model.get("type_parameters");
        if (raw instanceof List) for (Object item : Json.list(raw)) {
            Map<String, Object> formal = Json.map(item);
            formal.put("owner", owner); scope.put(Json.string(formal, "name"), owner);
        }
        bind(model, scope, 0);
    }

    private static void bind(Object value, Map<String, String> scope, int depth) {
        if (depth > 64) throw new IllegalArgumentException("BINARY_SIGNATURE_BUDGET");
        if (value instanceof Map) {
            Map<String, Object> type = Json.map(value);
            if ("TYPEVAR".equals(type.get("kind"))) {
                String owner = scope.get(type.get("name"));
                if (owner != null) {
                    type.put("owner", owner);
                    if (Boolean.TRUE.equals(type.remove("owner_unavailable"))) type.put("complete", true);
                }
                else { type.put("complete", false); type.put("owner_unavailable", true); }
            }
            for (Object nested : type.values()) if (nested instanceof List || nested instanceof Map) bind(nested, scope, depth + 1);
        } else if (value instanceof List) for (Object nested : Json.list(value)) bind(nested, scope, depth + 1);
    }

    private List<Object> formals() {
        List<Object> result = new ArrayList<>();
        if (peek() != '<') return result;
        position++;
        while (peek() != '>') {
            int start = position;
            while (peek() != ':') position++;
            String name = input.substring(start, position);
            if (name.isEmpty()) fail();
            position++;
            List<Object> bounds = new ArrayList<>();
            if (peek() != ':') bounds.add(type(0));
            while (peek() == ':') { position++; bounds.add(type(0)); }
            result.add(Json.object("name", name, "bounds", bounds));
        }
        position++;
        return result;
    }

    private Map<String, Object> type(int depth) {
        if (depth > 32 || ++nodes > 4096) throw new IllegalArgumentException("BINARY_SIGNATURE_BUDGET");
        char kind = peek(); position++;
        String primitive = switch (kind) {
            case 'Z' -> "BOOLEAN"; case 'B' -> "BYTE"; case 'C' -> "CHAR"; case 'S' -> "SHORT";
            case 'I' -> "INT"; case 'J' -> "LONG"; case 'F' -> "FLOAT"; case 'D' -> "DOUBLE";
            case 'V' -> "VOID"; default -> null;
        };
        if (primitive != null) return Json.object("version", 2, "kind", primitive,
                "display", primitive.toLowerCase(Locale.ROOT), "descriptor", String.valueOf(kind), "complete", true);
        if (kind == '[') {
            Map<String, Object> component = type(depth + 1);
            return Json.object("version", 2, "kind", "ARRAY", "component", component,
                    "display", component.get("display") + "[]", "complete", true);
        }
        if (kind == 'T') {
            int start = position;
            while (peek() != ';') position++;
            String name = input.substring(start, position++);
            if (name.isEmpty()) fail();
            return Json.object("version", 2, "kind", "TYPEVAR", "name", name, "display", name,
                    "complete", true, "bounds_from", "declaration_parameters");
        }
        if (kind != 'L') { fail(); return null; }
        Map<String, Object> current = null;
        String name = "";
        while (true) {
            int start = position;
            while (peek() != '<' && peek() != '.' && peek() != ';') position++;
            String part = input.substring(start, position).replace('/', '.');
            if (part.isEmpty()) fail();
            name = name.isEmpty() ? part : name + '$' + part;
            List<Object> arguments = new ArrayList<>();
            if (peek() == '<') {
                position++;
                while (peek() != '>') {
                    char variance = peek();
                    if (variance == '*' || variance == '+' || variance == '-') {
                        position++;
                        Map<String, Object> wildcard = Json.object("version", 2, "kind", "WILDCARD", "complete", true);
                        if (variance != '*') wildcard.put(variance == '+' ? "extends" : "super", type(depth + 1));
                        arguments.add(wildcard);
                    } else arguments.add(type(depth + 1));
                }
                position++;
            }
            current = declared(name, arguments, current);
            char delimiter = peek(); position++;
            if (delimiter == ';') return current;
            if (delimiter != '.') fail();
        }
    }

    private static Map<String, Object> declared(String name, List<Object> arguments, Object enclosing) {
        Map<String, Object> result = Json.object("version", 2, "kind", "DECLARED", "element", name,
                "display", name, "arguments", arguments, "complete", true, "origin", "binary_signature");
        if (enclosing != null) result.put("enclosing", enclosing);
        return result;
    }
    private char peek() { if (position >= input.length()) fail(); return input.charAt(position); }
    private void expect(char expected) { if (peek() != expected) fail(); position++; }
    private void end() { if (position != input.length()) fail(); }
    private void fail() { throw new IllegalArgumentException("BINARY_SIGNATURE_INVALID at " + position); }
}
