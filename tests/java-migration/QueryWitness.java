import com.anatomist.json.Json;
import com.anatomist.query.JavaSemanticRows;
import com.anatomist.query.QueryService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observe frozen public query APIs. No extraction or query rules are modified. */
public final class QueryWitness {
    private static Map<String,Object> type(JavaSemanticRows.TypeRelation row) {
        return Map.of("subject",row.subject(),"object",row.object(),
            "semantic",row.semantic(),"mechanism",row.mechanism());
    }
    private static Map<String,Object> callable(JavaSemanticRows.CallableRelation row) {
        return Map.of("subject",row.subject(),"object",row.object(),
            "semantic",row.semantic(),"mechanism",row.mechanism());
    }
    public static void main(String[] args) throws Exception {
        try (var query = new QueryService(Path.of(args[0]))) {
            query.selectNodes(null,"ALL");
            int depth = Integer.parseInt(args[3]);
            var result = new LinkedHashMap<String,Object>();
            var rows = new ArrayList<Map<String,Object>>();
            switch(args[1]) {
                case "runtime" -> {
                    var observed = query.runtimeImplementationsDetailed(args[2],args[4],args[5],depth,10000);
                    for(var row:observed.candidates()) rows.add(Map.of("id",row.entity().id,
                        "instantiability",row.instantiability(),"reason",row.reason(),"world",row.world(),
                        "proof",row.proof().stream().map(QueryWitness::type).toList()));
                    result.put("depth_limited",observed.depthLimited());
                    result.put("result_limited",observed.resultLimited());
                }
                case "callable" -> {
                    for(var row:query.callableRelations(args[2],args[4],true,depth,10000)) rows.add(callable(row));
                }
                case "dispatch" -> {
                    var site = query.callSite(args[2]);
                    if(site==null) throw new IllegalArgumentException("ORIGINAL_CALL_SITE_NOT_FOUND");
                    var observed=query.dispatchDetailed(site.dispatchInput(),args[4],args[5],depth,10000,100000);
                    for(var row:observed.targets()) {
                        var value=new LinkedHashMap<String,Object>();
                        value.put("target",row.target());value.put("candidate",row.candidateKind());
                        value.put("executable",row.executable());value.put("algorithm",row.algorithm());
                        value.put("world",row.world());value.put("resolution",row.resolutionStatus());
                        value.put("proof",row.proof().stream().map(QueryWitness::callable).toList());
                        value.put("type_proof",row.typeProof().stream().map(QueryWitness::type).toList());
                        rows.add(value);
                    }
                    result.put("reasons",observed.reasons());result.put("truncated",observed.truncated());
                }
                default -> throw new IllegalArgumentException("UNKNOWN_QUERY_WITNESS_OPERATION");
            }
            result.put("rows",rows);
            System.out.println(Json.writeCompact(result));
        }
    }
}
