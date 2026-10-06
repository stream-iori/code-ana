package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.analysis.model.JavaSemanticModel;

import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.*;

/** Bounded constant/handle flow over detached syntax. Never loads or invokes target classes. */
final class ReflectionModel {
    private sealed interface Value permits Unknown, Text, ClassValue, Classes, Handle {}

    private enum Unknown implements Value {
        VALUE
    }

    private record Text(String value, String origin) implements Value {}

    private record ClassValue(String name, String origin) implements Value {}

    private record Classes(List<ClassValue> values, int identity) implements Value {}

    private record Handle(
            String target,
            String owner,
            String member,
            List<String> parameters,
            boolean constructor,
            boolean declared,
            String origin)
            implements Value {}

    private static final Set<String> FRAMES =
            Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "METHOD", "LAMBDA_EXPRESSION");
    private static final Map<String, String> PRIMITIVES =
            Map.of(
                    "java.lang.Boolean",
                    "boolean",
                    "java.lang.Byte",
                    "byte",
                    "java.lang.Character",
                    "char",
                    "java.lang.Short",
                    "short",
                    "java.lang.Integer",
                    "int",
                    "java.lang.Long",
                    "long",
                    "java.lang.Float",
                    "float",
                    "java.lang.Double",
                    "double",
                    "java.lang.Void",
                    "void");
    private final Map<String, Object> model;
    private final BinaryIndex binaries;
    private final Map<String, Map<String, Object>> declarations;
    private final Map<String, String> constants = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> members;
    private final Set<String> external = new HashSet<>(), emitted = new HashSet<>();
    private final Map<String, Map<String, Object>> calls = new HashMap<>();
    private final Map<Integer, Integer> nodes = new HashMap<>();
    private List<Object> syntaxRows = List.of();
    private final Map<Integer, Map<String, Object>> syntaxCache =
            new LinkedHashMap<>(64, .75f, true);
    private String source;
    private int work;

    private ReflectionModel(Map<String, Object> model, BinaryIndex binaries, JavaSemanticModel environment) {
        this.model = model;
        this.binaries = binaries;
        declarations = environment.declarations();
        members = environment.members(declaration -> true);
        for (var declaration : declarations.values()) constant(declaration);
        for (Object raw : Json.list(model.get("external"))) {
            Map<String, Object> declaration = Json.map(raw);
            external.add(Json.string(declaration, "signature"));
            constant(declaration);
        }
    }

    static void analyze(Map<String, Object> context, Map<String, Object> model, Path javaHome)
            throws Exception {
        if (Json.list(model.get("observations")).stream()
                .map(Json::map)
                .noneMatch(
                        call ->
                                "call".equals(call.get("kind"))
                                        && Boolean.TRUE.equals(call.get("resolved"))
                                        && lookupApi(Json.string(call, "target")))) return;
        try (BinaryIndex binaries = new BinaryIndex(context, javaHome);
                JavaSemanticModel environment = new JavaSemanticModel(model)) {
            ReflectionModel analysis = new ReflectionModel(model, binaries, environment);
            for (JavaSemanticModel.Unit unit : environment.units()) {
                analysis.calls.clear();
                for (JavaSemanticModel.Binding binding : unit.bindings())
                    if (binding.call()) analysis.calls.put(site(binding.evidence()),binding.evidence());
                analysis.syntaxRows = unit.rows("syntax");
                List<Integer> positions = new AbstractList<>() {
                    public int size(){return analysis.syntaxRows.size();}
                    public Integer get(int index){Objects.checkIndex(index,size());return index;}
                };
                analysis.source(unit.source(),positions);
            }
        }
    }

    private void constant(Map<String, Object> declaration) {
        Object value = Json.map(declaration.get("attributes")).get("constant_value");
        if (value instanceof String text && text.length() <= 4096)
            constants.put(Json.string(declaration, "signature"), text);
    }

    private static String site(Map<String, Object> node) {
        return Json.string(node, "source") + ':' + node.get("start") + ':' + node.get("end");
    }

    private void source(String source, List<Integer> positions) throws Exception {
        this.source = source;
        work = 0;
        nodes.clear();
        syntaxCache.clear();
        for (int position : positions) {
            Map<String, Object> node = Json.map(syntaxRows.get(position));
            nodes.put(((Number) node.get("id")).intValue(), position);
        }
        try {
            for (int position : positions) {
                Map<String, Object> node = Json.map(syntaxRows.get(position));
                String kind = Json.string(node, "kind");
                if (kind.equals("METHOD") || kind.equals("LAMBDA_EXPRESSION")) {
                    Map<String, Value> state = new HashMap<>();
                    for (Map<String, Object> parameter : children(node, "parameters"))
                        put(state, (String) parameter.getOrDefault("symbol", ""), Unknown.VALUE);
                    eval(child(node, "body"), state, 0);
                } else if (kind.equals("VARIABLE") && declarationKind(node).equals("FIELD")) {
                    eval(child(node, "initializer"), new HashMap<>(), 0);
                } else if (kind.equals("BLOCK")) {
                    Map<String, Object> parent = node(((Number) node.get("parent")).intValue());
                    if (parent != null && Set.of("CLASS", "ENUM").contains(parent.get("kind")))
                        eval(node, new HashMap<>(), 0);
                }
            }
        } catch (Limit limited) {
            Json.list(model.get("diagnostics"))
                    .add(
                            Json.object(
                                    "source",
                                    source,
                                    "start",
                                    -1,
                                    "end",
                                    -1,
                                    "severity",
                                    "WARNING",
                                    "code",
                                    "REFLECTION_FLOW_BUDGET",
                                    "message",
                                    "Static reflection flow incomplete",
                                    "phase",
                                    "reflection"));
        }
    }

    private String declarationKind(Map<String, Object> node) {
        Map<String, Object> declaration = declarations.get(node.get("symbol"));
        return declaration == null
                ? ""
                : Json.string(Json.map(declaration.get("attributes")), "element_kind");
    }

    private static final class Limit extends RuntimeException {}

    private void budget(int depth) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        if (++work > 1000000 || depth > 256) throw new Limit();
    }

    private Map<String, Object> child(Map<String, Object> node, String role) {
        Object id = Json.map(node.get("roles")).get(role);
        return id instanceof Number number ? node(number.intValue()) : null;
    }

    private Map<String, Object> node(int id) {
        Integer position = nodes.get(id);
        if (position == null) return null;
        Map<String, Object> cached = syntaxCache.get(id);
        if (cached != null) return cached;
        Map<String, Object> row = Json.map(syntaxRows.get(position));
        syntaxCache.put(id, row);
        if (syntaxCache.size() > 64) syntaxCache.remove(syntaxCache.keySet().iterator().next());
        return row;
    }

    private List<Map<String, Object>> nodeViews(List<Integer> ids) {
        return new AbstractList<>() {
            @Override
            public int size() {
                return ids.size();
            }

            @Override
            public Map<String, Object> get(int index) {
                return node(ids.get(index));
            }
        };
    }

    private List<Map<String, Object>> children(Map<String, Object> node, String role) {
        Object raw = Json.map(node.get("roles")).get(role);
        if (!(raw instanceof List)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object id : Json.list(raw))
            if (id instanceof Number number && nodes.containsKey(number.intValue()))
                result.add(number.intValue());
        return nodeViews(result);
    }

    private List<Map<String, Object>> children(Map<String, Object> node) {
        return nodeViews(childIds(node));
    }

    private List<Integer> childIds(Map<String, Object> node) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (Object raw : Json.map(node.get("roles")).values()) {
            if (raw instanceof Number number) ids.add(number.intValue());
            else if (raw instanceof List)
                for (Object id : Json.list(raw)) ids.add(((Number) id).intValue());
        }
        return ids.stream().filter(nodes::containsKey).toList();
    }

    private void put(Map<String, Value> state, String key, Value value) {
        if (key.isEmpty()) return;
        if (!state.containsKey(key) && state.size() >= 4096) throw new Limit();
        state.put(key, local(value));
    }

    private Value eval(Map<String, Object> node, Map<String, Value> state, int depth)
            throws Exception {
        if (node == null) return Unknown.VALUE;
        budget(depth);
        String kind = Json.string(node, "kind"), symbol = (String) node.getOrDefault("symbol", "");
        switch (kind) {
            case "STRING_LITERAL":
                {
                    String value = (String) node.get("value");
                    return value.length() <= 4096 ? new Text(value, "literal") : Unknown.VALUE;
                }
            case "IDENTIFIER":
                {
                    if (state.containsKey(symbol)) return state.get(symbol);
                    String value = constants.get(symbol);
                    return value != null ? new Text(value, "compiler_constant") : Unknown.VALUE;
                }
            case "MEMBER_SELECT":
                {
                    if ("class".equals(node.get("name"))) {
                        String type = typeName(child(node, "expression"));
                        return type == null ? Unknown.VALUE : new ClassValue(type, "class_literal");
                    }
                    if ("TYPE".equals(node.get("name")) && symbol.endsWith("#TYPE")) {
                        String primitive = PRIMITIVES.get(symbol.substring(0, symbol.length() - 5));
                        if (primitive != null)
                            return new ClassValue(primitive, "primitive_type_field");
                    }
                    eval(child(node, "expression"), state, depth + 1);
                    String value = constants.get(symbol);
                    return value != null ? new Text(value, "compiler_constant") : Unknown.VALUE;
                }
            case "PARENTHESIZED":
            case "TYPE_CAST":
                return eval(child(node, "expression"), state, depth + 1);
            case "VARIABLE":
                {
                    Value value = eval(child(node, "initializer"), state, depth + 1);
                    put(state, symbol, value);
                    return value;
                }
            case "ASSIGNMENT":
                {
                    Map<String, Object> variable = child(node, "variable");
                    Value value = eval(child(node, "expression"), state, depth + 1);
                    if (variable != null && "IDENTIFIER".equals(variable.get("kind")))
                        put(state, (String) variable.getOrDefault("symbol", ""), value);
                    else {
                        eval(variable, state, depth + 1);
                        invalidateArrays(state);
                    }
                    return value;
                }
            case "PLUS":
                {
                    Value left = eval(child(node, "left"), state, depth + 1),
                            right = eval(child(node, "right"), state, depth + 1);
                    if (left instanceof Text a
                            && right instanceof Text b
                            && a.value().length() + b.value().length() <= 4096)
                        return new Text(a.value() + b.value(), "constant_concat");
                    return Unknown.VALUE;
                }
            case "BLOCK":
                {
                    Set<String> previous = new HashSet<>(state.keySet());
                    for (Map<String, Object> statement : children(node, "statements"))
                        eval(statement, state, depth + 1);
                    state.keySet().retainAll(previous);
                    return Unknown.VALUE;
                }
            case "IF":
            case "CONDITIONAL_EXPRESSION":
                {
                    eval(child(node, "condition"), state, depth + 1);
                    Map<String, Value> left = new HashMap<>(state), right = new HashMap<>(state);
                    Value a = eval(child(node, "then"), left, depth + 1);
                    Value b = eval(child(node, "else"), right, depth + 1);
                    merge(state, List.of(left, right));
                    return same(a, b) ? a : Unknown.VALUE;
                }
            case "CONDITIONAL_AND":
            case "CONDITIONAL_OR":
                {
                    eval(child(node, "left"), state, depth + 1);
                    Map<String, Value> skipped = new HashMap<>(state), taken = new HashMap<>(state);
                    eval(child(node, "right"), taken, depth + 1);
                    merge(state, List.of(skipped, taken));
                    return Unknown.VALUE;
                }
            case "FOR_LOOP":
            case "ENHANCED_FOR_LOOP":
            case "WHILE_LOOP":
            case "DO_WHILE_LOOP":
                {
                    for (Map<String, Object> init : children(node, "initializer"))
                        eval(init, state, depth + 1);
                    invalidateWrites(node, state);
                    eval(child(node, "condition"), state, depth + 1);
                    eval(child(node, "expression"), state, depth + 1);
                    eval(child(node, "variable"), state, depth + 1);
                    eval(child(node, "body"), state, depth + 1);
                    for (Map<String, Object> update : children(node, "update"))
                        eval(update, state, depth + 1);
                    invalidateWrites(node, state);
                    return Unknown.VALUE;
                }
            case "TRY":
                {
                    for (Map<String, Object> resource : children(node, "resources"))
                        eval(resource, state, depth + 1);
                    Map<String, Value> uncertain = new HashMap<>(state);
                    invalidateWrites(child(node, "body"), uncertain);
                    List<Map<String, Value>> outcomes = new ArrayList<>();
                    Map<String, Value> success = new HashMap<>(state);
                    eval(child(node, "body"), success, depth + 1);
                    outcomes.add(success);
                    for (Map<String, Object> caught : children(node, "catches")) {
                        Map<String, Value> branch = new HashMap<>(uncertain);
                        eval(caught, branch, depth + 1);
                        outcomes.add(branch);
                    }
                    merge(state, outcomes);
                    eval(child(node, "finally"), state, depth + 1);
                    return Unknown.VALUE;
                }
            case "SWITCH":
                {
                    eval(child(node, "expression"), state, depth + 1);
                    List<Map<String, Value>> outcomes = new ArrayList<>();
                    Map<String, Value> fallthrough = null;
                    boolean hasDefault = false;
                    for (Map<String, Object> branch : children(node, "cases")) {
                        hasDefault |= child(branch, "expression") == null;
                        Map<String, Value> outcome = new HashMap<>(state);
                        if (fallthrough != null)
                            merge(outcome, List.of(new HashMap<>(outcome), fallthrough));
                        eval(branch, outcome, depth + 1);
                        outcomes.add(outcome);
                        List<Map<String, Object>> statements = children(branch, "statements");
                        boolean fallsThrough = statements.isEmpty()
                                || !Set.of("BREAK", "RETURN", "THROW")
                                        .contains(statements.getLast().get("kind"));
                        fallthrough = fallsThrough ? new HashMap<>(outcome) : null;
                    }
                    if (!hasDefault) outcomes.add(new HashMap<>(state));
                    merge(state, outcomes);
                    return Unknown.VALUE;
                }
            case "CASE":
                {
                    for (Map<String, Object> statement : children(node, "statements"))
                        eval(statement, state, depth + 1);
                    return Unknown.VALUE;
                }
            case "NEW_ARRAY":
                {
                    List<ClassValue> values = new ArrayList<>();
                    List<Map<String, Object>> initializers = children(node, "initializers");
                    boolean known =
                            initializers.size() <= 256
                                    && Json.map(node.get("roles")).containsKey("initializers");
                    for (Map<String, Object> initializer : initializers) {
                        Value value = eval(initializer, state, depth + 1);
                        if (value instanceof ClassValue type) values.add(type);
                        else known = false;
                    }
                    for (Map<String, Object> dimension : children(node, "dimensions"))
                        eval(dimension, state, depth + 1);
                    return known
                            ? new Classes(List.copyOf(values), ((Number) node.get("id")).intValue())
                            : Unknown.VALUE;
                }
            case "METHOD_INVOCATION":
                {
                    Map<String, Object> selection = child(node, "selection");
                    Value receiver =
                            selection == null
                                    ? Unknown.VALUE
                                    : eval(child(selection, "expression"), state, depth + 1);
                    List<Value> args = new ArrayList<>();
                    for (Map<String, Object> arg : children(node, "arguments"))
                        args.add(eval(arg, state, depth + 1));
                    Map<String, Object> binding = calls.get(site(node));
                    String target =
                            binding != null && Boolean.TRUE.equals(binding.get("resolved"))
                                    ? Json.string(binding, "target")
                                    : "";
                    Value result = invoke(node, target, receiver, args);
                    if (!lookupApi(target))
                        for (Value arg : args)
                            if (arg instanceof Classes array) invalidateAliases(state, array);
                    return result;
                }
            case "NEW_CLASS":
                {
                    eval(child(node, "enclosing"), state, depth + 1);
                    for (Map<String, Object> argument : children(node, "arguments")) {
                        Value value = eval(argument, state, depth + 1);
                        if (value instanceof Classes array) invalidateAliases(state, array);
                    }
                    invalidateCapturedArrays(child(node, "body"), state);
                    return Unknown.VALUE;
                }
            default:
                {
                    if (FRAMES.contains(kind)) {
                        invalidateCapturedArrays(node, state);
                        return Unknown.VALUE;
                    }
                    for (Map<String, Object> item : children(node)) eval(item, state, depth + 1);
                    if (kind.endsWith("_ASSIGNMENT")
                            || kind.endsWith("_INCREMENT")
                            || kind.endsWith("_DECREMENT")) {
                        Map<String, Object> variable =
                                child(
                                        node,
                                        kind.endsWith("_ASSIGNMENT") ? "variable" : "expression");
                        if (variable != null && "IDENTIFIER".equals(variable.get("kind")))
                            put(state, (String) variable.getOrDefault("symbol", ""), Unknown.VALUE);
                        else invalidateArrays(state);
                    }
                    return Unknown.VALUE;
                }
        }
    }

    private String typeName(Map<String, Object> node) {
        if (node == null) return null;
        if ("PRIMITIVE_TYPE".equals(node.get("kind"))) return (String) node.get("name");
        if ("ARRAY_TYPE".equals(node.get("kind"))) {
            String component = typeName(child(node, "type"));
            return component == null ? null : component + "[]";
        }
        String symbol = (String) node.getOrDefault("symbol", "");
        return !symbol.isEmpty() && !symbol.contains("#") ? symbol : null;
    }

    private static void merge(Map<String, Value> state, List<Map<String, Value>> outcomes) {
        if (outcomes.isEmpty()) return;
        for (String key : new ArrayList<>(state.keySet())) {
            Value candidate = outcomes.getFirst().getOrDefault(key, Unknown.VALUE);
            state.put(
                    key,
                    outcomes.stream()
                                    .allMatch(
                                            branch ->
                                                    same(
                                                            candidate,
                                                            branch.getOrDefault(
                                                                    key, Unknown.VALUE)))
                            ? candidate
                            : Unknown.VALUE);
        }
    }

    /** Constant provenance differs across branches without changing the proven value. */
    private static Value local(Value value) {
        if (value instanceof Text text) return new Text(text.value(), "local_constant");
        if (value instanceof ClassValue type) return new ClassValue(type.name(), "local_handle");
        if (value instanceof Handle handle)
            return new Handle(handle.target(), handle.owner(), handle.member(), handle.parameters(),
                    handle.constructor(), handle.declared(), "local_handle");
        return value;
    }

    private static boolean same(Value left, Value right) {
        return Objects.equals(local(left), local(right));
    }

    private static void invalidateArrays(Map<String, Value> state) {
        state.replaceAll((key, value) -> value instanceof Classes ? Unknown.VALUE : value);
    }

    private static void invalidateAliases(Map<String, Value> state, Classes array) {
        state.replaceAll(
                (key, value) ->
                        value instanceof Classes other && other.identity() == array.identity()
                                ? Unknown.VALUE
                                : value);
    }

    private void invalidateCapturedArrays(Map<String, Object> root, Map<String, Value> state) {
        if (root == null || state.values().stream().noneMatch(value -> value instanceof Classes))
            return;
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(((Number) root.get("id")).intValue());
        Set<Integer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Map<String, Object> node = node(pending.removeFirst());
            budget(0);
            if (!seen.add(((Number) node.get("id")).intValue())) continue;
            Value value = state.get(node.get("symbol"));
            if (value instanceof Classes array) invalidateAliases(state, array);
            pending.addAll(childIds(node));
        }
    }

    private void invalidateWrites(Map<String, Object> root, Map<String, Value> state) {
        if (root == null) return;
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(((Number) root.get("id")).intValue());
        Set<Integer> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Map<String, Object> node = node(pending.removeFirst());
            budget(0);
            if (!seen.add(((Number) node.get("id")).intValue())) continue;
            String kind = Json.string(node, "kind");
            if (FRAMES.contains(kind)) continue;
            if (kind.equals("ASSIGNMENT")
                    || kind.endsWith("_ASSIGNMENT")
                    || kind.endsWith("_INCREMENT")
                    || kind.endsWith("_DECREMENT")) {
                Map<String, Object> variable =
                        child(node, kind.contains("ASSIGNMENT") ? "variable" : "expression");
                if (variable != null && "IDENTIFIER".equals(variable.get("kind")))
                    put(state, (String) variable.getOrDefault("symbol", ""), Unknown.VALUE);
                else invalidateArrays(state);
            }
            if (kind.equals("METHOD_INVOCATION")) {
                Map<String, Object> call = calls.get(site(node));
                if (call == null || !lookupApi(Json.string(call, "target")))
                    for (Map<String, Object> argument : children(node, "arguments")) {
                        Value value = state.get(argument.get("symbol"));
                        if (value instanceof Classes array) invalidateAliases(state, array);
                    }
            }
            pending.addAll(childIds(node));
        }
    }

    private static boolean lookupApi(String signature) {
        return signature.startsWith("java.lang.Class#forName(")
                || signature.startsWith("java.lang.Class#getMethod(")
                || signature.startsWith("java.lang.Class#getDeclaredMethod(")
                || signature.startsWith("java.lang.Class#getConstructor(")
                || signature.startsWith("java.lang.Class#getDeclaredConstructor(");
    }

    private Value invoke(Map<String, Object> node, String bound, Value receiver, List<Value> args)
            throws Exception {
        if (bound.startsWith("java.lang.Class#forName(")
                && !args.isEmpty()
                && args.getFirst() instanceof Text text
                && !text.value().isBlank()) {
            ClassValue value = new ClassValue(text.value(), text.origin());
            Map<String, Object> declaration = declarations.get(value.name());
            ensureExternal(
                    value.name(),
                    declaration == null
                            ? Json.object("element_kind", "CLASS", "lookup_available", false)
                            : null);
            emit(
                    node,
                    value.name(),
                    "reference",
                    Json.object(
                            "operation",
                            "CLASS_FOR_NAME",
                            "class_name",
                            value.name(),
                            "value_source",
                            text.origin()));
            return value;
        }
        if (receiver instanceof ClassValue type && bound.startsWith("java.lang.Class#")) {
            String name = bound.substring(bound.indexOf('#') + 1, bound.indexOf('('));
            boolean constructor =
                    name.equals("getConstructor") || name.equals("getDeclaredConstructor");
            if (!constructor && !name.equals("getMethod") && !name.equals("getDeclaredMethod"))
                return Unknown.VALUE;
            if (!constructor && (args.isEmpty() || !(args.getFirst() instanceof Text)))
                return Unknown.VALUE;
            String member = constructor ? "<init>" : ((Text) args.getFirst()).value();
            List<String> parameters = parameters(constructor ? args : args.subList(1, args.size()));
            if (parameters == null) return Unknown.VALUE;
            boolean declared = name.startsWith("getDeclared");
            String target = lookup(type.name(), member, parameters, declared, constructor);
            if (target == null) return Unknown.VALUE;
            Handle handle =
                    new Handle(
                            target,
                            type.name(),
                            member,
                            parameters,
                            constructor,
                            declared,
                            constructor ? type.origin() : ((Text) args.getFirst()).origin());
            emit(
                    node,
                    target,
                    "reference",
                    evidence(handle, constructor ? "CONSTRUCTOR_LOOKUP" : "METHOD_LOOKUP"));
            return handle;
        }
        if (receiver instanceof Handle handle
                && !handle.constructor()
                && bound.startsWith("java.lang.reflect.Method#invoke("))
            emit(node, handle.target(), "call", evidence(handle, "METHOD_INVOKE"));
        if (receiver instanceof Handle handle
                && handle.constructor()
                && bound.startsWith("java.lang.reflect.Constructor#newInstance("))
            emit(node, handle.target(), "call", evidence(handle, "CONSTRUCTOR_NEW_INSTANCE"));
        return Unknown.VALUE;
    }

    private static List<String> parameters(List<Value> args) {
        if (args.size() == 1 && args.getFirst() instanceof Classes array)
            return array.values().stream().map(ClassValue::name).toList();
        if (args.size() > 256 || args.stream().anyMatch(value -> !(value instanceof ClassValue)))
            return null;
        return args.stream().map(ClassValue.class::cast).map(ClassValue::name).toList();
    }

    private static Map<String, Object> evidence(Handle handle, String operation) {
        return Json.object(
                "operation",
                operation,
                "class_name",
                handle.owner(),
                "member_name",
                handle.member(),
                "parameter_types",
                handle.parameters(),
                "lookup",
                handle.declared() ? "DECLARED" : "PUBLIC_INHERITED",
                "value_source",
                handle.origin());
    }

    private void emit(
            Map<String, Object> node, String target, String role, Map<String, Object> attributes) {
        String key = site(node) + ':' + role + ':' + target;
        if (!emitted.add(key)) return;
        attributes.put("via", "REFLECTION");
        attributes.put("resolution", "EXACT_LOOKUP");
        attributes.put("binding_origin", "reflection_model");
        attributes.put("runtime_confirmed", false);
        var original = calls.get(site(node));
        String control = original == null ? "" : Json.string(Json.map(original.get("attributes")), "control");
        if (!control.isEmpty()) attributes.put("control", control);
        Json.list(model.get("observations"))
                .add(
                        Json.object(
                                "kind",
                                "reflection",
                                "source",
                                source,
                                "start",
                                node.get("start"),
                                "end",
                                node.get("end"),
                                "parent",
                                node.getOrDefault("owner", ""),
                                "name",
                                attributes.get("operation"),
                                "target",
                                target,
                                "role",
                                role,
                                "attributes",
                                attributes));
    }

    private String lookup(
            String owner,
            String member,
            List<String> parameters,
            boolean declared,
            boolean constructor)
            throws Exception {
        ArrayDeque<List<String>> levels = new ArrayDeque<>();
        levels.add(List.of(owner));
        Set<String> seen = new HashSet<>();
        int visits = 0;
        String suffix = '#' + member + '(' + String.join(",", parameters) + ')';
        while (!levels.isEmpty()) {
            Map<String, Map<String, Object>> matches = new TreeMap<>();
            List<String> parents = new ArrayList<>();
            boolean incompleteParents = false;
            for (String name : levels.removeFirst()) {
                if (!seen.add(name)) continue;
                if (seen.size() > 256) return null;
                Map<String, Object> sourceType = declarations.get(name);
                if (sourceType != null) {
                    for (Map<String, Object> method : members.getOrDefault(name, List.of())) {
                        if (++visits > 100000) return null;
                        Map<String, Object> a = Json.map(method.get("attributes"));
                        if (!Objects.equals(
                                        a.get("element_kind"),
                                        constructor ? "CONSTRUCTOR" : "METHOD")
                                || !Json.string(method, "signature").equals(name + suffix))
                            continue;
                        List<Object> modifiers = Json.list(a.getOrDefault("modifiers", List.of()));
                        if (!declared && !modifiers.contains("public")) continue;
                        if (!declared
                                && seen.size() > 1
                                && "INTERFACE"
                                        .equals(
                                                Json.map(sourceType.get("attributes"))
                                                        .get("element_kind"))
                                && modifiers.contains("static")) continue;
                        matches.put(Json.string(method, "signature"), a);
                    }
                    Map<String, Object> a = Json.map(sourceType.get("attributes"));
                    List<Object> superTypes =
                            new ArrayList<>(
                                    Json.list(a.getOrDefault("interface_type_models", List.of())));
                    if (a.get("superclass_type_model") instanceof Map)
                        superTypes.add(a.get("superclass_type_model"));
                    for (Object raw : superTypes) {
                        Map<String, Object> type = Json.map(raw);
                        if ("NONE".equals(type.get("kind"))) continue;
                        if (!Boolean.TRUE.equals(type.get("complete"))
                                || !"DECLARED".equals(type.get("kind"))) {
                            incompleteParents = true;
                            continue;
                        }
                        parents.add(Json.string(type, "element"));
                    }
                } else {
                    Map<String, Object> binary = binaries.readClass(name);
                    if (binary == null) return null;
                    Map<String, Object> header = Json.map(binary.get("declaration"));
                    if (!Boolean.TRUE.equals(header.get("members_complete"))) return null;
                    Map<String, Object> method =
                            Json.map(binary.get("members")).get(name + suffix) instanceof Map raw
                                    ? Json.map(raw)
                                    : null;
                    if (method != null) {
                        int access = ((Number) method.get("access")).intValue();
                        if (declared
                                || (access & Opcodes.ACC_PUBLIC) != 0
                                        && !(seen.size() > 1
                                                && (((Number) header.get("access")).intValue()
                                                                & Opcodes.ACC_INTERFACE)
                                                        != 0
                                                && (access & Opcodes.ACC_STATIC) != 0)) {
                            List<String> modifiers = new ArrayList<>();
                            if ((access & Opcodes.ACC_PUBLIC) != 0) modifiers.add("public");
                            Map<String, Object> a =
                                    Json.object(
                                            "element_kind",
                                            constructor ? "CONSTRUCTOR" : "METHOD",
                                            "modifiers",
                                            modifiers,
                                            "type_model",
                                            method.get("type_model"),
                                            "binary_class",
                                            header,
                                            "binary_member",
                                            method);
                            matches.put(name + suffix, a);
                        }
                    }
                    for (Object parent : Json.list(header.get("parents"))) {
                        if ((((Number) header.get("access")).intValue() & Opcodes.ACC_INTERFACE)
                                        != 0
                                && "java.lang.Object".equals(parent)) continue;
                        parents.add((String) parent);
                    }
                }
            }
            if (!matches.isEmpty()) {
                if (matches.size() != 1) return null;
                var match = matches.entrySet().iterator().next();
                ensureExternal(match.getKey(), match.getValue());
                return match.getKey();
            }
            if (declared || constructor) return null;
            if (incompleteParents) return null;
            if (!parents.isEmpty()) levels.add(parents);
        }
        return null;
    }

    private void ensureExternal(String signature, Map<String, Object> attributes) {
        if (declarations.containsKey(signature) || !external.add(signature)) return;
        Json.list(model.get("external"))
                .add(
                        Json.object(
                                "signature",
                                signature,
                                "name",
                                signature,
                                "attributes",
                                attributes == null ? Map.of() : attributes));
    }
}
