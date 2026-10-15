package com.anatomist.framework.sofa;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.JavaModel;
import com.anatomist.core.javac.*;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class SofaJavaModelTest {
    @Test
    void sameFqnWithANewerApiIsRetainedAsAnUnverifiedContract(@TempDir Path project)
            throws Exception {
        Path annotation =
                project.resolve("com/alipay/sofa/runtime/api/annotation/SofaService.java");
        Files.createDirectories(annotation.getParent());
        Files.writeString(
                annotation,
                "package com.alipay.sofa.runtime.api.annotation; public @interface SofaService { Class<?> interfaceType() default void.class; String uniqueId() default \"\"; String version() default \"\"; }");
        Path source =
                Files.writeString(
                        project.resolve("App.java"),
                        "interface Worker {} @com.alipay.sofa.runtime.api.annotation.SofaService class App implements Worker {}");
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var result = new ExtractionResult();
        var framework = new JavaFrameworkContext(project, List.of(source), config, false);
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        new SofaJavaModelAnnotations()
                                                .analyze(
                                                        new JavaUnitAnalysis(
                                                                framework,
                                                                model,
                                                                null,
                                                                source,
                                                                context.identity(source)),
                                                        result);
                                    }
                                })
                        .complete());
        assertEquals(1, result.nodes.size());
        var node = result.nodes.getFirst();
        var metadata = (Map<?, ?>) Json.parseTree(node.metadata);
        assertEquals(false, metadata.get("contractSupported"));
        assertEquals("unverified-api", metadata.get("contractBaseline"));
        assertEquals("Worker", metadata.get("contract"));
        assertEquals("unknown", metadata.get("transport"));
        assertTrue(result.edges.stream().allMatch(edge -> edge.relation.equals("CONFIGURES")));
    }
}
