package com.anatomist.framework;

import com.anatomist.core.ContentHash;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.util.Map;

/** Source-backed configuration entity shared by framework extractors, not a Java declaration. */
public final class FrameworkRegistration {
    private FrameworkRegistration() {}

    public static Node add(
            ExtractionResult result,
            String owner,
            String file,
            int line,
            int endLine,
            String key,
            String label,
            Map<String, Object> metadata) {
        var node = new Node();
        node.id = "framework-registration:" + ContentHash.sha256(file + "\n" + key);
        node.kind = "FRAMEWORK_REGISTRATION";
        node.domain = "configuration";
        node.entityKind = "config_entity";
        node.label = label;
        node.qualifiedName = node.id;
        node.sourceFile = file;
        node.sourceLocation = "L" + Math.max(1, line);
        node.beginLine = Math.max(1, line);
        node.endLine = Math.max(node.beginLine, endLine);
        node.beginColumn = 1;
        node.endColumn = Integer.MAX_VALUE;
        node.scope = "MAIN";
        node.metadata = Json.writeCompact(metadata);
        result.nodes.add(node);
        if (owner != null) {
            var edge = new Edge();
            edge.sourceId = owner;
            edge.targetId = node.id;
            edge.relation = "CONFIGURES";
            edge.sourceFile = file;
            edge.sourceLocation = node.sourceLocation;
            edge.confidence = "CONFIGURED";
            result.edges.add(edge);
        }
        return node;
    }
}
