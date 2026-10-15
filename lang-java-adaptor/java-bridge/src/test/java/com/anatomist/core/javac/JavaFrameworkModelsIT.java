package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.framework.sofa.SofaJavaModelAnnotations;
import com.anatomist.framework.spring.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaFrameworkModelsIT {
    @Test
    void detachedPluginsShareSpringXmlInventoryAndConsumeStagedJavaFacts(@TempDir Path project)
            throws Exception {
        Path source =
                write(
                        project,
                        "p/App.java",
                        "package p; class Worker {} class App { void setWorker(Worker worker) {} }");
        write(
                project,
                "beans.xml",
                """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:sofa="http://schema.alipay.com/sofa/schema/service">
                  <bean id="worker" class="p.Worker"/>
                  <bean id="app" class="p.App"><property name="worker" ref="worker"/></bean>
                  <sofa:service interface="p.Worker" ref="worker"><sofa:binding.jvm/></sofa:service>
                </beans>
                """);
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(25)).toString());
        var context = new JavaFrameworkContext(project, List.of(source), config, true);
        var registry =
                com.anatomist.application.FrameworkPlugins.builtIns().prepareDetached(context);
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        backend.setDeclarationOverlay(registry.overlays());
        var javaFacts = new ArrayList<JavaModelExtractor.UnitFacts>();
        var extractor = new JavaModelExtractor(context, null, registry);
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                analysis -> extractor.extract(analysis, javaFacts::add))
                        .complete());
        var facts = javaFacts.getFirst().facts();
        var known =
                facts.nodes.stream()
                        .map(node -> node.id)
                        .collect(java.util.stream.Collectors.toSet());
        var report = new ExtensionReport();
        var inventory =
                new ProjectResourceDiscovery()
                        .discover(
                                registry,
                                context,
                                new com.anatomist.core.ProjectScanner(Set.of()),
                                report);
        assertEquals(1, inventory.size());
        assertEquals("beans.xml", inventory.getFirst().sourceFile());
        var result = new ExtractionResult();
        var producers =
                new ProjectAnalysisRunner()
                        .run(
                                registry,
                                context,
                                inventory,
                                new DefaultProjectFactView(known, Map.of()),
                                result,
                                report);
        assertEquals(Set.of("spring-xml", "sofa-xml"), producers);
        assertTrue(report.diagnostics().isEmpty(), report.diagnostics().toString());
        assertTrue(result.edges.stream().anyMatch(edge -> edge.relation.equals("WIRES")));
        assertTrue(result.edges.stream().anyMatch(edge -> edge.relation.equals("CONFIGURES")));
        assertTrue(result.nodes.stream().anyMatch(node -> "sofa-xml".equals(node.producerId)));
        var disabled = new JavaFrameworkContext(project, List.of(source), config, false);
        assertTrue(
                new ProjectResourceDiscovery()
                        .discover(
                                registry,
                                disabled,
                                new com.anatomist.core.ProjectScanner(Set.of()),
                                new ExtensionReport())
                        .isEmpty());
    }

    @Test
    void springAndSofaFactsUseDetachedDeclarationsAcrossTargetJdks(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            annotations(project);
            Path source =
                    write(
                            project,
                            "p/App.java",
                            """
                    package p;
                    import org.springframework.stereotype.*;
                    import org.springframework.context.annotation.*;
                    import org.springframework.beans.factory.annotation.*;
                    import org.springframework.web.bind.annotation.*;
                    import org.springframework.context.event.EventListener;
                    import org.springframework.transaction.event.*;
                    import com.alipay.sofa.runtime.api.annotation.*;
                    import jakarta.annotation.*;
                    import java.util.*;
                    interface Worker {} interface Second {} interface Generic<T> {}
                    class Product implements Worker {
                        public void start() {} public void stop() {} public static void staticOnly() {}
                        @PostConstruct void init() {} @PreDestroy void destroy() {}
                        @EventListener(condition="event != null") void event(String event) {}
                        @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT) void transaction(String event) {}
                    }
                    @Service @Primary @Qualifier("fast") @Priority(2) @Profile("prod") class Impl extends Product {
                        @Autowired(required=false) Worker worker;
                        @SofaReference Worker sofaField;
                        @Resource(name="named", lookup="java:worker") Worker resource;
                        @jakarta.inject.Inject Worker injected;
                        @Autowired List<Worker> many;
                        @Autowired Map<String,Worker> mapped;
                        @Autowired Map<Integer,Worker> badMap;
                        @Autowired List raw;
                        @Autowired List<? extends Worker> wildcard;
                        @Autowired Worker[] array;
                        @Autowired Generic<String> generic;
                        @Autowired int unsupported;
                        Impl(@Qualifier("fast") Worker worker, @Value("1") int ignored, @SofaReference Worker sofa) {}
                        @Resource(lookup="java:setter") void setWorker(Worker worker) {}
                        @Resource void invalid(Worker worker) {}
                        @Resource void setTooMany(Worker one, Worker two) {}
                        @Autowired @Qualifier("fast") void inject(Worker one, @Qualifier("slow") Worker two) {}
                    }
                    @Component class Multi { Multi() {} Multi(Worker worker) {} }
                    @Component class Explicit { @Autowired Explicit(Worker worker) {} Explicit() {} }
                    @Configuration @Profile("prod") class Config {
                        @Bean(name={"factory","alias"}) @Primary @Qualifier("factory-qual") Product factory(Worker worker, @Value("1") int ignored) { return null; }
                        @Bean(initMethod="start", destroyMethod="stop") Product lifecycle() { return null; }
                        @Bean(initMethod="missing") Product missing() { return null; }
                        @Bean(initMethod="staticOnly") Product staticMethod() { return null; }
                        @Bean(initMethod="ping") String external() { return null; }
                    }
                    @RestController @RequestMapping(path={"/api","/v1"}) class Http {
                        @GetMapping(value={"/x","/y","/x"}, produces="text/plain") void get(@RequestParam("q") String q, @RequestBody Product product) {}
                        @PostMapping("/post") void post() {}
                        @PutMapping("/put") void put() {}
                        @DeleteMapping("/delete") void delete() {}
                        @PatchMapping("/patch") void patch() {}
                        @RequestMapping(path="/multi", method={RequestMethod.GET,RequestMethod.POST}, params="x=1", consumes="text/plain") void multi() {}
                        @RequestMapping void any() {}
                        void plain() {}
                    }
                    @SofaService class Provider implements Worker {}
                    @SofaService(interfaceType=Worker.class, uniqueId="explicit") class ExplicitProvider {}
                    @SofaService class UnsupportedProvider implements Worker, Second {}
                    class Consumer {
                        @SofaReference(uniqueId="id") Worker worker;
                        @SofaReference(uniqueId="${dynamic}") Worker dynamic;
                    }
                    @interface ServiceLookalike {} @ServiceLookalike class Fake {}
                    """);
            var facts = extract(project, source, release);
            assertEquals(
                    Set.of(
                            "bean:impl",
                            "bean:multi",
                            "bean:explicit",
                            "bean:config",
                            "bean:http",
                            "bean:factory",
                            "bean:lifecycle",
                            "bean:missing",
                            "bean:staticMethod",
                            "bean:external"),
                    facts.nodes.stream()
                            .filter(node -> node.kind.equals("BEAN"))
                            .map(node -> node.id)
                            .collect(java.util.stream.Collectors.toSet()),
                    "JDK " + release);
            var impl = node(facts, "bean:impl");
            var implMeta = metadata(impl.metadata);
            assertEquals(true, implMeta.get("primary"));
            assertEquals("fast", implMeta.get("qualifier"));
            assertEquals(true, implMeta.get("prioritySelectionUnknown"));
            assertTrue(implMeta.containsKey("configurationConstraints"));
            assertEquals(
                    List.of("factory", "alias"),
                    metadata(node(facts, "bean:factory").metadata).get("aliases"));
            var injections =
                    facts.edges.stream().filter(edge -> edge.relation.equals("INJECTS")).toList();
            assertEquals(17, injections.size(), injections.toString());
            assertTrue(
                    injections.stream()
                            .noneMatch(
                                    edge ->
                                            metadata(edge.metadata)
                                                    .get("injectionSite")
                                                    .toString()
                                                    .contains("ignored")));
            var resource =
                    injections.stream()
                            .filter(
                                    edge ->
                                            metadata(edge.metadata)
                                                    .get("injectionSite")
                                                    .equals("setter:worker"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(true, metadata(resource.metadata).get("matchingUnsupported"));
            assertEquals(
                    Map.of("lookup", "\"java:setter\""),
                    metadata(resource.metadata).get("resourceAttributes"));
            assertTrue(
                    injections.stream()
                            .anyMatch(
                                    edge ->
                                            "false"
                                                    .equals(
                                                            metadata(edge.metadata)
                                                                    .get("required"))));
            assertTrue(
                    injections.stream()
                            .anyMatch(
                                    edge ->
                                            metadata(edge.metadata)
                                                    .get("injectionShape")
                                                    .equals("array")));
            assertTrue(
                    injections.stream()
                            .anyMatch(edge -> metadata(edge.metadata).get("cardinality") != null));
            var routes = facts.nodes.stream().filter(node -> node.kind.equals("ROUTE")).toList();
            assertEquals(18, routes.size());
            assertTrue(
                    routes.stream()
                            .anyMatch(
                                    route ->
                                            route.id.equals(
                                                    "route:GET /api/x|handler:p.Http#get(java.lang.String,p.Product)")));
            assertTrue(routes.stream().anyMatch(route -> route.label.equals("ANY /api")));
            assertTrue(
                    routes.stream()
                            .allMatch(
                                    route ->
                                            route.scope.equals("GENERATED")
                                                    && route.module.equals("generated")));
            assertTrue(
                    facts.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("HANDLES")
                                                    && "p.Product#start()".equals(edge.targetId)));
            assertTrue(
                    facts.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("HANDLES")
                                                    && "p.Product#stop()".equals(edge.targetId)));
            assertTrue(
                    facts.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("HANDLES")
                                                    && "p.Product#missing()"
                                                            .equals(edge.externalTargetFqn)));
            assertTrue(
                    facts.nodes.stream()
                            .noneMatch(
                                    node ->
                                            node.metadata != null
                                                    && node.metadata.contains("(inferred)")));
            var registrations =
                    facts.nodes.stream()
                            .filter(node -> node.kind.equals("FRAMEWORK_REGISTRATION"))
                            .toList();
            var sofa =
                    registrations.stream()
                            .filter(node -> "sofa-annotations".equals(node.producerId))
                            .toList();
            assertEquals(6, sofa.size());
            assertEquals(
                    4,
                    sofa.stream()
                            .filter(
                                    node ->
                                            Boolean.TRUE.equals(
                                                    metadata(node.metadata)
                                                            .get("contractSupported")))
                            .count());
            assertTrue(registrations.stream().allMatch(node -> node.scope.equals("GENERATED")));
            assertTrue(
                    registrations.stream()
                            .anyMatch(
                                    node ->
                                            metadata(node.metadata).get("transactionPhase")
                                                    != null));
            assertTrue(
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            Set.of(
                                                            "spring-components",
                                                            "spring-mvc",
                                                            "spring-handlers",
                                                            "sofa-annotations")
                                                    .contains(edge.producerId))
                            .allMatch(
                                    edge ->
                                            !edge.relation.equals("CALLS")
                                                    && edge.confidence.equals("CONFIGURED")));
            assertFalse(Files.exists(project.resolve("target")));
        }
    }

    private static ExtractionResult extract(Path project, Path source, int release)
            throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        var backend =
                new JavacBackend(
                        project,
                        JavacToolchain.resolve(config.javacOptions(), release, null),
                        List.of(),
                        List.of(new SourceRoot(project, "generated", SourceScope.GENERATED)),
                        config.javacOptions());
        var registry =
                new JavaModelAnalyzerRegistry(
                        List.of(),
                        List.of(
                                new SpringJavaModelComponents(),
                                new SpringJavaModelMvc(),
                                new SpringJavaModelHandlers(),
                                new SofaJavaModelAnnotations()));
        backend.setDeclarationOverlay(registry.overlays());
        var extractor =
                new JavaModelExtractor(
                        new JavaFrameworkContext(project, List.of(source), config, false),
                        null,
                        registry);
        var results = new ArrayList<JavaModelExtractor.UnitFacts>();
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                context -> extractor.extract(context, results::add))
                        .complete());
        assertTrue(
                results.getFirst().diagnostics().stream()
                        .noneMatch(value -> value.code().equals("EXTENSION_JAVA_ANALYZER_FAILED")),
                results.getFirst().diagnostics().toString());
        return results.getFirst().facts();
    }

    private static Map<?, ?> metadata(String json) {
        return (Map<?, ?>) Json.parseTree(json);
    }

    private static Node node(ExtractionResult facts, String id) {
        return facts.nodes.stream().filter(node -> id.equals(node.id)).findFirst().orElseThrow();
    }

    private static Path write(Path project, String name, String source) throws Exception {
        Path file = project.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }

    private static void annotation(Path project, String name, String body, String meta)
            throws Exception {
        int separator = name.lastIndexOf('.');
        write(
                project,
                name.replace('.', '/') + ".java",
                "package "
                        + name.substring(0, separator)
                        + "; "
                        + meta
                        + " public @interface "
                        + name.substring(separator + 1)
                        + " { "
                        + body
                        + " }");
    }

    private static void annotations(Path project) throws Exception {
        annotation(
                project,
                "org.springframework.stereotype.Component",
                "String value() default \"\";",
                "");
        for (String name : List.of("Service", "Controller"))
            annotation(
                    project,
                    "org.springframework.stereotype." + name,
                    "String value() default \"\";",
                    "@Component");
        annotation(
                project,
                "org.springframework.context.annotation.Configuration",
                "String value() default \"\";",
                "@org.springframework.stereotype.Component");
        annotation(project, "org.springframework.context.annotation.Primary", "", "");
        annotation(
                project, "org.springframework.context.annotation.Profile", "String[] value();", "");
        annotation(
                project,
                "org.springframework.context.annotation.Bean",
                "String[] name() default {}; String[] value() default {}; String initMethod() default \"\"; String destroyMethod() default \"(inferred)\";",
                "");
        annotation(
                project,
                "org.springframework.beans.factory.annotation.Autowired",
                "boolean required() default true;",
                "");
        annotation(
                project,
                "org.springframework.beans.factory.annotation.Qualifier",
                "String value();",
                "");
        annotation(
                project,
                "org.springframework.beans.factory.annotation.Value",
                "String value();",
                "");
        annotation(
                project,
                "jakarta.annotation.Resource",
                "String name() default \"\"; String lookup() default \"\";",
                "");
        annotation(project, "jakarta.inject.Inject", "", "");
        annotation(project, "jakarta.annotation.Priority", "int value();", "");
        for (String name : List.of("PostConstruct", "PreDestroy"))
            annotation(project, "jakarta.annotation." + name, "", "");
        annotation(
                project,
                "org.springframework.context.event.EventListener",
                "String condition() default \"\";",
                "");
        write(
                project,
                "org/springframework/transaction/event/TransactionPhase.java",
                "package org.springframework.transaction.event; public enum TransactionPhase { BEFORE_COMMIT, AFTER_COMMIT }");
        annotation(
                project,
                "org.springframework.transaction.event.TransactionalEventListener",
                "TransactionPhase phase() default TransactionPhase.AFTER_COMMIT;",
                "");
        annotation(
                project,
                "org.springframework.web.bind.annotation.RestController",
                "",
                "@org.springframework.stereotype.Controller");
        write(
                project,
                "org/springframework/web/bind/annotation/RequestMethod.java",
                "package org.springframework.web.bind.annotation; public enum RequestMethod { GET, POST }");
        String mapping =
                "String[] value() default {}; String[] path() default {}; String[] consumes() default {}; String[] produces() default {}; String[] params() default {};";
        annotation(
                project,
                "org.springframework.web.bind.annotation.RequestMapping",
                mapping + "RequestMethod[] method() default {};",
                "");
        for (String name :
                List.of("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"))
            annotation(project, "org.springframework.web.bind.annotation." + name, mapping, "");
        for (String name : List.of("RequestParam", "RequestBody", "PathVariable"))
            annotation(
                    project,
                    "org.springframework.web.bind.annotation." + name,
                    "String value() default \"\";",
                    "");
        for (String name : List.of("SofaService", "SofaReference"))
            annotation(
                    project,
                    "com.alipay.sofa.runtime.api.annotation." + name,
                    "Class<?> interfaceType() default void.class; String uniqueId() default \"\";",
                    "");
    }
}
