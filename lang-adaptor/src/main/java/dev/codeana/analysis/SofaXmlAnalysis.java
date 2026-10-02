package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionPoint;
import dev.codeana.common.IndexedRecords;
import dev.codeana.common.Json;

import java.nio.file.*;
import java.util.*;

/** Static SOFA 4 / public SOFA XML registrations and their configuration tree. */
public final class SofaXmlAnalysis {
    public static final String INTERNAL = "http://schema.alipay.com/sofa/schema/service";
    private static final Set<String> NAMESPACES =
            Set.of(INTERNAL, "http://sofastack.io/schema/sofa");
    private static final Set<String> ROOTS =
            Set.of("service", "reference", "publisher", "consumer");
    private static final Set<String> TRANSPORTS =
            Set.of("jvm", "tr", "ws", "tri", "rest", "msg_broker");
    private static final String PRODUCER = "sofa-model-v1";
    private final Map<String, Object> context;
    private final List<Object> output = new ArrayList<>();
    private final IndexedRecords types;
    private final Map<String, List<String>> beanTargets = new HashMap<>();

    private record Doc(String path, String world, Set<String> gaps) {}

    private SofaXmlAnalysis(Map<String, Object> context, List<Object> core) {
        this.context = context;
        types = new IndexedRecords(core);
        for (int i = 0; i < core.size(); i++) {
            Map<String, Object> fact = Json.map(core.get(i));
            if (Set.of("declaration", "external_symbol").contains(fact.get("kind"))) {
                String signature = Json.string(fact, "signature");
                if (!signature.contains("#")) {
                    Map<String, Object> old = types.get(signature);
                    if (old == null || rank(fact) > rank(old)) types.index(signature, i);
                }
            }
            if ("java.bean".equals(fact.get("relation")))
                beanTargets
                        .computeIfAbsent(Json.string(fact, "name"), ignored -> new ArrayList<>())
                        .add(Json.string(fact, "id"));
        }
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
        SofaXmlAnalysis analysis = new SofaXmlAnalysis(context, core);
        analysis.read();
        return analysis.output;
    }

    private void check() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
    }

    private Map<String, Object> fact(Doc doc, XmlModel.Node node, String role, String kind) {
        if (output.size() >= 100000) throw new IllegalStateException("SOFA_XML_FACT_BUDGET");
        Map<String, Object> fact =
                Json.object(
                        "id",
                        Json.key(
                                "sofa-xml",
                                Json.string(context, "context"),
                                doc.world,
                                doc.path,
                                role,
                                Integer.toString(node == null ? 0 : node.ordinal)),
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
            Doc doc, XmlModel.Node node, String role, Map<String, Object> attributes) {
        Map<String, Object> fact = fact(doc, node, role, "facet");
        fact.put("relation", "java.sofa");
        attributes.put("schema_version", 1);
        attributes.put("runtime_confirmed", false);
        if (node != null) attributes.put("position", node.position());
        fact.put("attributes", attributes);
        return fact;
    }

    private void coverage(Doc doc) {
        Map<String, Object> coverage = fact(doc, null, "coverage", "coverage");
        coverage.put("status", doc.gaps.isEmpty() ? "complete" : "partial");
        coverage.put("evidence_complete", doc.gaps.isEmpty());
        coverage.put(
                "attributes",
                Json.object(
                        "reason",
                        doc.gaps.isEmpty() ? "Static SOFA configuration tree" : "Static SOFA gaps",
                        "gaps",
                        new ArrayList<>(doc.gaps),
                        "runtime_confirmed",
                        false));
    }

    private void read() throws Exception {
        long bytes = 0;
        int nodes = 0;
        for (Object raw : Json.list(context.getOrDefault("resources", List.of()))) {
            check();
            Map<String, Object> resource = Json.map(raw), unit = Json.map(resource.get("unit"));
            Doc doc =
                    new Doc(Json.string(unit, "path"), Json.string(unit, "world"), new TreeSet<>());
            try {
                bytes += Files.size(Path.of(Json.string(resource, "input")));
                if (bytes > 64L * 1024 * 1024)
                    throw new org.xml.sax.SAXException("SOFA_XML_PROJECT_BYTE_BUDGET");
                if (!XmlModel.mentionsNamespace(resource, NAMESPACES)) continue;
                XmlModel.Document model = XmlModel.read(resource);
                nodes += model.nodes().size();
                if (nodes > 100000)
                    throw new org.xml.sax.SAXException("SOFA_XML_PROJECT_NODE_BUDGET");
                Map<String, List<String>> targets = new HashMap<>();
                for (var entry : beanTargets.entrySet())
                    targets.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                // Independent SOFA mode can use literal bean→SOURCE type configuration as evidence.
                for (XmlModel.Node node : model.nodes())
                    if (node.name.equals("bean")
                            && (node.namespace.isEmpty()
                                    || node.namespace.equals(
                                            "http://www.springframework.org/schema/beans"))
                            && !node.attr("class").isEmpty()
                            && node.attr("factory-method").isEmpty()) {
                        Map<String, Object> type = types.get(node.attr("class"));
                        if (type != null && !node.attr("id").isBlank())
                            targets.computeIfAbsent(node.attr("id"), ignored -> new ArrayList<>())
                                    .add(Json.string(type, "id"));
                    }
                boolean seen = false;
                for (XmlModel.Node node : model.nodes()) {
                    if (!NAMESPACES.contains(node.namespace) || !ROOTS.contains(node.name))
                        continue;
                    XmlModel.Node ancestor = node.parent;
                    boolean nested = false;
                    while (ancestor != null) {
                        if (NAMESPACES.contains(ancestor.namespace)
                                && ROOTS.contains(ancestor.name)) {
                            nested = true;
                            break;
                        }
                        ancestor = ancestor.parent;
                    }
                    if (nested) {
                        doc.gaps.add("nested_registration");
                        continue;
                    }
                    registration(doc, node, targets);
                    seen = true;
                }
                if (seen) coverage(doc);
            } catch (ExtensionPoint.GateFailure fatal) {
                throw fatal;
            } catch (org.xml.sax.SAXException | java.nio.charset.CharacterCodingException invalid) {
                check();
                doc.gaps.add(String.valueOf(invalid.getMessage()));
                coverage(doc);
            }
        }
    }

    private void registration(Doc doc, XmlModel.Node root, Map<String, List<String>> targets) {
        boolean message = Set.of("publisher", "consumer").contains(root.name);
        List<XmlModel.Node> children = new ArrayList<>();
        descendants(root, root.namespace, children);
        List<String> bindings = new ArrayList<>();
        List<Object> configuration = new ArrayList<>();
        String listener = "";
        for (XmlModel.Node child : children) {
            if (child.name.startsWith("binding."))
                bindings.add(child.name.substring("binding.".length()));
            else if (child.name.equals("binding")) bindings.add("unknown");
            if (child.name.equals("listener")) listener = child.attr("ref");
            Map<String, Object> value =
                    Json.object(
                            "element",
                            child.name,
                            "attributes",
                            child.attributes,
                            "span",
                            child.span(),
                            "position",
                            child.position());
            if (child.name.equals("event")) value.put("channel", channel(child, root));
            configuration.add(value);
        }
        if (bindings.isEmpty()) bindings.add(message ? "msg_broker" : "jvm");
        String contract = root.attr("interface"), unique = root.attr("unique-id");
        boolean supported =
                (message
                                || !contract.isBlank()
                                        && !contract.contains("${")
                                        && !unique.contains("${"))
                        && !root.attributes.containsKey("parent")
                        && !root.attr("abstract").equalsIgnoreCase("true")
                        && bindings.stream().allMatch(TRANSPORTS::contains);
        String targetBean = message ? listener : root.attr("ref");
        Map<String, Object> declaration = types.get(contract);
        String role =
                switch (root.name) {
                    case "service" -> "provider";
                    case "reference" -> "requirement";
                    default -> root.name;
                };
        Map<String, Object> attributes =
                Json.object(
                        "kind",
                        root.name,
                        "role",
                        role,
                        "framework",
                        message ? "msgbroker" : "sofa",
                        "mechanism",
                        (message ? "msgbroker" : "sofa") + ".xml." + root.name,
                        "interface",
                        contract,
                        "contract",
                        contract,
                        "unique_id",
                        unique,
                        "group",
                        root.attr("group"),
                        "id",
                        root.attr("id"),
                        "xml_attributes",
                        root.attributes,
                        "namespace",
                        root.namespace,
                        "contract_baseline",
                        root.namespace.equals(INTERNAL)
                                ? "internal-runtime-4.19.1-xsd"
                                : "public-sofa-static",
                        "contract_supported",
                        supported,
                        "contract_type_available",
                        declaration != null,
                        "bindings",
                        bindings,
                        "transport",
                        bindings.size() == 1 ? bindings.getFirst() : "multiple",
                        "target_bean",
                        targetBean,
                        "configuration",
                        configuration);
        Map<String, Object> registration = facet(doc, root, "registration", attributes);
        String name = root.attr("id");
        if (name.isEmpty()) name = root.name + ":" + (targetBean.isEmpty() ? contract : targetBean);
        registration.put("name", name);
        if (declaration != null) registration.put("parent", declaration.get("id"));
        else if (!message) doc.gaps.add("contract_type_unavailable:" + contract);
        if (!supported) doc.gaps.add("unsupported_contract_or_transport:" + name);
        Map<XmlModel.Node, String> identities = new IdentityHashMap<>();
        identities.put(root, Json.string(registration, "id"));
        for (XmlModel.Node child : children) {
            check();
            XmlModel.Node parent = child.parent;
            while (parent != null && !identities.containsKey(parent)) parent = parent.parent;
            Map<String, Object> detail =
                    facet(
                            doc,
                            child,
                            "configuration",
                            Json.object(
                                    "kind",
                                    "configuration",
                                    "element",
                                    child.name,
                                    "xml_attributes",
                                    child.attributes,
                                    "value",
                                    child.text.toString().trim(),
                                    "channel",
                                    channel(child, root)));
            detail.put("parent", identities.getOrDefault(parent, Json.string(registration, "id")));
            identities.put(child, Json.string(detail, "id"));
            if (child.name.equals("listener"))
                beanReference(doc, child, Json.string(detail, "id"), child.attr("ref"), targets);
        }
        if (!message)
            beanReference(doc, root, Json.string(registration, "id"), targetBean, targets);
    }

    private void descendants(XmlModel.Node node, String namespace, List<XmlModel.Node> output) {
        check();
        for (XmlModel.Node child : node.children) {
            if (child.namespace.equals(namespace)) output.add(child);
            descendants(child, namespace, output);
        }
    }

    private static String channel(XmlModel.Node node, XmlModel.Node root) {
        for (XmlModel.Node parent = node.parent;
                parent != null && parent != root;
                parent = parent.parent)
            if (parent.namespace.equals(root.namespace) && parent.name.equals("channel"))
                return parent.attr("value");
        return "";
    }

    private void beanReference(
            Doc doc,
            XmlModel.Node node,
            String parent,
            String name,
            Map<String, List<String>> targets) {
        if (name.isBlank()) return;
        List<String> candidates =
                targets.getOrDefault(name, List.of()).stream().distinct().toList();
        if (candidates.size() != 1) doc.gaps.add("missing_or_ambiguous_target_bean:" + name);
        int index = 0;
        for (String target : candidates) {
            Map<String, Object> reference =
                    fact(doc, node, "bean-target-" + (index++), "reference");
            reference.put("parent", parent);
            reference.put("target", target);
            reference.put("relation", "config");
            reference.put("status", "possible");
            reference.put(
                    "attributes", Json.object("bean", name, "selection", "static_configuration"));
        }
    }
}
