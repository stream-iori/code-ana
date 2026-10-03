package dev.codeana.analysis;

import com.anatomist.core.WiringResolver;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Executes the independent, frozen resolver. No product derivation code. */
public final class WiringReferenceProbe {
    public static void main(String[] args) throws Exception {
        List<Edge> inputs = new ArrayList<>();
        for (Object item : (List<?>) Json.parseTree(Files.readString(Path.of(args[0])))) {
            Map<?, ?> row = (Map<?, ?>) item;
            Edge edge = new Edge();
            edge.sourceId = (String) row.get("source");
            edge.targetId = (String) row.get("target");
            edge.relation = (String) row.get("relation");
            edge.isExternal = Boolean.TRUE.equals(row.get("external"));
            edge.sourceFile = (String) row.get("file");
            edge.sourceLocation = (String) row.get("span");
            inputs.add(edge);
        }
        List<Object> outputs = new ArrayList<>();
        for (Edge edge : new WiringResolver().resolve(inputs)) {
            outputs.add(Map.of("source", edge.sourceId, "target", edge.targetId,
                    "confidence", edge.confidence, "file", edge.sourceFile,
                    "span", edge.sourceLocation, "metadata", Json.parseTree(edge.metadata)));
        }
        System.out.println(Json.writeCompact(outputs));
    }
}
