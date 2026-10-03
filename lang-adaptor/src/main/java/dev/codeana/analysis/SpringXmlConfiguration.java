package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.util.*;

/** Detached, flat Spring construction/configuration model over captured XML.
 * Synthetic attribute values are distinct from actual XML elements and retain
 * their source element as evidence. No class lookup or runtime selection here.
 */
final class SpringXmlConfiguration {
    private static final Set<String> CONFIG = Set.of(
            "property", "constructor-arg", "map", "list", "entry", "ref", "idref", "value", "null");
    private static final String NS = "http://www.springframework.org/schema/beans";
    private final List<Object> nodes = new ArrayList<>();
    private int constructorOrdinal;

    static Map<String, Object> describe(XmlModel.Document document, XmlModel.Node bean) {
        SpringXmlConfiguration model = new SpringXmlConfiguration();
        List<Object> roots = new ArrayList<>();
        for (XmlModel.Node child : bean.children) model.append(child, roots, "");
        Map<String, Object> attributes = new TreeMap<>();
        for (String name : List.of("primary", "autowire-candidate")) {
            String value = bean.attributes.get(name);
            if (value == null) continue;
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"))
                attributes.put(name.equals("primary") ? name : "autowireCandidate", Boolean.parseBoolean(value));
            else attributes.put("unresolved:" + name, value);
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (bean.attributes.containsKey("name"))
            aliases.addAll(Arrays.asList(bean.attr("name").split("[,;\\s]+")));
        Map<String, String> declaredAliases = new LinkedHashMap<>();
        int ordinal = 0;
        for (XmlModel.Node node : document.nodes()) {
            if (!spring(node)) continue;
            if (node.name.equals("bean") && node.ordinal < bean.ordinal) ordinal++;
            if (node.name.equals("alias") && node.attributes.containsKey("name")
                    && node.attributes.containsKey("alias"))
                declaredAliases.put(node.attr("alias"), node.attr("name"));
        }
        for (String alias : declaredAliases.keySet()) {
            String current = alias;
            Set<String> visited = new HashSet<>();
            while (declaredAliases.containsKey(current) && visited.add(current) && visited.size() <= 256)
                current = declaredAliases.get(current);
            if (name(bean).equals(current) || aliases.contains(current)) aliases.add(alias);
        }
        if (!aliases.isEmpty()) attributes.put("aliases", List.copyOf(aliases));
        XmlModel.Node outer = bean.parent;
        while (outer != null && !(spring(outer) && outer.name.equals("bean"))) outer = outer.parent;
        Map<String, Object> construction = Json.object(
                "name", name(bean), "className", bean.attributes.get("class"),
                "abstract", bean.attr("abstract").equalsIgnoreCase("true"),
                "parent", bean.attributes.get("parent"), "factoryBean", bean.attributes.get("factory-bean"),
                "factoryMethod", bean.attributes.get("factory-method"),
                "initMethod", bean.attributes.get("init-method"), "destroyMethod", bean.attributes.get("destroy-method"),
                "nestedIn", outer == null ? null : name(outer), "ordinal", ordinal, "attributes", attributes);
        return Json.object("version", 1, "construction", construction, "roots", roots, "nodes", model.nodes);
    }

    private static boolean spring(XmlModel.Node node) {
        return node.namespace.isEmpty() || node.namespace.equals(NS);
    }

    private void append(XmlModel.Node node, List<Object> siblings, String parentKind) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        if (!spring(node) || !CONFIG.contains(node.name) && !node.name.equals("bean")) {
            for (XmlModel.Node child : node.children) append(child, siblings, parentKind);
            return;
        }
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("xmlKind", node.name);
        metadata.put("ordinal", siblings.size());
        switch (node.name) {
            case "property" -> copy(metadata, "name", node.attributes.get("name"));
            case "constructor-arg" -> {
                int index = constructorOrdinal++;
                if (node.attributes.containsKey("index")) {
                    try { index = Integer.parseInt(node.attr("index")); }
                    catch (NumberFormatException ignored) { /* Preserve the explicit raw attribute separately. */ }
                }
                metadata.put("index", index);
                copy(metadata, "type", node.attributes.get("type"));
            }
            case "entry" -> copy(metadata, "key", first(node, "key", "key-ref"));
            case "ref", "idref" -> copy(metadata, "bean", first(node, "bean", "local", "parent"));
            case "value" -> {
                String value = node.attributes.get("value");
                if (value == null && !node.text.toString().trim().isEmpty()) value = node.text.toString().trim();
                copy(metadata, "value", value);
            }
            case "bean" -> {
                metadata.put("bean", name(node));
                copy(metadata, "value", node.attributes.get("class"));
            }
            default -> {}
        }
        if (parentKind.equals("list") && !metadata.containsKey("index")) metadata.put("index", siblings.size());
        List<Object> children = new ArrayList<>();
        add(siblings, metadata, node.position(), node.span(), false, children);
        if (node.name.equals("bean")) return; // Its configuration belongs to the nested Bean's model.
        if (Set.of("property", "constructor-arg", "entry").contains(node.name)) {
            String ref = first(node, "ref", "value-ref");
            if (ref != null) synthetic(node, children, "ref", "bean", ref);
            String value = node.attributes.get("value");
            if (value != null) synthetic(node, children, "value", "value", value);
        }
        for (XmlModel.Node child : node.children) append(child, children, node.name);
    }

    private void synthetic(XmlModel.Node parent, List<Object> siblings, String kind, String key, String value) {
        // Frozen SpringBeanParser leaves attribute-node ordinal at zero. Actual
        // sequence identity is the flat node index; preserve both explicitly.
        Map<String, Object> metadata = Json.object("xmlKind", kind, "ordinal", 0, key, value);
        Map<String, Object> position = Json.object("line", parent.line, "column", parent.column,
                "end_line", parent.line, "end_column", parent.column);
        add(siblings, metadata, position, parent.span(), true, new ArrayList<>());
    }

    private void add(List<Object> siblings, Map<String, Object> metadata, Map<String, Object> position,
                     Map<String, Object> evidence, boolean synthetic, List<Object> children) {
        if (nodes.size() >= XmlModel.MAX_NODES * 3) throw new IllegalStateException("XML_CONFIGURATION_MODEL_BUDGET");
        int index = nodes.size();
        nodes.add(Json.object("metadata", metadata, "position", position, "synthetic", synthetic,
                "source_element_span", evidence, "children", children));
        siblings.add(index);
    }

    private static void copy(Map<String, Object> map, String name, Object value) {
        if (value != null) map.put(name, value);
    }

    private static String name(XmlModel.Node node) {
        String named = first(node, "id", "name");
        if (named != null) return named.split("[,;\\s]+")[0];
        String type = node.attributes.get("class");
        return type != null ? type : "$bean@L" + node.line + "C" + node.column;
    }

    private static String first(XmlModel.Node node, String... names) {
        for (String name : names) if (node.attributes.containsKey(name)) return node.attributes.get(name);
        return null;
    }
}
