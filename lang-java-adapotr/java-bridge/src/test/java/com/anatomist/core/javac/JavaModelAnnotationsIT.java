package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import com.anatomist.framework.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaModelAnnotationsIT {
    private static final String COMPONENT = "org.springframework.stereotype.Component";

    @Test
    void binaryAnnotationDefaultsMetaPathsAndSofaApiMembersUseClassFiles(@TempDir Path directory)
            throws Exception {
        Path jar = directory.resolve("annotations.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            binary(output, COMPONENT, Map.of("value", ""), null);
            binary(output, "p.BinaryComposed", Map.of("value", ""), COMPONENT);
            binary(
                    output,
                    "com.alipay.sofa.runtime.api.annotation.SofaService",
                    Map.of("interfaceType", org.objectweb.asm.Type.getType("V"), "uniqueId", ""),
                    null);
        }
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file =
                    write(
                            project,
                            "p/Example.java",
                            """
                    package p;
                    @org.springframework.stereotype.Component("explicit") class Direct {}
                    @BinaryComposed class Composed {}
                    interface Worker {}
                    @com.alipay.sofa.runtime.api.annotation.SofaService class Provider implements Worker {}
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(jar),
                            List.of(project),
                            config.javacOptions());
            var framework = new JavaFrameworkContext(project, List.of(file), config, false);
            try (var binaries =
                    new com.anatomist.core.asmsolver.JavaBinaryRepository(
                            new com.anatomist.core.asmsolver.ClasspathClassFileSource(
                                    List.of(jar), release),
                            1024 * 1024)) {
                assertTrue(
                        backend.analyzeFiles(
                                        List.of(file),
                                        context -> {
                                            try (var model = new JavaModel(context)) {
                                                var analysis =
                                                        new JavaUnitAnalysis(
                                                                framework,
                                                                model,
                                                                binaries,
                                                                file,
                                                                context.identity(file));
                                                var support = new JavaModelAnnotations(analysis);
                                                assertEquals(
                                                        "explicit",
                                                        match(
                                                                        support,
                                                                        model,
                                                                        "p.Direct",
                                                                        Set.of(COMPONENT))
                                                                .string("value"));
                                                var meta =
                                                        match(
                                                                support,
                                                                model,
                                                                "p.Composed",
                                                                Set.of(COMPONENT));
                                                assertEquals(
                                                        List.of("p.BinaryComposed", COMPONENT),
                                                        meta.metaPath());
                                                assertEquals("unsupported", meta.attributeStatus());
                                                assertEquals(
                                                        Set.of("value"),
                                                        support.members(COMPONENT).orElseThrow());
                                                assertTrue(
                                                        support.members("absent.Annotation")
                                                                .isEmpty());
                                                var facts =
                                                        new com.anatomist.model.ExtractionResult();
                                                new com.anatomist.framework.sofa
                                                                .SofaJavaModelAnnotations()
                                                        .analyze(analysis, facts);
                                                assertEquals(1, facts.nodes.size());
                                                var metadata =
                                                        (Map<?, ?>)
                                                                com.anatomist.json.Json.parseTree(
                                                                        facts.nodes.getFirst()
                                                                                .metadata);
                                                assertEquals(
                                                        true, metadata.get("contractSupported"));
                                                assertEquals("p.Worker", metadata.get("contract"));
                                            }
                                        })
                                .complete());
            }
        }
    }

    private static void binary(
            java.util.jar.JarOutputStream output,
            String name,
            Map<String, Object> defaults,
            String meta)
            throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(
                org.objectweb.asm.Opcodes.V1_8,
                org.objectweb.asm.Opcodes.ACC_PUBLIC
                        | org.objectweb.asm.Opcodes.ACC_ABSTRACT
                        | org.objectweb.asm.Opcodes.ACC_INTERFACE
                        | org.objectweb.asm.Opcodes.ACC_ANNOTATION,
                name.replace('.', '/'),
                null,
                "java/lang/Object",
                new String[] {"java/lang/annotation/Annotation"});
        if (meta != null) {
            var annotation = writer.visitAnnotation("L" + meta.replace('.', '/') + ";", true);
            annotation.visit("value", "binary-meta");
            annotation.visitEnd();
        }
        for (var entry : defaults.entrySet()) {
            String descriptor =
                    entry.getValue() instanceof org.objectweb.asm.Type
                            ? "Ljava/lang/Class;"
                            : "Ljava/lang/String;";
            var method =
                    writer.visitMethod(
                            org.objectweb.asm.Opcodes.ACC_PUBLIC
                                    | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
                            entry.getKey(),
                            "()" + descriptor,
                            null,
                            null);
            var value = method.visitAnnotationDefault();
            value.visit(null, entry.getValue());
            value.visitEnd();
            method.visitEnd();
        }
        writer.visitEnd();
        output.putNextEntry(new java.util.jar.JarEntry(name.replace('.', '/') + ".class"));
        output.write(writer.toByteArray());
        output.closeEntry();
    }

    @Test
    void defaultsMetaPathsAliasForwardingAndCyclesUseDetachedDeclarations(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            write(
                    project,
                    "org/springframework/stereotype/Component.java",
                    """
                    package org.springframework.stereotype;
                    public @interface Component { String value() default ""; }
                    """);
            write(
                    project,
                    "org/springframework/core/annotation/AliasFor.java",
                    """
                    package org.springframework.core.annotation;
                    public @interface AliasFor {
                        Class<? extends java.lang.annotation.Annotation> annotation() default java.lang.annotation.Annotation.class;
                        String attribute() default ""; String value() default "";
                    }
                    """);
            Path file =
                    write(
                            project,
                            "p/Example.java",
                            """
                    package p;
                    import org.springframework.stereotype.Component;
                    import org.springframework.core.annotation.AliasFor;
                    @Component("meta") @interface Stereotype {
                        @AliasFor(annotation=Component.class, attribute="value") String name() default "default";
                    }
                    @Stereotype(name="outer-meta") @interface Outer {
                        @AliasFor(annotation=Stereotype.class, attribute="name") String value() default "outer-default";
                    }
                    @Second @interface First {} @First @interface Second {}
                    @Stereotype(name="explicit") class Service {}
                    @Stereotype class DefaultService {}
                    @Outer("unknown-override") class UnsupportedService {}
                    @Component class DirectService {}
                    @First class CyclicService {}
                    @missing.Custom(value="raw", number=2, flag=true, dynamic=System.getenv("x")) class MissingService {}
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            var projectContext = new JavaFrameworkContext(project, List.of(file), config, false);
            assertTrue(
                    backend.analyzeFiles(
                                    List.of(file),
                                    context -> {
                                        try (var model = new JavaModel(context)) {
                                            var support =
                                                    new JavaModelAnnotations(
                                                            new JavaUnitAnalysis(
                                                                    projectContext,
                                                                    model,
                                                                    null,
                                                                    file,
                                                                    context.identity(file)));
                                            var explicit =
                                                    match(
                                                            support,
                                                            model,
                                                            "p.Service",
                                                            Set.of(COMPONENT));
                                            assertEquals(
                                                    List.of("p.Stereotype", COMPONENT),
                                                    explicit.metaPath());
                                            assertEquals("static", explicit.attributeStatus());
                                            assertEquals("explicit", explicit.string("value"));
                                            assertEquals("exact", explicit.resolutionStatus());
                                            assertEquals(
                                                    "p.Stereotype",
                                                    explicit.evidence().get("annotationFqn"));
                                            assertEquals(
                                                    "default",
                                                    match(
                                                                    support,
                                                                    model,
                                                                    "p.DefaultService",
                                                                    Set.of(COMPONENT))
                                                            .string("value"));
                                            assertEquals(
                                                    "unsupported",
                                                    match(
                                                                    support,
                                                                    model,
                                                                    "p.UnsupportedService",
                                                                    Set.of(COMPONENT))
                                                            .attributeStatus());
                                            assertEquals(
                                                    "",
                                                    match(
                                                                    support,
                                                                    model,
                                                                    "p.DirectService",
                                                                    Set.of(COMPONENT))
                                                            .string("value"));
                                            var cyclic =
                                                    ((JavaModel.FoundType)
                                                                    model.type("p.CyclicService"))
                                                            .declaration();
                                            assertTrue(
                                                    support.first(cyclic, Set.of(COMPONENT))
                                                            .isEmpty());
                                            var raw =
                                                    match(
                                                            support,
                                                            model,
                                                            "p.MissingService",
                                                            Set.of("missing.Custom"));
                                            assertEquals("heuristic", raw.resolutionStatus());
                                            assertEquals("raw", raw.string("value"));
                                            assertInstanceOf(
                                                    JavaBinaryType.UnavailableValue.class,
                                                    raw.attributes().get("dynamic"));
                                            assertEquals(
                                                    new JavaBinaryType.Scalar(2),
                                                    raw.attributes().get("number"));
                                            assertEquals(
                                                    new JavaBinaryType.Scalar(true),
                                                    raw.attributes().get("flag"));
                                        }
                                    })
                            .complete());
        }
    }

    private static JavaModelAnnotations.Match match(
            JavaModelAnnotations support, JavaModel model, String owner, Set<String> roots) {
        return support.first(((JavaModel.FoundType) model.type(owner)).declaration(), roots)
                .orElseThrow();
    }

    private static Path write(Path project, String name, String source) throws Exception {
        Path file = project.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }
}
