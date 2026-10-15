package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacDeclarationViewsIT {
    @Test
    void dependencyBodiesAreOmittedWithoutLosingBindingsOrConstants(@TempDir Path root)
            throws Exception {
        Path dependency =
                Files.writeString(
                        root.resolve("Generic.java"),
                        "class Generic<T extends Number> { static final int N = 3; T echo(T value) { System.out.println(value); return value; } static class Nested {} }");
        Path use =
                Files.writeString(
                        root.resolve("Use.java"),
                        "class Use { Integer run(Generic<Integer> value) { return value.echo(1); } }");
        var targets = new ArrayList<List<String>>();
        for (boolean reduced : List.of(false, true)) {
            var backend = backend(root);
            backend.setDependencyDeclarations(reduced);
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(use), contexts::add).complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                var library = model.unit(dependency);
                assertEquals(reduced, library.declarations());
                assertEquals(
                        reduced,
                        model.syntax(dependency).nodes().stream()
                                .noneMatch(node -> node.kind().equals("METHOD_INVOCATION")));
                var owner = ((JavaModel.FoundType) model.type("Generic")).declaration();
                var constant =
                        model.members(owner).stream()
                                .filter(ref -> model.declaration(ref).symbol().name().equals("N"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(new JavaBinaryType.Scalar(3), model.declaration(constant).constant());
                var calls = new ArrayList<String>();
                for (var node : model.syntax(use).nodes()) {
                    if (!node.kind().equals("METHOD_INVOCATION")) continue;
                    var binding = model.unit(use).bindings().get(node.id());
                    assertTrue(binding.declarationResolved());
                    calls.add(
                            JavaTypeNames.callableId(model.unit(use).types(), binding.symbol())
                                    + ":"
                                    + JavaTypeNames.erased(
                                            model.unit(use).types(), binding.expressionType()));
                }
                targets.add(calls);
            }
        }
        assertEquals(
                List.of("Generic#echo(java.lang.Number):java.lang.Integer"), targets.getFirst());
        assertEquals(targets.getFirst(), targets.getLast());
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend(root).analyzeFiles(List.of(dependency), contexts::add).complete());
        try (var model = new JavaModel(contexts.getFirst())) {
            assertFalse(model.unit(dependency).declarations());
            assertTrue(
                    model.syntax(dependency).nodes().stream()
                            .anyMatch(node -> node.kind().equals("METHOD_INVOCATION")));
        }
    }

    private static JavacBackend backend(Path root) throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(17)).toString());
        return new JavacBackend(
                JavacToolchain.resolve(config.javacOptions(), 17, null),
                List.of(),
                List.of(root),
                config.javacOptions());
    }
}
