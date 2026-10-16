package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeana.common.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

class AnalysisRequestTest {
    @TempDir Path root;

    Map<String, Object> input() {
        return Json.object(
                "version",
                10,
                "scratch",
                root.toString(),
                "java_home",
                root.toString(),
                "contexts",
                List.of());
    }

    @Test
    void defaultsAreNamedAndKnownIntegerFieldsRemainExact() throws Exception {
        var raw = input();
        var request = AnalysisRequest.parse(raw);
        assertTrue(request.full());
        assertTrue(request.signatureCache());
        assertEquals(1, request.parallelism());
        assertEquals(512, request.compilerHeapMb());
        assertEquals(120, request.compilerTimeout().toSeconds());
        assertThrows(
                IllegalArgumentException.class,
                () -> ContextOptions.parse(Json.object("lombok_strict", "false")));
        raw.put("parallelism", new BigDecimal("1.5"));
        assertThrows(IllegalArgumentException.class, () -> AnalysisRequest.parse(raw));
    }

    @Test
    void compactContextsPreserveSharedSelectionAndPublicationRoles() throws Exception {
        var raw = input();
        raw.put("compact_inputs", true);
        raw.put(
                "input_table",
                List.of(
                        Json.object(
                                "unit",
                                Json.object(
                                        "path",
                                        "src/A.java",
                                        "hash",
                                        "sealed",
                                        "module",
                                        "app",
                                        "world",
                                        "main",
                                        "scope",
                                        "MAIN",
                                        "language",
                                        "java"),
                                "input",
                                root.resolve("A.java").toString())));
        raw.put("classpath_table", List.of());
        raw.put(
                "contexts",
                List.of(
                        Json.object(
                                "context",
                                "app@MAIN",
                                "module",
                                "app",
                                "world",
                                "main",
                                "scope",
                                "MAIN",
                                "digest",
                                "context",
                                "source_indices",
                                List.of(0),
                                "publish_indices",
                                List.of(0),
                                "resource_indices",
                                List.of(),
                                "classpath_indices",
                                List.of())));
        var context = AnalysisRequest.parse(raw).contexts().getFirst();
        assertEquals("src/A.java", context.sources().getFirst().path());
        assertTrue(context.sources().getFirst().publish());
        assertEquals(64L << 20, context.options().modelArchiveBytes());
        assertThrows(UnsupportedOperationException.class, () -> context.sources().clear());
    }

    @Test
    void baselineFilesAreVerifiedWhenTheAttemptStarts() throws Exception {
        Path file = root.resolve("baseline.json");
        Files.writeString(file, "{\"contexts\":{}}");
        var raw = input();
        raw.put(
                "baseline_summary",
                Json.object(
                        "path",
                        file.toString(),
                        "bytes",
                        Files.size(file),
                        "sha256",
                        Json.sha(file)));
        var request = AnalysisRequest.parse(raw);
        Files.writeString(file, "{}");
        assertThrows(java.io.IOException.class, () -> request.baseline().read());
    }

    @Test
    void baselineRetainsRelationOwnershipUntilFullDeltaSelection() throws Exception {
        var raw = input();
        raw.put(
                "baseline_summary",
                Json.object(
                        "changed_sources",
                        List.of("A.java"),
                        "type_relations",
                        List.of(
                                Json.object(
                                        "source_file",
                                        "A.java",
                                        "source_symbol",
                                        "A",
                                        "target_symbol",
                                        "Base",
                                        "relation",
                                        "EXTENDS"))));
        var baseline = AnalysisRequest.parse(raw).baseline().read();
        assertEquals(Set.of("A.java"), baseline.changedSources());
        assertEquals("A.java", baseline.relations().getFirst().sourceFile());
        assertEquals("A", baseline.relations().getFirst().fact().sourceType());
    }
}
