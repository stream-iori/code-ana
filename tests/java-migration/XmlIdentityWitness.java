import com.anatomist.application.FrameworkPlugins;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;
import com.anatomist.store.*;
import java.nio.file.*;
import java.util.*;

/** Original XML identity and persistence only; an empty Java view is explicit. */
public final class XmlIdentityWitness {
    private static Map<String,Object> fields(Object value) throws Exception {
        var row = new TreeMap<String,Object>();
        for (var field : value.getClass().getFields()) {
            var item = field.get(value);
            if (item instanceof String text && field.getName().equals("metadata") && !text.isBlank())
                item = Json.parseTree(text);
            row.put(field.getName(), item);
        }
        return row;
    }
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]);
        String relative = args[1];
        var configuration = new ProjectConfig();
        configuration.setSpringXml(true);
        var context = new JavaFrameworkContext(project, List.of(), configuration, true);
        var result = new ExtractionResult();
        new ProjectAnalysisRunner().run(FrameworkPlugins.builtIns().prepareDetached(context), context,
            List.of(new ProjectResource(project.resolve(relative), relative, "spring-xml")),
            new DefaultProjectFactView(Set.of(), Map.of(), List.of(), List.of()),
            result, new ExtensionReport());
        var nodes = new ArrayList<Map<String,Object>>();
        var edges = new ArrayList<Map<String,Object>>();
        for (var node : result.nodes) nodes.add(fields(node));
        for (var edge : result.edges) edges.add(fields(edge));
        Path database = Path.of(args[2]);
        try (var store = new SqliteStore(database);
             var stage = new StagedGraphStore(database, new SourceIdentityResolver(project, List.of()))) {
            store.initSchema();
            stage.writeRawBatch(result);
            stage.finalizeRawFacts();
            stage.promoteFull(store);
        }
        System.out.println(Json.writeCompact(Map.of("scope", "xml_identity_with_empty_java_view",
            "nodes", nodes, "edges", edges)));
    }
}
