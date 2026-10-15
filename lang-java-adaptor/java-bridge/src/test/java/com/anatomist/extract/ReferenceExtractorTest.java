package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class ReferenceExtractorTest {

    @Test
    void missingThrownTypeStaysAnExplicitNavigationGap(@TempDir Path directory) throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory, "A.java", "class A { void run() throws MissingFailure {} }");
        try (var model = new com.anatomist.core.java.JavaModel(context)) {
            var facts = new ExtractionResult();
            var gaps =
                    new JavaTypeFacts(model, directory).extract(directory.resolve("A.java"), facts);
            assertTrue(
                    gaps.stream()
                            .anyMatch(
                                    gap ->
                                            gap.owner().equals("A#run()")
                                                    && gap.target().equals("MissingFailure")
                                                    && gap.relation().equals("REFERENCES")));
            assertFalse(
                    facts.edges.stream()
                            .anyMatch(edge -> "MissingFailure".equals(edge.externalTargetFqn)));
        }
    }

    @Test
    void declarationThrowsBoundsAndWildcardReferencesKeepSourceOccurrences(@TempDir Path directory)
            throws Exception {
        var config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of());
        var facts =
                JavaFactsTestSupport.extract(
                        directory,
                        "A.java",
                        """
                import java.util.List;
                class Bound {}
                class Failure extends Exception {}
                class A<T extends Bound> {
                  List<? extends Bound> input;
                  List<? super Bound> output;
                  <U extends Bound> A() throws Failure {}
                  Bound run(Bound first, Bound second) throws Failure { return first; }
                }
                """,
                        config);
        var constructor =
                facts.edges.stream()
                        .filter(
                                edge ->
                                        edge.relation.equals("REFERENCES")
                                                && edge.sourceId.equals("A#A()"))
                        .toList();
        assertTrue(
                constructor.stream()
                        .anyMatch(edge -> "Bound".equals(edge.targetId) && edge.beginLine == 7));
        assertTrue(
                constructor.stream()
                        .anyMatch(edge -> "Failure".equals(edge.targetId) && edge.beginLine == 7));
        assertTrue(
                facts.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("REFERENCES")
                                                && edge.sourceId.equals("A")
                                                && "Bound".equals(edge.targetId)
                                                && edge.beginLine == 4));
        for (String field : List.of("A#input", "A#output"))
            assertTrue(
                    facts.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("REFERENCES")
                                                    && edge.sourceId.equals(field)
                                                    && "Bound".equals(edge.targetId)
                                                    && edge.beginLine != null));
        var occurrences =
                facts.edges.stream()
                        .filter(
                                edge ->
                                        edge.relation.equals("REFERENCES")
                                                && edge.sourceId.equals("A#run(Bound,Bound)")
                                                && "Bound".equals(edge.targetId))
                        .toList();
        assertEquals(3, occurrences.size());
        assertTrue(occurrences.stream().allMatch(edge -> edge.beginLine == 8));
        assertEquals(3, occurrences.stream().map(edge -> edge.beginColumn).distinct().count());
        assertTrue(
                facts.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("REFERENCES")
                                                && edge.sourceId.equals("A#run(Bound,Bound)")
                                                && "Failure".equals(edge.targetId)
                                                && edge.beginLine == 8));
    }

    @Test
    void externalExclusionsKeepInternalTargetsAndCalls(@TempDir Path directory) throws Exception {
        var config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.**", "pkg.**"));
        var result =
                JavaFactsTestSupport.extract(
                        directory,
                        "A.java",
                        """
                package pkg;
                class A {
                  static class Order {}
                  java.util.List<Order> orders;
                  void run() { System.out.println(orders); }
                }
                """,
                        config);
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "REFERENCES".equals(edge.relation)
                                                && "pkg.A.Order".equals(edge.targetId)));
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "READS".equals(edge.relation)
                                                && "pkg.A#orders".equals(edge.targetId)));
        assertFalse(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.isExternal
                                                && Set.of("REFERENCES", "READS", "WRITES")
                                                        .contains(edge.relation)));
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "CALLS".equals(edge.relation)
                                                && "java.io.PrintStream#println(java.lang.Object)"
                                                        .equals(edge.externalTargetFqn)));
    }

    @Test
    void emitsProjectInternalFieldType(@TempDir Path directory) throws Exception {
        // Nested declarations remain internal reference targets.
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  static class Order {}\n"
                        + "  Order o;\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        boolean fieldRef =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "REFERENCES".equals(e.relation)
                                                && "field_type".equals(e.context)
                                                && !e.isExternal
                                                && "pkg.A#o".equals(e.sourceId)
                                                && "pkg.A.Order".equals(e.targetId));
        assertTrue(fieldRef, "field_type REFERENCES missing; got " + r.edges);
    }

    @Test
    void emitsParameterAndReturnTypeReferences(@TempDir Path directory) throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  static class Req {}\n"
                        + "  static class Res {}\n"
                        + "  Res run(Req r) { return null; }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Set<String> seen =
                r.edges.stream()
                        .filter(e -> "REFERENCES".equals(e.relation))
                        .map(e -> e.context + "→" + e.targetId)
                        .collect(Collectors.toSet());
        assertTrue(seen.contains("parameter_type→pkg.A.Req"), "got " + seen);
        assertTrue(seen.contains("return_type→pkg.A.Res"), "got " + seen);
    }

    @Test
    void recursesIntoGenericArgs(@TempDir Path directory) throws Exception {
        String source =
                "package pkg;\n"
                        + "import java.util.List;\n"
                        + "public class A {\n"
                        + "  static class Order {}\n"
                        + "  List<Order> orders;\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        boolean genericArg =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "REFERENCES".equals(e.relation)
                                                && "generic_arg".equals(e.context)
                                                && "pkg.A.Order".equals(e.targetId));
        assertTrue(genericArg, "expected generic_arg REFERENCES; got " + r.edges);
    }

    @Test
    void visit_lambdaParameterType_emitsReferencesEdge(@TempDir Path directory) throws Exception {
        String source =
                "package pkg; import java.util.function.Function;"
                        + "public class A {"
                        + "  static class Req {}"
                        + "  static class Res {}"
                        + "  Function<Req, Res> f = (Req r) -> new Res();"
                        + "}";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        boolean lambdaParamRef =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "REFERENCES".equals(e.relation)
                                                && "parameter_type".equals(e.context)
                                                && e.sourceId != null
                                                && e.sourceId.contains("$lambda@L")
                                                && "pkg.A.Req".equals(e.targetId));
        assertTrue(
                lambdaParamRef,
                "lambda parameter_type REFERENCES (source=LAMBDA id, target=Req) missing; got "
                        + r.edges);
    }

    @Test
    void emitsExternalEdgeForJdkType(@TempDir Path directory) throws Exception {
        // External exclusions filter the target and still traverse generic arguments.
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.lang.*"));

        String source =
                "package pkg;\n"
                        + "import java.util.List;\n"
                        + "public class A {\n"
                        + "  List<String> items;\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source, config);

        boolean externalRef =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "REFERENCES".equals(e.relation)
                                                && e.isExternal
                                                && "java.util.List".equals(e.externalTargetFqn));
        assertTrue(externalRef, "external REFERENCES for java.util.List expected; got " + r.edges);

        // java.lang.String should be excluded
        boolean stringRef =
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        e.isExternal
                                                && "java.lang.String".equals(e.externalTargetFqn));
        assertFalse(stringRef, "java.lang.String should be excluded by config; got " + r.edges);
    }

    @Test
    void externalExcludeFiltersSuppressedPatterns(@TempDir Path directory) throws Exception {
        ProjectConfig config = new ProjectConfig();
        config.setExternalExcludePatterns(List.of("java.**"));

        String source =
                "package pkg;\n"
                        + "import java.util.Map;\n"
                        + "public class A {\n"
                        + "  Map<String, Object> data;\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source, config);

        boolean anyExternal =
                r.edges.stream().anyMatch(e -> e.isExternal && "REFERENCES".equals(e.relation));
        assertFalse(anyExternal, "all java.** should be excluded; got " + r.edges);
    }
}
