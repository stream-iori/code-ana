package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeana.common.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisSessionTest {
    @TempDir Path temporary;

    private Map<String, Object> request(String source) throws Exception {
        Path input = temporary.resolve("input.java");
        Files.writeString(input, source);
        Path scratch = Files.createDirectory(temporary.resolve("scratch"));
        Path home =
                Path.of(System.getProperty("java8.home", Path.of(System.getProperty("user.home"), ".sdkman/candidates/java/8.0.492-zulu").toString()));
        assertTrue(Files.isExecutable(home.resolve("bin/javac")), "Java 8 fixture SDK required");
        Map<String, Object> unit =
                Json.object(
                        "path",
                        "src/A.java",
                        "hash",
                        Json.sha(input),
                        "module",
                        ".",
                        "world",
                        "main",
                        "scope",
                        "MAIN",
                        "input",
                        input.toString(),
                        "publish",
                        true);
        Map<String, Object> context =
                Json.object(
                        "context",
                        "main",
                        "module",
                        ".",
                        "world",
                        "main",
                        "scope",
                        "MAIN",
                        "digest",
                        Json.sha(input),
                        "lombok",
                        "off",
                        "sources",
                        List.of(unit),
                        "classpath",
                        List.of(),
                        "resources",
                        List.of(),
                        "dependency_modules",
                        List.of());
        return Json.object(
                "analysis_grant", Json.object("id","fixture/java/worker","language","java","limits",Json.object("max_work",10000000L,"max_nodes",1000000L,"max_payload_bytes",536870912L,"max_depth",256)),
                "analysis_usage",Json.object("work",0L,"nodes",0L,"payload_bytes",0L,"max_depth",0),
                "deadline_ms",0L,
                "version",
                12,
                "scratch",
                scratch.toString(),
                "java_home",
                home.toString(),
                "contexts",
                List.of(context),
                "full",
                true,
                "compiler_heap_bytes",
                256L << 20,
                "result_bytes",
                64L << 20,
                "fact_limit",
                100000);
    }

    private List<dev.codeana.graph.GraphRecords.Row> run(Map<String,Object> request) throws Exception {
        try(var session=new AnalysisSession(request)) {
            var result=session.analyze();var archive=(GraphArchive)result.get("facts");
            Path destination=Path.of(Json.string(request,"scratch")).resolve("fixture.rows");
            archive.export(destination);
            List<dev.codeana.graph.GraphRecords.Row> rows=new ArrayList<>();
            try(var input=new java.io.DataInputStream(Files.newInputStream(destination))) {
                assertEquals(0x47524131,input.readInt());
                while(input.available()>0)rows.add(dev.codeana.graph.GraphRecords.Row.parseFrom(input.readNBytes(input.readInt())));
            }
            return rows;
        }
    }
    private static String text(dev.codeana.graph.GraphRecords.Row row,int index) {
        return row.getCells(index).getText();
    }

    @Test void productionSelectionKeepsLombokWithoutSpringOrSofa() throws Exception {
        var req = request("@lombok.Data class A { String name; }");
        var context = Json.map(Json.list(req.get("contexts")).getFirst());
        context.put("lombok", "model");
        context.put("spring", false);
        context.put("spring_xml", false);
        context.put("sofa", false);
        var annotation = temporary.resolve("Data.java");
        Files.writeString(annotation, "package lombok; public @interface Data {}");
        var original = Json.map(Json.list(context.get("sources")).getFirst());
        var unit = Json.object("path","src/lombok/Data.java","hash",Json.sha(annotation),"module",".","world","main","scope","MAIN","input",annotation.toString(),"publish",true);
        context.put("sources", List.of(original, unit));
        var rows = run(req);
        assertTrue(rows.stream().anyMatch(row -> row.getTable()==dev.codeana.graph.GraphRecords.Table.NODE && text(row,1).equals("A#getName()")));
    }

    @Test void publishesAnatomistNodesDeclarationsAndCallsWithoutRawModelFacts() throws Exception {
        var rows=run(request("class A { void caller(){callee(1);} void callee(int x){} }"));
        var nodes=rows.stream().filter(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.NODE).toList();
        assertTrue(nodes.stream().anyMatch(r->text(r,1).equals("A#callee(int)")));
        assertTrue(nodes.stream().allMatch(r->text(r,0).startsWith("java-core::")));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.DECLARATION));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.EDGE && text(r,6).equals("CALLS")));
        assertFalse(rows.stream().flatMap(r->r.getCellsList().stream()).anyMatch(c->c.getText().equals("java.syntax")||c.getText().equals("java.binding")||c.getText().equals("java.type")));
    }
    @Test void classNamedMethodAndConstructorKeepSeparateProducerIdentities() throws Exception {
        var rows=run(request("class A { public void A(){} void caller(){new A();A();} }"));
        var symbols=rows.stream().filter(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.NODE).map(r->text(r,1)).toList();
        assertTrue(symbols.contains("A#A()"));assertTrue(symbols.contains("A#<init>()"));
    }
    @Test void declarationAnnotationRetainsStructuredUpstreamValue() throws Exception {
        var rows=run(request("@interface Mark {String value();} @Mark(\"中😀\") class A {}"));
        var annotations=rows.stream().filter(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.ANNOTATION).toList();
        assertFalse(annotations.isEmpty());assertTrue(annotations.stream().anyMatch(r->text(r,3).contains("中😀")));
    }
    @Test void unresolvedSourceCallKeepsItsSiteAndNeverInventsTarget() throws Exception {
        var rows=run(request("class A {void caller(){missing();}}"));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.UNRESOLVED_CALL && text(r,7).equals("missing")));
        assertFalse(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.EDGE && text(r,1).contains("missing")));
    }
    @Test void uncapturedNeighborDoesNotBecomeADeclaration() throws Exception {
        var req=request("class A {Missing value;}");
        Files.writeString(temporary.resolve("Missing.java"),"class Missing {}");
        var rows=run(req);
        assertFalse(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.NODE && text(r,1).equals("Missing")));
    }
    @Test void invalidCaptureFailsBeforeCompilerStarts() throws Exception {
        var req=request("class A {}");
        var context=Json.map(Json.list(req.get("contexts")).getFirst());
        Json.map(Json.list(context.get("sources")).getFirst()).put("hash","wrong");
        try(var session=new AnalysisSession(req)){assertThrows(java.io.IOException.class,session::analyze);}
    }
    @Test void commentRowsKeepUtf8BodyMapsAndExactPublishedWorldReferences() throws Exception {
        String source = "/** 库存😀HTTPServer2 */\r\nclass A {\r\n void run() { // 调用库存\r\n  System.out.println(1); // 完成\r\n }\r\n} // 孤立";
        var rows = run(request(source));
        var comments = rows.stream().filter(r -> r.getTable() == dev.codeana.graph.GraphRecords.Table.COMMENT).toList();
        assertEquals(4, comments.size());
        var links = rows.stream().filter(r -> r.getTable() == dev.codeana.graph.GraphRecords.Table.COMMENT_LINK).toList();
        var nodes = rows.stream().filter(r -> r.getTable() == dev.codeana.graph.GraphRecords.Table.NODE).toList();
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        for (var comment : comments) {
            assertEquals(18, comment.getCellsCount());
            assertEquals("main", comment.getWorld());
            assertEquals("src/A.java", text(comment, 1));
            assertEquals(Json.sha(bytes), text(comment, 2));
            int start = Math.toIntExact(comment.getCells(9).getInteger());
            int end = Math.toIntExact(comment.getCells(10).getInteger());
            assertTrue(new String(bytes, start, end - start, StandardCharsets.UTF_8).startsWith("/"));
            String expected = "comment_" + Json.sha(Json.write(List.of(text(comment, 7), "java", ".", "MAIN", "main",
                    "src/A.java", Json.sha(bytes), Integer.toString(start), Integer.toString(end), text(comment, 8)))
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, text(comment, 0));
            int previous = 0;
            var bodyBytes = text(comment, 15).getBytes(StandardCharsets.UTF_8);
            for (Object raw : Json.list(com.anatomist.json.Json.parseTree(text(comment, 16)))) {
                var segment = Json.map(raw);
                assertEquals(previous, Json.number(segment, "body_start"));
                previous = Math.toIntExact(Json.number(segment, "body_end"));
                int a = Math.toIntExact(Json.number(segment, "source_start"));
                int b = Math.toIntExact(Json.number(segment, "source_end"));
                assertTrue(a >= start && b <= end);
                assertArrayEquals(Arrays.copyOfRange(bodyBytes, Math.toIntExact(Json.number(segment, "body_start")), previous),
                        Arrays.copyOfRange(bytes, a, b));
            }
            assertEquals(bodyBytes.length, previous);
        }
        assertTrue(links.stream().anyMatch(l -> text(l, 1).equals("documents") && text(l, 2).equals("java-core::.::MAIN::A")));
        assertTrue(links.stream().anyMatch(l -> text(l, 1).equals("enclosing_callable") && text(l, 2).equals("java-core::.::MAIN::A#run()")));
        for (var link : links) {
            assertEquals(14, link.getCellsCount());
            assertEquals("main", link.getWorld());
            if (link.getCells(2).hasText()) assertTrue(nodes.stream().anyMatch(n -> text(n, 0).equals(text(link, 2)) && n.getWorld().equals(link.getWorld())));
        }
        String orphan = text(comments.getLast(), 0);
        assertTrue(links.stream().noneMatch(l -> text(l, 0).equals(orphan)));
    }

    @Test void oldRequestVersionIsExplicitlyRejected() throws Exception {
        var req=request("class A {}");req.put("version",10);
        assertThrows(IllegalArgumentException.class,()->new AnalysisSession(req));
    }
    @Test void archiveRetainsFileCacheAndProviderWithoutInventingCoverage() throws Exception {
        var rows=run(request("class A {}"));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.FILE_CACHE && text(r,1).equals("src/A.java")));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.PROVIDER && text(r,0).equals("java-core")));
        assertFalse(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.COVERAGE && text(r,5).equals("SEMANTIC")));
    }
    @Test void dependencyFallbackDisclosesGapEvenWithoutMissingSymbols() throws Exception {
        var req=request("class A { String value(){return \"ok\".trim();} }");
        req.put("dependency_mode", "source_only_fallback");
        req.put("dependency_failure", Json.object("module", ".", "pom", "pom.xml",
                "coordinate", "fixture:missing:1", "reason", "offline", "round", 0));
        var rows=run(req);
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.PROJECT_META
                && text(r,0).equals("analysis_mode") && text(r,1).equals("source_only")));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.DIAGNOSTIC
                && text(r,1).equals("CLASSPATH_UNAVAILABLE") && text(r,13).contains("pom.xml")));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.COVERAGE
                && text(r,0).equals("*") && text(r,1).equals(".") && text(r,2).equals("MAIN") && text(r,6).equals("partial")));
        assertTrue(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.EDGE
                && text(r,3).equals("java.lang.String#trim()")));
    }
    @Test void requestedSourceOnlyDoesNotInventDependencyFailure() throws Exception {
        var req=request("class A {}");req.put("dependency_mode", "source_only_requested");
        var rows=run(req);
        assertFalse(rows.stream().anyMatch(r->r.getTable()==dev.codeana.graph.GraphRecords.Table.DIAGNOSTIC
                && text(r,1).equals("CLASSPATH_UNAVAILABLE")));
    }
}
