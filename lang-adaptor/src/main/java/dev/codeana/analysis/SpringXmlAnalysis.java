package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionPoint;
import dev.codeana.common.IndexedRecords;
import dev.codeana.common.Json;

import java.nio.file.*;
import java.util.*;

/** Spring configuration evidence. It never selects runtime beans or executes factories. */
public final class SpringXmlAnalysis {
    private static final String NS = "http://www.springframework.org/schema/beans",
            PRODUCER = "spring-model-v1";
    private static final Set<String> VALUE_NODES =
            Set.of(
                    "property",
                    "constructor-arg",
                    "map",
                    "list",
                    "set",
                    "array",
                    "entry",
                    "key",
                    "ref",
                    "idref",
                    "value",
                    "null",
                    "props",
                    "prop",
                    "bean",
                    "qualifier",
                    "meta");
    private static final Set<String> KNOWN_NODES = new HashSet<>();

    static {
        KNOWN_NODES.addAll(VALUE_NODES);
        KNOWN_NODES.addAll(
                Set.of(
                        "beans",
                        "alias",
                        "import",
                        "description",
                        "qualifier",
                        "meta",
                        "lookup-method",
                        "replaced-method"));
    }

    private final Map<String, Object> context;
    private BinaryIndex binary;
    private int lookupSteps;

    private record Lookup(List<Map<String, Object>> candidates, boolean complete) {}

    private record Located(String type, int depth) {}

    private final List<Object> output = new ArrayList<>();
    private final IndexedRecords declarations;
    private final Map<String, Map<String, Object>> externalAdditions = new HashMap<>();
    private final Map<String, List<String>> members = new HashMap<>();
    private final Map<String, List<Bean>> names = new HashMap<>();
    private final Map<String, List<String>> aliases = new HashMap<>();
    private final List<Doc> documents = new ArrayList<>();
    private final Map<String, Doc> paths = new HashMap<>();
    private final Map<XmlModel.Node, Bean> beans = new IdentityHashMap<>();
    private final Map<XmlModel.Node, String> nodeIds = new IdentityHashMap<>();

    private record Doc(String path, String world, XmlModel.Document model, Set<String> gaps) {}

    private static final class Bean {
        final Doc doc;
        final XmlModel.Node node;
        final String id, name;
        final List<String> names;
        String type;

        Bean(Doc doc, XmlModel.Node node, String id, String name, List<String> names, String type) {
            this.doc = doc;
            this.node = node;
            this.id = id;
            this.name = name;
            this.names = names;
            this.type = type;
        }
    }

    private SpringXmlAnalysis(Map<String, Object> context, List<Object> core) {
        this.context = context;
        declarations = new IndexedRecords(core);
        for (int i = 0; i < core.size(); i++) {
            Map<String, Object> fact = Json.map(core.get(i));
            if (Set.of("declaration", "external_symbol").contains(fact.get("kind"))) {
                String signature = Json.string(fact, "signature");
                Map<String, Object> previous = declaration(signature);
                if (previous == null || rank(fact) > rank(previous))
                    declarations.index(signature, i);
            }
            if ("java.bean".equals(fact.get("relation"))) {
                Map<String, Object> attributes = Json.map(fact.get("attributes"));
                String name = Json.string(fact, "name"),
                        type = (String) attributes.getOrDefault("type", "");
                Bean bean =
                        new Bean(null, null, Json.string(fact, "id"), name, List.of(name), type);
                names.computeIfAbsent(name, ignored -> new ArrayList<>()).add(bean);
                for (Object alias : list(attributes.get("aliases")))
                    if (alias instanceof String s && !s.equals(name))
                        names.computeIfAbsent(s, ignored -> new ArrayList<>()).add(bean);
            }
        }
        for (Map<String, Object> declaration : declarations.values()) {
            String signature = Json.string(declaration, "signature");
            int member = signature.indexOf('#');
            if (member > 0)
                members.computeIfAbsent(
                                signature.substring(0, member), ignored -> new ArrayList<>())
                        .add(signature);
        }
        for (List<String> values : members.values()) values.sort(Comparator.naturalOrder());
    }

    private Map<String, Object> declaration(String signature) {
        Map<String, Object> original = declarations.get(signature);
        return original == null ? externalAdditions.get(signature) : original;
    }

    private int rank(Map<String, Object> fact) {
        return ("declaration".equals(fact.get("kind")) ? 2 : 0)
                + (Objects.equals(Json.map(fact.get("owner")).get("world"), context.get("world"))
                        ? 1
                        : 0);
    }

    public static List<Object> extract(Map<String, Object> context, List<Object> core)
            throws Exception {
        if (Json.list(context.get("resources")).isEmpty()) return List.of();
        SpringXmlAnalysis analysis = new SpringXmlAnalysis(context, core);
        try {
            analysis.read();
            analysis.emit();
            return analysis.output;
        } finally {
            if (analysis.binary != null) analysis.binary.close();
        }
    }

    private void check() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
    }

    private static List<Object> list(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }

    private static boolean spring(XmlModel.Node node) {
        return node.namespace.isEmpty() || NS.equals(node.namespace);
    }

    private static List<String> split(String value) {
        return value.isBlank() ? List.of() : Arrays.asList(value.trim().split("[,;\\s]+"));
    }

    private String identity(Doc doc, XmlModel.Node node, String role) {
        return Json.key(
                "spring-xml",
                Json.string(context, "context"),
                doc.world,
                doc.path,
                role,
                Integer.toString(node == null ? 0 : node.ordinal));
    }

    private Map<String, Object> fact(Doc doc, XmlModel.Node node, String role, String kind) {
        if (output.size() >= 100000) throw new IllegalStateException("SPRING_XML_FACT_BUDGET");
        Map<String, Object> fact =
                Json.object(
                        "id",
                        identity(doc, node, role),
                        "kind",
                        kind,
                        "owner",
                        Json.object(
                                "world",
                                doc.world,
                                "producer",
                                PRODUCER,
                                "kind",
                                "resource",
                                "id",
                                doc.path),
                        "source",
                        doc.path,
                        "span",
                        node == null ? null : node.span(),
                        "name",
                        "",
                        "signature",
                        "",
                        "parent",
                        "",
                        "target",
                        "",
                        "relation",
                        "",
                        "status",
                        "modeled",
                        "origin",
                        PRODUCER,
                        "evidence_complete",
                        false,
                        "declaration_resolved",
                        false,
                        "attributes",
                        new TreeMap<String, Object>());
        output.add(fact);
        return fact;
    }

    private Map<String, Object> facet(
            Doc doc,
            XmlModel.Node node,
            String role,
            String namespace,
            Map<String, Object> attributes) {
        Map<String, Object> fact = fact(doc, node, role, "facet");
        fact.put("relation", namespace);
        attributes.put("schema_version", 1);
        attributes.put("runtime_confirmed", false);
        if (node != null) attributes.put("position", node.position());
        fact.put("attributes", attributes);
        return fact;
    }

    private void coverage(Doc doc) {
        Map<String, Object> fact = fact(doc, null, "coverage", "coverage");
        fact.put("status", doc.gaps.isEmpty() ? "complete" : "partial");
        fact.put("evidence_complete", doc.gaps.isEmpty());
        fact.put(
                "attributes",
                Json.object(
                        "reason",
                        doc.gaps.isEmpty() ? "Static XML configuration tree" : "Static XML gaps",
                        "gaps",
                        new ArrayList<>(doc.gaps),
                        "runtime_confirmed",
                        false));
    }

    private void read() throws Exception {
        long bytes = 0;
        int nodes = 0;
        for (Object raw : list(context.get("resources"))) {
            check();
            Map<String, Object> resource = Json.map(raw), unit = Json.map(resource.get("unit"));
            String path = Json.string(unit, "path"), world = Json.string(unit, "world");
            try {
                bytes += Files.size(Path.of(Json.string(resource, "input")));
                if (bytes > 64L * 1024 * 1024)
                    throw new org.xml.sax.SAXException("XML_PROJECT_BYTE_BUDGET");
                XmlModel.Document model = XmlModel.read(resource);
                if (model.root() == null
                        || !spring(model.root())
                        || !model.root().name.equals("beans")) {
                    coverage(new Doc(path, world, model, new TreeSet<>()));
                    continue;
                }
                nodes += model.nodes().size();
                if (nodes > 100000) throw new org.xml.sax.SAXException("XML_PROJECT_NODE_BUDGET");
                Doc doc = new Doc(path, world, model, new TreeSet<>());
                documents.add(doc);
                paths.put(path, doc);
                for (XmlModel.Node node : model.nodes()) {
                    check();
                    if (!spring(node)) {
                        doc.gaps.add("unsupported_namespace:" + node.namespace);
                        continue;
                    }
                    if (!KNOWN_NODES.contains(node.name))
                        doc.gaps.add("unsupported_element:" + node.name);
                    if (Set.of("lookup-method", "replaced-method").contains(node.name))
                        doc.gaps.add("dynamic_member_configuration:" + node.name);
                    if (node.name.equals("alias")) {
                        if (node.attr("alias").isBlank() || node.attr("name").isBlank())
                            doc.gaps.add("invalid_alias");
                        else
                            aliases.computeIfAbsent(
                                            node.attr("alias"), ignored -> new ArrayList<>())
                                    .add(node.attr("name"));
                    }
                    if (!node.name.equals("bean")) continue;
                    LinkedHashSet<String> registered = new LinkedHashSet<>();
                    if (!node.attr("id").isBlank()) registered.add(node.attr("id"));
                    registered.addAll(split(node.attr("name")));
                    String name =
                            registered.isEmpty()
                                    ? node.attr("class").isBlank()
                                            ? "@bean:" + path + ":" + node.ordinal
                                            : node.attr("class")
                                    : registered.getFirst();
                    if (registered.isEmpty()
                            && node.parent == model.root()
                            && !node.attr("class").isBlank()) {
                        registered.add(name);
                    }
                    Bean bean =
                            new Bean(
                                    doc,
                                    node,
                                    identity(doc, node, "bean"),
                                    name,
                                    List.copyOf(registered),
                                    node.attr("class"));
                    beans.put(node, bean);
                    // Nested definitions have identity and type evidence, but no global name
                    // registration.
                    if (node.parent != null && node.parent.name.equals("beans"))
                        for (String alias : registered)
                            names.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(bean);
                }
            } catch (ExtensionPoint.GateFailure fatal) {
                throw fatal;
            } catch (org.xml.sax.SAXException | java.nio.charset.CharacterCodingException invalid) {
                check();
                coverage(
                        new Doc(
                                path,
                                world,
                                null,
                                new TreeSet<>(Set.of(String.valueOf(invalid.getMessage())))));
            }
        }
    }

    private List<Bean> named(String name, Set<String> visited) {
        check();
        if (visited.size() >= 256 || !visited.add(name)) return List.of();
        LinkedHashMap<String, Bean> found = new LinkedHashMap<>();
        for (Bean bean : names.getOrDefault(name, List.of())) found.put(bean.id, bean);
        for (String target : aliases.getOrDefault(name, List.of()))
            for (Bean bean : named(target, new HashSet<>(visited))) found.put(bean.id, bean);
        return List.copyOf(found.values());
    }

    private List<Bean> named(String name) {
        return named(name, new HashSet<>());
    }

    private String type(Bean bean, Set<String> visited) throws Exception {
        if (bean.node == null) return bean.type;
        if (visited.size() >= 128 || !visited.add(bean.id)) return "";
        String factory = bean.node.attr("factory-method");
        if (!factory.isBlank()) {
            String owner = bean.node.attr("class");
            if (!bean.node.attr("factory-bean").isBlank()) {
                List<Bean> found = named(bean.node.attr("factory-bean"));
                owner = found.size() == 1 ? type(found.getFirst(), visited) : "";
            }
            Lookup found =
                    typedMethods(
                            owner,
                            factory,
                            argumentHints(bean, visited),
                            bean.node.attr("factory-bean").isBlank());
            Set<String> returns = new LinkedHashSet<>();
            boolean allKnown = found.complete && !found.candidates.isEmpty();
            for (Map<String, Object> candidate : found.candidates) {
                Object model = Json.map(candidate.get("attributes")).get("return_type_model");
                if (model instanceof Map
                        && Json.map(model).get("element") instanceof String s
                        && !s.isBlank()) returns.add(s);
                else allKnown = false;
            }
            if (allKnown && returns.size() == 1) return returns.iterator().next();
            bean.doc.gaps.add("factory_return_type_unknown:" + factory);
            return "";
        }
        if (!bean.node.attr("class").isEmpty()) return bean.node.attr("class");
        String parent = bean.node.attr("parent");
        if (!parent.isEmpty()) {
            List<Bean> found = named(parent);
            if (found.size() == 1) return type(found.getFirst(), visited);
        }
        return "";
    }

    private static boolean sourceDeclaration(Map<String, Object> fact) {
        if ("declaration".equals(fact.get("kind"))) return true;
        Object origin = Json.map(fact.get("attributes")).get("source_origin");
        return origin instanceof Map && Json.map(origin).get("path") instanceof String path
                && !path.isEmpty();
    }

    private Map<String, Object> binaryClass(String name) throws Exception {
        if (name.isBlank()) return null;
        Map<String, Object> source = declaration(name);
        if (source != null && sourceDeclaration(source)) return null;
        if (binary == null)
            binary = new BinaryIndex(context, Path.of(Json.string(context, "target_java_home")));
        return binary.readClass(name);
    }

    private Map<String, Object> external(
            String signature, String name, String parent, Map<String, Object> attributes) {
        Map<String, Object> previous = declaration(signature);
        if (previous != null) return previous;
        if (output.size() >= 100000) throw new IllegalStateException("SPRING_XML_FACT_BUDGET");
        attributes.put("source_available", false);
        attributes.put("binding_origin", "binary_metadata");
        String id =
                Json.key(
                        "spring-xml-binary",
                        Json.string(context, "context"),
                        Json.string(context, "world"),
                        signature);
        Map<String, Object> fact =
                Json.object(
                        "id",
                        id,
                        "kind",
                        "external_symbol",
                        "owner",
                        Json.object(
                                "world",
                                context.get("world"),
                                "producer",
                                PRODUCER,
                                "kind",
                                "context",
                                "id",
                                context.get("context")),
                        "source",
                        "",
                        "span",
                        null,
                        "name",
                        name,
                        "signature",
                        signature,
                        "parent",
                        parent,
                        "target",
                        "",
                        "relation",
                        "",
                        "status",
                        "possible",
                        "origin",
                        PRODUCER,
                        "evidence_complete",
                        false,
                        "declaration_resolved",
                        false,
                        "attributes",
                        attributes);
        externalAdditions.put(signature, fact);
        output.add(fact);
        return fact;
    }

    private Map<String, Object> classDeclaration(String name, Map<String, Object> found) {
        Map<String, Object> metadata = Json.map(found.get("declaration")),
                type = Json.map(metadata.get("type_model"));
        return external(
                name,
                name,
                "",
                Json.object(
                        "element_kind",
                        "CLASS",
                        "binary_class",
                        metadata,
                        "superclass_type_model",
                        type.get("superclass"),
                        "interface_type_models",
                        type.get("interfaces")));
    }

    private Map<String, Object> methodDeclaration(
            String signature, Map<String, Object> raw, Map<String, Object> owner) {
        String name = signature.substring(signature.indexOf('#') + 1, signature.indexOf('('));
        Map<String, Object> type = Json.map(raw.get("type_model"));
        List<String> modifiers = new ArrayList<>();
        int access = ((Number) raw.get("access")).intValue();
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
                                16,
                                "final",
                                1024,
                                "abstract")
                        .entrySet())
            if ((access & flag.getKey()) != 0) modifiers.add(flag.getValue());
        Collections.sort(modifiers);
        return external(
                signature,
                name,
                Json.string(owner, "id"),
                Json.object(
                        "element_kind",
                        name.equals("<init>") ? "CONSTRUCTOR" : "METHOD",
                        "modifiers",
                        modifiers,
                        "binary_member",
                        raw,
                        "parameter_type_models",
                        type.get("parameters"),
                        "return_type_model",
                        type.get("return")));
    }

    private Lookup methods(String type, String name, int arity, boolean staticOnly)
            throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean complete = true;
        Deque<Located> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Map<String, Integer> selected = new HashMap<>();
        if (!type.isBlank()) queue.add(new Located(type, 0));
        while (!queue.isEmpty() && visited.size() < 256) {
            check();
            if (++lookupSteps > 100000) return new Lookup(result, false);
            Located location = queue.remove();
            String owner = location.type;
            if (!visited.add(owner)) continue;
            Map<String, Object> found = binaryClass(owner), declaration = declaration(owner);
            Map<String, Object> attributes =
                    declaration == null ? Map.of() : Json.map(declaration.get("attributes"));
            if (found != null) {
                Map<String, Object> metadata = Json.map(found.get("declaration")),
                        classType = Json.map(metadata.get("type_model"));
                if (staticOnly
                        && !owner.equals(type)
                        && (((Number) metadata.get("access")).intValue() & 512) != 0) continue;
                attributes =
                        Json.object(
                                "superclass_type_model",
                                classType.get("superclass"),
                                "interface_type_models",
                                classType.get("interfaces"));
                if (Boolean.FALSE.equals(metadata.get("members_complete"))) complete = false;
                for (var entry : Json.map(found.get("members")).entrySet()) {
                    if (++lookupSteps > 100000) return new Lookup(result, false);
                    if ((lookupSteps & 255) == 0) check();
                    Map<String, Object> raw = Json.map(entry.getValue());
                    if (!"METHOD".equals(raw.get("kind"))
                            || !entry.getKey().startsWith(owner + "#" + name + "(")) continue;
                    Map<String, Object> model = Json.map(raw.get("type_model"));
                    if (list(model.get("parameters")).size() != arity
                            || staticOnly != ((((Number) raw.get("access")).intValue() & 8) != 0))
                        continue;
                    String callable = entry.getKey().substring(owner.length());
                    Integer depth = selected.putIfAbsent(callable, location.depth);
                    if (depth == null || depth == location.depth)
                        result.add(
                                methodDeclaration(
                                        entry.getKey(), raw, classDeclaration(owner, found)));
                }
            } else if (declaration != null && sourceDeclaration(declaration)) {
                if (staticOnly
                        && !owner.equals(type)
                        && "INTERFACE".equals(attributes.get("element_kind"))) continue;
                for (String memberIdentity : members.getOrDefault(owner, List.of())) {
                    Map<String, Object> member = declaration(memberIdentity);
                    if (++lookupSteps > 100000) return new Lookup(result, false);
                    Map<String, Object> memberAttributes = Json.map(member.get("attributes"));
                    if (name.equals(member.get("name"))
                            && Set.of("METHOD", "CONSTRUCTOR")
                                    .contains(memberAttributes.get("element_kind"))
                            && list(memberAttributes.get("parameter_type_models")).size() == arity
                            && staticOnly
                                    == list(memberAttributes.get("modifiers")).contains("static")) {
                        String signature = Json.string(member, "signature"),
                                callable = signature.substring(signature.indexOf('#'));
                        Integer depth = selected.putIfAbsent(callable, location.depth);
                        if (depth == null || depth == location.depth) result.add(member);
                    }
                }
            } else complete = false;
            if (name.equals("<init>")) return new Lookup(result, complete);
            Object parent = attributes.get("superclass_type_model");
            if (parent instanceof Map
                    && Json.map(parent).get("element") instanceof String s
                    && !s.isEmpty()) queue.add(new Located(s, location.depth + 1));
            for (Object raw : list(attributes.get("interface_type_models")))
                if (Json.map(raw).get("element") instanceof String s && !s.isEmpty())
                    queue.add(new Located(s, location.depth + 1));
        }
        return new Lookup(result, complete && queue.isEmpty());
    }

    private List<String> argumentHints(Bean bean, Set<String> visited) throws Exception {
        List<XmlModel.Node> arguments =
                bean.node.children.stream()
                        .filter(n -> spring(n) && n.name.equals("constructor-arg"))
                        .toList();
        List<String> hints = new ArrayList<>(Collections.nCopies(arguments.size(), ""));
        boolean[] occupied = new boolean[arguments.size()];
        List<XmlModel.Node> unindexed = new ArrayList<>();
        for (XmlModel.Node argument : arguments) {
            if (argument.attr("index").isBlank()) {
                unindexed.add(argument);
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(argument.attr("index"));
            } catch (NumberFormatException invalid) {
                index = -1;
            }
            if (index < 0 || index >= hints.size() || occupied[index]) {
                bean.doc.gaps.add("invalid_constructor_argument_index:" + argument.attr("index"));
                return Collections.nCopies(arguments.size(), "");
            }
            occupied[index] = true;
            hints.set(index, typeHint(argument, new HashSet<>(visited)));
        }
        int index = 0;
        for (XmlModel.Node argument : unindexed) {
            while (occupied[index]) index++;
            occupied[index] = true;
            hints.set(index, typeHint(argument, new HashSet<>(visited)));
        }
        return hints;
    }

    private String typeHint(XmlModel.Node argument, Set<String> visited) throws Exception {
        if (!argument.attr("type").isBlank()) return argument.attr("type");
        String name = argument.attr("ref");
        for (XmlModel.Node child : argument.children)
            if (spring(child) && child.name.equals("ref")) {
                name = !child.attr("bean").isBlank() ? child.attr("bean") : child.attr("local");
                break;
            }
        if (!name.isBlank()) {
            List<Bean> found = named(name);
            return found.size() == 1 ? type(found.getFirst(), visited) : "";
        }
        return "";
    }

    private static String erased(String value) {
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (char c : value.trim().toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && !Character.isWhitespace(c)) result.append(c);
        }
        return result.toString().replace("...", "[]").replace('$', '.');
    }

    private static boolean sameHint(String parameter, String hint) {
        String left = erased(parameter), right = erased(hint);
        if (left.equals(right)) return true;
        // A qualified hint cannot select a different qualified type sharing the same simple name.
        if (left.indexOf('.') >= 0 && right.indexOf('.') >= 0) return false;
        return left.substring(left.lastIndexOf('.') + 1)
                .equals(right.substring(right.lastIndexOf('.') + 1));
    }

    private Lookup typedMethods(String owner, String name, List<String> hints, boolean staticOnly)
            throws Exception {
        Lookup found = methods(owner, name, hints.size(), staticOnly);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> candidate : found.candidates) {
            String signature = Json.string(candidate, "signature");
            int open = signature.indexOf('('), close = signature.lastIndexOf(')');
            if (open < 0 || close < open) continue;
            String parameters = signature.substring(open + 1, close);
            String[] names = parameters.isBlank() ? new String[0] : parameters.split(",", -1);
            boolean match = names.length == hints.size();
            for (int i = 0; match && i < names.length; i++)
                if (!hints.get(i).isBlank() && !sameHint(names[i], hints.get(i))) match = false;
            if (match) result.add(candidate);
        }
        return new Lookup(result, found.complete);
    }

    private void link(
            Doc doc,
            XmlModel.Node node,
            String role,
            String parent,
            String target,
            String relation,
            Map<String, Object> attributes) {
        Map<String, Object> edge = fact(doc, node, role, "reference");
        edge.put("parent", parent);
        edge.put("target", target);
        edge.put("relation", relation);
        edge.put("status", "possible");
        edge.put("attributes", attributes);
    }

    private void reference(
            Doc doc, XmlModel.Node node, String role, String parent, String name, String relation) {
        if (name.isBlank()) return;
        List<Bean> targets = named(name);
        Map<String, Object> metadata =
                facet(
                        doc,
                        node,
                        role,
                        "java.spring.xml",
                        Json.object(
                                "kind",
                                relation,
                                "bean",
                                name,
                                "candidate_count",
                                targets.size(),
                                "selection",
                                "possible",
                                "xml_attributes",
                                node.attributes));
        metadata.put("parent", parent);
        if (targets.size() != 1) doc.gaps.add("missing_or_ambiguous_bean:" + name);
        int i = 0;
        for (Bean target : targets)
            link(
                    doc,
                    node,
                    role + "-target-" + (i++),
                    Json.string(metadata, "id"),
                    target.id,
                    "config",
                    Json.object("bean", name));
    }

    private void member(
            Doc doc,
            XmlModel.Node node,
            String role,
            String parent,
            String type,
            String name,
            int arity,
            boolean staticOnly)
            throws Exception {
        member(doc, node, role, parent, type, name, Collections.nCopies(arity, ""), staticOnly);
    }

    private void member(
            Doc doc,
            XmlModel.Node node,
            String role,
            String parent,
            String type,
            String name,
            List<String> hints,
            boolean staticOnly)
            throws Exception {
        if (name.isBlank()) return;
        Lookup found = typedMethods(type, name, hints, staticOnly);
        List<Map<String, Object>> candidates = found.candidates;
        boolean typed = hints.stream().anyMatch(hint -> !hint.isBlank());
        if (!found.complete) doc.gaps.add("incomplete_member_lookup:" + type + "#" + name);
        Map<String, Object> detail =
                facet(
                        doc,
                        node,
                        role,
                        "java.spring.xml",
                        Json.object(
                                "kind",
                                role,
                                "type",
                                type,
                                "member",
                                name,
                                "candidate_count",
                                candidates.size(),
                                "arity",
                                hints.size(),
                                "parameter_type_hints",
                                hints,
                                "lookup_complete",
                                found.complete,
                                "selection",
                                "possible"));
        detail.put("parent", parent);
        if (candidates.size() != 1)
            doc.gaps.add("missing_or_ambiguous_member:" + type + "#" + name);
        int i = 0;
        for (Map<String, Object> candidate : candidates)
            link(
                    doc,
                    node,
                    role + "-target-" + (i++),
                    Json.string(detail, "id"),
                    Json.string(candidate, "id"),
                    "config_member",
                    Json.object(
                            "lookup",
                            name,
                            "applicability",
                            typed ? "explicit_or_bean_type_hints" : "name_and_arity_only"));
    }

    private void emit() throws Exception {
        // Register every bean identity first, then create cross-file links and nested config rows.
        for (Doc doc : documents)
            for (XmlModel.Node node : doc.model.nodes()) {
                Bean bean = beans.get(node);
                if (bean == null) continue;
                bean.type = type(bean, new HashSet<>());
                String factory = node.attr("factory-method");
                Map<String, Object> found = binaryClass(bean.type);
                Map<String, Object> declaration =
                        found == null ? declaration(bean.type) : classDeclaration(bean.type, found);
                Map<String, Object> registered =
                        facet(
                                doc,
                                node,
                                "bean",
                                "java.bean",
                                Json.object(
                                        "kind",
                                        "xml_bean",
                                        "name",
                                        bean.name,
                                        "type",
                                        bean.type,
                                        "aliases",
                                        bean.names,
                                        "abstract",
                                        node.attr("abstract").equalsIgnoreCase("true"),
                                        "parent_bean",
                                        node.attr("parent"),
                                        "factory_bean",
                                        node.attr("factory-bean"),
                                        "factory_method",
                                        factory,
                                        "scope",
                                        node.attr("scope"),
                                        "lazy_init",
                                        node.attr("lazy-init"),
                                        "selection",
                                        "possible",
                                        "xml_attributes",
                                        node.attributes,
                                        "nested",
                                        node.parent != null && !node.parent.name.equals("beans")));
                Json.map(registered.get("attributes")).put("xml_configuration_model",
                        SpringXmlConfiguration.describe(doc.model, node));
                registered.put("name", bean.name);
                if (declaration != null) registered.put("parent", declaration.get("id"));
                else if (!node.attr("abstract").equalsIgnoreCase("true") || !bean.type.isEmpty())
                    doc.gaps.add("unresolved_bean_type:" + bean.name);
                // Configuration identity and factory product identity differ. Keep
                // Java's class lookup here; Rust derives edges from these identities.
                String configured = node.attr("class");
                Map<String, Object> configuredOwner = declaration(configured);
                if (configuredOwner != null)
                    link(doc, node, "configured-owner", bean.id,
                            Json.string(configuredOwner, "id"), "config_owner", new TreeMap<>());
                if (!bean.type.isBlank()) {
                    Map<String, Object> configuredType = declaration;
                    if (configuredType == null) {
                        configuredType = external(bean.type, bean.type, "",
                                Json.object("element_kind", "CLASS", "configured_only", true));
                        Json.map(configuredType.get("attributes")).put("binding_origin", "xml_configuration");
                    }
                    link(doc, node, "configured-type", bean.id,
                            Json.string(configuredType, "id"), "config_type", new TreeMap<>());
                }
            }
        for (Doc doc : documents) {
            for (XmlModel.Node node : doc.model.nodes()) {
                check();
                if (!spring(node)) continue;
                Bean bean = beans.get(node);
                if (bean != null) {
                    Bean outer = enclosing(node);
                    if (outer != null)
                        link(
                                doc,
                                node,
                                "nested-bean",
                                nodeIds.getOrDefault(node.parent, outer.id),
                                bean.id,
                                "config_nested_bean",
                                Json.object("nested_in", outer.id));
                    reference(doc, node, "parent", bean.id, node.attr("parent"), "parent_bean");
                    reference(
                            doc,
                            node,
                            "factory-bean",
                            bean.id,
                            node.attr("factory-bean"),
                            "factory_bean");
                    for (String dependency : split(node.attr("depends-on")))
                        reference(
                                doc,
                                node,
                                "depends-" + dependency,
                                bean.id,
                                dependency,
                                "depends_on");
                    member(
                            doc,
                            node,
                            "init",
                            bean.id,
                            bean.type,
                            node.attr("init-method"),
                            0,
                            false);
                    if (!node.attr("destroy-method").equals("(inferred)"))
                        member(
                                doc,
                                node,
                                "destroy",
                                bean.id,
                                bean.type,
                                node.attr("destroy-method"),
                                0,
                                false);
                    if (!node.attr("factory-method").isBlank()) {
                        String factoryType = node.attr("class");
                        if (!node.attr("factory-bean").isBlank()) {
                            List<Bean> targets = named(node.attr("factory-bean"));
                            factoryType = targets.size() == 1 ? targets.getFirst().type : "";
                        }
                        member(
                                doc,
                                node,
                                "factory-method",
                                bean.id,
                                factoryType,
                                node.attr("factory-method"),
                                argumentHints(bean, new HashSet<>()),
                                node.attr("factory-bean").isBlank());
                    } else if (!node.attr("abstract").equalsIgnoreCase("true")
                            && !node.attr("autowire").equals("constructor"))
                        member(
                                doc,
                                node,
                                "constructor",
                                bean.id,
                                bean.type,
                                "<init>",
                                argumentHints(bean, new HashSet<>()),
                                false);
                }
                if (VALUE_NODES.contains(node.name) && !node.name.equals("bean")) value(doc, node);
                if (node.name.equals("alias"))
                    reference(doc, node, "alias", "", node.attr("name"), "alias");
                if (node.name.equals("import")) imported(doc, node);
                if (node.name.equals("beans"))
                    facet(
                            doc,
                            node,
                            "beans-config",
                            "java.spring.xml",
                            Json.object(
                                    "kind",
                                    "beans_configuration",
                                    "xml_attributes",
                                    node.attributes));
            }
            coverage(doc);
        }
    }

    private Bean enclosing(XmlModel.Node node) {
        for (XmlModel.Node parent = node.parent; parent != null; parent = parent.parent)
            if (beans.containsKey(parent)) return beans.get(parent);
        return null;
    }

    private void value(Doc doc, XmlModel.Node node) throws Exception {
        Bean owner = enclosing(node);
        if (owner == null) {
            doc.gaps.add("config_outside_bean:" + node.name);
            return;
        }
        String parent =
                nodeIds.getOrDefault(
                        node.parent,
                        beans.containsKey(node.parent) ? beans.get(node.parent).id : owner.id);
        Map<String, Object> tree =
                facet(
                        doc,
                        node,
                        "config",
                        "java.spring.xml",
                        Json.object(
                                "kind",
                                node.name,
                                "xml_attributes",
                                node.attributes,
                                "value",
                                node.attr("value").isEmpty()
                                        ? node.text.toString().trim()
                                        : node.attr("value"),
                                "type",
                                node.attr("type"),
                                "name",
                                node.attr("name"),
                                "key",
                                node.attr("key"),
                                "index",
                                node.attr("index"),
                                "bean_definition",
                                owner.id));
        tree.put("parent", parent);
        nodeIds.put(node, Json.string(tree, "id"));
        String reference = node.attr("ref");
        if (Set.of("ref", "idref").contains(node.name))
            reference = !node.attr("bean").isBlank() ? node.attr("bean") : node.attr("local");
        reference(
                doc,
                node,
                "value-ref",
                Json.string(tree, "id"),
                reference,
                node.name.equals("idref") ? "idref" : "bean_ref");
        if (Set.of("ref", "idref").contains(node.name) && !node.attr("parent").isBlank()) {
            Map<String, Object> parentRef =
                    facet(
                            doc,
                            node,
                            "parent-context-ref",
                            "java.spring.xml",
                            Json.object(
                                    "kind",
                                    "parent_context_ref",
                                    "bean",
                                    node.attr("parent"),
                                    "selection",
                                    "unknown_parent_container"));
            parentRef.put("parent", tree.get("id"));
            doc.gaps.add("parent_container_not_available:" + node.attr("parent"));
        }
        reference(
                doc, node, "key-ref", Json.string(tree, "id"), node.attr("key-ref"), "map_key_ref");
        reference(
                doc,
                node,
                "value-ref-attribute",
                Json.string(tree, "id"),
                node.attr("value-ref"),
                "map_value_ref");
        if (node.name.equals("property") && !node.attr("name").isBlank()) {
            String name = node.attr("name"),
                    setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            member(
                    doc,
                    node,
                    "property-member",
                    Json.string(tree, "id"),
                    owner.type,
                    setter,
                    List.of(typeHint(node, new HashSet<>())),
                    false);
        }
    }

    private void imported(Doc doc, XmlModel.Node node) {
        String resource = node.attr("resource");
        List<String> matches = new ArrayList<>();
        if (!resource.contains("${") && !resource.contains("*")) {
            if (resource.startsWith("classpath:")) {
                String suffix = resource.substring("classpath:".length()).replaceFirst("^/", "");
                for (String path : paths.keySet())
                    if (path.equals(suffix) || path.endsWith('/' + suffix)) matches.add(path);
            } else if (!resource.contains(":")) {
                Path parent = Path.of(doc.path).getParent();
                String path =
                        (parent == null ? Path.of(resource) : parent.resolve(resource))
                                .normalize()
                                .toString();
                if (paths.containsKey(path)) matches.add(path);
            }
        }
        Collections.sort(matches);
        facet(
                doc,
                node,
                "import",
                "java.spring.xml",
                Json.object(
                        "kind",
                        "import",
                        "resource",
                        resource,
                        "sealed_matches",
                        matches,
                        "selection",
                        "possible"));
        if (matches.size() != 1) doc.gaps.add("import_not_unique_in_sealed_inventory:" + resource);
    }
}
