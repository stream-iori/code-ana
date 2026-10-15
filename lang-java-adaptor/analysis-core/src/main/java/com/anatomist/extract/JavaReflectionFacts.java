package com.anatomist.extract;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/** Bounded constant and handle propagation for compiler-identified core reflection APIs. */
public final class JavaReflectionFacts {
    private static final Set<String> TYPES =
            Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD");
    private static final Set<String> UPDATES =
            Set.of(
                    "PREFIX_INCREMENT",
                    "PREFIX_DECREMENT",
                    "POSTFIX_INCREMENT",
                    "POSTFIX_DECREMENT");
    private static final Map<String, String> PRIMITIVES =
            Map.ofEntries(
                    Map.entry("java.lang.Boolean", "boolean"),
                    Map.entry("java.lang.Byte", "byte"),
                    Map.entry("java.lang.Character", "char"),
                    Map.entry("java.lang.Short", "short"),
                    Map.entry("java.lang.Integer", "int"),
                    Map.entry("java.lang.Long", "long"),
                    Map.entry("java.lang.Float", "float"),
                    Map.entry("java.lang.Double", "double"),
                    Map.entry("java.lang.Void", "void"));

    private sealed interface Value permits Unknown, Text, ClassValue, Classes, Handle {}

    private enum Unknown implements Value {
        INSTANCE
    }

    private record Text(String value, String source) implements Value {}

    private record ClassValue(String name, String source) implements Value {}

    private record Classes(List<ClassValue> values) implements Value {
        Classes {
            values = List.copyOf(values);
        }
    }

    private record Handle(
            JavaReflectionLookup.Target target,
            boolean constructor,
            boolean declared,
            String source)
            implements Value {}

    private final JavaModel model;
    private final JavaIdentities identities;
    private final JavaSiteFacts sites;
    private final JavaReflectionLookup lookup;
    private final Path project;
    private int remaining;

    public JavaReflectionFacts(JavaModel model, Path project, JavaBinaryRepository binaries) {
        this.model = Objects.requireNonNull(model);
        this.identities = new JavaIdentities(model);
        this.sites = new JavaSiteFacts(model);
        this.lookup = new JavaReflectionLookup(model, binaries);
        this.project = project.toAbsolutePath().normalize();
    }

    public void extract(Path path, ExtractionResult result) {
        remaining = 1_000_000;
        for (JavaNode node : model.syntax(path).nodes()) {
            if (node.kind().equals("METHOD") || node.kind().equals("LAMBDA_EXPRESSION")) {
                walk(path, node.tree("getBody"), new State(), result, 0);
            } else if (node.kind().equals("VARIABLE")
                    && model.syntax(path)
                            .parent(node)
                            .map(parent -> TYPES.contains(parent.kind()))
                            .orElse(false)) {
                eval(path, node.tree("getInitializer"), new State(), result, 0);
            } else if (node.kind().equals("BLOCK")
                    && model.syntax(path)
                            .parent(node)
                            .map(parent -> TYPES.contains(parent.kind()))
                            .orElse(false)) {
                walk(path, node, new State(), result, 0);
            }
        }
    }

    private void budget(int depth) {
        if (depth > 256 || --remaining < 0)
            throw new IllegalArgumentException("reflection analysis exceeds work budget");
    }

    private void walk(Path path, JavaNode node, State state, ExtractionResult result, int depth) {
        if (node == null) return;
        budget(depth);
        switch (node.kind()) {
            case "BLOCK" -> {
                Map<String, Value> shadows = new LinkedHashMap<>();
                for (JavaNode statement : node.trees("getStatements")) {
                    if (statement.kind().equals("VARIABLE")) {
                        String name = statement.text("getName");
                        shadows.putIfAbsent(name, state.get(name));
                    }
                    walk(path, statement, state, result, depth + 1);
                }
                shadows.forEach(state::put);
            }
            case "VARIABLE" ->
                    state.put(
                            node.text("getName"),
                            local(
                                    eval(
                                            path,
                                            node.tree("getInitializer"),
                                            state,
                                            result,
                                            depth + 1)));
            case "EXPRESSION_STATEMENT", "RETURN", "THROW", "YIELD" ->
                    eval(path, node.tree("getExpression"), state, result, depth + 1);
            case "IF" -> {
                eval(path, node.tree("getCondition"), state, result, depth + 1);
                State yes = state.copy(), no = state.copy();
                walk(path, node.tree("getThenStatement"), yes, result, depth + 1);
                walk(path, node.tree("getElseStatement"), no, result, depth + 1);
                state.merge(List.of(yes, no));
            }
            case "FOR_LOOP", "ENHANCED_FOR_LOOP", "WHILE_LOOP", "DO_WHILE_LOOP" -> {
                State body = state.copy();
                for (JavaNode initializer : node.trees("getInitializer"))
                    walk(path, initializer, body, result, depth + 1);
                // A source site can execute again after a previous loop iteration's writes.
                invalidateWrites(path, node.tree("getStatement"), body);
                eval(path, node.tree("getCondition"), body, result, depth + 1);
                eval(path, node.tree("getExpression"), body, result, depth + 1);
                JavaNode variable = node.tree("getVariable");
                if (variable != null) body.put(variable.text("getName"), Unknown.INSTANCE);
                walk(path, node.tree("getStatement"), body, result, depth + 1);
                for (JavaNode update : node.trees("getUpdate"))
                    walk(path, update, body, result, depth + 1);
                invalidateWrites(path, node, state);
            }
            case "SWITCH", "SWITCH_EXPRESSION" -> {
                eval(path, node.tree("getExpression"), state, result, depth + 1);
                List<State> branches = new ArrayList<>();
                boolean hasDefault = false;
                State fallthrough = null;
                for (JavaNode branch : node.trees("getCases")) {
                    hasDefault |=
                            branch.values("getExpressions").isEmpty()
                                    && branch.tree("getExpression") == null
                                    && branch.trees("getLabels").stream()
                                            .allMatch(
                                                    value ->
                                                            value.kind()
                                                                    .equals("DEFAULT_CASE_LABEL"));
                    State outcome = state.copy();
                    if (fallthrough != null) outcome.merge(List.of(outcome.copy(), fallthrough));
                    walk(path, branch, outcome, result, depth + 1);
                    branches.add(outcome);
                    fallthrough = fallsThrough(branch) ? outcome.copy() : null;
                }
                if (!hasDefault) branches.add(state.copy());
                state.merge(branches);
            }
            case "CASE" -> {
                for (JavaNode statement : node.trees("getStatements"))
                    walk(path, statement, state, result, depth + 1);
                walk(path, node.tree("getBody"), state, result, depth + 1);
            }
            case "TRY" -> {
                for (JavaNode resource : node.trees("getResources"))
                    walk(path, resource, state, result, depth + 1);
                List<State> outcomes = new ArrayList<>();
                State body = state.copy();
                walk(path, node.tree("getBlock"), body, result, depth + 1);
                outcomes.add(body);
                for (JavaNode caught : node.trees("getCatches")) {
                    State branch = state.copy();
                    JavaNode parameter = caught.tree("getParameter");
                    if (parameter != null) branch.put(parameter.text("getName"), Unknown.INSTANCE);
                    walk(path, caught.tree("getBlock"), branch, result, depth + 1);
                    outcomes.add(branch);
                }
                state.merge(outcomes);
                walk(path, node.tree("getFinallyBlock"), state, result, depth + 1);
            }
            case "SYNCHRONIZED" -> {
                eval(path, node.tree("getExpression"), state, result, depth + 1);
                walk(path, node.tree("getBlock"), state, result, depth + 1);
            }
            case "LABELED_STATEMENT" ->
                    walk(path, node.tree("getStatement"), state, result, depth + 1);
            case "ASSERT" -> {
                eval(path, node.tree("getCondition"), state, result, depth + 1);
                eval(path, node.tree("getDetail"), state, result, depth + 1);
            }
            case "CLASS",
                    "INTERFACE",
                    "ENUM",
                    "ANNOTATION_TYPE",
                    "RECORD",
                    "METHOD",
                    "LAMBDA_EXPRESSION" -> {}
            default -> eval(path, node, state, result, depth + 1);
        }
    }

    private Value eval(Path path, JavaNode node, State state, ExtractionResult result, int depth) {
        if (node == null) return Unknown.INSTANCE;
        budget(depth);
        switch (node.kind()) {
            case "STRING_LITERAL" -> {
                return text(node.text("getValue"), "STRING_LITERAL");
            }
            case "IDENTIFIER" -> {
                return state.get(node.text("getName"));
            }
            case "PARENTHESIZED", "TYPE_CAST" -> {
                return eval(path, node.tree("getExpression"), state, result, depth + 1);
            }
            case "MEMBER_SELECT" -> {
                JavaNode expression = node.tree("getExpression");
                if (node.text("getIdentifier").equals("class") && expression != null) {
                    JavaBinding binding = model.unit(path).bindings().get(expression.id());
                    String name =
                            binding == null
                                    ? "<unresolved>"
                                    : identities.erased(path, binding.expressionType());
                    if (name.equals("<unresolved>") && expression.kind().equals("PRIMITIVE_TYPE"))
                        name = expression.text("getPrimitiveTypeKind").toLowerCase(Locale.ROOT);
                    return name.equals("<unresolved>")
                            ? Unknown.INSTANCE
                            : new ClassValue(lookup.canonical(name), "CLASS_LITERAL");
                }
                eval(path, expression, state, result, depth + 1);
                JavaBinding binding = model.unit(path).bindings().get(node.id());
                if (binding != null
                        && binding.kind().equals("FIELD")
                        && binding.name().equals("TYPE")) {
                    String primitive = PRIMITIVES.get(binding.owner());
                    if (primitive != null) return new ClassValue(primitive, "CLASS_LITERAL");
                }
                return Unknown.INSTANCE;
            }
            case "ASSIGNMENT" -> {
                Value value = eval(path, node.tree("getExpression"), state, result, depth + 1);
                JavaNode variable = node.tree("getVariable");
                if (variable != null && variable.kind().equals("IDENTIFIER"))
                    state.put(variable.text("getName"), local(value));
                else {
                    invalidateArray(path, variable, state, result, depth + 1);
                    eval(path, variable, state, result, depth + 1);
                }
                return value;
            }
            case "PLUS" -> {
                Value left = eval(path, node.tree("getLeftOperand"), state, result, depth + 1);
                Value right = eval(path, node.tree("getRightOperand"), state, result, depth + 1);
                return left instanceof Text a && right instanceof Text b
                        ? text(a.value() + b.value(), "STRING_EXPRESSION")
                        : Unknown.INSTANCE;
            }
            case "CONDITIONAL_AND", "CONDITIONAL_OR" -> {
                eval(path, node.tree("getLeftOperand"), state, result, depth + 1);
                State skipped = state.copy(), evaluated = state.copy();
                eval(path, node.tree("getRightOperand"), evaluated, result, depth + 1);
                state.merge(List.of(skipped, evaluated));
                return Unknown.INSTANCE;
            }
            case "CONDITIONAL_EXPRESSION" -> {
                eval(path, node.tree("getCondition"), state, result, depth + 1);
                State yes = state.copy(), no = state.copy();
                Value a = eval(path, node.tree("getTrueExpression"), yes, result, depth + 1);
                Value b = eval(path, node.tree("getFalseExpression"), no, result, depth + 1);
                state.merge(List.of(yes, no));
                return same(a, b) ? a : Unknown.INSTANCE;
            }
            case "METHOD_INVOCATION" -> {
                JavaNode select = node.tree("getMethodSelect");
                Value receiver =
                        select == null
                                ? Unknown.INSTANCE
                                : eval(
                                        path,
                                        select.tree("getExpression"),
                                        state,
                                        result,
                                        depth + 1);
                List<Value> arguments = new ArrayList<>();
                for (JavaNode argument : node.trees("getArguments"))
                    arguments.add(eval(path, argument, state, result, depth + 1));
                Value value = invoke(path, node, receiver, arguments, result);
                JavaBinding binding = model.unit(path).bindings().get(node.id());
                boolean lookupApi = lookupApi(binding);
                if (!lookupApi)
                    for (Value argument : arguments)
                        if (argument instanceof Classes) state.invalidateAliases(argument);
                return value;
            }
            case "NEW_ARRAY" -> {
                for (JavaNode dimension : node.trees("getDimensions"))
                    eval(path, dimension, state, result, depth + 1);
                if (!(node.fields().get("getInitializers") instanceof List<?>))
                    return Unknown.INSTANCE;
                List<ClassValue> values = new ArrayList<>();
                boolean complete = node.trees("getInitializers").size() <= 256;
                for (JavaNode initializer : node.trees("getInitializers")) {
                    Value value = eval(path, initializer, state, result, depth + 1);
                    if (value instanceof ClassValue type && values.size() < 256) values.add(type);
                    else complete = false;
                }
                return complete ? new Classes(values) : Unknown.INSTANCE;
            }
            case "CLASS",
                    "INTERFACE",
                    "ENUM",
                    "ANNOTATION_TYPE",
                    "RECORD",
                    "METHOD",
                    "LAMBDA_EXPRESSION" -> {
                return Unknown.INSTANCE;
            }
            case "SWITCH_EXPRESSION" -> {
                walk(path, node, state, result, depth + 1);
                return Unknown.INSTANCE;
            }
            default -> {
                for (JavaNode child : children(node)) eval(path, child, state, result, depth + 1);
                if (UPDATES.contains(node.kind()) || node.kind().endsWith("_ASSIGNMENT")) {
                    JavaNode variable =
                            node.tree(
                                    UPDATES.contains(node.kind())
                                            ? "getExpression"
                                            : "getVariable");
                    if (variable != null && variable.kind().equals("IDENTIFIER"))
                        state.put(variable.text("getName"), Unknown.INSTANCE);
                    else invalidateArray(path, variable, state, result, depth + 1);
                }
                return Unknown.INSTANCE;
            }
        }
    }

    private Value invoke(
            Path path,
            JavaNode node,
            Value receiver,
            List<Value> arguments,
            ExtractionResult result) {
        JavaBinding binding = model.unit(path).bindings().get(node.id());
        if (binding == null || !binding.kind().equals("METHOD")) return Unknown.INSTANCE;
        String owner = binding.owner(), name = binding.name();
        if (owner.equals("java.lang.Class")
                && name.equals("forName")
                && !arguments.isEmpty()
                && arguments.getFirst() instanceof Text text) {
            ClassValue value = new ClassValue(lookup.canonical(text.value()), text.source());
            if (model.type(value.name()) instanceof JavaModel.AmbiguousType)
                return Unknown.INSTANCE;
            emit(
                    path,
                    node,
                    value.name(),
                    lookup.internal(value.name()),
                    value.name(),
                    null,
                    null,
                    false,
                    "REFERENCES",
                    "CLASS_FOR_NAME",
                    text.source(),
                    result);
            return value;
        }
        if (owner.equals("java.lang.Class") && receiver instanceof ClassValue type) {
            boolean constructor =
                    name.equals("getConstructor") || name.equals("getDeclaredConstructor");
            if (!constructor && !name.equals("getMethod") && !name.equals("getDeclaredMethod"))
                return Unknown.INSTANCE;
            if (!constructor && (arguments.isEmpty() || !(arguments.getFirst() instanceof Text)))
                return Unknown.INSTANCE;
            String member = constructor ? null : ((Text) arguments.getFirst()).value();
            List<String> parameters =
                    parameters(constructor ? arguments : arguments.subList(1, arguments.size()));
            if (parameters == null) return Unknown.INSTANCE;
            boolean declared = name.startsWith("getDeclared");
            var target = lookup.find(type.name(), member, parameters, declared, constructor);
            if (target == null) return Unknown.INSTANCE;
            String source = constructor ? type.source() : ((Text) arguments.getFirst()).source();
            Handle handle = new Handle(target, constructor, declared, source);
            emitHandle(
                    path,
                    node,
                    handle,
                    "REFERENCES",
                    constructor ? "CONSTRUCTOR_LOOKUP" : "METHOD_LOOKUP",
                    result);
            return handle;
        }
        if (receiver instanceof Handle handle
                && !handle.constructor()
                && owner.equals("java.lang.reflect.Method")
                && name.equals("invoke"))
            emitHandle(path, node, handle, "CALLS", "METHOD_INVOKE", result);
        if (receiver instanceof Handle handle
                && handle.constructor()
                && owner.equals("java.lang.reflect.Constructor")
                && name.equals("newInstance"))
            emitHandle(path, node, handle, "CALLS", "CONSTRUCTOR_NEW_INSTANCE", result);
        return Unknown.INSTANCE;
    }

    private static List<String> parameters(List<Value> values) {
        if (values.size() == 1 && values.getFirst() instanceof Classes classes)
            return classes.values().stream().map(ClassValue::name).toList();
        if (values.size() > 256
                || values.stream().anyMatch(value -> !(value instanceof ClassValue))) return null;
        return values.stream().map(ClassValue.class::cast).map(ClassValue::name).toList();
    }

    private void emitHandle(
            Path path,
            JavaNode node,
            Handle handle,
            String relation,
            String operation,
            ExtractionResult result) {
        var target = handle.target();
        emit(
                path,
                node,
                target.id(),
                target.internal(),
                target.owner(),
                target.member(),
                target.parameters(),
                handle.declared(),
                relation,
                operation,
                handle.source(),
                result);
    }

    private void emit(
            Path path,
            JavaNode node,
            String target,
            boolean internal,
            String className,
            String member,
            List<String> parameters,
            boolean declared,
            String relation,
            String operation,
            String source,
            ExtractionResult result) {
        String owner = identities.owner(path, node).orElse(null);
        if (owner == null || !node.span().present() || target.isBlank()) return;
        Edge edge = new Edge();
        edge.sourceId = owner;
        edge.relation = relation;
        edge.confidence = GraphConstants.Confidence.INFERRED;
        edge.callKind = relation.equals("CALLS") ? GraphConstants.CallKind.REFLECTION : null;
        edge.sourceFile = project.relativize(path).toString().replace('\\', '/');
        edge.producerId = ProducerIds.JAVA_CORE;
        edge.context = sites.control(path, node);
        sites.coordinates(edge, path, node.span());
        edge.receiverStaticType = relation.equals("CALLS") ? className : null;
        if (internal) edge.targetId = target;
        else {
            edge.isExternal = true;
            edge.externalTargetFqn = target;
            edge.resolution = GraphConstants.Resolution.REFLECTION;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("via", GraphConstants.MetadataVia.REFLECTION);
        metadata.put("operation", operation);
        metadata.put("resolution", "EXACT");
        metadata.put("class_name", className);
        if (member != null) metadata.put("member_name", member);
        if (parameters != null) {
            metadata.put("parameter_types", parameters);
            metadata.put("lookup", declared ? "DECLARED" : "PUBLIC_INHERITED");
        }
        metadata.put("value_source", source);
        edge.metadata = Json.writeCompact(metadata);
        result.edges.add(edge);
    }

    private void invalidateWrites(Path path, JavaNode node, State state) {
        if (node == null) return;
        ArrayDeque<JavaNode> pending = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        pending.add(node);
        while (!pending.isEmpty()) {
            JavaNode current = pending.removeFirst();
            if (!seen.add(current.id())) continue;
            budget(0);
            if (TYPES.contains(current.kind())
                    || current.kind().equals("METHOD")
                    || current.kind().equals("LAMBDA_EXPRESSION")) continue;
            if (current.kind().equals("ASSIGNMENT")
                    || current.kind().endsWith("_ASSIGNMENT")
                    || UPDATES.contains(current.kind())) {
                JavaNode variable =
                        current.tree(
                                UPDATES.contains(current.kind()) ? "getExpression" : "getVariable");
                if (variable != null && variable.kind().equals("IDENTIFIER"))
                    state.put(variable.text("getName"), Unknown.INSTANCE);
                else if (variable != null && variable.kind().equals("ARRAY_ACCESS"))
                    state.invalidateArrays();
            }
            if (current.kind().equals("METHOD_INVOCATION")
                    && !lookupApi(model.unit(path).bindings().get(current.id())))
                for (JavaNode argument : current.trees("getArguments"))
                    if (argument.kind().equals("IDENTIFIER")
                            && state.get(argument.text("getName")) instanceof Classes value)
                        state.invalidateAliases(value);
            pending.addAll(children(current));
        }
    }

    private static boolean lookupApi(JavaBinding binding) {
        return binding != null
                && binding.owner().equals("java.lang.Class")
                && Set.of(
                                "getMethod",
                                "getDeclaredMethod",
                                "getConstructor",
                                "getDeclaredConstructor")
                        .contains(binding.name());
    }

    private static boolean fallsThrough(JavaNode branch) {
        if (branch.text("getCaseKind").equals("RULE")) return false;
        List<JavaNode> statements = branch.trees("getStatements");
        return statements.isEmpty()
                || !Set.of("BREAK", "RETURN", "THROW").contains(statements.getLast().kind());
    }

    private void invalidateArray(
            Path path, JavaNode variable, State state, ExtractionResult result, int depth) {
        if (variable != null && variable.kind().equals("ARRAY_ACCESS")) {
            Value value = eval(path, variable.tree("getExpression"), state, result, depth + 1);
            if (value instanceof Classes) state.invalidateAliases(value);
        }
    }

    private static List<JavaNode> children(JavaNode node) {
        Map<Integer, JavaNode> children = new LinkedHashMap<>();
        for (Object value : node.fields().values()) {
            if (value instanceof JavaNode child) children.put(child.id(), child);
            else if (value instanceof List<?> values)
                for (Object item : values)
                    if (item instanceof JavaNode child) children.put(child.id(), child);
        }
        return List.copyOf(children.values());
    }

    private static Value text(String value, String source) {
        return value.length() <= 4096 ? new Text(value, source) : Unknown.INSTANCE;
    }

    private static Value local(Value value) {
        return switch (value) {
            case Text text -> new Text(text.value(), "LOCAL_CONSTANT");
            case ClassValue type -> new ClassValue(type.name(), "LOCAL_HANDLE");
            case Handle handle ->
                    new Handle(
                            handle.target(),
                            handle.constructor(),
                            handle.declared(),
                            "LOCAL_HANDLE");
            default -> value;
        };
    }

    private static boolean same(Value left, Value right) {
        return local(left).equals(local(right));
    }

    private static final class State {
        private final Map<String, Value> values = new LinkedHashMap<>();

        Value get(String name) {
            return values.getOrDefault(name, Unknown.INSTANCE);
        }

        void put(String name, Value value) {
            if (value == Unknown.INSTANCE) values.remove(name);
            else if (values.containsKey(name) || values.size() < 4096) values.put(name, value);
        }

        State copy() {
            State copy = new State();
            copy.values.putAll(values);
            return copy;
        }

        void invalidateAliases(Value value) {
            values.entrySet().removeIf(entry -> entry.getValue().equals(value));
        }

        void invalidateArrays() {
            values.values().removeIf(value -> value instanceof Classes);
        }

        void merge(List<State> outcomes) {
            if (outcomes.isEmpty()) return;
            values.clear();
            values.putAll(outcomes.getFirst().values);
            for (int i = 1; i < outcomes.size(); i++) {
                State other = outcomes.get(i);
                values.entrySet()
                        .removeIf(entry -> !same(entry.getValue(), other.get(entry.getKey())));
            }
        }
    }
}
