package com.anatomist.framework.spring;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.JavaModel;
import com.anatomist.core.javac.*;
import com.anatomist.framework.*;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class SpringJavaModelTest {
    @Test
    void singleConstructorInjectionSurvivesAnnotationPrefilter(@TempDir Path project)
            throws Exception {
        Path component = project.resolve("org/springframework/stereotype/Component.java");
        Files.createDirectories(component.getParent());
        Files.writeString(
                component,
                "package org.springframework.stereotype; public @interface Component {}");
        Path source =
                Files.writeString(
                        project.resolve("Service.java"),
                        "class Dependency {} @org.springframework.stereotype.Component class Service { Service(Dependency dependency) {} }");
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var facts = new ExtractionResult();
        var framework = new JavaFrameworkContext(project, List.of(source), config, false);
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        new SpringJavaModelComponents()
                                                .analyze(
                                                        new JavaUnitAnalysis(
                                                                framework,
                                                                model,
                                                                null,
                                                                source,
                                                                context.identity(source)),
                                                        facts);
                                    }
                                })
                        .complete());
        assertTrue(
                facts.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("INJECTS")
                                                && "Dependency".equals(edge.targetId)
                                                && edge.metadata.contains(
                                                        "implicitSingleConstructor")));
    }

    @Test
    void failedDeclarationDiscardsOnlyItsOwnFacts(@TempDir Path project) throws Exception {
        Path source = Files.writeString(project.resolve("Example.java"), "class Example {}");
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var framework = new JavaFrameworkContext(project, List.of(source), config, false);
        var facts = new ExtractionResult();
        var report = new ExtensionReport();
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        var unit =
                                                new JavaUnitAnalysis(
                                                        framework,
                                                        model,
                                                        null,
                                                        source,
                                                        context.identity(source));
                                        var reference =
                                                model.declarations(source).stream()
                                                        .filter(
                                                                item ->
                                                                        model.declaration(item)
                                                                                .symbol()
                                                                                .kind()
                                                                                .equals("CLASS"))
                                                        .findFirst()
                                                        .orElseThrow();
                                        SpringDeclarationExecution.run(
                                                unit,
                                                "spring-components",
                                                reference,
                                                report,
                                                facts,
                                                isolated -> {
                                                    var bad = new com.anatomist.model.Node();
                                                    bad.id = "discard";
                                                    isolated.nodes.add(bad);
                                                    throw new IllegalArgumentException("budget");
                                                });
                                        SpringDeclarationExecution.run(
                                                unit,
                                                "spring-components",
                                                reference,
                                                report,
                                                facts,
                                                isolated -> {
                                                    var good = new com.anatomist.model.Node();
                                                    good.id = "keep";
                                                    isolated.nodes.add(good);
                                                });
                                    }
                                })
                        .complete());
        assertEquals(List.of("keep"), facts.nodes.stream().map(node -> node.id).toList());
        assertEquals(1, report.diagnostics().size());
        assertEquals("EXTENSION_JAVA_DECLARATION_FAILED", report.diagnostics().getFirst().code());
    }

    @Test
    void largeAnnotatedTypeDoesNotExhaustSpringAnnotationBudget(@TempDir Path project)
            throws Exception {
        Path bean = project.resolve("org/springframework/context/annotation/Bean.java");
        Files.createDirectories(bean.getParent());
        Files.writeString(
                bean,
                "package org.springframework.context.annotation; public @interface Bean { String value() default \"\"; }");
        Path post = project.resolve("javax/annotation/PostConstruct.java");
        Files.createDirectories(post.getParent());
        Files.writeString(post, "package javax.annotation; public @interface PostConstruct {}");
        StringBuilder body = new StringBuilder("class Large {\n");
        for (int index = 0; index < 238; index++)
            body.append("  private String field").append(index).append(";\n");
        for (int index = 0; index < 476; index++)
            body.append("  @SuppressWarnings(\"all\") void method").append(index).append("() {}\n");
        body.append(
                        "  @org.springframework.context.annotation.Bean String bean() { return \"ok\"; }\n")
                .append("  @javax.annotation.PostConstruct void ready() {}\n}");
        Path source = Files.writeString(project.resolve("Large.java"), body);
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        var framework = new JavaFrameworkContext(project, List.of(source), config, false);
        var facts = new ExtractionResult();
        var report = new ExtensionReport();
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                context -> {
                                    try (var model = new JavaModel(context)) {
                                        var analysis =
                                                new JavaUnitAnalysis(
                                                        framework,
                                                        model,
                                                        null,
                                                        source,
                                                        context.identity(source));
                                        new SpringJavaModelComponents()
                                                .analyze(analysis, facts, report);
                                        new SpringJavaModelHandlers()
                                                .analyze(analysis, facts, report);
                                    }
                                })
                        .complete());
        assertTrue(report.diagnostics().isEmpty(), report.diagnostics().toString());
        assertTrue(facts.nodes.stream().anyMatch(node -> node.id.equals("bean:bean")));
        assertTrue(
                facts.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("HANDLES")
                                                && edge.targetId != null
                                                && edge.targetId.endsWith("#ready()")));
    }

    @Test
    void detachedBeanAndFactoryDependencyFactsDoNotRequireAParserAdapter(@TempDir Path project)
            throws Exception {
        Path annotation = project.resolve("org/springframework/context/annotation/Bean.java");
        Files.createDirectories(annotation.getParent());
        Files.writeString(
                annotation,
                "package org.springframework.context.annotation; public @interface Bean { String[] value() default {}; }");
        Path source =
                Files.writeString(
                        project.resolve("App.java"),
                        "class Worker {} class App { @org.springframework.context.annotation.Bean(\"worker\") Worker worker(Worker dependency) { return null; } }");
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
                                        new SpringJavaModelComponents()
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
        assertEquals("bean:worker", result.nodes.getFirst().id);
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("DEFINED_BY")
                                                && "App#worker(Worker)".equals(edge.targetId)));
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("INJECTS")
                                                && "Worker".equals(edge.targetId)));
        assertTrue(result.edges.stream().noneMatch(edge -> edge.relation.equals("CALLS")));
    }
}
