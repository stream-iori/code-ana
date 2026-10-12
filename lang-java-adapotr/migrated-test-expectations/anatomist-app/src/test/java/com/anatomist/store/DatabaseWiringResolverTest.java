package com.anatomist.store;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.WiringResolver;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

class DatabaseWiringResolverTest {
    @Test
    void candidateMetadataDoesNotDependOnModuleArrivalOrder(@TempDir Path root) throws Exception {
        try (var store = new SqliteStore(root.resolve("wiring.db"))) {
            store.initSchema();
            try (var insert =
                    store.connection()
                            .prepareStatement(
                                    "INSERT INTO nodes(id,symbol_id,label,kind,qualified_name,source_file,module,scope) VALUES (?,?,?,'CLASS',?,'Fixture.java','test','MAIN')")) {
                for (String id : List.of("Owner", "Service", "First", "Last")) {
                    for (int column = 1; column <= 4; column++) insert.setString(column, id);
                    insert.executeUpdate();
                }
            }
            List<String> expected = null;
            var edges =
                    List.of(
                            edge("Last", "Service", "IMPLEMENTS"),
                            edge("Owner", "Service", "INJECTS"),
                            edge("First", "Service", "IMPLEMENTS"));
            for (var ordered : List.of(edges, edges.reversed())) {
                try (var clear = store.connection().createStatement()) {
                    clear.executeUpdate("DELETE FROM edges");
                }
                store.writeEdgesBatched(ordered, 100);
                assertEquals(2, DatabaseWiringResolver.rebuild(store.connection()));
                List<String> metadata = new ArrayList<>();
                try (var query = store.connection().createStatement();
                        var rows =
                                query.executeQuery(
                                        "SELECT metadata FROM edges WHERE relation='WIRES' ORDER BY target_id")) {
                    while (rows.next()) metadata.add(rows.getString(1));
                }
                assertEquals(
                        List.of("First", "Last"),
                        ((Map<?, ?>) Json.parseTree(metadata.getFirst())).get("candidates"));
                assertEquals(
                        metadata,
                        new WiringResolver()
                                .resolve(ordered).stream().map(edge -> edge.metadata).toList());
                if (expected == null) expected = metadata;
                else assertEquals(expected, metadata);
            }
        }
    }

    private static Edge edge(String source, String target, String relation) {
        Edge edge = new Edge();
        edge.sourceId = source;
        edge.targetId = target;
        edge.relation = relation;
        edge.confidence = "EXTRACTED";
        edge.sourceFile = "Fixture.java";
        edge.sourceLocation = "L1";
        return edge;
    }
}
