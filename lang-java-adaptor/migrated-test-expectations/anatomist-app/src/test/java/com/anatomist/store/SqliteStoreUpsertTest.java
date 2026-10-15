package com.anatomist.store;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.model.SemanticAnnotation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

class SqliteStoreUpsertTest {

    private SqliteStore store;

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void upsertSemanticAnnotation_insertsNewRow(@TempDir Path tmp) throws Exception {
        store = setup(tmp);

        SemanticAnnotation sa = sa("com.x.OrderService", "REVIEWED", "LLM", "MEDIUM", "reviewed");
        store.upsertSemanticAnnotation(sa);

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM semantic_annotations"));
        assertEquals("reviewed", readLabel(c, "com.x.OrderService", "REVIEWED", "LLM"));
    }

    @Test
    void upsertSemanticAnnotation_replacesOnDuplicateKey(@TempDir Path tmp) throws Exception {
        store = setup(tmp);

        store.upsertSemanticAnnotation(sa("com.x.OrderService", "REVIEWED", "LLM", "MEDIUM", "v1"));
        store.upsertSemanticAnnotation(sa("com.x.OrderService", "REVIEWED", "LLM", "HIGH", "v2"));

        Connection c = store.connection();
        assertEquals(1, count(c, "SELECT count(*) FROM semantic_annotations"));
        assertEquals("v2", readLabel(c, "com.x.OrderService", "REVIEWED", "LLM"));
        try (Statement st = c.createStatement();
                ResultSet rs =
                        st.executeQuery(
                                "SELECT confidence FROM semantic_annotations WHERE node_id='com.x.OrderService'")) {
            assertTrue(rs.next());
            assertEquals("HIGH", rs.getString(1));
        }
    }

    @Test
    void upsertSemanticAnnotation_distinctSourceCoexists(@TempDir Path tmp) throws Exception {
        store = setup(tmp);

        store.upsertSemanticAnnotation(
                sa("com.x.OrderService", "REVIEWED", "LLM", "MEDIUM", "from-llm"));
        store.upsertSemanticAnnotation(
                sa("com.x.OrderService", "REVIEWED", "DOC", "HIGH", "from-doc"));

        Connection c = store.connection();
        assertEquals(2, count(c, "SELECT count(*) FROM semantic_annotations"));
    }

    @Test
    void upsertSemanticAnnotations_batchUpserts(@TempDir Path tmp) throws Exception {
        store = setup(tmp);

        store.upsertSemanticAnnotation(
                sa("com.x.OrderService", "REVIEWED", "LLM", "MEDIUM", "old"));

        List<SemanticAnnotation> batch =
                List.of(
                        sa("com.x.OrderService", "REVIEWED", "LLM", "HIGH", "updated"),
                        sa("com.x.PaymentService", "REVIEWED", "LLM", "MEDIUM", "new"));
        store.upsertSemanticAnnotations(batch);

        Connection c = store.connection();
        assertEquals(2, count(c, "SELECT count(*) FROM semantic_annotations"));
        assertEquals("updated", readLabel(c, "com.x.OrderService", "REVIEWED", "LLM"));
        assertEquals("new", readLabel(c, "com.x.PaymentService", "REVIEWED", "LLM"));
    }

    private static SqliteStore setup(Path tmp) {
        SqliteStore s = new SqliteStore(tmp.resolve("index.db"));
        s.initSchema();
        // Seed nodes so FK on semantic_annotations.node_id is satisfied (ON DELETE SET NULL is
        // fine,
        // but inserting a node makes the test stricter).
        ExtractionResult r = new ExtractionResult();
        Node n1 = new Node();
        n1.id = "com.x.OrderService";
        n1.label = "OrderService";
        n1.kind = "CLASS";
        n1.qualifiedName = "com.x.OrderService";
        n1.sourceFile = "OrderService.java";
        n1.scope = "MAIN";
        Node n2 = new Node();
        n2.id = "com.x.PaymentService";
        n2.label = "PaymentService";
        n2.kind = "CLASS";
        n2.qualifiedName = "com.x.PaymentService";
        n2.sourceFile = "PaymentService.java";
        n2.scope = "MAIN";
        r.nodes.add(n1);
        r.nodes.add(n2);
        s.write(r);
        return s;
    }

    private static SemanticAnnotation sa(
            String nodeId, String category, String source, String confidence, String label) {
        SemanticAnnotation s = new SemanticAnnotation();
        s.nodeId = nodeId;
        s.category = category;
        s.source = source;
        s.confidence = confidence;
        s.businessLabel = label;
        return s;
    }

    private static int count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String readLabel(Connection c, String nodeId, String category, String source)
            throws Exception {
        try (java.sql.PreparedStatement ps =
                c.prepareStatement(
                        "SELECT business_label FROM semantic_annotations WHERE node_id=? AND category=? AND source=?")) {
            ps.setString(1, nodeId);
            ps.setString(2, category);
            ps.setString(3, source);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }
}
