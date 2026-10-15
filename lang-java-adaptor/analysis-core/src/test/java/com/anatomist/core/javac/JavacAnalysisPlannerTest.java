package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.SourceScope;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

class JavacAnalysisPlannerTest {
    @Test
    void diamondWaitsForBothServices() {
        var planner =
                new JavacAnalysisPlanner(
                        List.of(
                                context("common"),
                                context("left", "common"),
                                context("right", "common"),
                                context("app", "left", "right")));
        planner.completed(planner.ready().getFirst());
        var services = planner.ready();
        assertEquals(List.of("left", "right"), names(services));
        planner.completed(services.getFirst());
        assertEquals(List.of("right"), names(planner.ready()));
        planner.completed(services.getLast());
        assertEquals(List.of("app"), names(planner.ready()));
    }

    @Test
    void batchesFollowReadinessRatherThanBlockedGlobalOrder() {
        var planner =
                new JavacAnalysisPlanner(List.of(context("a"), context("b", "a"), context("c")));
        assertEquals(List.of("a", "c"), names(planner.ready()));
        var first = planner.ready();
        first.forEach(planner::completed);
        assertEquals(List.of("b"), names(planner.ready()));
        planner.completed(planner.ready().getFirst());
        assertTrue(planner.finished());
        assertThrows(IllegalStateException.class, () -> planner.completed(first.getFirst()));
    }

    @Test
    void rejectsSelfAndMultiModuleCyclesAndDuplicatePublication() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavacAnalysisPlanner(List.of(context("a", "a"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavacAnalysisPlanner(List.of(context("a", "b"), context("b", "a"))));
        var shared = Path.of("shared.java");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JavacAnalysisPlanner(
                                List.of(
                                        new JavacContexts.Context(
                                                "a",
                                                SourceScope.MAIN,
                                                List.of(shared),
                                                Set.of(shared),
                                                Set.of()),
                                        new JavacContexts.Context(
                                                "b",
                                                SourceScope.MAIN,
                                                List.of(shared),
                                                Set.of(shared),
                                                Set.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JavacAnalysisPlanner(List.of(context("a"), context("a"))));
    }

    @Test
    void testsWaitForMainAndMissingEmptyModulesDoNotBlock() {
        var main = context("app", "empty");
        var test =
                new JavacContexts.Context("app", SourceScope.TEST, List.of(), Set.of(), Set.of());
        var planner = new JavacAnalysisPlanner(List.of(main, test));
        assertEquals(1, planner.ready().size());
        planner.completed(planner.ready().getFirst());
        assertEquals(SourceScope.TEST, planner.ready().getFirst().context().scope());
    }

    private static JavacContexts.Context context(String name, String... dependencies) {
        return new JavacContexts.Context(
                name, SourceScope.MAIN, List.of(), Set.of(), Set.of(dependencies));
    }

    private static List<String> names(List<JavacAnalysisPlanner.Task> tasks) {
        return tasks.stream().map(task -> task.context().module()).toList();
    }
}
