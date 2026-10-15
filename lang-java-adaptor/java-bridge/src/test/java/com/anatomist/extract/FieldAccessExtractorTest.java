package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

class FieldAccessExtractorTest {

    @TempDir Path directory;
    private final java.util.List<com.anatomist.core.IndexDiagnostic> diagnostics =
            new java.util.ArrayList<>();

    @Test
    void importedTypeQualifiersDoNotBecomeFieldNotFound() throws Exception {
        String source =
                "package pkg; import javax.crypto.Cipher; import javax.crypto.KeyGenerator;"
                        + "class A { void run() throws Exception {"
                        + "Cipher.getInstance(\"AES\"); KeyGenerator.getInstance(\"AES\");"
                        + "int mode = Cipher.ENCRYPT_MODE; } }";
        ExtractionResult result = fields(source);

        assertEquals(
                0,
                fieldGaps(),
                "type qualifiers must not be tracked as missing fields: " + diagnostics);
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "READS".equals(edge.relation)
                                                && "javax.crypto.Cipher#ENCRYPT_MODE"
                                                        .equals(edge.externalTargetFqn)));
    }

    @Test
    void fullyQualifiedTypeChainDoesNotBecomeFieldNotFound() throws Exception {
        String source =
                "package pkg; class A { void run() throws Exception {"
                        + "javax.crypto.Cipher.getInstance(\"AES\"); } }";
        fields(source);

        assertEquals(
                0, fieldGaps(), "package/type scope chain must not be tracked as field access");
    }

    @Test
    void undeclaredReceiverKeepsCallAndCompilerDiagnosticsWithoutInventingAField()
            throws Exception {
        String source = "package pkg; class A { void run() { missing.value.toString(); } }";
        fields(source);

        assertEquals(0, fieldGaps());
        assertTrue(diagnostics.stream().anyMatch(d -> "NAVIGATION_CALL_GRAPH".equals(d.phase())));
        assertTrue(diagnostics.stream().anyMatch(d -> "JAVAC_ANALYZE".equals(d.phase())));
    }

    @Test
    void declaredMissingReceiverStillProducesFieldDiagnostic() throws Exception {
        fields(
                "package pkg; class A { Missing receiver; void run() { receiver.value.toString(); } }");
        assertTrue(fieldGaps() > 0, () -> "missing field diagnostic: " + diagnostics);
    }

    @Test
    void writePlainAssignment() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int x;\n"
                        + "  void set() { this.x = 5; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        long writes =
                r.edges.stream()
                        .filter(
                                e ->
                                        "WRITES".equals(e.relation)
                                                && "pkg.A#set()".equals(e.sourceId)
                                                && "pkg.A#x".equals(e.targetId))
                        .count();
        assertEquals(1, writes, "got " + r.edges);
        long reads = r.edges.stream().filter(e -> "READS".equals(e.relation)).count();
        assertEquals(0, reads, "plain `=` shouldn't emit READS; got " + r.edges);
    }

    @Test
    void readViaNameExpr() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int x;\n"
                        + "  int get() { return x + 1; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        long reads =
                r.edges.stream()
                        .filter(
                                e ->
                                        "READS".equals(e.relation)
                                                && "pkg.A#get()".equals(e.sourceId)
                                                && "pkg.A#x".equals(e.targetId))
                        .count();
        assertEquals(1, reads, "got " + r.edges);
    }

    @Test
    void compoundAssignmentEmitsBoth() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int x;\n"
                        + "  void inc() { x += 1; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        List<String> kinds =
                r.edges.stream()
                        .filter(e -> "pkg.A#x".equals(e.targetId))
                        .map(e -> e.relation)
                        .collect(Collectors.toList());
        assertTrue(kinds.contains("WRITES"), "got " + r.edges);
        assertTrue(kinds.contains("READS"), "got " + r.edges);
    }

    @Test
    void incrementEmitsWrite() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int x;\n"
                        + "  void bump() { x++; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        long writes =
                r.edges.stream()
                        .filter(e -> "WRITES".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                        .count();
        assertEquals(1, writes, "got " + r.edges);
        long reads =
                r.edges.stream()
                        .filter(e -> "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                        .count();
        assertEquals(0, reads, "x++ shouldn't emit READS; got " + r.edges);
    }

    @Test
    void enclosingId_lambdaBodyFieldAccess_attributesToLambdaNode() throws Exception {
        String source =
                "package pkg; import java.util.function.Supplier;"
                        + "public class A {"
                        + "  int x;"
                        + "  void run() {"
                        + "    Supplier<Integer> sup = () -> x + 1;"
                        + "  }"
                        + "}";
        ExtractionResult r = fields(source);

        var read =
                r.edges.stream()
                        .filter(e -> "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no READS; got " + r.edges));
        assertTrue(
                read.sourceId.startsWith("pkg.A#run()$lambda@L"),
                "field access inside lambda must attribute to LAMBDA node; got " + read.sourceId);
    }

    /** Distinct source occurrences of x must retain their own read/write roles. */
    @Test
    void selfAssignment_rhsEmitsRead_andOtherReadsInSameClassSurvive() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int x;\n"
                        + "  void inc()   { x = x + 1; }\n"
                        + "  int  value() { return x; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        long writes =
                r.edges.stream()
                        .filter(e -> "WRITES".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                        .count();
        long reads =
                r.edges.stream()
                        .filter(e -> "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                        .count();
        assertEquals(1, writes, "got " + r.edges);
        assertEquals(2, reads, "expected RHS read + return read; got " + r.edges);
    }

    @Test
    void nestedFieldTargetWritesLeafAndReadsReceiverField() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  B holder;\n"
                        + "  void set() { this.holder.value = 1; }\n"
                        + "  static class B { int value; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        assertEquals(
                1,
                r.edges.stream()
                        .filter(
                                e ->
                                        "WRITES".equals(e.relation)
                                                && "pkg.A.B#value".equals(e.targetId))
                        .count(),
                "got " + r.edges);
        assertEquals(
                1,
                r.edges.stream()
                        .filter(
                                e ->
                                        "READS".equals(e.relation)
                                                && "pkg.A#holder".equals(e.targetId))
                        .count(),
                "got " + r.edges);
    }

    @Test
    void context_recordedForWriteInsideCatch() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  boolean failed;\n"
                        + "  void run(){\n"
                        + "    try { risky(); }\n"
                        + "    catch (RuntimeException e) { this.failed = true; }\n"
                        + "  }\n"
                        + "  void risky(){}\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        var write =
                r.edges.stream()
                        .filter(
                                e ->
                                        "WRITES".equals(e.relation)
                                                && "pkg.A#failed".equals(e.targetId))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("got " + r.edges));
        assertEquals("catch@L6", write.context);
    }

    @Test
    void context_nullForUnconditionalRead() throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  int x;\n"
                        + "  int get() { return x; }\n"
                        + "}\n";
        ExtractionResult r = fields(source);

        var read =
                r.edges.stream()
                        .filter(e -> "READS".equals(e.relation) && "pkg.A#x".equals(e.targetId))
                        .findFirst()
                        .orElseThrow();
        assertNull(read.context);
    }

    @Test
    void emitsExternalReadForSystemOut() throws Exception {
        // Allow java.lang.System through by using a config that only excludes java.io.*
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.io.*"));

        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  void run() { System.out.println(\"hi\"); }\n"
                        + "}\n";
        ExtractionResult r = fields(source, config);

        boolean externalRead =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "READS".equals(e.relation)
                                                && e.isExternal
                                                && "java.lang.System#out"
                                                        .equals(e.externalTargetFqn));
        assertTrue(externalRead, "external READS for System.out expected; got " + r.edges);
    }

    @Test
    void externalNestedFieldUsesBinaryOwner() throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("unrelated.**"));
        ExtractionResult result =
                fields(
                        "package pkg; class A { Object value = "
                                + "javax.crypto.spec.PSource.PSpecified.DEFAULT; }",
                        config);

        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "READS".equals(edge.relation)
                                                && edge.isExternal
                                                && "javax.crypto.spec.PSource$PSpecified#DEFAULT"
                                                        .equals(edge.externalTargetFqn)),
                "nested external field should use its physical binary owner: " + result.edges);
    }

    @Test
    void externalFieldAccessExcludedByConfig() throws Exception {
        // Exclude everything under java.*
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.**"));

        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  void run() { System.out.println(\"hi\"); }\n"
                        + "}\n";
        ExtractionResult r = fields(source, config);

        boolean anyExternal = r.edges.stream().anyMatch(e -> e.isExternal);
        assertFalse(anyExternal, "java.** should be excluded; got " + r.edges);
    }

    private long fieldGaps() {
        return diagnostics.stream()
                .filter(
                        d ->
                                "NAVIGATION_TARGET_UNRESOLVED".equals(d.code())
                                        && "NAVIGATION_FIELD_ACCESS".equals(d.phase()))
                .mapToLong(d -> d.count())
                .sum();
    }

    private ExtractionResult fields(String source) throws Exception {
        return fields(source, new ProjectConfig());
    }

    private ExtractionResult fields(String source, ProjectConfig config) throws Exception {
        var result =
                JavaFactsTestSupport.extract(
                        directory,
                        "A.java",
                        source,
                        config,
                        com.anatomist.framework.JavaModelAnalyzerRegistry.NONE,
                        diagnostics::add);
        result.edges.removeIf(edge -> !java.util.Set.of("READS", "WRITES").contains(edge.relation));
        return result;
    }
}
