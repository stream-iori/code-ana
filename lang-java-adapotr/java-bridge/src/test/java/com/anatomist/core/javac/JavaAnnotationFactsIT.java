package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import com.anatomist.extract.JavaAnnotationFacts;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavaAnnotationFactsIT {
    @Test
    void syntaxAttributesPreserveJsonLiteralTypesAndWrittenExpressions(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            var project = Files.createDirectory(directory.resolve("literals-" + release));
            var facts =
                    com.anatomist.core.JavaFactsTestSupport.extract(
                            project,
                            "A.java",
                            """
                @interface Values {
                  boolean flag(); int number(); long large(); double fraction();
                  float rounded(); char letter(); String text(); int symbolic();
                }
                class Constants { static final int N = 3; }
                @Values(flag=true, number=0xff, large=4_000_000_000L, fraction=1.25,
                  rounded=1.23456789f, letter='x', text="hello", symbolic=Constants.N)
                class A {}
                """,
                            release);
            var value =
                    facts.annotations.stream()
                            .filter(annotation -> annotation.nodeId.equals("A"))
                            .findFirst()
                            .orElseThrow();
            var attributes = (Map<?, ?>) com.anatomist.json.Json.parseTree(value.attributes);
            assertEquals(true, attributes.get("flag"));
            assertEquals(255L, ((Number) attributes.get("number")).longValue());
            assertEquals(4_000_000_000L, ((Number) attributes.get("large")).longValue());
            assertEquals(1.25, ((Number) attributes.get("fraction")).doubleValue());
            assertEquals(1.23456789, ((Number) attributes.get("rounded")).doubleValue());
            assertEquals("x", attributes.get("letter"));
            assertEquals("hello", attributes.get("text"));
            assertEquals("Constants.N", attributes.get("symbolic"));
        }
    }

    @Test
    void sourceRetentionRepeatableValuesParameterTargetsAndMetaCyclesSurvive(
            @TempDir Path directory) throws Exception {
        String source =
                """
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE,ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER})
                @Repeatable(Tags.class)
                @interface Tag { String value(); int number() default 1; Class<?> type() default Object.class; }
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE,ElementType.FIELD,ElementType.METHOD,ElementType.PARAMETER})
                @interface Tags { Tag[] value(); }
                @MetaB @interface MetaA {}
                @MetaA @interface MetaB {}
                class Constants { static final int N = 9; }
                @Tag("a") @Tag("b") @Deprecated @MetaA
                class Sample {
                    @Tag(value="field", number=Constants.N, type=String.class) String field;
                    @Tag("method") void use(@Tag("parameter") String name) {}
                    @Absent("fallback") int unresolved;
                    @SuppressWarnings("unchecked") java.util.List raw;
                    @SuppressWarnings({"one", "two"}) int array;
                }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = Files.writeString(project.resolve("Sample.java"), source);
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            List<JavaAnalysisContext> contexts = new ArrayList<>();
            assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                var result = new ExtractionResult();
                new JavaAnnotationFacts(model, project, null).extract(file, result);
                for (var reference : model.declarations(file)) {
                    var declaration = model.declaration(reference);
                    if (declaration.symbol().name().equals("name"))
                        assertTrue(
                                model.syntax(reference).isPresent(),
                                "parameter syntax: " + declaration);
                }
                var uses =
                        result.annotations.stream()
                                .filter(annotation -> annotation.nodeId.startsWith("Sample"))
                                .toList();
                assertEquals(
                        10,
                        uses.size(),
                        "JDK "
                                + release
                                + ": "
                                + uses.stream()
                                        .map(
                                                annotation ->
                                                        annotation.nodeId
                                                                + " "
                                                                + annotation.rawName
                                                                + " "
                                                                + annotation.attributes)
                                        .toList());
                var repeated =
                        uses.stream()
                                .filter(
                                        annotation ->
                                                annotation.nodeId.equals("Sample")
                                                        && annotation.rawName.equals("Tag"))
                                .toList();
                assertEquals(
                        List.of("{\"value\":\"a\"}", "{\"value\":\"b\"}"),
                        repeated.stream().map(annotation -> annotation.attributes).toList());
                assertTrue(
                        repeated.stream()
                                .allMatch(
                                        annotation ->
                                                "Tag".equals(annotation.annotationFqn)
                                                        && "exact"
                                                                .equals(
                                                                        annotation
                                                                                .resolutionStatus)));
                var field =
                        uses.stream()
                                .filter(annotation -> annotation.nodeId.equals("Sample#field"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        "{\"value\":\"field\",\"number\":\"Constants.N\",\"type\":\"String.class\"}",
                        field.attributes);
                assertEquals(
                        "{\"value\":\"unchecked\"}",
                        uses.stream()
                                .filter(annotation -> annotation.nodeId.equals("Sample#raw"))
                                .findFirst()
                                .orElseThrow()
                                .attributes);
                assertEquals(
                        "{\"value\":[\"one\",\"two\"]}",
                        uses.stream()
                                .filter(annotation -> annotation.nodeId.equals("Sample#array"))
                                .findFirst()
                                .orElseThrow()
                                .attributes);
                var parameter =
                        uses.stream()
                                .filter(annotation -> "parameter[0]".equals(annotation.targetPath))
                                .findFirst()
                                .orElseThrow();
                assertEquals("Sample#use(java.lang.String)", parameter.nodeId);
                assertEquals(
                        "{\"_param\":0,\"_name\":\"name\",\"value\":\"parameter\"}",
                        parameter.attributes);
                var missing =
                        uses.stream()
                                .filter(annotation -> annotation.rawName.equals("Absent"))
                                .findFirst()
                                .orElseThrow();
                assertNull(missing.annotationFqn);
                assertEquals("unresolved", missing.resolutionStatus);
                assertEquals("{\"value\":\"fallback\"}", missing.attributes);
                assertTrue(
                        result.annotationMetaRelations.stream()
                                .anyMatch(
                                        relation ->
                                                relation.annotationFqn.equals("Tag")
                                                        && relation.metaAnnotationFqn.equals(
                                                                "java.lang.annotation.Repeatable")));
                assertTrue(
                        result.annotationMetaRelations.stream()
                                .anyMatch(
                                        relation ->
                                                relation.annotationFqn.equals("MetaA")
                                                        && relation.metaAnnotationFqn.equals(
                                                                "MetaB")));
                assertTrue(result.annotationMetaRelations.size() < 100);
                assertTrue(
                        uses.stream()
                                .allMatch(
                                        annotation ->
                                                annotation.beginLine > 0
                                                        && annotation.beginColumn > 0));
            }
            tested++;
        }
        assertTrue(tested > 0);
    }
}
