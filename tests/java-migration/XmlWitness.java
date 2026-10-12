import com.anatomist.application.FrameworkPlugins;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.NodeKeyFactory;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;
import com.anatomist.store.SqliteStore;
import java.nio.file.*;
import java.util.*;

/** Observe unchanged frozen XML analyzers before database identity/upsert rules. */
public final class XmlWitness {
    private static Map<String,Object> fields(Object value) throws Exception {
        var out = new TreeMap<String,Object>();
        for (var field : value.getClass().getFields()) {
            var item = field.get(value);
            if (item instanceof String text && field.getName().equals("metadata") && !text.isBlank())
                item = Json.parseTree(text);
            out.put(field.getName(), item);
        }
        return out;
    }
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[1]);
        var configuration = new ProjectConfig(); configuration.setSpringXml(true);
        var context = new JavaFrameworkContext(project, List.of(), configuration, true);
        var symbols = new ArrayList<SymbolFact>();
        var known = new HashSet<String>();
        var beans = new LinkedHashMap<String,BeanRefTarget>();
        List<TypeRelationFact> hierarchy;
        try (var store = new SqliteStore(Path.of(args[0]))) {
            // Resource facts never seed their own pre-publication replay.
            var targets = store.readBeanClassTargets();
            for (var symbol : store.readSymbolFacts()) {
                if (symbol.sourceFile() == null || !symbol.sourceFile().endsWith(".java")) continue;
                String id = NodeKeyFactory.symbolId(symbol.symbolId());
                known.add(id);
                symbols.add(new SymbolFact(id,id,symbol.kind(),symbol.module(),symbol.scope(),symbol.sourceFile(),symbol.metadata()));
                if (symbol.kind().equals("BEAN")) {
                    String type = targets.get(symbol.symbolId());
                    if (type != null) beans.put(id,new BeanRefTarget(id,type));
                }
            }
            hierarchy = store.readTypeRelations();
        }
        var inventory = new ArrayList<ProjectResource>();
        for (var relative : Files.readAllLines(Path.of(args[2]))) {
            if (!relative.isBlank() && com.anatomist.core.SpringBeanParser.isSpringBeansFile(project.resolve(relative))) inventory.add(new ProjectResource(project.resolve(relative),relative,"spring-xml"));
        }
        var result = new ExtractionResult();
        new ProjectAnalysisRunner().run(FrameworkPlugins.builtIns().prepareDetached(context),context,inventory,
            new DefaultProjectFactView(known,beans,symbols,hierarchy),result,new ExtensionReport());
        var nodes = new ArrayList<Map<String,Object>>();
        var edges = new ArrayList<Map<String,Object>>();
        for (var node : result.nodes) nodes.add(fields(node));
        for (var edge : result.edges) edges.add(fields(edge));
        System.out.println(Json.writeCompact(Map.of("nodes",nodes,"edges",edges,"java_symbols",symbols.size(),"resources",inventory.size())));
    }
}
