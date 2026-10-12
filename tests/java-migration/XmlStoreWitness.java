import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.json.Json;
import com.anatomist.model.*;
import com.anatomist.store.*;
import java.nio.file.*;
import java.util.*;

/** Observe original staging of an explicitly unresolved, incompatible XML member. */
public final class XmlStoreWitness {
    private static Node node(String id,String kind,String file) {
        var n = new Node(); n.id=id; n.symbolId=id; n.kind=kind;
        n.label=id; n.qualifiedName=id; n.sourceFile=file;
        n.language="java"; n.providerId="java-core"; n.producerId="java-core";
        n.module="."; n.scope="MAIN"; return n;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Owner.java"),"package p; class Owner { void setValue(Expected value){} } class Expected {}");
        Files.writeString(root.resolve("beans.xml"),"<beans/>");
        var result=new ExtractionResult();
        result.nodes.add(node("p.Owner","CLASS","src/Owner.java"));
        result.nodes.add(node("p.Owner#setValue(p.Expected)","METHOD","src/Owner.java"));
        var site=node("bean:owner/callable-ref:setter:value","XML_CALLABLE_REF","beans.xml");
        site.producerId="spring-xml"; site.metadata="{\"resolutionStatus\":\"unresolved\",\"candidateCount\":0}";
        result.nodes.add(site);
        var edge=new Edge(); edge.sourceId=site.id; edge.sourceFile=site.sourceFile;
        edge.externalTargetFqn="p.Owner#setValue(p.Actual)"; edge.isExternal=true;
        edge.relation="BINDS_TO"; edge.language="java"; edge.providerId="java-core";
        edge.producerId="spring-xml"; edge.metadata=site.metadata;
        result.edges.add(edge);
        Path database=root.resolve("original.db");
        try(var store=new SqliteStore(database);var stage=new StagedGraphStore(database,
                new SourceIdentityResolver(root,List.of(root.resolve("src"))))) {
            store.initSchema(); stage.writeRawBatch(result); stage.finalizeRawFacts(); stage.promoteFull(store);
            try(var statement=store.connection().createStatement();var rows=statement.executeQuery(
                "SELECT n.symbol_id,e.is_external,e.external_target_fqn,e.metadata FROM edges e LEFT JOIN nodes n ON n.id=e.target_id WHERE relation='BINDS_TO'")) {
                if(!rows.next())throw new IllegalStateException("ORIGINAL_BINDING_NOT_STORED");
                var observed=new LinkedHashMap<String,Object>();
                observed.put("requested",edge.externalTargetFqn); observed.put("selected",rows.getString(1));
                observed.put("external",rows.getBoolean(2)); observed.put("external_target",rows.getString(3));
                observed.put("metadata",Json.parseTree(rows.getString(4)));
                System.out.println(Json.writeCompact(observed));
            }
        }
    }
}
