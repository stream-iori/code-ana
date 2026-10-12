package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.SourceRoot;
import com.anatomist.core.SourceScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacContextsTest {
    @Test
    void dependenciesPrecedeConsumersAndCyclesBelowTheRequestedModuleAreRejected(
            @TempDir Path project) throws Exception {
        Path a = source(project, "a", "main", "A.java");
        Path test = source(project, "a", "test", "ATest.java");
        Path b = source(project, "b", "main", "B.java");
        Path c = source(project, "c", "main", "C.java");
        String dependency =
                "<dependency><groupId>g</groupId><artifactId>%s</artifactId></dependency>";
        pom(project, "a", dependency.formatted("b"), "17");
        pom(project, "b", dependency.formatted("c"), "17");
        pom(project, "c", "", "17");
        List<SourceRoot> roots =
                List.of(
                        new SourceRoot(a.getParent(), "a", SourceScope.MAIN),
                        new SourceRoot(test.getParent(), "a", SourceScope.TEST),
                        new SourceRoot(b.getParent(), "b", SourceScope.MAIN),
                        new SourceRoot(c.getParent(), "c", SourceScope.MAIN));
        var contexts =
                JavacContexts.prepare(
                        project, roots, List.of(a, test, b, c), List.of(test, a, b, c));
        assertEquals(
                List.of("c@MAIN", "b@MAIN", "a@MAIN", "a@TEST"),
                contexts.stream().map(ctx -> ctx.module() + "@" + ctx.scope()).toList());
        pom(project, "c", dependency.formatted("b"), "17");
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        JavacContexts.prepare(
                                                project, roots, List.of(a, b, c), List.of(a)))
                        .getMessage()
                        .contains("JAVAC_MODULE_CYCLE"));
    }

    @Test
    void mainSeesDependenciesAndTestDoesNotLeakIntoMain(@TempDir Path project) throws Exception {
        Path main = source(project, "a", "main", "Main.java");
        Path test = source(project, "a", "test", "Test.java");
        Path dependency = source(project, "b", "main", "Library.java");
        Path unrelated = source(project, "c", "main", "Other.java");
        pom(
                project,
                "a",
                "<dependency><groupId>g</groupId><artifactId>b</artifactId></dependency>",
                "17");
        pom(project, "b", "", "17");
        pom(project, "c", "", "17");
        List<SourceRoot> roots =
                List.of(
                        new SourceRoot(main.getParent(), "a", SourceScope.MAIN),
                        new SourceRoot(test.getParent(), "a", SourceScope.TEST),
                        new SourceRoot(dependency.getParent(), "b", SourceScope.MAIN),
                        new SourceRoot(unrelated.getParent(), "c", SourceScope.MAIN));
        var contexts =
                JavacContexts.prepare(
                        project,
                        roots,
                        List.of(main, test, dependency, unrelated),
                        List.of(main, test));
        var production =
                contexts.stream()
                        .filter(c -> c.scope() == SourceScope.MAIN)
                        .findFirst()
                        .orElseThrow();
        assertEquals(Set.of(main, dependency), Set.copyOf(production.sources()));
        var testing =
                contexts.stream()
                        .filter(c -> c.scope() == SourceScope.TEST)
                        .findFirst()
                        .orElseThrow();
        assertEquals(Set.of(main, test, dependency), Set.copyOf(testing.sources()));
        assertEquals(Set.of(test), testing.publish());
    }

    @Test
    void refusesMixedModuleReleases(@TempDir Path project) throws Exception {
        Path a = source(project, "a", "main", "A.java"), b = source(project, "b", "main", "B.java");
        pom(project, "a", "", "8");
        pom(project, "b", "", "17");
        var roots =
                List.of(
                        new SourceRoot(a.getParent(), "a", SourceScope.MAIN),
                        new SourceRoot(b.getParent(), "b", SourceScope.MAIN));
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        JavacContexts.prepare(
                                                project, roots, List.of(a, b), List.of(a, b)))
                        .getMessage()
                        .contains("JAVAC_MIXED_RELEASE_UNSUPPORTED"));
    }

    private static Path source(Path project, String module, String scope, String name)
            throws Exception {
        Path file =
                Files.createDirectories(project.resolve(module + "/src/" + scope + "/java"))
                        .resolve(name);
        Files.writeString(file, "class " + name.replace(".java", "") + " {}");
        return file;
    }

    private static void pom(Path project, String module, String dependencies, String release)
            throws Exception {
        Files.writeString(
                project.resolve(module + "/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"
                        + module
                        + "</artifactId><properties><maven.compiler.release>"
                        + release
                        + "</maven.compiler.release></properties><dependencies>"
                        + dependencies
                        + "</dependencies></project>");
    }
}
