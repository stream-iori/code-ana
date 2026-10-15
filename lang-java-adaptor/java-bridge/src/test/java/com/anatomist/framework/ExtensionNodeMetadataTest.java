package com.anatomist.framework;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ExtensionNodeMetadataTest {
    @TempDir Path directory;

    @Test
    void namespacesMergeIdempotentlyAndSurviveSnapshot() throws Exception {
        var original =
                JavaFactsTestSupport.analyze(
                        directory, "Sample.java", "class Sample { String value; }");
        var extensions =
                new JavaModelExtensions(
                        List.of(
                                contribution("first", "lombok", Map.of("coverage", "complete")),
                                contribution("repeat", "lombok", Map.of("coverage", "complete")),
                                contribution("other", "other", Map.of("enabled", true))));
        var augmented = extensions.augment(original, null);
        var metadata = augmented.units().getFirst().extensions();
        assertEquals(
                Map.of("coverage", "complete"),
                metadata.declarationMetadata().get("Sample").get("lombok"));
        assertEquals(
                Map.of("enabled", true), metadata.declarationMetadata().get("Sample").get("other"));
        assertTrue(original.units().getFirst().extensions().declarationMetadata().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> metadata.declarationMetadata().get("Sample").put("bad", true));
        var facts =
                JavaFactsTestSupport.declarations(
                        directory, "Sample.java", "class Sample { String value; }", metadata);
        var type =
                facts.nodes.stream()
                        .filter(node -> node.label.equals("Sample"))
                        .findFirst()
                        .orElseThrow();
        var serialized = (Map<?, ?>) Json.parseTree(type.metadata);
        assertEquals(false, serialized.get("isAbstract"));
        assertEquals(Map.of("coverage", "complete"), serialized.get("lombok"));
        assertEquals(Map.of("enabled", true), serialized.get("other"));
    }

    @Test
    void conflictingNamespaceIsRejectedAndOriginalContributionSurvives() throws Exception {
        var original = JavaFactsTestSupport.analyze(directory, "Sample.java", "class Sample {}");
        var result =
                new JavaModelExtensions(
                                List.of(
                                        contribution(
                                                "first", "lombok", Map.of("coverage", "complete")),
                                        contribution(
                                                "conflict",
                                                "lombok",
                                                Map.of("coverage", "partial")),
                                        contribution("healthy", "other", Map.of("enabled", true))))
                        .augment(original, null);
        var data = result.units().getFirst().extensions();
        assertEquals(
                Map.of("coverage", "complete"),
                data.declarationMetadata().get("Sample").get("lombok"));
        assertEquals(
                Map.of("enabled", true), data.declarationMetadata().get("Sample").get("other"));
        assertTrue(
                data.diagnostics().stream()
                        .anyMatch(
                                value ->
                                        value.code().equals("JAVA_MODEL_EXTENSION_FAILED")
                                                && value.symbol().equals("conflict")
                                                && value.sample()
                                                        .contains(
                                                                "EXTENSION_METADATA_NAMESPACE_CONFLICT")));
    }

    private static JavaModelExtension contribution(
            String id, String namespace, Map<String, Object> value) {
        return new JavaModelExtension() {
            public String id() {
                return id;
            }

            public JavaAnalysisContext augment(
                    JavaAnalysisContext context, JavaBinaryRepository binaries) {
                var units =
                        context.units().stream()
                                .map(
                                        unit -> {
                                            var old = unit.extensions();
                                            var metadata =
                                                    new LinkedHashMap<>(old.declarationMetadata());
                                            var row =
                                                    new LinkedHashMap<>(
                                                            metadata.getOrDefault(
                                                                    "Sample", Map.of()));
                                            row.put(namespace, value);
                                            metadata.put("Sample", row);
                                            return new JavaUnit(
                                                    unit.path(),
                                                    unit.tree(),
                                                    unit.processing(),
                                                    unit.bindings(),
                                                    unit.types(),
                                                    unit.declarations(),
                                                    unit.declarationInventory(),
                                                    unit.sourceHash(),
                                                    new JavaExtensionData(
                                                            old.generated(),
                                                            metadata,
                                                            old.diagnostics(),
                                                            old.counters()));
                                        })
                                .toList();
                return new JavaAnalysisContext(
                        context.module(),
                        context.scope(),
                        units,
                        context.publish(),
                        context.diagnostics(),
                        context.analysisFailure(),
                        context.sourceIdentities());
            }
        };
    }
}
