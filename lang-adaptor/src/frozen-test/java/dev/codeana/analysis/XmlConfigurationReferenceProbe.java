package dev.codeana.analysis;

import com.anatomist.core.SpringBeanParser;
import dev.codeana.common.Json;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;

/** Run the frozen parser itself; never reconstruct expected XML from product facts. */
public final class XmlConfigurationReferenceProbe {
    private static int append(SpringBeanParser.XmlConfigNode node, List<Object> nodes) {
        int index = nodes.size();
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("xmlKind", node.kind);
        metadata.put("ordinal", node.ordinal);
        put(metadata, "name", node.name);
        put(metadata, "key", node.key);
        put(metadata, "index", node.index);
        put(metadata, "bean", node.bean);
        put(metadata, "value", node.value);
        put(metadata, "type", node.type);
        List<Object> children = new ArrayList<>();
        nodes.add(Json.object("metadata", metadata,
                "position", Json.object("line", node.line, "column", node.column,
                        "end_line", node.endLine > 0 ? node.endLine : node.line,
                        "end_column", node.endColumn > 0 ? node.endColumn : node.column),
                "children", children));
        for (SpringBeanParser.XmlConfigNode child : node.children) children.add(append(child, nodes));
        return index;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static List<Object> models(Path source) {
        List<Object> result = new ArrayList<>();
        for (SpringBeanParser.ParsedBean bean : new SpringBeanParser().parse(source)) {
            List<Object> nodes = new ArrayList<>(), roots = new ArrayList<>();
            for (SpringBeanParser.XmlConfigNode child : bean.children()) roots.add(append(child, nodes));
            Map<String, Object> construction = Json.object(
                    "name", bean.name(), "className", bean.className(), "abstract", bean.abstractBean(),
                    "parent", bean.parent(), "factoryBean", bean.factoryBean(), "factoryMethod", bean.factoryMethod(),
                    "initMethod", bean.initMethod(), "destroyMethod", bean.destroyMethod(),
                    "nestedIn", bean.nestedIn(), "ordinal", bean.ordinal(), "attributes", bean.attributes());
            result.add(Json.object("version", 1, "construction", construction, "roots", roots, "nodes", nodes));
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            System.out.println(Json.write(models(Path.of(args[0]))));
        } else if (args.length == 2 && args[0].equals("--inventory")) {
            List<Object> result = new ArrayList<>();
            for (Object item : Json.list(Json.parse(Files.readAllBytes(Path.of(args[1]))))) {
                Map<String,Object> input = Json.map(item);
                result.add(Json.object("file", Json.string(input,"file"),
                        "models", models(Path.of(Json.string(input,"path")))));
            }
            System.out.println(Json.write(result));
        } else throw new IllegalArgumentException("XML_INPUT_REQUIRED");
    }
}
