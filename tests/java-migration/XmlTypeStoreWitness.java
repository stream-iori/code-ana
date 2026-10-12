import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.json.Json;
import com.anatomist.model.*;
import com.anatomist.store.*;
import java.nio.file.*;
import java.util.*;

/** Observe original global promotion across explicitly different modules. */
public final class XmlTypeStoreWitness {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root.resolve("provider"));
        Files.createDirectories(root.resolve("consumer"));
        Files.writeString(root.resolve("provider/Provider.java"), "package p; class Provider {}");
        Files.writeString(root.resolve("consumer/beans.xml"), "<beans/>");
        var target = new Node();
        target.id = "p.Provider"; target.symbolId = target.id; target.kind = "CLASS";
        target.label = "Provider"; target.qualifiedName = target.id;
        target.sourceFile = "provider/Provider.java"; target.module = "provider";
        target.scope = "MAIN"; target.language = "java";
        target.providerId = "java-core"; target.producerId = "java-core";
        var bean = new Node();
        bean.id = "bean:consumer@consumer/beans.xml"; bean.kind = "BEAN";
        bean.label = "consumer"; bean.qualifiedName = bean.id;
        bean.sourceFile = "consumer/beans.xml"; bean.module = "consumer";
        bean.scope = "MAIN"; bean.producerId = "spring-xml";
        var edge = new Edge();
        edge.sourceId = bean.id; edge.sourceFile = bean.sourceFile;
        edge.externalTargetFqn = target.id; edge.isExternal = true;
        edge.relation = "DEFINED_BY"; edge.producerId = "spring-xml";
        edge.language = "java"; edge.providerId = "java-core";
        var facts = new ExtractionResult();
        facts.nodes.add(target); facts.nodes.add(bean); facts.edges.add(edge);
        Path database = root.resolve("original.db");
        try (var store = new SqliteStore(database);
             var stage = new StagedGraphStore(database, new SourceIdentityResolver(root, List.of(root.resolve("provider"))))) {
            store.initSchema(); stage.writeRawBatch(facts); stage.finalizeRawFacts(); stage.promoteFull(store);
            try (var statement = store.connection().createStatement(); var rows = statement.executeQuery(
                    "SELECT n.symbol_id,n.module,e.is_external,e.external_target_fqn FROM edges e JOIN nodes n ON n.id=e.target_id WHERE e.relation='DEFINED_BY'")) {
                if (!rows.next()) throw new IllegalStateException("ORIGINAL_GLOBAL_PROMOTION_MISSING");
                var result = new LinkedHashMap<String,Object>();
                result.put("consumer_module", "consumer"); result.put("declared_dependencies", List.of());
                result.put("requested_external", true); result.put("requested", target.id);
                result.put("selected", rows.getString(1)); result.put("selected_module", rows.getString(2));
                result.put("published_external", rows.getBoolean(3));
                result.put("external_target", rows.getString(4));
                System.out.println(Json.writeCompact(result));
            }
        }
    }
}
