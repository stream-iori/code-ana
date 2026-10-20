package com.anatomist.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.store.SqliteStore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * L3 end-to-end test for {@link AnnotateCommand}: single write, upsert, batch, source validation.
 */
class AnnotateCommandIT {

    static Path dbPath;

    @BeforeAll
    static void buildIndex(@TempDir Path tmp) throws Exception {
        Path repo = Path.of(System.getProperty("user.dir"));
        Path fixture = repo.resolve("fixtures/mini-spring-shop");
        assertTrue(Files.isDirectory(fixture));
        dbPath = tmp.resolve("annotate-cli.db");

        String projectSource =
                String.join(
                        File.pathSeparator,
                        fixture.resolve("api/src/main/java").toString(),
                        fixture.resolve("domain/src/main/java").toString(),
                        fixture.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd)
                .parseArgs(
                        fixture.toString(),
                        "--jdk-home",
                        com.anatomist.core.JavaFactsTestSupport.targetHome(8).toString(),
                        "--project-source",
                        projectSource,
                        "--no-classpath",
                        "--output",
                        dbPath.toString());
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream old = System.out;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            assertEquals(0, cmd.call(), cap.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(old);
        }
    }

    @Test
    void annotate_singleWrite_succeeds() throws Exception {
        String node = "com.example.shop.service.OrderService";
        int rc =
                runExit(
                        "annotate",
                        node,
                        "--label",
                        "reviewed",
                        "--category",
                        "REVIEWED",
                        "--source",
                        "LLM",
                        "--confidence",
                        "HIGH",
                        "--index",
                        dbPath.toString());
        assertEquals(0, rc);

        try (Connection c = openDb()) {
            assertEquals(
                    1,
                    count(
                            c,
                            "SELECT count(*) FROM semantic_annotations a JOIN nodes n ON n.id=a.node_id "
                                    + "WHERE n.symbol_id='"
                                    + node
                                    + "' AND a.source='LLM' AND a.category='REVIEWED'"));
            assertEquals(
                    "reviewed",
                    scalar(
                            c,
                            "SELECT a.business_label FROM semantic_annotations a JOIN nodes n ON n.id=a.node_id "
                                    + "WHERE n.symbol_id='"
                                    + node
                                    + "' AND a.source='LLM' AND a.category='REVIEWED'"));
        }
    }

    @Test
    void annotate_upsert_updatesInPlace() throws Exception {
        String node = "com.example.shop.service.PriceCalculator";
        assertEquals(
                0,
                runExit(
                        "annotate",
                        node,
                        "--label",
                        "v1",
                        "--category",
                        "REVIEWED",
                        "--source",
                        "LLM",
                        "--index",
                        dbPath.toString()));
        assertEquals(
                0,
                runExit(
                        "annotate",
                        node,
                        "--label",
                        "v2",
                        "--category",
                        "REVIEWED",
                        "--source",
                        "LLM",
                        "--index",
                        dbPath.toString()));

        try (Connection c = openDb()) {
            assertEquals(
                    1,
                    count(
                            c,
                            "SELECT count(*) FROM semantic_annotations a JOIN nodes n ON n.id=a.node_id "
                                    + "WHERE n.symbol_id='"
                                    + node
                                    + "' AND a.source='LLM' AND a.category='REVIEWED'"));
            assertEquals(
                    "v2",
                    scalar(
                            c,
                            "SELECT a.business_label FROM semantic_annotations a JOIN nodes n ON n.id=a.node_id "
                                    + "WHERE n.symbol_id='"
                                    + node
                                    + "' AND a.source='LLM' AND a.category='REVIEWED'"));
        }
    }

    @Test
    void annotate_sourceConvention_rejectedExit2() {
        int rc =
                runExit(
                        "annotate",
                        "com.example.shop.service.OrderService",
                        "--label",
                        "x",
                        "--category",
                        "REVIEWED",
                        "--source",
                        "CONVENTION",
                        "--index",
                        dbPath.toString());
        assertEquals(2, rc);
    }

    @Test
    void annotate_sourceJavadoc_rejectedExit2() {
        int rc =
                runExit(
                        "annotate",
                        "com.example.shop.service.OrderService",
                        "--label",
                        "x",
                        "--category",
                        "REVIEWED",
                        "--source",
                        "JAVADOC",
                        "--index",
                        dbPath.toString());
        assertEquals(2, rc);
    }

    @Test
    void annotate_exactStorageKeyStillHonorsModuleSelection() throws Exception {
        String storageId;
        try (Connection c = openDb()) {
            storageId =
                    scalar(
                            c,
                            "SELECT id FROM nodes WHERE symbol_id="
                                    + "'com.example.shop.service.OrderService'");
        }
        int rc =
                runExit(
                        "annotate",
                        storageId,
                        "--category",
                        "WRONG_MODULE",
                        "--source",
                        "LLM",
                        "--module",
                        "api",
                        "--scope",
                        "MAIN",
                        "--index",
                        dbPath.toString());
        assertEquals(2, rc);
        try (Connection c = openDb()) {
            assertEquals(
                    0,
                    count(
                            c,
                            "SELECT count(*) FROM semantic_annotations "
                                    + "WHERE category='WRONG_MODULE'"));
        }
    }

    @Test
    void annotate_fromJson_batch(@TempDir Path tmp) throws Exception {
        Path batch = tmp.resolve("batch.json");
        Files.writeString(
                batch,
                "[\n"
                        + "  {\"node_id\":\"com.example.shop.repository.OrderRepository\",\"category\":\"BUSINESS_REPOSITORY\",\"source\":\"LLM\",\"confidence\":\"HIGH\",\"business_label\":\"订单仓储\"},\n"
                        + "  {\"node_id\":\"com.example.shop.controller.OrderController\",\"category\":\"BUSINESS_CONTROLLER\",\"source\":\"DOC\",\"confidence\":\"MEDIUM\",\"business_label\":\"订单控制器\"}\n"
                        + "]\n",
                StandardCharsets.UTF_8);

        int rc = runExit("annotate", "--from-json", batch.toString(), "--index", dbPath.toString());
        assertEquals(0, rc);

        try (Connection c = openDb()) {
            assertEquals(
                    1,
                    count(
                            c,
                            "SELECT count(*) FROM semantic_annotations a JOIN nodes n ON n.id=a.node_id "
                                    + "WHERE n.symbol_id='com.example.shop.repository.OrderRepository' AND a.source='LLM'"));
            assertEquals(
                    1,
                    count(
                            c,
                            "SELECT count(*) FROM semantic_annotations a JOIN nodes n ON n.id=a.node_id "
                                    + "WHERE n.symbol_id='com.example.shop.controller.OrderController' AND a.source='DOC'"));
        }
    }

    @Test
    void annotate_fromJson_rejectsConventionEntry(@TempDir Path tmp) throws Exception {
        Path batch = tmp.resolve("bad.json");
        Files.writeString(
                batch,
                "[{\"node_id\":\"com.example.shop.service.OrderService\","
                        + "\"category\":\"REVIEWED\",\"source\":\"CONVENTION\"}]",
                StandardCharsets.UTF_8);
        int rc = runExit("annotate", "--from-json", batch.toString(), "--index", dbPath.toString());
        assertEquals(2, rc);
    }

    private static Connection openDb() throws Exception {
        return new SqliteStore(dbPath).connection();
    }

    private static int count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static int runExit(String... args) {
        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(cap, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(cap, true, StandardCharsets.UTF_8));
            return new CommandLine(new AnatomistCli())
                    .execute(com.anatomist.test.CliTestSupport.withTargetJdk(args));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
