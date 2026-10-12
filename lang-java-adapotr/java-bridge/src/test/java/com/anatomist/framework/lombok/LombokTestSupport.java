package com.anatomist.framework.lombok;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.asmsolver.JavaBinarySources;
import com.anatomist.core.java.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real compiler declarations with the detached, signature-only Lombok overlay. */
final class LombokTestSupport {
    private LombokTestSupport() {}

    static Analysis analyze(Path directory, String source, boolean strict) throws Exception {
        Path lombok =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        assertTrue(Files.isRegularFile(lombok));
        Path slf4j =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/slf4j/slf4j-api/2.0.3/slf4j-api-2.0.3.jar");
        assertTrue(Files.isRegularFile(slf4j));
        var classpath = List.of(lombok, slf4j);
        var context = JavaFactsTestSupport.analyze(directory, "Models.java", source, classpath);
        var extension = new LombokJavaModelExtension(directory, strict);
        try (var binaries =
                JavaBinarySources.open(
                        classpath,
                        Path.of(System.getProperty("java.home")),
                        25,
                        16L * 1024 * 1024)) {
            return new Analysis(extension.augment(context, binaries), extension);
        }
    }

    record Analysis(JavaAnalysisContext context, LombokJavaModelExtension extension) {
        JavaUnit unit() {
            return context.units().getFirst();
        }

        JavaDeclaration type(String name) {
            try (var model = new JavaModel(context)) {
                return model.declarations(unit().path()).stream()
                        .map(model::declaration)
                        .filter(
                                value ->
                                        value.symbol().name().equals(name)
                                                && java.util.Set.of(
                                                                "CLASS",
                                                                "INTERFACE",
                                                                "ANNOTATION_TYPE")
                                                        .contains(value.symbol().kind()))
                        .findFirst()
                        .orElseThrow();
            }
        }

        List<JavaDeclaration> members(JavaDeclaration owner) {
            try (var model = new JavaModel(context)) {
                var reference =
                        model.declarations(unit().path()).stream()
                                .filter(value -> model.declaration(value) == owner)
                                .findFirst()
                                .orElseThrow();
                return model.members(reference).stream().map(model::declaration).toList();
            }
        }

        List<JavaDeclaration> methods(JavaDeclaration owner, String name) {
            return members(owner).stream()
                    .filter(
                            value ->
                                    value.symbol().kind().equals("METHOD")
                                            && value.symbol().name().equals(name))
                    .toList();
        }

        List<JavaDeclaration> constructors(JavaDeclaration owner) {
            return members(owner).stream()
                    .filter(
                            value ->
                                    value.symbol().kind().equals("CONSTRUCTOR")
                                            && value.origin()
                                                    != JavaDeclaration.Origin.COMPILER_SYNTHETIC)
                    .toList();
        }

        java.util.Optional<JavaDeclaration> field(JavaDeclaration owner, String name) {
            return members(owner).stream()
                    .filter(
                            value ->
                                    value.symbol().kind().equals("FIELD")
                                            && value.symbol().name().equals(name))
                    .findFirst();
        }

        JavaGeneratedOrigin origin(JavaDeclaration declaration) {
            String id = declaration.canonicalName();
            if (id.isEmpty()) {
                try (var model = new JavaModel(context)) {
                    var ref =
                            model.declarations(unit().path()).stream()
                                    .filter(value -> model.declaration(value) == declaration)
                                    .findFirst()
                                    .orElseThrow();
                    id = new JavaIdentities(model).declaration(ref);
                }
            }
            return unit().extensions().generated().get(id);
        }

        Map<String, Object> metadata(JavaDeclaration declaration) {
            String id = declaration.canonicalName();
            if (id.isEmpty()) id = declaration.symbol().owner() + "#" + declaration.symbol().name();
            return unit().extensions().declarationMetadata().getOrDefault(id, Map.of());
        }

        Map<String, Object> lombok(JavaDeclaration declaration) {
            Object value = metadata(declaration).get("lombok");
            if (!(value instanceof Map<?, ?> map))
                throw new IllegalStateException("missing Lombok metadata");
            var result = new java.util.LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, item));
            return java.util.Collections.unmodifiableMap(result);
        }

        List<com.anatomist.core.IndexDiagnostic> diagnostics() {
            return unit().extensions().diagnostics();
        }

        JavaType executable(JavaDeclaration method) {
            return unit().types().get(method.symbol().declaredType());
        }

        JavaType returnType(JavaDeclaration method) {
            return unit().types().get(executable(method).returns());
        }

        List<String> arguments(JavaType type) {
            return type.arguments().stream().map(ref -> unit().types().get(ref).display()).toList();
        }

        String returns(JavaDeclaration method) {
            return unit().types().get(executable(method).returns()).display();
        }

        String parameter(JavaDeclaration method, int index) {
            return unit().types().get(executable(method).parameters().get(index)).display();
        }

        JavaAnalysisContext repeat() {
            return extension.augment(context, null);
        }
    }
}
