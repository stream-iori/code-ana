package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.java.JavaModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Regression cases adapted from main's JavaParser recursive-symbol fix for the javac backend. */
class JavacRecursiveSymbolResolutionIT {
    private static final Path FIXTURE =
            Path.of("fixtures/recursive-symbol-resolution/src/main/java/p");

    @Test
    void enumConstantBodiesWithNestedCallbacksRemainIndexable(@TempDir Path project)
            throws Exception {
        try (var model = analyze(project, "ShrinkRule")) {
            assertInstanceOf(JavaModel.FoundType.class, model.type("p.ShrinkRule"));
            assertInstanceOf(JavaModel.FoundType.class, model.type("p.ShrinkRule.Provider"));
        }
    }

    @Test
    void recursiveEnumBoundsWithMethodReferencesRemainIndexable(@TempDir Path project)
            throws Exception {
        try (var model = analyze(project, "EnumCache")) {
            var type = (JavaModel.FoundType) model.type("p.EnumCache");
            assertTrue(
                    model.members(type.declaration()).stream()
                            .anyMatch(
                                    ref -> model.declaration(ref).symbol().name().equals("name")));
        }
    }

    @Test
    void missingReturnTypeKeepsIndependentMethodCandidate(@TempDir Path project) throws Exception {
        try (var model = analyze(project, "PartialCandidates")) {
            var type = (JavaModel.FoundType) model.type("p.PartialCandidates");
            assertTrue(
                    model.members(type.declaration()).stream()
                            .anyMatch(
                                    ref ->
                                            model.declaration(ref)
                                                    .symbol()
                                                    .name()
                                                    .equals("available")));
        }
    }

    private static JavaModel analyze(Path project, String name) throws Exception {
        Path sourceRoot = Files.createDirectories(project.resolve("src/main/java/p"));
        Path source = sourceRoot.resolve(name + ".java");
        Files.copy(FIXTURE.resolve(name + ".java"), source);
        var config = new ProjectConfig();
        config.setJavaHome(
                Objects.requireNonNull(JavacBackendIT.home(8), "Java 8 test JDK").toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 8, null),
                        List.of(),
                        List.of(sourceRoot),
                        config.javacOptions());
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
        assertEquals(1, contexts.size());
        assertEquals(
                List.of(source),
                contexts.getFirst().units().stream().map(unit -> unit.path()).toList());
        return new JavaModel(contexts.getFirst());
    }
}
