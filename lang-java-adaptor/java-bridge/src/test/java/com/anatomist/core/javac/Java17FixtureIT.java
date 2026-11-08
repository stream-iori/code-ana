package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;
import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Java17FixtureIT {
    private JavacBackend backend(Path root) throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(JavacBackendIT.home(17).toString());
        config.setJavaVersion(17);
        return new JavacBackend(JavacToolchain.resolve(config.javacOptions(), 17, null),
                List.of(), List.of(root), config.javacOptions());
    }

    @Test
    void modernSyntaxAndJdkMembersHaveCompilerSelectedTargets(@TempDir Path root) throws Exception {
        Path file = Files.copy(Path.of("../tests/fixtures/java-native/java17/Modern.java"), root.resolve("Modern.java"));
        var contexts = new ArrayList<JavaAnalysisContext>();
        var compiler = backend(root);
        assertTrue(compiler.analyzeFiles(List.of(file), contexts::add).complete());
        var context = contexts.getFirst();
        assertTrue(context.diagnostics().stream().noneMatch(d -> d.kind().equals("ERROR")), context.diagnostics().toString());
        try (var model = new JavaModel(context)) {
            var unit = model.unit(file);
            var targets = new HashSet<String>();
            for (var node : model.syntax(file).nodes()) {
                if (!Set.of("METHOD_INVOCATION", "MEMBER_REFERENCE", "NEW_CLASS").contains(node.kind())) continue;
                var binding = unit.bindings().get(node.id());
                if (binding != null && binding.declarationResolved() && binding.symbol() != null)
                    targets.add(JavaTypeNames.callableId(unit.types(), binding.symbol()));
            }
            for (String expected : List.of("java.util.List#of", "java.util.List#copyOf", "java.lang.String#isBlank",
                    "java.lang.String#formatted", "java.util.stream.Stream#toList", "fixture.modern.Modern.Point#x",
                    "fixture.modern.Modern.Box#Box", "fixture.modern.Modern.Point#Point(int)", "fixture.modern.Modern.Named#name()"))
                assertTrue(targets.stream().anyMatch(t -> t.startsWith(expected)), expected + ": " + targets);
            var result = new com.anatomist.model.ExtractionResult();
            try (var binaries = compiler.openBinaryRepository(context)) {
                assertTrue(new com.anatomist.extract.JavaCallFacts(model, root, ".", "MAIN", binaries).extract(file, result).isEmpty());
            }
            for (String expected : List.of("fixture.modern.Modern.Named#name()", "fixture.modern.Modern.Point#x()",
                    "fixture.modern.Modern.Point#Point(int)", "fixture.modern.Modern.Box#Box(java.lang.String)"))
                assertTrue(result.edges.stream().anyMatch(edge -> edge.relation.equals("CALLS") && edge.sourceId.contains("$methodref") && expected.equals(edge.targetId)), expected);
            for (var comment : model.comments(file)) {
                var links = model.commentLinks(file, comment);
                assertTrue(links.stream().allMatch(l -> l.basis() == JavaCommentRelations.Basis.LANGUAGE_RULE));
                if (comment.body().contains("方法内")) {
                    var link = links.stream().filter(l -> l.relation() == JavaCommentRelations.Relation.ENCLOSING_CALLABLE).findFirst().orElseThrow();
                    assertNotNull(link.declaration());
                    assertEquals("fixture.modern.Modern#run(java.lang.Object)", new JavaIdentities(model).declaration(link.declaration()));
                }
                if (comment.body().contains("lambda隔离"))
                    assertTrue(links.stream().allMatch(l -> l.declaration() == null));
            }
        }
    }

    @Test
    void outOfScopePatternVariableDoesNotReceiveAMethodBinding(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("Invalid.java"), "class Invalid { interface A { String apply(String s); } interface B { String apply(String s); } static void pick(A a) {} static void pick(B b) {} int run(Object value) { if (value instanceof String s) {} return s.length(); } boolean invalidOr(Object value) { return value instanceof String s || s.isBlank(); } void ambiguous() { pick(String::trim); } }");
        var contexts = new ArrayList<JavaAnalysisContext>();
        backend(root).analyzeFiles(List.of(file), contexts::add);
        var context = contexts.getFirst();
        assertTrue(context.diagnostics().stream().anyMatch(d -> d.kind().equals("ERROR")));
        try (var model = new JavaModel(context)) {
            for (var node : model.syntax(file).nodes()) {
                if (!node.kind().equals("METHOD_INVOCATION")) continue;
                String text = model.source(file).slice(node.span());
                if (!text.contains("s.length") && !text.contains("s.isBlank") && !text.contains("pick(String::trim)")) continue;
                var binding = model.unit(file).bindings().get(node.id());
                assertTrue(binding == null || !binding.declarationResolved());
            }
        }
    }
}
