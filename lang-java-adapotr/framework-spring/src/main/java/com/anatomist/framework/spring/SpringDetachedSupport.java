package com.anatomist.framework.spring;

import com.anatomist.core.java.*;
import com.anatomist.framework.JavaUnitAnalysis;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.util.*;

final class SpringDetachedSupport {
    private SpringDetachedSupport() {}

    static int line(JavaUnitAnalysis unit, JavaModel.DeclarationRef reference) {
        var span = unit.model().declaration(reference).span();
        return span.present()
                ? unit.model().source(reference.source()).position(span.start()).line()
                : 0;
    }

    static String display(JavaTypes types, int reference) {
        if (reference < 0) return "<unresolved>";
        var type = types.get(reference);
        return type.display().isEmpty() ? JavaTypeNames.erased(types, reference) : type.display();
    }

    static List<JavaModel.DeclarationRef> parameters(
            JavaUnitAnalysis unit, JavaModel.DeclarationRef reference) {
        return unit.model().members(reference).stream()
                .filter(
                        member ->
                                unit.model()
                                        .declaration(member)
                                        .symbol()
                                        .kind()
                                        .equals("PARAMETER"))
                .toList();
    }

    static Node node(
            JavaUnitAnalysis unit,
            String id,
            String label,
            String kind,
            int line,
            Map<String, Object> metadata) {
        Node node = new Node();
        node.id = id;
        node.label = label;
        node.qualifiedName = label;
        node.kind = kind;
        node.sourceFile = unit.sourceFile();
        node.sourceLocation = "L" + line;
        node.module = unit.identity().module();
        node.scope = unit.identity().scope().name();
        node.metadata = Json.writeCompact(metadata);
        return node;
    }

    static Edge edge(
            JavaUnitAnalysis unit, String source, String target, String relation, int line) {
        Edge edge = new Edge();
        edge.sourceId = source;
        edge.targetId = target;
        edge.relation = relation;
        edge.confidence = GraphConstants.Confidence.CONFIGURED;
        edge.sourceFile = unit.sourceFile();
        edge.sourceLocation = "L" + line;
        if (line > 0) edge.beginLine = line;
        return edge;
    }

    static String decapitalize(String name) {
        if (name.isEmpty()
                || name.length() > 1
                        && Character.isUpperCase(name.charAt(0))
                        && Character.isUpperCase(name.charAt(1))) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static Map<String, Object> shape(JavaTypes types, int reference) {
        var type = types.get(reference);
        String shape = "single", container = null;
        int element = -1;
        boolean unsupported = false;
        if (type.kind() == JavaType.Kind.ARRAY) {
            shape = "array";
            container = "array";
            element = type.component();
        } else if (type.kind() == JavaType.Kind.DECLARED) {
            String raw = JavaTypeNames.erased(types, reference);
            if (Set.of("java.util.List", "java.util.Set", "java.util.Collection", "java.util.Map")
                    .contains(raw)) {
                shape = "collection";
                int count = raw.equals("java.util.Map") ? 2 : 1;
                if (type.arguments().size() == count
                        && (count == 1
                                || JavaTypeNames.erased(types, type.arguments().getFirst())
                                        .equals("java.lang.String"))) {
                    container = raw;
                    element = type.arguments().getLast();
                } else unsupported = true;
            } else if (!type.arguments().isEmpty()) {
                shape = "parameterized";
                unsupported = true;
            }
        } else unsupported = true;
        if (element >= 0
                && (types.get(element).kind() != JavaType.Kind.DECLARED
                        || !types.get(element).arguments().isEmpty())) unsupported = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("injectionShape", shape);
        if (unsupported) result.put("matchingUnsupported", true);
        else if (element >= 0) {
            result.put("collectionType", container);
            result.put("elementType", display(types, element));
            result.put("cardinality", "many");
        }
        return result;
    }
}
