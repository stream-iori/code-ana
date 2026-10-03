package dev.codeana.analysis;

import static dev.codeana.analysis.TypeOperations.*;

import dev.codeana.common.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Bounded selection from detached source/overlay/binary declarations; never changes javac evidence.
 */
final class CallRecovery {
    private final Map<String, Object> model;
    private final Map<String, Map<String, Object>> known = new HashMap<>(),
            sources = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> members = new HashMap<>();

    private record NodeKey(String source, long id) {}

    private final Map<NodeKey, Integer> nodes = new HashMap<>();
    private final Map<NodeKey, Map<String, Object>> calls = new LinkedHashMap<>();
    private final Map<NodeKey, Result> results = new HashMap<>();
    // Candidate probes must not publish recoveries, external symbols, or cached guesses.
    private final Deque<Map<NodeKey, Result>> provisionalResults = new ArrayDeque<>();
    private final Map<NodeKey, Map<String, Object>> expectedOverrides = new HashMap<>();
    private final Map<NodeKey, String> inferenceOwners = new HashMap<>();
    private final Set<NodeKey> modeledExpected = new HashSet<>();
    private final Set<String> modeledNames = new HashSet<>();
    private final Set<NodeKey> active = new HashSet<>();
    private final List<Object> syntax;
    private final BinaryIndex binary;
    private final TypeOperations types;
    private int work, uncertainPhase;
    private String currentSource;

    private record Result(Map<String, Object> type, boolean modeled,
            List<Map<String, Object>> parameters, String target, boolean blocked) {
        Result(Map<String, Object> type, boolean modeled, List<Map<String, Object>> parameters, String target) {
            this(type, modeled, parameters, target, false);
        }
        Result(Map<String, Object> type, boolean modeled) { this(type, modeled, List.of(), ""); }
        Result(Map<String, Object> type, boolean modeled, List<Map<String, Object>> parameters) {
            this(type, modeled, parameters, "");
        }
    }

    private record Candidate(
            Map<String, Object> declaration,
            Map<String, Object> returns,
            List<Map<String, Object>> parameters,
            int phase,
            boolean modeled,
            boolean modeledInput,
            String origin,
            Map<String, Object> varargElement) {}

    private record Located(Map<String, Object> use, int depth) {}

    private record Sam(List<Map<String, Object>> parameters, Map<String, Object> returns) {}
    private record PolyMatch(Match match, boolean modeledInput) {}

    static void recover(Map<String, Object> model, Map<String, Object> context, Path javaHome)
            throws IOException {
        if (Json.list(model.get("declarations")).stream()
                .map(Json::map)
                .noneMatch(
                        d -> "lombok_model".equals(map(d.get("attributes")).get("binding_origin"))))
            return;
        try (BinaryIndex binary = new BinaryIndex(context, javaHome)) {
            CallRecovery recovery = new CallRecovery(model, binary);
            new ModeledOverrides(model, context, recovery.sources, recovery.members, recovery.known,
                    recovery.types, binary).generate();
            for (Map<String, Object> call : recovery.calls.values()) {
                recovery.work = 0;
                recovery.types.reset();
                recovery.currentSource = text(call.get("source"));
                try {
                    recovery.warmBuilderReceiver(call);
                    Result selected = recovery.resolve(call, 0);
                    recovery.work = 0;
                    recovery.types.reset();
                    boolean input = recovery.modeledInputs(recovery.node(id(map(call.get("attributes")).get("tree_id"))), 0);
                    if (Boolean.TRUE.equals(call.get("resolved")) && !call.containsKey("modeled_target") && input)
                        map(call.get("attributes")).put(selected.blocked
                                ? "recovery_target_blocked" : "recovery_original_selected_modeled_input", true);
                } catch (TypeOperations.Budget exhausted) {
                    map(call.get("attributes")).put("recovery_failure", "recovery_budget");
                    map(call.get("attributes")).put("recovery_target_blocked", true);
                }
            }
            recovery.recoverCallbacks();
            recovery.recoverEnumLabels();
        }
    }

    /** Keep recovered callback descriptors separate from the original javac attributes. */
    private void recoverCallbacks() throws IOException {
        Map<NodeKey,Map<String,Object>> callbacks=new HashMap<>();
        for(Object raw:syntax) {
            Map<String,Object> site=map(raw);
            if("LAMBDA_EXPRESSION".equals(site.get("kind")))
                callbacks.put(new NodeKey(text(site.get("source")),id(site.get("start"))),site);
        }
        for (Object raw : list(model.get("declarations"))) {
            Map<String,Object> declaration = map(raw), attributes = map(declaration.get("attributes"));
            if (!"LAMBDA".equals(attributes.get("element_kind"))
                    || "DECLARED".equals(kind(map(attributes.get("functional_type_model"))))) continue;
            currentSource = text(declaration.get("source")); work = 0; types.reset();
            Map<String,Object> site = callbacks.getOrDefault(new NodeKey(currentSource,id(declaration.get("start"))),Map.of());
            if (!"LAMBDA_EXPRESSION".equals(site.get("kind"))) continue;
            try {
                Map<String,Object> functional = expected(site, attributes, 0);
                Sam descriptor = sam(functional, 0);
                if (descriptor == null || descriptor.parameters.size()
                        != list(map(site.get("roles")).get("parameters")).size()) continue;
                attributes.put("recovered_callback", Json.object("functional_type_model", functional,
                        "parameter_type_models", descriptor.parameters,
                        "binding_origin", "source_recovery", "evidence_complete", false));
            } catch (TypeOperations.Budget exhausted) {
                attributes.put("callback_recovery_failure", "recovery_budget");
            }
        }
    }

    private CallRecovery(Map<String, Object> model, BinaryIndex binary) {
        this.model = model;
        this.binary = binary;
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> d = Json.map(raw);
            String signature = text(d.get("signature"));
            known.put(signature, d);
            String kind = text(map(d.get("attributes")).get("element_kind"));
            if (Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(kind))
                sources.put(signature, d);
            if (Set.of("METHOD", "CONSTRUCTOR").contains(kind))
                members.computeIfAbsent(text(d.get("parent")), unused -> new ArrayList<>()).add(d);
            if ("lombok_model".equals(map(d.get("attributes")).get("binding_origin")))
                modeledNames.add(text(d.get("name")));
        }
        syntax = Json.list(model.get("syntax"));
        for (int i = 0; i < syntax.size(); i++) {
            Map<String, Object> node = Json.map(syntax.get(i));
            nodes.put(
                    new NodeKey(text(node.get("source")), ((Number) node.get("id")).longValue()),
                    i);
        }
        for (Object raw : Json.list(model.get("observations"))) {
            Map<String, Object> call = Json.map(raw);
            if ("call".equals(call.get("kind"))
                    && map(call.get("attributes")).get("tree_id") instanceof Number id)
                calls.put(new NodeKey(text(call.get("source")), id.longValue()), call);
        }
        types = new TypeOperations(sources, binary);
    }

    // Warm only a generated builder receiver chain. Poly arguments keep their
    // original candidate/context schedule; the recursion/work limits stay fixed.
    private void warmBuilderReceiver(Map<String,Object> call) throws IOException {
        List<Map<String,Object>> chain=new ArrayList<>();
        Map<String,Object> tree=node(id(map(call.get("attributes")).get("tree_id")));
        while(true) {
            step(0);
            Map<String,Object> selection=child(tree,"selection");
            if(!"MEMBER_SELECT".equals(selection.get("kind")))break;
            tree=child(selection,"expression");
            if(!"METHOD_INVOCATION".equals(tree.get("kind")))break;
            Map<String,Object> receiver=calls.get(key(tree));
            if(receiver==null)break;
            chain.add(receiver);
            if(chain.size()>=4096)throw new TypeOperations.Budget();
        }
        if(chain.size()<8)return;
        Result factory=resolve(chain.getLast(),0);
        Map<String,Object> declaration=sources.get(text(factory.type.get("element")));
        if(declaration==null || !Set.of("builder","super_builder").contains(
                text(map(declaration.get("attributes")).get("modeled_capability"))))return;
        for(int i=chain.size()-2;i>=0;i--)resolve(chain.get(i),0);
    }

    private void step(int depth) {
        if (++work > 100_000 || depth >= 64 || Thread.currentThread().isInterrupted())
            throw new TypeOperations.Budget();
    }

    private Map<String, Object> node(long id) {
        Integer position = nodes.get(new NodeKey(currentSource, id));
        return position == null ? Map.of() : Json.map(syntax.get(position));
    }

    private static long id(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private Map<String, Object> child(Map<String, Object> node, String role) {
        return node(id(map(node.get("roles")).get(role)));
    }

    private void recoverEnumLabels() throws IOException {
        Set<NodeKey> labels = new HashSet<>();
        for (Object raw : syntax) {
            Map<String,Object> label = Json.map(raw);
            if (!"IDENTIFIER".equals(label.get("kind"))) continue;
            currentSource = text(label.get("source"));
            Map<String,Object> branch = node(id(label.get("parent")));
            if ("CASE".equals(branch.get("kind"))
                    && id(map(branch.get("roles")).get("expression")) == id(label.get("id")))
                labels.add(key(label));
        }
        if (labels.isEmpty()) return;
        Map<NodeKey, Map<String, Object>> references = new HashMap<>();
        for (Object raw : Json.list(model.get("observations"))) {
            Map<String,Object> value = Json.map(raw);
            NodeKey key = new NodeKey(text(value.get("source")),id(map(value.get("attributes")).get("tree_id")));
            if ("reference".equals(value.get("kind")) && labels.contains(key)) references.put(key,value);
        }
        for (Object raw : syntax) {
            Map<String,Object> label = Json.map(raw);
            if (!"IDENTIFIER".equals(label.get("kind"))) continue;
            currentSource = text(label.get("source"));
            Map<String,Object> branch = node(id(label.get("parent")));
            if (!"CASE".equals(branch.get("kind"))
                    || id(map(branch.get("roles")).get("expression")) != id(label.get("id"))) continue;
            Map<String,Object> selection = node(id(branch.get("parent")));
            if (!"SWITCH".equals(selection.get("kind"))) continue;
            Map<String,Object> selector = child(selection,"expression");
            while ("PARENTHESIZED".equals(selector.get("kind"))) selector = child(selector,"expression");
            if (!"METHOD_INVOCATION".equals(selector.get("kind"))) continue;
            Map<String,Object> call = calls.get(key(selector));
            if (call == null) continue;
            Map<String,Object> existing = references.get(key(label));
            Map<String,Object> declared = existing == null ? null : known.get(text(existing.get("target")));
            if (declared != null && "ENUM_CONSTANT".equals(map(declared.get("attributes")).get("element_kind"))
                    && !Boolean.FALSE.equals(existing.get("resolved"))) continue;
            work = 0; types.reset();
            try {
                Result selected = resolve(call,0);
                if (!selected.modeled || !kind(selected.type).equals("DECLARED")) continue;
                String owner = text(selected.type.get("element"));
                Map<String,Object> enumeration = sources.get(owner);
                if (enumeration == null || !"ENUM".equals(map(enumeration.get("attributes")).get("element_kind"))) continue;
                String target = owner+'#'+text(label.get("name"));
                Map<String,Object> constant = known.get(target);
                if (constant == null || !"ENUM_CONSTANT".equals(map(constant.get("attributes")).get("element_kind"))) continue;
                Map<String,Object> recovered = Json.object("kind","reference","source",currentSource,
                        "start",label.get("start"),"end",label.get("end"),"parent",label.get("owner"),
                        "name",label.get("name"),"target",target,"resolved",false,"complete",false,
                        "attributes",Json.object("tree_id",label.get("id"),"reference_kind","enum_constant",
                                "field_access","read","binding_origin","modeled_enum_label",
                                "selector_tree_id",selector.get("id")));
                Json.list(model.get("observations")).add(recovered);
            } catch (TypeOperations.Budget exhausted) {
                map(call.get("attributes")).put("recovery_failure","recovery_budget");
            }
        }
    }

    private NodeKey key(Map<String, Object> tree) {
        return new NodeKey(currentSource, id(tree.get("id")));
    }

    private Map<NodeKey, Result> resultCache() {
        return provisionalResults.isEmpty() ? results : provisionalResults.peek();
    }

    private Result expression(long id, int depth) throws IOException {
        step(depth);
        NodeKey key = new NodeKey(currentSource, id);
        Map<String, Object> node = node(id);
        String kind = text(node.get("kind"));
        if (Set.of("LAMBDA_EXPRESSION", "MEMBER_REFERENCE").contains(kind))
            return new Result(Json.object("kind", "POLY", "tree_id", id), false);
        if (calls.containsKey(key)) return resolve(calls.get(key), depth + 1);
        if (kind.equals("PARENTHESIZED"))
            return expression(id(map(node.get("roles")).get("expression")), depth + 1);
        if (kind.equals("ARRAY_ACCESS")) {
            Result array = expression(id(map(node.get("roles")).get("expression")), depth + 1);
            return new Result(map(array.type.get("component")), array.modeled);
        }
        // Anatomist's limited expression recovery: no numeric promotion, array branch
        // merging or unrelated branch LUB. Missing operands never become valid evidence.
        if (kind.equals("CONDITIONAL_EXPRESSION")) {
            Result condition = expression(id(child(node, "condition").get("id")), depth + 1);
            if (!booleanValue(condition.type, depth + 1)) return new Result(Map.of(), false);
            Result left = expression(id(child(node, "then").get("id")), depth + 1);
            Result right = expression(id(child(node, "else").get("id")), depth + 1);
            boolean modeled = condition.modeled || left.modeled || right.modeled;
            if (!left.type.isEmpty() && !right.type.isEmpty()) {
                String a = kind(left.type), b = kind(right.type);
                if (!Set.of("ARRAY", "VOID").contains(a)
                        && types.same(left.type, right.type, 0) == Match.YES)
                    return new Result(left.type, modeled);
                if (a.equals("NULL") && b.equals("DECLARED"))
                    return new Result(right.type, modeled);
                if (b.equals("NULL") && a.equals("DECLARED"))
                    return new Result(left.type, modeled);
            }
            return new Result(attributedExpression(node), modeled);
        }
        if (kind.equals("LOGICAL_COMPLEMENT")) {
            Result value = expression(id(child(node, "expression").get("id")), depth + 1);
            return new Result(booleanValue(value.type, depth + 1) ? scalar("BOOLEAN") : Map.of(),
                    value.modeled);
        }
        if (Set.of("PLUS", "LESS_THAN", "GREATER_THAN", "LESS_THAN_EQUAL", "GREATER_THAN_EQUAL",
                "CONDITIONAL_AND", "CONDITIONAL_OR", "EQUAL_TO", "NOT_EQUAL_TO").contains(kind)) {
            Result left = expression(id(child(node, "left").get("id")), depth + 1);
            Result right = expression(id(child(node, "right").get("id")), depth + 1);
            boolean modeled = left.modeled || right.modeled;
            Map<String, Object> a = types.readable(left.type, 0);
            Map<String, Object> b = types.readable(right.type, 0);
            if (a.isEmpty() || b.isEmpty()
                    || Set.of("ERROR", "UNKNOWN", "NONE", "VOID").contains(kind(a))
                    || Set.of("ERROR", "UNKNOWN", "NONE", "VOID").contains(kind(b)))
                return new Result(Map.of(), modeled);
            if (kind.equals("PLUS"))
                return new Result("java.lang.String".equals(a.get("element"))
                                || "java.lang.String".equals(b.get("element"))
                        ? named("java.lang.String") : attributedExpression(node), modeled);
            if (Set.of("LESS_THAN", "GREATER_THAN", "LESS_THAN_EQUAL", "GREATER_THAN_EQUAL")
                    .contains(kind))
                return new Result(numeric(a) && numeric(b) ? scalar("BOOLEAN") : Map.of(), modeled);
            if (Set.of("CONDITIONAL_AND", "CONDITIONAL_OR").contains(kind))
                return new Result(booleanValue(a, depth + 1) && booleanValue(b, depth + 1)
                        ? scalar("BOOLEAN") : Map.of(), modeled);
            boolean primitives = TypeOperations.primitive(kind(a)) || TypeOperations.primitive(kind(b));
            boolean compatible = primitives
                    ? numeric(a) && numeric(b) || booleanValue(a, depth + 1) && booleanValue(b, depth + 1)
                    : types.convert(a, b, false) == Match.YES || types.convert(b, a, false) == Match.YES;
            return new Result(compatible ? scalar("BOOLEAN") : Map.of(), modeled);
        }
        if (kind.equals("IDENTIFIER")) {
            Result parameter = lambdaParameter(node, depth + 1);
            if (parameter != null) return parameter;
        }
        Map<String, Object> attributed = attributedExpression(node);
        if (!attributed.isEmpty()) return new Result(attributed, false);
        Map<String, Object> declaration = known.get(text(node.get("symbol")));
        if (declaration != null) {
            Map<String,Object> attributes=map(declaration.get("attributes"));
            Map<String,Object> proof=map(attributes.get("supplemental_variable"));
            if (!proof.isEmpty()) return new Result(map(proof.get("type_model")),true);
            return new Result(map(attributes.get("type_model")), false);
        }
        if (kind.equals("NULL_LITERAL"))
            return new Result(Json.object("kind", "NULL", "complete", true), false);
        if (kind.equals("STRING_LITERAL")) return new Result(named("java.lang.String"), false);
        if (kind.endsWith("_LITERAL"))
            return new Result(
                    Json.object("kind", kind.substring(0, kind.indexOf('_')), "complete", true),
                    false);
        return new Result(Map.of(), false);
    }

    private Map<String, Object> attributedExpression(Map<String,Object> node) {
        Map<String,Object> type=map(node.get("expression_type_model"));
        if (Set.of("ERROR", "UNKNOWN", "NONE").contains(kind(type))) {
            Map<String, Object> supplemental = SupplementalExpressions.type(model, node);
            if (!supplemental.isEmpty()) return supplemental;
        }
        return Set.of("ERROR","NONE").contains(kind(type)) ? Map.of() : type;
    }

    private static Map<String, Object> scalar(String kind) {
        return Json.object("version", 2, "kind", kind, "complete", true);
    }

    private boolean booleanValue(Map<String, Object> type, int depth) throws IOException {
        Map<String, Object> readable = types.readable(type, 0);
        return kind(readable).equals("BOOLEAN") || "java.lang.Boolean".equals(readable.get("element"));
    }

    private static boolean numeric(Map<String, Object> type) {
        return Set.of("BYTE", "SHORT", "CHAR", "INT", "LONG", "FLOAT", "DOUBLE").contains(kind(type))
                || Set.of("java.lang.Byte", "java.lang.Short", "java.lang.Character", "java.lang.Integer",
                        "java.lang.Long", "java.lang.Float", "java.lang.Double").contains(text(type.get("element")));
    }

    private Result resolve(Map<String, Object> call, int depth) throws IOException {
        // A call starts its own expression walk; nested call count is bounded separately.
        depth = 0;
        step(depth);
        if (!provisionalResults.isEmpty()) {
            call = new TreeMap<>(call);
            call.put("attributes", new TreeMap<>(map(call.get("attributes"))));
        }
        Map<String, Object> attrs = map(call.get("attributes"));
        long id = id(attrs.get("tree_id"));
        boolean javacResolved = Boolean.TRUE.equals(call.get("resolved"));
        NodeKey key = new NodeKey(currentSource, id);
        Result old = resultCache().get(key);
        if (old != null) return old;
        if (active.size() >= 64) throw new TypeOperations.Budget();
        if (!active.add(key)) return new Result(Map.of(), false);
        if (provisionalResults.isEmpty() && call.remove("modeled_target") != null) {
            attrs.keySet().removeIf(name -> name.startsWith("recovery_")
                    || name.startsWith("recovered_") || name.equals("binding_origin"));
        }
        int previousUncertainPhase = uncertainPhase;
        boolean provisional = !provisionalResults.isEmpty();
        Map<String, Object> originalAttributes = provisional ? new TreeMap<>(attrs) : null;
        Object originalTarget = call.get("modeled_target");
        try {
            if (javacResolved && !("MEMBER_REFERENCE".equals(node(id).get("kind"))
                            && (expectedOverrides.containsKey(key) || modeledReferenceContext(node(id), depth + 1)))
                    && !(diamond(node(id)) && (expectedOverrides.containsKey(key) || modeledReferenceContext(node(id), depth + 1)))
                    && !(expectedOverrides.containsKey(key) && genericInvocation(call, depth + 1))
                    && !potential(node(id), 0)
                    && !modeledInputs(node(id), 0)) {
                Result result = attributedResult(attrs);
                resultCache().put(key, result);
                return result;
            }
            String failure = text(attrs.get("binding_failure"));
            if (Set.of("inaccessible", "static_context").contains(failure))
                return unavailable(attrs, failure);
            Map<String, Object> tree = node(id), selection = child(tree, "selection");
            boolean reference = text(tree.get("kind")).equals("MEMBER_REFERENCE");
            String name =
                    reference
                            ? "NEW".equals(attrs.get("reference_mode"))
                                    ? "<init>"
                                    : text(tree.get("name"))
                            : text(attrs.get("lookup_name"));
            if (name.isEmpty()) return unavailable(attrs, "unsupported_site");
            Map<String, Object> receiver = map(attrs.get("receiver_type_model"));
            // Model evidence may survive an unavailable receiver invocation.
            boolean input = modeledInputs(tree, 0)
                    || reference && (modeledExpected.contains(key)
                            || modeledReferenceContext(tree, depth + 1));
            if ("MEMBER_SELECT".equals(selection.get("kind"))) {
                Result value =
                        expression(id(map(selection.get("roles")).get("expression")), depth + 1);
                if (value.blocked) return unavailable(attrs, "receiver_selection_blocked");
                if (value.modeled || receiver.isEmpty() || kind(receiver).equals("ERROR"))
                    receiver = value.type;
                if (value.modeled) attrs.put("recovery_receiver_type_qualifier", false);
                input |= value.modeled;
            }
            boolean diamond = diamond(tree);
            if (diamond) {
                Map<String, Object> targetType = writtenExpected(tree, depth + 1);
                if (!targetType.isEmpty()) {
                    Map<String, Object> rawReceiver = new TreeMap<>(receiver);
                    rawReceiver.put("arguments", List.of());
                    Map<String, Object> inferred = constructorReceiver(rawReceiver, targetType, depth + 1);
                    List<Object> formals = list(types.attributes(text(receiver.get("element"))).get("type_parameter_models"));
                    if (list(inferred.get("arguments")).size() == formals.size()
                            && types.convert(inferred, targetType, false) == Match.YES) receiver = inferred;
                    else if (list(receiver.get("arguments")).size() != formals.size()
                            || types.convert(receiver, targetType, false) != Match.YES
                            || list(receiver.get("arguments")).stream().anyMatch(v -> !Boolean.TRUE.equals(map(v).get("complete"))))
                        return unavailable(attrs, "constructor_target_unavailable");
                }
            }
            Sam sam = null;
            boolean typeReference = false;
            if (reference) {
                Map<String, Object> qualifier = child(tree, "qualifier");
                Result value = expression(id(map(tree.get("roles")).get("qualifier")), depth + 1);
                receiver = value.modeled ? value.type : map(attrs.get("qualifier_type_model"));
                input |= value.modeled;
                Map<String, Object> target = known.get(text(qualifier.get("symbol")));
                typeReference =
                        !value.modeled
                                && ("PARAMETERIZED_TYPE".equals(qualifier.get("kind"))
                                        || target != null
                                                && sources.containsKey(
                                                        text(target.get("signature")))
                                        || !text(qualifier.get("symbol")).isEmpty()
                                                && text(qualifier.get("symbol"))
                                                        .equals(receiver.get("element")));
                attrs.put(
                        "recovery_receiver_type_qualifier",
                        false); // Instance reference legality is checked against the SAM below.
                sam = sam(expected(tree, attrs, depth + 1), depth + 1);
                if (sam == null) return unavailable(attrs, "functional_descriptor_unavailable");
                if (name.equals("<init>") && typeReference)
                    receiver = constructorReceiver(receiver, sam.returns, depth + 1);
            }
            if (receiver.isEmpty() && !name.equals("<init>")) {
                // An unqualified static import can retain a selected declaration even
                // when a modeled argument prevented javac attribution of the invocation.
                String selected = text(attrs.get("javac_selected_declaration"));
                int separator = selected.lastIndexOf('#');
                if (separator > 0 && selected.substring(separator + 1).startsWith(name + "(")) {
                    String declaringType = selected.substring(0, separator);
                    Map<String,Object> declaration = known.get(selected);
                    boolean statics = declaration != null
                            && list(map(declaration.get("attributes")).get("modifiers")).contains("static");
                    if (declaration == null) {
                        Map<String,Object> found = binary.readClass(declaringType);
                        Map<String,Object> member = found == null ? Map.of() : map(map(found.get("members")).get(selected));
                        statics = "METHOD".equals(member.get("kind"))
                                && member.get("access") instanceof Number access && (access.intValue() & 8) != 0;
                    }
                    if (statics) {
                        receiver = named(declaringType);
                        attrs.put("recovery_receiver_type_qualifier", true);
                        attrs.put("recovery_static_owner_origin", "javac_selected_declaration");
                    }
                }
                if (receiver.isEmpty()) receiver = named(text(attrs.get("caller_type")));
            }
            boolean uninstantiatedMethodReceiver = kind(receiver).equals("TYPEVAR")
                    && "binary_signature".equals(receiver.get("origin"))
                    && text(receiver.get("owner")).contains("#");
            receiver = types.readable(receiver, 0);
            if (!kind(receiver).equals("DECLARED"))
                return unavailable(attrs, "receiver_unavailable");
            List<Object> argumentNodes = list(map(tree.get("roles")).get("arguments"));
            List<Object> attributed = list(attrs.get("argument_type_models"));
            List<Map<String, Object>> arguments = new ArrayList<>();
            for (int i = 0; i < attributed.size(); i++) {
                Map<String, Object> argument = map(attributed.get(i));
                if (i < argumentNodes.size()) {
                    Result value = expression(id(argumentNodes.get(i)), depth + 1);
                    // NONE/UNKNOWN carry no argument proof; preserve the original evidence,
                    // but use a checked expression type for applicability as the frozen resolver does.
                    if (value.modeled || Set.of("ERROR", "UNKNOWN", "NONE", "").contains(kind(argument))
                            || kind(value.type).equals("POLY")) argument = value.type;
                    Map<String, Object> nested = calls.get(new NodeKey(currentSource, id(argumentNodes.get(i))));
                    if (nested != null && (genericInvocation(nested, depth + 1)
                            || value.modeled && mentionsFormal(value.type, value.target.isEmpty()
                                    ? text(nested.get("modeled_target")) : value.target, depth + 1))) {
                        argument = new TreeMap<>(argument);
                        argument.put("poly_invocation_id", id(argumentNodes.get(i)));
                    }
                    if (diamond(node(id(argumentNodes.get(i))))) {
                        argument = new TreeMap<>(argument);
                        argument.put("diamond_constructor_id", id(argumentNodes.get(i)));
                    }
                    boolean changedType = value.modeled;
                    // A replaced constructor can leave its instance type unchanged.
                    // Generated methods still influence overload recovery, even when
                    // javac selected Object.toString with the same return type.
                    if (changedType && nested != null && "constructor".equals(
                            map(nested.get("attributes")).get("mechanism"))) {
                        Map<String,Object> originalType = map(map(nested.get("attributes")).get("expression_type_model"));
                        if (Boolean.TRUE.equals(originalType.get("complete"))
                                && Boolean.TRUE.equals(value.type.get("complete"))
                                && types.same(originalType, value.type, 0) == Match.YES)
                            changedType = false;
                    }
                    input |= changedType;
                }
                arguments.add(argument);
            }
            if (reference) arguments.addAll(sam.parameters);
            // javac can select Object.toString or a raw JDK member after an earlier
            // modeled invocation failed. Keep that evidence, but recover the actual use.
            Map<String, Object> owner = sources.get(text(receiver.get("element")));
            boolean retiredConstructor =
                    name.equals("<init>")
                            && owner != null
                            && Boolean.TRUE.equals(
                                    LombokModel.summary(owner).get("constructor_replaced"));
            List<Object> explicitTypes = list(attrs.get("explicit_type_argument_models"));
            if (explicitTypes.size() != list(map(tree.get("roles")).get("type_arguments")).size())
                return unavailable(attrs, "explicit_type_arguments_unavailable");
            for (Object explicit : explicitTypes)
                if (!explicitType(map(explicit), depth + 1))
                    return unavailable(attrs, "explicit_type_arguments_unavailable");
            List<Candidate> applicable = new ArrayList<>();
            uncertainPhase = Integer.MAX_VALUE;
            ArrayDeque<Located> queue = new ArrayDeque<>();
            queue.add(new Located(receiver, 0));
            Set<String> seen = new HashSet<>();
            Map<String, Integer> hidden = new HashMap<>();
            boolean uncertain = false;
            while (!queue.isEmpty()) {
                step(depth);
                Located location = queue.removeFirst();
                Map<String, Object> use = location.use;
                String className = text(use.get("element"));
                if (!seen.add(TypeOperations.identity(use))) continue;
                if (seen.size() > 256) throw new TypeOperations.Budget();
                if (types.attributes(className).isEmpty()) {
                    uncertain = true;
                    continue;
                }
                List<Map<String, Object>> declarations = members.getOrDefault(className, List.of());
                if (!sources.containsKey(className)) {
                    Map<String, Object> found = binary.readClass(className);
                    if (found == null
                            || Boolean.FALSE.equals(
                                    map(found.get("declaration")).get("members_complete"))) {
                        uncertain = true;
                        continue;
                    }
                    declarations = binaryMembers(className, found);
                }
                Map<String, Map<String, Object>> variables =
                        types.bindings(use, text(call.get("source")) + ':' + id + "/receiver");
                for (Map<String, Object> declaration : declarations) {
                    step(depth);
                    String signature = text(declaration.get("signature"));
                    String method =
                            signature.contains("#")
                                    ? signature.substring(
                                            signature.indexOf('#') + 1, signature.indexOf('('))
                                    : "";
                    if (!method.equals(name)) continue;
                    String override = signature.substring(signature.indexOf('#') + 1);
                    Integer nearer = hidden.get(override);
                    if (nearer != null && nearer < location.depth) continue;
                    hidden.merge(override, location.depth, Math::min);
                    if (!accessible(declaration, call, receiver, location.depth, depth + 1))
                        continue;
                    List<Map<String, Object>> inputs = arguments;
                    Map<String, Map<String, Object>> candidateVariables = variables;
                    Map<String, Object> referenceReceiver = receiver;
                    boolean unbound =
                            reference
                                    && typeReference
                                    && !name.equals("<init>")
                                    && !list(map(declaration.get("attributes")).get("modifiers"))
                                            .contains("static");
                    if (reference
                            && !typeReference
                            && list(map(declaration.get("attributes")).get("modifiers"))
                                    .contains("static")) continue;
                    if (unbound) {
                        if (arguments.isEmpty()
                                || types.convert(arguments.getFirst(), receiver, false)
                                        != Match.YES) continue;
                        TypeOperations.Projection projection = types.project(arguments.getFirst(), className);
                        if (projection.match() != Match.YES) {
                            if (projection.match() == Match.UNKNOWN) uncertainPhase = 0;
                            continue;
                        }
                        TypeOperations.Projection qualifier = types.project(arguments.getFirst(),
                                text(receiver.get("element")));
                        if (qualifier.match() != Match.YES) {
                            if (qualifier.match() == Match.UNKNOWN) uncertainPhase = 0;
                            continue;
                        }
                        referenceReceiver = qualifier.type();
                        candidateVariables = types.bindings(projection.type(),
                                text(call.get("source")) + ':' + id + "/unbound-receiver");
                        inputs = arguments.subList(1, arguments.size());
                    }
                    for (int phase = 0; phase <= 2; phase++) {
                        Candidate candidate =
                                applicable(
                                        declaration,
                                        candidateVariables,
                                        inputs,
                                        explicitTypes,
                                        // Target context constrains method formals only. Looking
                                        // outward for a non-generic candidate needlessly probes
                                        // its enclosing invocation, including unrelated overloads.
                                        reference || list(map(declaration.get("attributes"))
                                                .get("type_parameter_models")).isEmpty()
                                                ? Map.of() : writtenExpected(tree, depth + 1),
                                        input,
                                        phase,
                                        text(call.get("source")) + ':' + id,
                                        depth + 1);
                        if (candidate != null && reference) {
                            Map<String, Object> returns =
                                    name.equals("<init>") ? receiver : candidate.returns;
                            if (!kind(sam.returns).equals("VOID")) {
                                // While inferring a formal SAM output, first discover the
                                // referenced return; concrete conversion runs after inference.
                                String inferenceOwner = inferenceOwners.get(key);
                                Match match = inferenceOwner != null
                                                && mentionsFormal(sam.returns, inferenceOwner, depth + 1)
                                        ? Match.YES : types.convert(returns, sam.returns, true);
                                if (match == Match.UNKNOWN)
                                    uncertainPhase = Math.min(uncertainPhase, phase);
                                if (match != Match.YES) continue;
                            }
                            if (unbound) {
                                List<Map<String, Object>> expanded = new ArrayList<>();
                                expanded.add(referenceReceiver);
                                expanded.addAll(candidate.parameters);
                                candidate =
                                        new Candidate(
                                                candidate.declaration,
                                                candidate.returns,
                                                expanded,
                                                candidate.phase,
                                                candidate.modeled,
                                                candidate.modeledInput,
                                                candidate.origin,
                                                candidate.varargElement);
                            }
                        }
                        if (candidate != null) {
                            applicable.add(candidate);
                            break;
                        }
                    }
                }
                if (!name.equals("<init>"))
                    for (Map<String, Object> parent : types.parents(use, "parents:" + id))
                        queue.addLast(new Located(parent, location.depth + 1));
            }
            if (uncertain) return unavailable(attrs, "incomplete_hierarchy");
            if (applicable.isEmpty()) {
                if(uninstantiatedMethodReceiver)
                    return unavailable(attrs,"receiver_uninstantiated_method_variable");
                Result confirmed = confirmedUnknownArgument(call, receiver, arguments, explicitTypes, reference, depth + 1);
                if (confirmed != null) return confirmed;
                return unavailable(attrs, "no_applicable_candidate");
            }
            int phase = applicable.stream().mapToInt(Candidate::phase).min().orElseThrow();
            if (uncertainPhase <= phase) return unavailable(attrs, "incomplete_candidate");
            applicable.removeIf(candidate -> candidate.phase != phase);
            List<Candidate> maxima = new ArrayList<>();
            for (Candidate candidate : applicable) {
                boolean dominated = false;
                for (Candidate other : applicable)
                    if (candidate != other && moreSpecific(other, candidate, depth + 1)) {
                        dominated = true;
                        break;
                    }
                if (!dominated) maxima.add(candidate);
            }
            if (maxima.size() != 1) return unavailable(attrs, "ambiguous_candidates");
            Candidate selected = maxima.getFirst();
            input |= selected.modeledInput;
            // A generated overload or a modeled lambda body can explain javac's failure.
            // Only the checked candidate may establish that evidence; names alone cannot.
            if (!input && !selected.modeled) {
                // A checked contextual descriptor can instantiate the same declaration
                // already selected by javac. Its return is the method result, not the
                // functional-interface type of the method-reference expression.
                if ((reference || diamond || genericInvocation(call, depth + 1))
                        && javacResolved && expectedOverrides.containsKey(key)
                        && text(attrs.get("javac_selected_declaration"))
                                .equals(text(selected.declaration.get("signature")))) {
                    Result result = new Result(name.equals("<init>") ? receiver : selected.returns,
                            false, selected.parameters,
                            text(selected.declaration.get("signature")));
                    resultCache().put(key, result);
                    return result;
                }
                if (failure.equals("ambiguous")) return unavailable(attrs, "javac_ambiguous");
                if (failure.equals("inapplicable") && !retiredConstructor)
                    return unavailable(attrs, "javac_inapplicable");
                return unavailable(attrs, "no_modeled_input");
            }
            String target = text(selected.declaration.get("signature"));
            call.put("modeled_target", target);
            attrs.put("binding_origin", selected.origin);
            attrs.put("recovery_phase", phase);
            attrs.put("recovery_failure", "");
            attrs.put("recovered_parameter_type_models", selected.parameters);
            Map<String, Object> returns = name.equals("<init>") ? receiver : selected.returns;
            attrs.put("recovered_return_type_model", returns);
            attrs.put("recovery_input", input ? "modeled_expression" : "modeled_declaration");
            if (reference)
                attrs.put(
                        "recovered_sam",
                        Json.object("parameters", sam.parameters, "return", sam.returns));
            if (provisionalResults.isEmpty() && !known.containsKey(target))
                addExternal(selected.declaration);
            Result result = new Result(returns, true, selected.parameters, target);
            // An outer invocation may still supply this factory's target type. Do not
            // freeze an uninferred method formal in the global cache during reentry.
            if (!provisionalResults.isEmpty() || !mentionsFormal(returns, target, depth + 1))
                resultCache().put(key, result);
            return result;
        } finally {
            active.remove(key);
            uncertainPhase = previousUncertainPhase;
            if (provisional) {
                attrs.clear(); attrs.putAll(originalAttributes);
                if (originalTarget == null) call.remove("modeled_target");
                else call.put("modeled_target", originalTarget);
            }
        }
    }

    private Result attributedResult(Map<String, Object> attrs) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Object value : list(map(attrs.get("selected_type_model")).get("parameters")))
            parameters.add(map(value));
        return new Result(map(attrs.get("expression_type_model")), false, parameters);
    }

    private boolean modeledInputs(Map<String, Object> tree, int depth) throws IOException {
        step(depth);
        Map<String, Object> selection = child(tree, "selection");
        if ("MEMBER_SELECT".equals(selection.get("kind"))
                && modeledExpression(child(selection, "expression"), depth + 1))
            return true;
        if ("MEMBER_REFERENCE".equals(tree.get("kind"))
                && expression(id(map(tree.get("roles")).get("qualifier")), depth + 1).modeled)
            return true;
        for (Object argument : list(map(tree.get("roles")).get("arguments")))
            if (modeledExpression(node(id(argument)), depth + 1)) return true;
        return false;
    }

    private boolean modeledExpression(Map<String, Object> tree, int depth) throws IOException {
        step(depth);
        if (tree.isEmpty()) return false;
        String kind = text(tree.get("kind"));
        if (kind.equals("METHOD_INVOCATION"))
            return expression(id(tree.get("id")), 0).modeled || modeledInputs(tree, depth + 1);
        if (kind.equals("MEMBER_REFERENCE"))
            return modeledReference(tree, depth + 1) || modeledInputs(tree, depth + 1);
        if (kind.equals("LAMBDA_EXPRESSION")) {
            provisionalResults.push(new HashMap<>());
            try {
                Map<String, Object> body = child(tree, "body");
                return "BLOCK".equals(body.get("kind")) ? modeledBlock(body, depth + 1)
                        : modeledExpression(body, depth + 1);
            }
            finally { provisionalResults.pop(); }
        }
        if (kind.equals("IDENTIFIER")) return expression(id(tree.get("id")), 0).modeled;
        if (kind.equals("CONDITIONAL_EXPRESSION"))
            return modeledExpression(child(tree, "condition"), depth + 1)
                    || modeledExpression(child(tree, "then"), depth + 1)
                    || modeledExpression(child(tree, "else"), depth + 1);
        return modeledExpression(child(tree, "left"), depth + 1)
                || modeledExpression(child(tree, "right"), depth + 1)
                || modeledExpression(child(tree, "expression"), depth + 1);
    }

    private boolean modeledBlock(Map<String, Object> body, int depth) throws IOException {
        ArrayDeque<Map<String, Object>> pending = new ArrayDeque<>();
        pending.add(body);
        while (!pending.isEmpty()) {
            step(depth);
            if (pending.size() >= 4096) throw new TypeOperations.Budget();
            Map<String, Object> tree = pending.removeFirst();
            String kind = text(tree.get("kind"));
            if (Set.of("METHOD", "CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(kind)) continue;
            if (Set.of("METHOD_INVOCATION", "MEMBER_REFERENCE", "LAMBDA_EXPRESSION").contains(kind)) {
                if (modeledExpression(tree, depth + 1)) return true;
                continue;
            }
            for (Object value : map(tree.get("roles")).values()) {
                if (value instanceof Number) pending.add(node(id(value)));
                else for (Object item : list(value)) pending.add(node(id(item)));
            }
        }
        return false;
    }

    private boolean modeledReference(Map<String, Object> tree, int depth) throws IOException {
        step(depth);
        Map<String, Object> call = calls.get(key(tree));
        if (call == null) return false;
        Map<String, Object> attrs = map(call.get("attributes"));
        Map<String, Object> selected = known.get(text(attrs.get("javac_selected_declaration")));
        if (Boolean.TRUE.equals(call.get("resolved")) && selected != null
                && !"lombok_model".equals(map(selected.get("attributes")).get("binding_origin"))) return false;
        String name = "NEW".equals(attrs.get("reference_mode")) ? "<init>" : text(tree.get("name"));
        ArrayDeque<Map<String, Object>> pending = new ArrayDeque<>();
        pending.add(map(attrs.get("qualifier_type_model")));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            step(depth);
            Map<String, Object> current = types.readable(pending.removeFirst(), 0);
            String owner = text(current.get("element"));
            if (!visited.add(owner)) continue;
            if (visited.size() > 256) throw new TypeOperations.Budget();
            if (!sources.containsKey(owner)) continue;
            for (Map<String, Object> member : members.getOrDefault(owner, List.of())) {
                String signature = text(member.get("signature"));
                if (signature.startsWith(owner + "#" + name + "(")
                        && "lombok_model".equals(map(member.get("attributes")).get("binding_origin"))) return true;
            }
            pending.addAll(types.parents(current, "modeled-reference:" + id(tree.get("id"))));
        }
        return false;
    }

    // Frozen confirmedBinary: retain the exact compiler-selected binary member when
    // local modeled evidence is incomplete. A known incompatibility still vetoes it.
    private Result confirmedUnknownArgument(Map<String, Object> call, Map<String, Object> receiver,
            List<Map<String, Object>> arguments, List<Object> explicit, boolean reference, int depth)
            throws IOException {
        step(depth);
        if (reference || !explicit.isEmpty() || !Boolean.TRUE.equals(call.get("resolved"))
                || !modeledInputs(node(id(map(call.get("attributes")).get("tree_id"))), depth + 1)) return null;
        String signature = text(call.get("target")), owner = text(receiver.get("element"));
        if (!signature.startsWith(owner + "#") || sources.containsKey(owner)) return null;
        Map<String, Object> found = binary.readClass(owner);
        if (found == null || Boolean.FALSE.equals(map(found.get("declaration")).get("members_complete"))) return null;
        String name = text(map(call.get("attributes")).get("lookup_name"));
        Map<String, Object> selected = null;
        for (Map<String, Object> declaration : binaryMembers(owner, found)) {
            Map<String, Object> attrs = map(declaration.get("attributes"));
            String member = text(declaration.get("signature"));
            if (!member.startsWith(owner + "#" + name + "(")) continue;
            List<Object> params = list(attrs.get("parameter_type_models"));
            boolean variadic = Boolean.TRUE.equals(attrs.get("varargs"));
            if (variadic ? arguments.size() < params.size() - 1 : arguments.size() != params.size()) continue;
            if (!accessible(declaration, call, receiver, 0, depth + 1)) continue;
            if (signature.equals(member)) {
                if (selected != null) return null;
                selected = declaration;
            }
        }
        if (selected == null) return null;
        Map<String, Object> attrs = map(selected.get("attributes"));
        boolean generic = !list(attrs.get("type_parameter_models")).isEmpty();
        Map<String, Map<String, Object>> variables = types.bindings(receiver, "confirmed:" + signature);
        List<Map<String, Object>> parameters = new ArrayList<>();
        boolean unavailable = false;
        List<Object> raw = list(attrs.get("parameter_type_models"));
        if (generic && arguments.stream().allMatch(a -> !a.isEmpty()
                && !Set.of("ERROR", "UNKNOWN").contains(kind(a)))) {
            // The frozen confirmedBinary path distinguishes a disproved constraint
            // from unavailable local evidence. Probes must not change the caller's phase.
            int savedPhase = uncertainPhase;
            boolean uncertain = false;
            Candidate constrained = null;
            try {
                for (int phase = 0; phase <= 2; phase++) {
                    uncertainPhase = Integer.MAX_VALUE;
                    constrained = applicable(selected, variables, arguments, explicit,
                            writtenExpected(node(id(map(call.get("attributes")).get("tree_id"))), depth + 1),
                            true, phase, text(call.get("source")) + ":confirmed", depth + 1);
                    uncertain |= uncertainPhase <= phase;
                    if (constrained != null) break;
                }
            } finally { uncertainPhase = savedPhase; }
            if (constrained == null && !uncertain) return null;
            if (constrained != null) {
                parameters.addAll(constrained.parameters);
                return confirmedResult(call, signature, constrained.returns, parameters);
            }
            unavailable = true;
        }

        for (int i = 0; i < arguments.size(); i++) {
            Map<String, Object> argument = arguments.get(i);
            Map<String, Object> parameter = types.substitute(map(raw.get(Math.min(i, raw.size() - 1))), variables);
            parameters.add(parameter);
            if (argument.isEmpty() || Set.of("ERROR", "UNKNOWN").contains(kind(argument))) {
                unavailable = true;
                continue;
            }
            if (generic && kind(parameter).equals("TYPEVAR")
                    && signature.equals(parameter.get("owner")) && kind(argument).equals("DECLARED")) {
                String key = variable(parameter);
                Map<String,Object> previous = variables.putIfAbsent(key, argument);
                if (previous != null && types.same(previous, argument, 0) != Match.YES) return null;
                for (Object formal : list(attrs.get("type_parameter_models"))) {
                    if (!key.equals(variable(map(formal)))) continue;
                    for (Object bound : list(map(formal).get("bounds")))
                        if (types.convert(argument, types.substitute(map(bound), variables), true) == Match.NO) return null;
                }
                parameter = types.substitute(parameter, variables);
                parameters.set(i, parameter);
            }
            Match match;
            if (kind(argument).equals("POLY")) {
                match = polyCompatible(node(id(argument.get("tree_id"))), parameter, true, depth + 1).match;
            } else {
                match = types.convert(argument, parameter, true);
                if (Boolean.TRUE.equals(attrs.get("varargs")) && i >= raw.size() - 1
                        && (i >= raw.size() || match == Match.NO)) {
                    if (!kind(parameter).equals("ARRAY")) return null;
                    match = types.convert(argument, map(parameter.get("component")), true);
                }
            }
            if (match == Match.NO) return null;
            unavailable |= match == Match.UNKNOWN;
        }
        if (!unavailable) return null;
        Map<String, Object> returns = generic ? types.substitute(map(attrs.get("return_type_model")), variables)
                : map(map(call.get("attributes")).get("expression_type_model"));
        return confirmedResult(call, signature, returns, parameters);
    }

    private Result confirmedResult(Map<String,Object> call, String signature,
            Map<String,Object> returns, List<Map<String,Object>> parameters) {
        call.put("modeled_target", signature);
        map(call.get("attributes")).putAll(Json.object("binding_origin", "binary_recovery",
                "recovery_failure", "", "recovery_input", "compiler_confirmed_unknown_argument",
                "recovered_return_type_model", returns, "recovered_parameter_type_models", parameters));
        Result result = new Result(returns, true, parameters, signature);
        resultCache().put(key(node(id(map(call.get("attributes")).get("tree_id")))), result);
        return result;
    }

    private boolean potential(Map<String, Object> tree, int depth) {
        // This is a name scan, not expression/type inference. Keep work bounded
        // without charging syntax wrapper nodes against semantic recursion depth.
        Deque<Map<String, Object>> pending = new ArrayDeque<>();
        Set<NodeKey> seen = new HashSet<>();
        pending.add(tree);
        while (!pending.isEmpty()) {
            step(0);
            if (pending.size() >= 4096) throw new TypeOperations.Budget();
            Map<String, Object> current = pending.removeLast();
            if (current.isEmpty() || !seen.add(key(current))) continue;
            Map<String, Object> call = calls.get(key(current));
            if (call != null && modeledNames.contains(text(map(call.get("attributes")).get("lookup_name"))))
                return true;
            if (call != null && "NEW".equals(map(call.get("attributes")).get("reference_mode"))) {
                Map<String, Object> qualifier = map(map(call.get("attributes")).get("qualifier_type_model"));
                Map<String, Object> declaration = sources.get(text(qualifier.get("element")));
                if (declaration != null && Boolean.TRUE.equals(
                        LombokModel.summary(declaration).get("constructor_replaced"))) return true;
            }
            if (Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(text(current.get("kind"))))
                continue;
            for (Object value : map(current.get("roles")).values()) {
                if (value instanceof Number) pending.add(node(id(value)));
                for (Object item : list(value)) pending.add(node(id(item)));
            }
        }
        return false;
    }

    private Result lambdaParameter(Map<String, Object> expression, int depth) throws IOException {
        step(depth);
        Map<String, Object> declaration = known.get(text(expression.get("symbol")));
        if (declaration == null
                || !"PARAMETER".equals(map(declaration.get("attributes")).get("element_kind")))
            return null;
        for (Map<String, Object> parent = node(id(expression.get("parent")));
                !parent.isEmpty(); parent = node(id(parent.get("parent")))) {
            step(0);
            String kind = text(parent.get("kind"));
            if (Set.of("METHOD", "CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(kind))
                return null;
            if (!kind.equals("LAMBDA_EXPRESSION")) continue;
            List<Object> parameters = list(map(parent.get("roles")).get("parameters"));
            for (int index = 0; index < parameters.size(); index++) {
                Map<String, Object> parameter = node(id(parameters.get(index)));
                // Match the actual bound declaration, so a shadowing nested lambda cannot
                // borrow another callback's parameter just because the names are equal.
                if (!text(parameter.get("symbol")).equals(expression.get("symbol"))) continue;
                if (!child(parameter, "type").isEmpty()) return null;
                boolean modeled = modeledExpected.contains(key(parent));
                Map<String, Object> outer = node(id(parent.get("parent")));
                while ("PARENTHESIZED".equals(outer.get("kind"))) {
                    step(0); outer = node(id(outer.get("parent")));
                }
                Result selected = resultCache().get(key(outer));
                modeled |= selected != null && selected.modeled;
                Map<String,Object> attributed = map(map(declaration.get("attributes")).get("type_model"));
                // A complete javac parameter needs no reentry into its enclosing call.
                // Candidate-local descriptors still take precedence when inference supplies one.
                if (!modeled && !expectedOverrides.containsKey(key(parent))
                        && Boolean.TRUE.equals(attributed.get("complete")))
                    return new Result(attributed, false);
                Map<String, Object> expected = writtenExpected(parent, depth + 1);
                Sam descriptor = sam(expected, depth + 1);
                if (descriptor == null || index >= descriptor.parameters.size()) return null;
                return new Result(descriptor.parameters.get(index), modeled);
            }
            // A captured parameter of an outer lambda may be used inside a nested one.
        }
        return null;
    }

    private Result unavailable(Map<String, Object> attrs, String reason) {
        attrs.put("recovery_failure", reason);
        Map<String, Object> original = calls.get(new NodeKey(currentSource, id(attrs.get("tree_id"))));
        // A known failed constraint blocks a receiver/poly result. UNKNOWN candidates
        // do not contradict a declaration already selected by javac; an unresolved
        // original call still has no receiver evidence to supply.
        boolean blocked = reason.equals("no_applicable_candidate")
                && (uncertainPhase == Integer.MAX_VALUE || original == null
                        || !Boolean.TRUE.equals(original.get("resolved")));
        if (original != null && Boolean.TRUE.equals(original.get("resolved"))) {
            Result result = attributedResult(attrs);
            return cacheUnavailable(attrs, new Result(result.type, false, result.parameters, result.target, blocked));
        }
        return cacheUnavailable(attrs, new Result(Map.of(), false, List.of(), "", blocked));
    }

    // Cache failed selections too, as in the frozen resolver. Repeated evidence
    // walks must not turn an unrelated generated name into a budget failure.
    // Candidate-local descriptors use their own provisional cache.
    private Result cacheUnavailable(Map<String,Object> attrs, Result result) {
        resultCache().put(new NodeKey(currentSource, id(attrs.get("tree_id"))), result);
        return result;
    }

    private boolean modeledReferenceContext(Map<String, Object> tree, int depth) {
        Map<String, Object> child = tree;
        Map<String, Object> parent = node(id(child.get("parent")));
        while ("PARENTHESIZED".equals(parent.get("kind"))) {
            step(depth++); child = parent; parent = node(id(parent.get("parent")));
        }
        boolean argument = false;
        for (Object candidate : list(map(parent.get("roles")).get("arguments")))
            argument |= id(candidate) == id(child.get("id"));
        if (!argument) return false;
        Result outer = resultCache().get(key(parent));
        return outer != null && outer.modeled;
    }

    private Map<String, Object> expected(Map<String, Object> tree, Map<String, Object> attributes, int depth)
            throws IOException {
        Map<String, Object> override = expectedOverrides.get(key(tree));
        if (override != null) return override;
        Map<String, Object> contextual = writtenExpected(tree, depth + 1);
        if (!contextual.isEmpty()) return contextual;
        Map<String, Object> type = map(attributes.get("functional_type_model"));
        if (kind(type).equals("DECLARED")) return type;
        Map<String, Object> parent = node(id(tree.get("parent")));
        for (int hops = 0; hops < 32 && "PARENTHESIZED".equals(parent.get("kind")); hops++)
            parent = node(id(parent.get("parent")));
        if ("VARIABLE".equals(parent.get("kind"))) {
            Map<String, Object> declaration = known.get(text(parent.get("symbol")));
            if (declaration != null)
                return map(map(declaration.get("attributes")).get("type_model"));
        }
        if ("RETURN".equals(parent.get("kind"))) {
            Map<String, Object> method = known.get(text(tree.get("owner")));
            if (method != null) return map(map(method.get("attributes")).get("return_type_model"));
        }
        if ("ASSIGNMENT".equals(parent.get("kind"))) {
            Map<String, Object> variable = child(parent, "variable"),
                    declaration = known.get(text(variable.get("symbol")));
            if (declaration != null)
                return map(map(declaration.get("attributes")).get("type_model"));
        }
        return Map.of();
    }

    private Sam sam(Map<String, Object> functional, int depth) throws IOException {
        step(depth);
        if (!kind(functional).equals("DECLARED")) return null;
        String target = text(functional.get("element"));
        Map<String, Object> source = sources.get(target);
        if (source != null
                && !"INTERFACE".equals(map(source.get("attributes")).get("element_kind")))
            return null;
        if (source == null) {
            Map<String, Object> found = binary.readClass(target);
            if (found == null
                    || (((Number) map(found.get("declaration")).get("access")).intValue() & 0x200)
                            == 0) return null;
        }
        functional = groundFunctional(functional, depth + 1);
        ArrayDeque<Located> queue = new ArrayDeque<>();
        queue.add(new Located(functional, 0));
        Set<String> seen = new HashSet<>();
        Map<String, Integer> nearest = new HashMap<>();
        Map<String, Sam> descriptors = new HashMap<>();
        while (!queue.isEmpty()) {
            step(depth);
            Located located = queue.removeFirst();
            Map<String, Object> use = located.use;
            String owner = text(use.get("element"));
            if (!seen.add(TypeOperations.identity(use))) continue;
            if (seen.size() > 256 || types.attributes(owner).isEmpty()) return null;
            List<Map<String, Object>> declarations = members.getOrDefault(owner, List.of());
            if (!sources.containsKey(owner)) {
                Map<String, Object> found = binary.readClass(owner);
                if (found == null
                        || Boolean.FALSE.equals(
                                map(found.get("declaration")).get("members_complete"))) return null;
                declarations = binaryMembers(owner, found);
            }
            Map<String, Map<String, Object>> variables =
                    types.bindings(use, "sam:" + currentSource);
            for (Map<String, Object> declaration : declarations) {
                Map<String, Object> attrs = map(declaration.get("attributes"));
                List<Object> modifiers = list(attrs.get("modifiers"));
                String signature = text(declaration.get("signature")),
                        key = signature.substring(signature.indexOf('#') + 1);
                if (key.startsWith("<")
                        || Set.of("equals(java.lang.Object)", "hashCode()", "toString()")
                                .contains(key)
                        || modifiers.contains("static")
                        || modifiers.contains("private")) continue;
                Integer previous = nearest.get(key);
                if (previous != null && previous < located.depth) continue;
                nearest.putIfAbsent(key, located.depth);
                if (!modifiers.contains("abstract")) {
                    descriptors.remove(key);
                    continue;
                }
                if (!list(attrs.get("type_parameter_models")).isEmpty()) return null;
                List<Map<String, Object>> parameters = new ArrayList<>();
                for (Object parameter : list(attrs.get("parameter_type_models")))
                    parameters.add(types.substitute(map(parameter), variables));
                Sam descriptor =
                        new Sam(
                                parameters,
                                types.substitute(map(attrs.get("return_type_model")), variables));
                Sam old = descriptors.get(key);
                if (old == null
                        || types.convert(descriptor.returns, old.returns, false) == Match.YES)
                    descriptors.put(key, descriptor);
                else if (types.convert(old.returns, descriptor.returns, false) != Match.YES)
                    return null;
            }
            for (Map<String, Object> parent : types.parents(use, "sam-parents"))
                if (!"java.lang.Object".equals(parent.get("element")))
                    queue.add(new Located(parent, located.depth + 1));
        }
        return descriptors.size() == 1 ? descriptors.values().iterator().next() : null;
    }

    private boolean diamond(Map<String, Object> tree) {
        if (!"NEW_CLASS".equals(tree.get("kind"))) return false;
        Map<String, Object> identifier = child(tree, "type");
        return "PARAMETERIZED_TYPE".equals(identifier.get("kind"))
                && list(map(identifier.get("roles")).get("type_arguments")).isEmpty();
    }

    private Map<String, Object> constructorReceiver(Map<String, Object> receiver,
            Map<String, Object> target, int depth) throws IOException {
        step(depth);
        if (!kind(receiver).equals("DECLARED") || !kind(target).equals("DECLARED")
                || Boolean.FALSE.equals(target.get("complete"))
                || Boolean.FALSE.equals(receiver.get("complete"))
                || !list(receiver.get("arguments")).isEmpty()) return receiver;
        String owner = text(receiver.get("element"));
        List<Object> formals = list(types.attributes(owner).get("type_parameter_models"));
        if (formals.isEmpty()) return receiver;
        List<Object> arguments = new ArrayList<>();
        for (Object raw : formals) {
            Map<String, Object> formal = map(raw);
            List<Object> bounds = list(formal.get("bounds"));
            arguments.add(Json.object("kind", "TYPEVAR", "name", formal.get("name"),
                    "owner", formal.get("owner"), "complete", true,
                    "upper", bounds.isEmpty() ? named("java.lang.Object") : bounds.getFirst()));
        }
        Map<String, Object> pattern = new TreeMap<>(receiver);
        pattern.put("arguments", arguments);
        // The captured spelling and generic signature described the raw qualifier.
        // Keep its valid erasure descriptor; publish the instantiated structure.
        pattern.remove("display");
        pattern.remove("signature");
        TypeOperations.Projection projection = types.project(pattern, text(target.get("element")));
        if (projection.match() != Match.YES) return receiver;
        Map<String, Map<String, Object>> variables = new HashMap<>();
        if (!seedReturn(pattern, target, owner, variables, depth + 1)) return receiver;
        for (Object raw : formals) {
            Map<String, Object> formal = map(raw), inferred = variables.get(variable(formal));
            if (inferred == null || !explicitType(inferred, depth + 1)) return receiver;
            for (Object bound : list(formal.get("bounds")))
                if (types.convert(inferred, types.substitute(map(bound), variables), false)
                        != Match.YES) return receiver;
        }
        Map<String, Object> result = types.substitute(pattern, variables);
        return types.convert(result, target, false) == Match.YES ? result : receiver;
    }

    private Map<String, Object> groundFunctional(Map<String, Object> functional, int depth)
            throws IOException {
        step(depth);
        List<Object> formals =
                list(
                        types.attributes(text(functional.get("element")))
                                .get("type_parameter_models"));
        List<Object> arguments = list(functional.get("arguments"));
        if (formals.size() != arguments.size()) return functional;
        List<Object> ground = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            Map<String, Object> argument = map(arguments.get(i));
            if (kind(argument).equals("WILDCARD")) {
                if (argument.get("super") instanceof Map) argument = map(argument.get("super"));
                else if (argument.get("extends") instanceof Map)
                    argument = map(argument.get("extends"));
                else {
                    List<Object> bounds = list(map(formals.get(i)).get("bounds"));
                    argument =
                            bounds.isEmpty() ? named("java.lang.Object") : map(bounds.getFirst());
                }
            }
            ground.add(argument);
        }
        Map<String, Object> result = new TreeMap<>(functional);
        result.put("arguments", ground);
        return result;
    }

    private Candidate applicable(
            Map<String, Object> declaration,
            Map<String, Map<String, Object>> ownerVariables,
            List<Map<String, Object>> arguments,
            List<Object> explicitTypes,
            Map<String, Object> targetReturn,
            boolean modeledInput,
            int phase,
            String scope,
            int depth)
            throws IOException {
        step(depth);
        List<Map<String, Object>> capturedArguments = new ArrayList<>(arguments);
        for (int i = 0; i < arguments.size(); i++)
            if (!kind(arguments.get(i)).equals("POLY")
                    && !arguments.get(i).containsKey("poly_invocation_id")
                    && !arguments.get(i).containsKey("diamond_constructor_id"))
                capturedArguments.set(i, types.captureArgument(arguments.get(i), scope + "/argument/" + i));
        arguments = capturedArguments;
        Map<String, Object> attrs = map(declaration.get("attributes"));
        List<Object> raw = list(attrs.get("parameter_type_models"));
        boolean variableArity = phase == 2 && Boolean.TRUE.equals(attrs.get("varargs"));
        if (!variableArity && raw.size() != arguments.size()
                || variableArity && arguments.size() < raw.size() - 1) return null;
        if (phase == 2 && !variableArity) return null;
        Map<String, Map<String, Object>> variables = new HashMap<>(ownerVariables);
        List<Object> formals = list(attrs.get("type_parameter_models"));
        // JLS 15.12.2.1 allows explicit arguments on non-generic methods and ignores them.
        if (!explicitTypes.isEmpty() && !formals.isEmpty()) {
            if (explicitTypes.size() != formals.size()) return null;
            for (int i = 0; i < formals.size(); i++) {
                String key = variable(map(formals.get(i)));
                if (key.isEmpty()) {
                    uncertainPhase = Math.min(uncertainPhase, phase);
                    return null;
                }
                variables.put(key, map(explicitTypes.get(i)));
            }
        }
        if (explicitTypes.isEmpty() && !formals.isEmpty()
                && Set.of("DECLARED", "ARRAY").contains(kind(targetReturn))
                && returnTargetSeedable(arguments, raw, ownerVariables,
                        map(attrs.get("return_type_model")), targetReturn, depth + 1)
                && !seedReturn(types.substitute(map(attrs.get("return_type_model")), ownerVariables),
                        targetReturn, text(declaration.get("signature")), variables, arguments.isEmpty(), depth + 1)) return null;
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            Map<String, Object> parameter =
                    types.substitute(map(raw.get(Math.min(i, raw.size() - 1))), variables);
            if (variableArity && i >= raw.size() - 1) parameter = map(parameter.get("component"));
            if (!kind(arguments.get(i)).equals("POLY")
                    && !arguments.get(i).containsKey("poly_invocation_id")
                    && !arguments.get(i).containsKey("diamond_constructor_id")) {
                Match constraint = infer(parameter, arguments.get(i),
                        text(declaration.get("signature")), variables, depth + 1);
                if (constraint != Match.YES) {
                    if (constraint == Match.UNKNOWN) uncertainPhase = Math.min(uncertainPhase, phase);
                    return null;
                }
            }
            if (explicitTypes.isEmpty() && kind(arguments.get(i)).equals("POLY")
                    && mentionsFormal(parameter, text(declaration.get("signature")), depth + 1)) {
                Match inferred = inferPoly(node(id(arguments.get(i).get("tree_id"))), parameter,
                        text(declaration.get("signature")), variables, modeledInput, depth + 1);
                if (inferred != Match.YES) {
                    if (inferred == Match.UNKNOWN) uncertainPhase = Math.min(uncertainPhase, phase);
                    return null;
                }
            }
            parameters.add(parameter);
        }
        List<Map<String, Object>> contextualArguments = new ArrayList<>(arguments);
        for (int i = 0; i < parameters.size(); i++) {
            Map<String, Object> argument = arguments.get(i);
            boolean diamondArgument = argument.containsKey("diamond_constructor_id");
            if (!argument.containsKey("poly_invocation_id") && !diamondArgument) continue;
            Result value = contextualInvocation(node(id(argument.get(diamondArgument
                            ? "diamond_constructor_id" : "poly_invocation_id"))),
                    types.substitute(parameters.get(i), variables), depth + 1);
            Map<String, Object> contextualType = types.captureArgument(value.type, scope + "/argument/" + i);
            if (value.blocked) return null;
            if (!value.modeled && !(!value.target.isEmpty() && !value.type.isEmpty())) {
                uncertainPhase = Math.min(uncertainPhase, phase); return null;
            }
            Match constraint = infer(types.substitute(parameters.get(i), variables), contextualType,
                    text(declaration.get("signature")), variables, depth + 1);
            if (constraint != Match.YES) {
                if (constraint == Match.UNKNOWN) uncertainPhase = Math.min(uncertainPhase, phase);
                return null;
            }
            contextualArguments.set(i, contextualType);
        }
        if (explicitTypes.isEmpty() && !formals.isEmpty() && !targetReturn.isEmpty()) {
            if (!seedReturn(
                    types.substitute(map(attrs.get("return_type_model")), variables),
                    targetReturn,
                    text(declaration.get("signature")),
                    variables,
                    arguments.isEmpty(), depth + 1)) return null;
        }
        for (Object formal : list(attrs.get("type_parameter_models"))) {
            Map<String, Object> type = map(formal), inferred = variables.get(variable(type));
            if (inferred == null)
                continue; // Keep an uninferred formal; do not invent a concrete target type.
            for (Object bound : list(type.get("bounds"))) {
                Match match =
                        types.convert(inferred, types.substitute(map(bound), variables), false);
                if (match == Match.UNKNOWN) uncertainPhase = Math.min(uncertainPhase, phase);
                if (match != Match.YES) return null;
            }
        }
        for (int i = 0; i < parameters.size(); i++) {
            parameters.set(i, types.substitute(parameters.get(i), variables));
            Match match;
            if (kind(arguments.get(i)).equals("POLY")) {
                PolyMatch poly = polyCompatible(node(id(arguments.get(i).get("tree_id"))),
                        parameters.get(i), modeledInput, depth + 1);
                match = poly.match; modeledInput |= poly.modeledInput;
            } else match = types.convert(contextualArguments.get(i), parameters.get(i), phase > 0);
            if (match == Match.UNKNOWN) uncertainPhase = Math.min(uncertainPhase, phase);
            if (match != Match.YES) return null;
        }
        Map<String, Object> inferredReturn =
                types.substitute(map(attrs.get("return_type_model")), variables);
        if (!formals.isEmpty() && !targetReturn.isEmpty()) {
            Match match = types.convert(inferredReturn, targetReturn, true);
            // Foreign formals belong to the enclosing invocation; concrete
            // positions and bounds have already been checked separately.
            if (match == Match.UNKNOWN && unboundTarget(targetReturn,
                    text(declaration.get("signature")), depth + 1)) match = Match.YES;
            if (match == Match.UNKNOWN) uncertainPhase = Math.min(uncertainPhase, phase);
            if (match != Match.YES) return null;
        }
        boolean modeled = "lombok_model".equals(attrs.get("binding_origin"));
        return new Candidate(
                declaration,
                types.substitute(map(attrs.get("return_type_model")), variables),
                parameters,
                phase,
                modeled,
                modeledInput,
                modeled
                        ? "lombok_model"
                        : attrs.containsKey("binary_member")
                                ? "binary_recovery"
                                : "source_recovery",
                variableArity
                        ? types.substitute(map(map(raw.getLast()).get("component")), variables)
                        : Map.of());
    }

    private Map<String, Object> writtenExpected(Map<String, Object> site, int depth)
            throws IOException {
        Map<String, Object> override = expectedOverrides.get(key(site));
        if (override != null) return override;
        Map<String, Object> child = site;
        for (Map<String, Object> parent = node(id(child.get("parent")));
                !parent.isEmpty();
                child = parent, parent = node(id(child.get("parent")))) {
            step(depth++);
            String kind = text(parent.get("kind"));
            if (kind.equals("PARENTHESIZED")) continue;
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS").contains(kind)) {
                List<Object> arguments = list(map(parent.get("roles")).get("arguments"));
                int index = arguments.indexOf(child.get("id"));
                Result selected = resultCache().get(key(parent));
                Map<String, Object> call = calls.get(key(parent));
                if (selected == null && call != null && !active.contains(key(parent)))
                    selected = resolve(call, depth + 1);
                return selected != null && index >= 0 && index < selected.parameters.size()
                        ? selected.parameters.get(index) : Map.of();
            }
            if (kind.equals("TYPE_CAST") && !"METHOD_INVOCATION".equals(site.get("kind")))
                return expression(id(map(parent.get("roles")).get("type")), depth + 1).type;
            if (kind.equals("VARIABLE")
                    && id(map(parent.get("roles")).get("initializer")) == id(child.get("id"))) {
                Map<String, Object> declaration = known.get(text(parent.get("symbol")));
                return declaration == null
                        ? Map.of()
                        : map(map(declaration.get("attributes")).get("type_model"));
            }
            if (kind.equals("ASSIGNMENT")
                    && id(map(parent.get("roles")).get("expression")) == id(child.get("id")))
                return expression(id(map(parent.get("roles")).get("variable")), depth + 1).type;
            if (kind.equals("RETURN")) {
                for (Map<String, Object> declaration = node(id(parent.get("parent")));
                        !declaration.isEmpty();
                        declaration = node(id(declaration.get("parent")))) {
                    step(depth++);
                    if (text(declaration.get("kind")).equals("METHOD")) {
                        Map<String, Object> method = known.get(text(declaration.get("symbol")));
                        return method == null
                                ? Map.of()
                                : map(map(method.get("attributes")).get("return_type_model"));
                    }
                    if ("LAMBDA_EXPRESSION".equals(declaration.get("kind"))) {
                        Sam descriptor = sam(writtenExpected(declaration, depth + 1), depth + 1);
                        return descriptor == null ? Map.of() : descriptor.returns;
                    }
                    if (Set.of("CLASS", "INTERFACE", "ENUM")
                            .contains(text(declaration.get("kind")))) return Map.of();
                }
            }
            if (kind.equals("LAMBDA_EXPRESSION")
                    && id(map(parent.get("roles")).get("body")) == id(child.get("id"))) {
                Sam descriptor = sam(writtenExpected(parent, depth + 1), depth + 1);
                return descriptor == null ? Map.of() : descriptor.returns;
            }
            return Map
                    .of(); // Cast/invocation/lambda targets need separate poly-expression evidence.
        }
        return Map.of();
    }

    private boolean returnTargetSeedable(List<Map<String,Object>> arguments, List<Object> raw,
            Map<String,Map<String,Object>> ownerVariables, Map<String,Object> result,
            Map<String,Object> target, int depth) throws IOException {
        if (!kind(result).equals("TYPEVAR")) return true;
        String formal = variable(result);
        for (int i=0;i<raw.size();i++) {
            Map<String,Object> parameter=types.substitute(map(raw.get(i)),ownerVariables);
            if (!nestedFormal(parameter,formal,depth+1)) continue;
            if (i>=arguments.size() || !arguments.get(i).containsKey("poly_invocation_id")
                    || !sameNestedErasure(arguments.get(i),parameter,formal,target,depth+1)) return false;
        }
        return true;
    }

    private boolean nestedFormal(Map<String,Object> type,String formal,int depth) {
        step(depth);
        if (kind(type).equals("TYPEVAR")) return formal.equals(variable(type));
        for (Object value:list(type.get("arguments")))
            if (nestedFormal(map(value),formal,depth+1)) return true;
        for (String field:List.of("component","enclosing","extends","super"))
            if (type.get(field) instanceof Map && nestedFormal(map(type.get(field)),formal,depth+1)) return true;
        return false;
    }

    private boolean sameNestedErasure(Map<String,Object> actual,Map<String,Object> parameter,
            String formal,Map<String,Object> target,int depth) throws IOException {
        step(depth);
        if (kind(parameter).equals("TYPEVAR") && formal.equals(variable(parameter)))
            return kind(actual).equals("DECLARED") && actual.get("element").equals(target.get("element"));
        if (!nestedFormal(parameter,formal,depth+1)) return true;
        if (kind(parameter).equals("DECLARED") && kind(actual).equals("DECLARED")) {
            Projection projected=types.project(actual,text(parameter.get("element")));
            if (projected.match()!=Match.YES) return false;
            List<Object> patterns=list(parameter.get("arguments")),values=list(projected.type().get("arguments"));
            if (patterns.size()!=values.size()) return false;
            for(int i=0;i<patterns.size();i++)
                if(!sameNestedErasure(map(values.get(i)),map(patterns.get(i)),formal,target,depth+1)) return false;
            return true;
        }
        if(kind(parameter).equals("ARRAY") && kind(actual).equals("ARRAY"))
            return sameNestedErasure(map(actual.get("component")),map(parameter.get("component")),formal,target,depth+1);
        if(kind(parameter).equals("WILDCARD")) {
            if(kind(actual).equals("WILDCARD")) actual=actual.get("super") instanceof Map ? map(actual.get("super"))
                    : actual.get("extends") instanceof Map ? map(actual.get("extends")) : named("java.lang.Object");
            if(parameter.get("super") instanceof Map)
                return sameNestedErasure(actual,map(parameter.get("super")),formal,target,depth+1);
            if(parameter.get("extends") instanceof Map)
                return sameNestedErasure(actual,map(parameter.get("extends")),formal,target,depth+1);
        }
        return false;
    }

    private boolean seedReturn(
            Map<String, Object> pattern,
            Map<String, Object> target,
            String owner,
            Map<String, Map<String, Object>> variables,
            int depth) throws IOException {
        return seedReturn(pattern, target, owner, variables, false, depth);
    }

    private boolean seedReturn(Map<String,Object> pattern, Map<String,Object> target,
            String owner, Map<String,Map<String,Object>> variables, boolean wildcardHints, int depth)
            throws IOException {
        step(depth);
        if (wildcardHints && kind(target).equals("WILDCARD")) {
            target = target.get("super") instanceof Map ? map(target.get("super"))
                    : target.get("extends") instanceof Map ? map(target.get("extends")) : named("java.lang.Object");
            if (unboundTarget(target, owner, depth + 1)) return true;
        }
        if (Set.of("TYPEVAR", "WILDCARD").contains(kind(target))) return true;
        if (kind(pattern).equals("TYPEVAR") && owner.equals(pattern.get("owner"))) {
            if (!Set.of("DECLARED", "ARRAY").contains(kind(target))
                    || Boolean.FALSE.equals(target.get("complete"))) return false;
            String key = variable(pattern);
            if (key.isEmpty()) return false;
            Map<String, Object> previous = variables.get(key);
            if (previous == null) variables.put(key, target);
            return previous == null || types.convert(previous, target, true) == Match.YES;
        }
        if (kind(pattern).equals("DECLARED") && kind(target).equals("DECLARED")) {
            Projection projection = types.project(pattern, text(target.get("element")));
            if (projection.match() != Match.YES) return true;
            List<Object> parameters = list(projection.type().get("arguments")),
                    values = list(target.get("arguments"));
            if (parameters.size() == values.size())
                for (int i = 0; i < parameters.size(); i++)
                    if (!seedReturn(
                            map(parameters.get(i)),
                            map(values.get(i)),
                            owner,
                            variables,
                            wildcardHints, depth + 1)) return false;
        }
        return true;
    }

    private boolean genericInvocation(Map<String,Object> call, int depth) {
        Map<String,Object> attrs = map(call.get("attributes"));
        if (!"invoke".equals(attrs.get("mechanism")) || !list(attrs.get("explicit_type_argument_models")).isEmpty()) return false;
        String signature = text(attrs.get("javac_selected_declaration"));
        Map<String,Object> declared = map(attrs.get("declared_type_model"));
        if (mentionsFormal(map(declared.get("return")), signature, depth + 1)) return true;
        signature = text(call.get("modeled_target"));
        Map<String,Object> recovered = known.get(signature);
        return recovered != null && mentionsFormal(map(map(recovered.get("attributes"))
                .get("return_type_model")), signature, depth + 1);
    }

    private boolean mentionsFormal(Map<String, Object> type, String owner, int depth) {
        step(depth);
        if (owner.isEmpty()) return false;
        if (dev.codeana.common.TypeGraph.isView(type)) {
            dev.codeana.common.TypeGraph graph=dev.codeana.common.TypeGraph.owner(type);
            ArrayDeque<Integer> pending=new ArrayDeque<>();
            Set<Integer> seen=new HashSet<>();pending.add(dev.codeana.common.TypeGraph.reference(type));
            while(!pending.isEmpty()) {
                step(depth);int id=pending.removeFirst();if(!seen.add(id))continue;
                Map<String,Object> node=graph.node(id);
                if("TYPEVAR".equals(node.get("kind"))) {
                    if(owner.equals(node.get("owner")))return true;
                    continue;
                }
                for(String role:List.of("component","enclosing","extends","super")) {
                    Object child=node.get(role);
                    if(child instanceof Number && ((Number)child).intValue()>=0)pending.add(((Number)child).intValue());
                }
                for(Object child:list(node.get("arguments")))pending.add(((Number)child).intValue());
            }
            return false;
        }
        if (kind(type).equals("TYPEVAR")) return owner.equals(type.get("owner"));
        for (String key : List.of("component", "enclosing", "extends", "super"))
            if (type.get(key) instanceof Map
                    && mentionsFormal(map(type.get(key)), owner, depth + 1)) return true;
        for (Object argument : list(type.get("arguments")))
            if (mentionsFormal(map(argument), owner, depth + 1)) return true;
        return false;
    }

    private Result contextualInvocation(Map<String, Object> site, Map<String, Object> target, int depth)
            throws IOException {
        step(depth);
        NodeKey key = key(site);
        Map<String, Object> call = calls.get(key);
        if (call == null) return new Result(Map.of(), false);
        Map<String, Object> previous = expectedOverrides.put(key, target);
        int previousPhase = uncertainPhase;
        provisionalResults.push(new HashMap<>());
        try { return resolve(call, depth + 1); }
        finally {
            provisionalResults.pop(); uncertainPhase = previousPhase;
            if (previous == null) expectedOverrides.remove(key); else expectedOverrides.put(key, previous);
        }
    }

    /** Frozen inferPoly output constraints; all probes are candidate-local. */
    private Match inferPoly(Map<String, Object> poly, Map<String, Object> parameter, String owner,
            Map<String, Map<String, Object>> variables, boolean modeledInput, int depth)
            throws IOException {
        step(depth);
        Sam descriptor = sam(parameter, depth + 1);
        if (descriptor == null) return Match.UNKNOWN;
        NodeKey key = key(poly);
        if ("MEMBER_REFERENCE".equals(poly.get("kind")) && !descriptor.parameters.isEmpty()) {
            Map<String, Object> call = calls.get(key), qualifier = child(poly, "qualifier");
            if (call == null) return Match.UNKNOWN;
            Map<String, Object> attrs = map(call.get("attributes"));
            Map<String, Object> receiver = map(attrs.get("qualifier_type_model"));
            String symbol = text(qualifier.get("symbol"));
            boolean typeQualifier = "PARAMETERIZED_TYPE".equals(qualifier.get("kind"))
                    || sources.containsKey(symbol)
                    || !symbol.isEmpty() && symbol.equals(receiver.get("element"));
            Map<String, Object> first = descriptor.parameters.getFirst();
            if (!"NEW".equals(attrs.get("reference_mode")) && typeQualifier
                    && mentionsFormal(first, owner, depth + 1)
                    && referenceStaticity(receiver, text(poly.get("name")), descriptor.parameters.size(),
                            first, depth + 1) == Match.NO) {
                Match constraint = infer(first, receiver, owner, variables, depth + 1);
                if (constraint != Match.YES) return constraint;
                parameter = types.substitute(parameter, variables);
                descriptor = sam(parameter, depth + 1);
                if (descriptor == null) return Match.UNKNOWN;
            }
        }
        Map<String, Object> previous = expectedOverrides.put(key, parameter);
        String previousOwner = inferenceOwners.put(key, owner);
        boolean previousModeled = modeledExpected.contains(key);
        if (modeledInput) modeledExpected.add(key);
        int previousPhase = uncertainPhase;
        provisionalResults.push(new HashMap<>());
        try {
            if ("MEMBER_REFERENCE".equals(poly.get("kind"))) {
                Map<String, Object> call = calls.get(key);
                if (call == null) return Match.UNKNOWN;
                Result selected = resolve(call, depth + 1);
                if (selected.blocked) return Match.NO;
                if (selected.type.isEmpty() || !selected.modeled && selected.target.isEmpty())
                    return Match.UNKNOWN;
                if (kind(selected.type).equals("VOID") && !kind(descriptor.returns).equals("VOID"))
                    return Match.NO;
                return infer(descriptor.returns, selected.type, owner, variables, depth + 1);
            }
            if (!"LAMBDA_EXPRESSION".equals(poly.get("kind"))) return Match.UNKNOWN;
            if (list(map(poly.get("roles")).get("parameters")).size() != descriptor.parameters.size())
                return Match.NO;
            Map<String, Object> body = child(poly, "body");
            if (body.isEmpty()) return Match.UNKNOWN;
            if (!"BLOCK".equals(body.get("kind"))) {
                Result value = expression(id(body.get("id")), depth + 1);
                if (value.blocked) return Match.NO;
                if (value.type.isEmpty()) return Match.UNKNOWN;
                if (kind(value.type).equals("VOID") && !kind(descriptor.returns).equals("VOID"))
                    return Match.NO;
                return infer(descriptor.returns, value.type, owner, variables, depth + 1);
            }
            List<Map<String, Object>> returns = new ArrayList<>();
            collectReturns(body, returns, depth + 1);
            boolean voidReturn = kind(descriptor.returns).equals("VOID");
            if (voidReturn) {
                for (Map<String, Object> site : returns)
                    if (!child(site, "expression").isEmpty()) return Match.NO;
                return Match.YES;
            }
            Match completion = abruptCompletion(body, 0);
            if (completion != Match.YES) return completion;
            if (returns.isEmpty()) return Match.UNKNOWN;
            for (Map<String, Object> site : returns) {
                Map<String, Object> returned = child(site, "expression");
                if (returned.isEmpty()) return Match.NO;
                Result value = expression(id(returned.get("id")), depth + 1);
                if (value.blocked) return Match.NO;
                if (value.type.isEmpty()) return Match.UNKNOWN;
                Match constraint = infer(descriptor.returns, value.type, owner, variables, depth + 1);
                if (constraint != Match.YES) return constraint;
            }
            return Match.YES;
        } finally {
            provisionalResults.pop(); uncertainPhase = previousPhase;
            if (previous == null) expectedOverrides.remove(key); else expectedOverrides.put(key, previous);
            if (previousOwner == null) inferenceOwners.remove(key); else inferenceOwners.put(key, previousOwner);
            if (previousModeled) modeledExpected.add(key); else modeledExpected.remove(key);
        }
    }

    /** Only a proven absence of an arity-compatible static alternative permits receiver seeding. */
    private Match referenceStaticity(Map<String, Object> receiver, String name, int count,
            Map<String, Object> firstInput, int depth) throws IOException {
        step(depth);
        if (!kind(receiver).equals("DECLARED")) return Match.UNKNOWN;
        boolean statics = false, instances = false;
        boolean instancePossible = types.convert(firstInput, receiver, false) != Match.NO;
        Deque<Map<String, Object>> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(receiver);
        while (!pending.isEmpty()) {
            step(depth);
            Map<String, Object> current = pending.removeFirst();
            String owner = text(current.get("element"));
            if (!visited.add(owner)) continue;
            if (visited.size() > 256) return Match.UNKNOWN;
            Map<String, Object> source = sources.get(owner);
            List<Map<String, Object>> declarations;
            String ownerKind;
            if (source != null) {
                declarations = members.getOrDefault(owner, List.of());
                ownerKind = text(map(source.get("attributes")).get("element_kind"));
            } else {
                Map<String, Object> found = binary.readClass(owner);
                if (found == null || Boolean.FALSE.equals(map(found.get("declaration")).get("members_complete")))
                    return Match.UNKNOWN;
                declarations = binaryMembers(owner, found);
                int access = ((Number) map(found.get("declaration")).get("access")).intValue();
                ownerKind = (access & 0x200) != 0 ? "INTERFACE" : "CLASS";
            }
            for (Map<String, Object> declaration : declarations) {
                if (!name.equals(declaration.get("name"))) continue;
                Map<String, Object> attrs = map(declaration.get("attributes"));
                List<Object> modifiers = list(attrs.get("modifiers"));
                if (source == null && !modifiers.contains("public")) continue;
                int arity = list(attrs.get("parameter_type_models")).size();
                boolean varargs = Boolean.TRUE.equals(attrs.get("varargs"));
                if (modifiers.contains("static")) {
                    if (ownerKind.equals("INTERFACE") && !owner.equals(receiver.get("element"))) continue;
                    if (arity == count || varargs && count >= arity - 1) statics = true;
                } else if (instancePossible && (arity == count - 1 || varargs && count - 1 >= arity - 1))
                    instances = true;
            }
            pending.addAll(types.parents(current, "static-reference:" + owner));
        }
        return statics && instances ? Match.UNKNOWN : statics ? Match.YES : Match.NO;
    }

    private PolyMatch polyCompatible(Map<String, Object> poly, Map<String, Object> parameter,
            boolean modeledInput, int depth)
            throws IOException {
        NodeKey key = key(poly);
        boolean previousModeled = modeledExpected.contains(key);
        if (modeledInput) modeledExpected.add(key); else modeledExpected.remove(key);
        try {
            return polyProbe(poly, parameter, depth);
        } finally {
            if (previousModeled) modeledExpected.add(key); else modeledExpected.remove(key);
        }
    }

    private PolyMatch polyProbe(Map<String, Object> poly, Map<String, Object> parameter, int depth)
            throws IOException {
        step(depth);
        Sam descriptor = sam(parameter, depth + 1);
        if (descriptor == null) return new PolyMatch(Match.UNKNOWN, false);
        NodeKey key = key(poly);
        Map<String, Object> previous = expectedOverrides.put(key, parameter);
        int previousPhase = uncertainPhase;
        provisionalResults.push(new HashMap<>());
        try {
            if ("MEMBER_REFERENCE".equals(poly.get("kind"))) {
                Map<String, Object> call = calls.get(key);
                if (call == null) return new PolyMatch(Match.UNKNOWN, false);
                Result selected = resolve(call, depth + 1);
                // A failed contextual check may retain javac's original expression
                // type. That fallback is not a verified referenced method result.
                if (selected.blocked) return new PolyMatch(Match.NO, false);
                return new PolyMatch(selected.modeled || !selected.target.isEmpty()
                        && !selected.type.isEmpty() ? Match.YES : Match.UNKNOWN,
                        selected.modeled);
            }
            if (!"LAMBDA_EXPRESSION".equals(poly.get("kind"))) return new PolyMatch(Match.UNKNOWN, false);
            List<Object> parameters = list(map(poly.get("roles")).get("parameters"));
            if (parameters.size() != descriptor.parameters.size()) return new PolyMatch(Match.NO, false);
            for (int i = 0; i < parameters.size(); i++) {
                Map<String, Object> written = child(node(id(parameters.get(i))), "type");
                if (written.isEmpty()) continue;
                Match match = types.same(expression(id(written.get("id")), depth + 1).type,
                        descriptor.parameters.get(i), 0);
                if (match != Match.YES) return new PolyMatch(match, false);
            }
            Map<String, Object> body = child(poly, "body");
            boolean voidReturn = kind(descriptor.returns).equals("VOID");
            if (!"BLOCK".equals(body.get("kind"))) {
                if (voidReturn)
                    return new PolyMatch(statementExpression(text(body.get("kind"))) ? Match.YES : Match.NO,
                            modeledExpression(body, depth + 1));
                Result value = expression(id(body.get("id")), depth + 1);
                if (value.blocked) return new PolyMatch(Match.NO, false);
                return new PolyMatch(types.convert(value.type, descriptor.returns, true),
                        modeledExpression(body, depth + 1));
            }
            List<Map<String, Object>> returns = new ArrayList<>();
            collectReturns(body, returns, depth + 1);
            if (!voidReturn) {
                Match completion = abruptCompletion(body, 0);
                if (completion != Match.YES) return new PolyMatch(completion, false);
            }
            Match result = Match.YES;
            for (Map<String, Object> statement : returns) {
                Map<String, Object> value = child(statement, "expression");
                if (voidReturn != value.isEmpty()) return new PolyMatch(Match.NO, false);
                if (!voidReturn) {
                    Result expression = expression(id(value.get("id")), depth + 1);
                    if (expression.blocked) return new PolyMatch(Match.NO, false);
                    Match match = types.convert(expression.type, descriptor.returns, true);
                    if (match == Match.NO) return new PolyMatch(match, false);
                    if (match == Match.UNKNOWN) result = match;
                }
            }
            // Constructor recovery preserves its own target. Its unchanged instance
            // type does not by itself establish modeled input for the enclosing call.
            return new PolyMatch(result, modeledBlock(body, depth + 1));
        } finally {
            provisionalResults.pop(); uncertainPhase = previousPhase;
            if (previous == null) expectedOverrides.remove(key);
            else expectedOverrides.put(key, previous);
        }
    }

    private static boolean statementExpression(String kind) {
        return Set.of("METHOD_INVOCATION", "NEW_CLASS", "ASSIGNMENT", "MULTIPLY_ASSIGNMENT",
                "DIVIDE_ASSIGNMENT", "REMAINDER_ASSIGNMENT", "PLUS_ASSIGNMENT", "MINUS_ASSIGNMENT",
                "LEFT_SHIFT_ASSIGNMENT", "RIGHT_SHIFT_ASSIGNMENT", "UNSIGNED_RIGHT_SHIFT_ASSIGNMENT",
                "AND_ASSIGNMENT", "XOR_ASSIGNMENT", "OR_ASSIGNMENT", "PREFIX_INCREMENT",
                "PREFIX_DECREMENT", "POSTFIX_INCREMENT", "POSTFIX_DECREMENT").contains(kind);
    }

    private void collectReturns(Map<String, Object> tree, List<Map<String, Object>> returns, int depth) {
        step(depth);
        String kind = text(tree.get("kind"));
        if (Set.of("LAMBDA_EXPRESSION", "CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(kind))
            return;
        if (kind.equals("RETURN")) { returns.add(tree); return; }
        for (Object value : map(tree.get("roles")).values()) {
            if (value instanceof Number) collectReturns(node(id(value)), returns, depth + 1);
            else for (Object item : list(value)) collectReturns(node(id(item)), returns, depth + 1);
        }
    }

    /** Frozen simple completion proof; complex control flow stays UNKNOWN. */
    private Match abruptCompletion(Map<String, Object> tree, int depth) {
        if (tree.isEmpty()) return Match.NO;
        step(depth);
        return switch (text(tree.get("kind"))) {
            case "RETURN", "THROW" -> Match.YES;
            case "BLOCK" -> {
                Match completion = Match.NO;
                for (Object statement : list(map(tree.get("roles")).get("statements"))) {
                    Match next = abruptCompletion(node(id(statement)), depth + 1);
                    if (next == Match.YES) { completion = Match.YES; break; }
                    if (next == Match.UNKNOWN) completion = Match.UNKNOWN;
                }
                yield completion;
            }
            case "IF" -> {
                Match thenPart = abruptCompletion(child(tree, "then"), depth + 1);
                Match elsePart = abruptCompletion(child(tree, "else"), depth + 1);
                yield thenPart == Match.YES && elsePart == Match.YES ? Match.YES
                        : thenPart == Match.UNKNOWN || elsePart == Match.UNKNOWN ? Match.UNKNOWN : Match.NO;
            }
            case "TRY" -> {
                Match cleanup = abruptCompletion(child(tree, "finally"), depth + 1);
                if (cleanup != Match.NO) yield cleanup;
                Match body = abruptCompletion(child(tree, "body"), depth + 1);
                for (Object handler : list(map(tree.get("roles")).get("catches"))) {
                    Match branch = abruptCompletion(child(node(id(handler)), "body"), depth + 1);
                    if (branch == Match.NO) { body = Match.NO; break; }
                    if (branch == Match.UNKNOWN && body != Match.NO) body = Match.UNKNOWN;
                }
                yield body;
            }
            case "VARIABLE", "EXPRESSION_STATEMENT", "EMPTY_STATEMENT", "ASSERT" -> Match.NO;
            default -> Match.UNKNOWN;
        };
    }

    private boolean explicitType(Map<String, Object> type, int depth) {
        step(depth);
        if (Boolean.FALSE.equals(type.get("complete"))) return false;
        if (kind(type).equals("ARRAY")) {
            Map<String, Object> component = map(type.get("component"));
            return Set.of("BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "CHAR", "FLOAT", "DOUBLE")
                            .contains(kind(component))
                    || explicitType(component, depth + 1);
        }
        if (kind(type).equals("TYPEVAR")) return !variable(type).isEmpty();
        if (!kind(type).equals("DECLARED") || text(type.get("element")).isEmpty()) return false;
        for (Object raw : list(type.get("arguments"))) {
            Map<String, Object> argument = map(raw);
            if (kind(argument).equals("WILDCARD")) {
                if (argument.get("extends") instanceof Map
                        && !explicitType(map(argument.get("extends")), depth + 1)) return false;
                if (argument.get("super") instanceof Map
                        && !explicitType(map(argument.get("super")), depth + 1)) return false;
            } else if (!explicitType(argument, depth + 1)) return false;
        }
        return !(type.get("enclosing") instanceof Map)
                || explicitType(map(type.get("enclosing")), depth + 1);
    }

    private Match infer(
            Map<String, Object> parameter,
            Map<String, Object> actual,
            String owner,
            Map<String, Map<String, Object>> variables,
            int depth)
            throws IOException {
        step(depth);
        // Null constrains no generic variable, matching the frozen resolver.
        if (kind(actual).equals("NULL") && mentionsFormal(parameter, owner, 0)) return Match.YES;
        // A fixed wildcard constrains containment, not conversion to its bound.
        // Collector<CharSequence,?,String> is valid for Collector<? super String,A,R>.
        if (!mentionsFormal(parameter, owner, 0) && kind(parameter).equals("WILDCARD"))
            return types.contained(actual, parameter, 0);
        if (!mentionsFormal(parameter, owner, depth + 1)
                && kind(parameter).equals("DECLARED") && kind(actual).equals("DECLARED")
                && types.convert(actual, parameter, true) == Match.NO) return Match.NO;
        if (kind(parameter).equals("TYPEVAR") && owner.equals(parameter.get("owner"))) {
            if (Set.of("ERROR", "UNKNOWN", "NULL", "").contains(kind(actual))) return Match.UNKNOWN;
            String key = variable(parameter);
            if (kind(actual).equals("TYPEVAR") && key.equals(variable(actual))) return Match.YES;
            Map<String, Object> value = boxed(actual), previous = variables.get(key);
            if (previous != null) return types.same(previous, value, 0);
            variables.put(key, value);
            return Match.YES;
        }
        if (kind(parameter).equals("WILDCARD")) {
            if (parameter.get("super") instanceof Map)
                return infer(map(parameter.get("super")), actual, owner, variables, depth + 1);
            if (parameter.get("extends") instanceof Map)
                return infer(map(parameter.get("extends")), actual, owner, variables, depth + 1);
            return Match.YES;
        }
        if (kind(parameter).equals("ARRAY") && kind(actual).equals("ARRAY"))
            return infer(
                    map(parameter.get("component")),
                    map(actual.get("component")),
                    owner,
                    variables,
                    depth + 1);
        if (kind(parameter).equals("DECLARED") && kind(actual).equals("DECLARED")) {
            Projection projection = types.project(actual, text(parameter.get("element")));
            if (projection.match() != Match.YES)
                return Match.YES; // Conversion below decides applicability.
            List<Object> params = list(parameter.get("arguments")),
                    values = list(projection.type().get("arguments"));
            if (params.size() == values.size())
                for (int i = 0; i < params.size(); i++) {
                    Match constraint = infer(map(params.get(i)), map(values.get(i)), owner, variables, depth + 1);
                    if (constraint != Match.YES) return constraint;
                }
        }
        return Match.YES;
    }

    private boolean unboundTarget(Map<String, Object> type, String owner, int depth) {
        step(depth);
        if (!Boolean.TRUE.equals(type.get("complete"))) return false;
        if (kind(type).equals("TYPEVAR")) return !text(type.get("owner")).isEmpty()
                && !owner.equals(type.get("owner"));
        for (Object raw : list(type.get("arguments")))
            if (unboundTarget(map(raw), owner, depth + 1)) return true;
        for (String key : List.of("component", "upper", "lower", "extends", "super"))
            if (type.get(key) instanceof Map && unboundTarget(map(type.get(key)), owner, depth + 1))
                return true;
        return false;
    }

    private boolean moreSpecific(Candidate first, Candidate second, int depth) throws IOException {
        step(depth);
        if (first.parameters.size() != second.parameters.size()) return false;
        boolean strict = false;
        for (int i = 0; i < first.parameters.size(); i++) {
            if (types.convert(first.parameters.get(i), second.parameters.get(i), false)
                    != Match.YES) return false;
            strict |= types.same(first.parameters.get(i), second.parameters.get(i), 0) == Match.NO;
        }
        if (first.phase == 2 && second.phase == 2) {
            if (types.convert(first.varargElement, second.varargElement, false) != Match.YES)
                return false;
            strict |= types.same(first.varargElement, second.varargElement, 0) == Match.NO;
        }
        if (strict) return true;
        String a = text(first.declaration.get("parent")),
                b = text(second.declaration.get("parent"));
        return !a.equals(b) && types.project(named(a), b).match() == Match.YES;
    }

    private boolean accessible(
            Map<String, Object> declaration,
            Map<String, Object> call,
            Map<String, Object> receiver,
            int inheritedDepth,
            int depth)
            throws IOException {
        step(depth);
        Map<String, Object> attrs = map(call.get("attributes"));
        List<Object> mods = list(map(declaration.get("attributes")).get("modifiers"));
        String caller = text(attrs.get("caller_type")), owner = text(declaration.get("parent"));
        boolean constructor = text(declaration.get("signature")).contains("#<init>(");
        if (!constructor
                && Boolean.TRUE.equals(
                        attrs.getOrDefault(
                                "recovery_receiver_type_qualifier",
                                attrs.get("receiver_type_qualifier")))
                && !mods.contains("static")) return false;
        if (!constructor
                && Boolean.TRUE.equals(attrs.get("caller_static"))
                && !"method_reference".equals(attrs.get("mechanism"))
                && !text(call.get("name")).contains(".")
                && !mods.contains("static")) return false;
        if (mods.contains("private"))
            return inheritedDepth == 0 && nest(caller).equals(nest(owner)) && !caller.isEmpty();
        if (mods.contains("public")) return typeAccessible(owner, caller, depth + 1);
        if (pkg(caller).equals(pkg(owner)) && !caller.isEmpty()) return true;
        if (mods.contains("protected")
                && !constructor
                && types.project(named(caller), owner).match() == Match.YES)
            return mods.contains("static") || types.project(receiver, caller).match() == Match.YES;
        return false;
    }

    private boolean typeAccessible(String owner, String caller, int depth) throws IOException {
        step(depth);
        Map<String, Object> declaration = sources.get(owner);
        if (declaration == null) {
            Map<String, Object> found = binary.readClass(owner);
            return found != null
                    && ((((Number) map(found.get("declaration")).get("access")).intValue() & 1) != 0
                            || pkg(owner).equals(pkg(caller)));
        }
        Map<String, Object> attrs = map(declaration.get("attributes"));
        List<Object> mods = list(attrs.get("modifiers"));
        if (mods.contains("private") && !nest(owner).equals(nest(caller))) return false;
        if (!mods.contains("public")
                && !mods.contains("private")
                && !pkg(owner).equals(pkg(caller))) return false;
        String enclosing = text(attrs.get("enclosing_type"));
        return enclosing.isEmpty()
                || !enclosing.contains("#") && typeAccessible(enclosing, caller, depth + 1);
    }

    private String nest(String type) {
        Set<String> seen = new HashSet<>();
        while (seen.add(type) && seen.size() < 64) {
            Map<String, Object> declaration = sources.get(type);
            if (declaration == null) return type;
            String enclosing = text(map(declaration.get("attributes")).get("enclosing_type"));
            if (enclosing.isEmpty()) return type;
            int hash = enclosing.indexOf('#');
            type = hash < 0 ? enclosing : enclosing.substring(0, hash);
        }
        return "";
    }

    private static String pkg(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? "" : type.substring(0, dot);
    }

    private List<Map<String, Object>> binaryMembers(String owner, Map<String, Object> found) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : map(found.get("members")).entrySet()) {
            Map<String, Object> raw = map(entry.getValue());
            if (!text(raw.get("kind")).equals("METHOD")) continue;
            int access = ((Number) raw.get("access")).intValue();
            if ((access & (0x40 | 0x1000)) != 0)
                continue; // Bridges/synthetic methods do not create overload alternatives.
            List<Object> modifiers = new ArrayList<>();
            for (var flag :
                    Map.of(
                                    1,
                                    "public",
                                    2,
                                    "private",
                                    4,
                                    "protected",
                                    8,
                                    "static",
                                    0x400,
                                    "abstract")
                            .entrySet())
                if ((access & flag.getKey()) != 0) modifiers.add(flag.getValue());
            modifiers.sort(Comparator.comparing(Object::toString));
            Map<String, Object> type = map(raw.get("type_model"));
            String signature = entry.getKey();
            result.add(
                    Json.object(
                            "signature",
                            signature,
                            "parent",
                            owner,
                            "name",
                            signature.substring(signature.indexOf('#') + 1, signature.indexOf('(')),
                            "attributes",
                            Json.object(
                                    "element_kind",
                                    "METHOD",
                                    "modifiers",
                                    modifiers,
                                    "varargs",
                                    (access & 0x80) != 0,
                                    "type_model",
                                    type,
                                    "parameter_type_models",
                                    type.get("parameters"),
                                    "return_type_model",
                                    type.get("return"),
                                    "type_parameter_models",
                                    raw.getOrDefault("type_parameters", type.get("type_parameters")),
                                    "binary_class",
                                    found.get("declaration"),
                                    "binary_member",
                                    raw)));
        }
        return result;
    }

    private void addExternal(Map<String, Object> declaration) {
        String signature = text(declaration.get("signature"));
        known.put(signature, declaration);
        Json.list(model.get("external"))
                .add(
                        Json.object(
                                "signature",
                                signature,
                                "name",
                                declaration.get("name"),
                                "attributes",
                                declaration.get("attributes")));
    }
}
