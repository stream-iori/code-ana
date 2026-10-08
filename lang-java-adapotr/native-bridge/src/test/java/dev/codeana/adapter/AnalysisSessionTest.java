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
                Path.of(System.getProperty("user.home"), ".sdkman/candidates/java/8.0.492-zulu");
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
                "version",
                7,
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

    private List<Map<String, Object>> run(Map<String, Object> request) throws Exception {
        try (var session = new AnalysisSession(request)) {
            Map<String, Object> result = session.analyze();
            List<Map<String, Object>> facts = new ArrayList<>();
            for (Object row : (Iterable<?>) result.get("facts")) facts.add(Json.map(row));
            return facts;
        }
    }

    @Test
    void selectedMethodAndUnicodeOffsetsSurviveProjection() throws Exception {
        String text =
                "class A { String s=\"😀中文\"; void caller(){ callee(1); } void callee(int x){} void callee(String x){} }";
        var facts = run(request(text));
        Map<String, Object> method =
                facts.stream()
                        .filter(f -> "A#callee(int)".equals(f.get("signature")))
                        .findFirst()
                        .orElseThrow();
        Map<String, Object> call =
                facts.stream()
                        .filter(
                                f ->
                                        "call_target".equals(f.get("kind"))
                                                && method.get("id").equals(f.get("target")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("resolved", call.get("status"));
        assertEquals(true, call.get("declaration_resolved"));
        Map<String, Object> span = Json.map(call.get("span"));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "callee(1)",
                new String(
                        Arrays.copyOfRange(
                                bytes,
                                ((Number) span.get("start")).intValue(),
                                ((Number) span.get("end")).intValue()),
                        StandardCharsets.UTF_8));
        Set<Object> ids = new HashSet<>();
        for (var f : facts) assertTrue(ids.add(f.get("id")), "duplicate fact");
        for (var f : facts)
            for (String key : List.of("parent", "target"))
                if (!"".equals(f.get(key))) assertTrue(ids.contains(f.get(key)), "dangling " + f);
    }

    @Test
    void uncapturedNeighborIsInvisibleAndProcessorIsNotRun() throws Exception {
        var req = request("class A { Missing missing; void f(){ Missing.run(); } }");
        Files.writeString(
                temporary.resolve("Missing.java"), "class Missing { static void run(){} }");
        var facts = run(req);
        assertTrue(
                facts.stream()
                        .noneMatch(
                                f ->
                                        "Missing".equals(f.get("signature"))
                                                && "declaration".equals(f.get("kind"))));
        assertTrue(facts.stream().anyMatch(f -> "diagnostic".equals(f.get("kind"))));
        try (var files = Files.walk(temporary)) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".class")));
        }
    }

    @Test
    void xmlOnlyContextPublishesOriginalFrameworkEvidence() throws Exception {
        var req = request("class A { public void start(){} }");
        var context = Json.map(Json.list(req.get("contexts")).getFirst());
        Path input = temporary.resolve("beans.xml");
        Files.writeString(
                input, "<beans><bean id=\"a\" class=\"A\" init-method=\"start\"/></beans>");
        context.put("spring", true);
        context.put("spring_xml", true);
        context.put(
                "resources",
                List.of(
                        Json.object(
                                "input",
                                input.toString(),
                                "unit",
                                Json.object(
                                        "path",
                                        "resources/beans.xml",
                                        "hash",
                                        Json.sha(input),
                                        "module",
                                        ".",
                                        "world",
                                        "resources",
                                        "scope",
                                        "RESOURCE"))));
        var facts = run(req);
        assertTrue(
                facts.stream().anyMatch(f -> "java.bean".equals(f.get("relation"))),
                facts.toString());
        assertTrue(
                facts.stream()
                        .filter(f -> "resources/beans.xml".equals(f.get("source")))
                        .allMatch(f -> "resource".equals(Json.map(f.get("owner")).get("kind"))));
    }

    @Test
    void corruptCaptureIsRejectedBeforeCompilation() throws Exception {
        var req = request("class A {}");
        Files.writeString(temporary.resolve("input.java"), "class Changed {}");
        assertTrue(
                assertThrows(Exception.class, () -> run(req))
                        .getMessage()
                        .contains("CAPTURE_HASH_MISMATCH"));
    }
}
