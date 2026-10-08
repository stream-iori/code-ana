package dev.codeana.adapter;

import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.core.SpringBeanParser.XmlConfigNode;
import com.anatomist.framework.spring.XmlBeanGraphExtractor;
import dev.codeana.common.Json;
import java.util.*;

/** Serializes the upstream XML model; never resolves names or chooses Java members. */
final class XmlConfigurationProjection {
    interface Span { Object at(int line, int column, int endLine, int endColumn); }

    static Map<String, Map<String, Object>> describe(CapturedProject.Source source, Span spans) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (ParsedBean bean : new SpringBeanParser().parse(source.file())) {
            List<Object> nodes = new ArrayList<>(), roots = new ArrayList<>();
            for (XmlConfigNode child : bean.children()) append(child, roots, nodes, spans,
                    spans.at(bean.line(), bean.column(), bean.endLine(), bean.endColumn()), 0);
            Map<String, Object> construction = Json.object(
                    "name", bean.name(), "className", bean.className(), "abstract", bean.abstractBean(),
                    "parent", bean.parent(), "factoryBean", bean.factoryBean(), "factoryMethod", bean.factoryMethod(),
                    "initMethod", bean.initMethod(), "destroyMethod", bean.destroyMethod(),
                    "nestedIn", bean.nestedIn(), "ordinal", bean.ordinal(), "attributes", bean.attributes());
            result.put(XmlBeanGraphExtractor.beanNodeId(bean.name(), source.path()),
                    Json.object("version", 1, "construction", construction, "roots", roots, "nodes", nodes));
        }
        return result;
    }

    private static void append(XmlConfigNode node, List<Object> siblings, List<Object> nodes,
            Span spans, Object parentSpan, int depth) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if (depth > 256 || nodes.size() >= 30000) throw new IllegalArgumentException("XML_CONFIGURATION_MODEL_BUDGET");
        boolean synthetic = node.endLine <= 0 || node.endColumn <= 0;
        int el = synthetic ? node.line : node.endLine, ec = synthetic ? node.column : node.endColumn;
        Object element = synthetic ? parentSpan : spans.at(node.line, node.column, el, ec);
        Map<String, Object> metadata = Json.object("xmlKind", node.kind, "ordinal", node.ordinal);
        for (var pair : List.of(new AbstractMap.SimpleEntry<>("name", node.name),
                new AbstractMap.SimpleEntry<>("key", node.key), new AbstractMap.SimpleEntry<>("index", node.index),
                new AbstractMap.SimpleEntry<>("bean", node.bean), new AbstractMap.SimpleEntry<>("value", node.value),
                new AbstractMap.SimpleEntry<>("type", node.type)))
            if (pair.getValue() != null) metadata.put(pair.getKey(), pair.getValue());
        List<Object> children = new ArrayList<>();
        siblings.add(nodes.size());
        nodes.add(Json.object("metadata", metadata, "position", position(node.line, node.column, el, ec),
                "synthetic", synthetic, "source_element_span", element, "children", children));
        for (var child : node.children) append(child, children, nodes, spans, element, depth + 1);
    }

    static Map<String, Object> position(Integer bl, Integer bc, Integer el, Integer ec) {
        return Json.object("line", bl, "column", bc, "end_line", el, "end_column", ec);
    }
}
