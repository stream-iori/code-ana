package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.*;
import com.anatomist.framework.JavaModelExtensions;
import com.anatomist.framework.lombok.LombokJavaModelExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaRecoveryBudgetIT {
    @Test
    void independentEvidenceQueriesDoNotSpendPreviousQueriesBudget(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Example.java"),
                        "@lombok.Getter class Example { String name; String run() { return getName(); } }");
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.6/lombok-1.18.6.jar");
        var config = new ProjectConfig();
        config.setJavaHome(JavaFactsTestSupport.targetHome(8).toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 8, null),
                        List.of(lombok),
                        List.of(project),
                        config.javacOptions());
        backend.setDeclarationOverlay(
                new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, false))));
        backend.analyzeFiles(
                List.of(source),
                context -> {
                    try (var model = new JavaModel(context)) {
                        assertTrue(model.hasModeledDeclarations());
                        var unit = model.unit(source);
                        var recovery = new JavaModeledCallRecovery(model, unit);
                        var call =
                                model.syntax(source).nodes().stream()
                                        .filter(
                                                node ->
                                                        node.kind().equals("METHOD_INVOCATION")
                                                                && node.tree("getMethodSelect")
                                                                        .text("getName")
                                                                        .equals("getName"))
                                        .findFirst()
                                        .orElseThrow();
                        for (int query = 0; query < 100_010; query++)
                            assertEquals(
                                    JavaModeledCallRecovery.InputEvidence.ABSENT,
                                    recovery.inputEvidence(call));
                        JavaNode deep =
                                new JavaNode(500, "IDENTIFIER", -1, -1, Map.of("getName", "value"));
                        for (int depth = 0; depth < 70; depth++)
                            deep =
                                    new JavaNode(
                                            501 + depth,
                                            "PARENTHESIZED",
                                            -1,
                                            -1,
                                            Map.of("getExpression", deep));
                        var complex =
                                new JavaNode(
                                        600,
                                        "METHOD_INVOCATION",
                                        -1,
                                        -1,
                                        Map.of("getArguments", List.of(deep)));
                        assertEquals(
                                JavaModeledCallRecovery.InputEvidence.INCOMPLETE,
                                recovery.inputEvidence(complex));
                        assertEquals(
                                JavaModeledCallRecovery.InputEvidence.ABSENT,
                                recovery.inputEvidence(call));
                        assertInstanceOf(
                                JavaModeledCallRecovery.Selected.class, recovery.resolveSite(call));
                    }
                });
    }
}
