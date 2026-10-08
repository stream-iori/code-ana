package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapturedProjectTest {
    @TempDir Path temporary;

    private Map<String, Object> request(String root) throws Exception {
        Path input = temporary.resolve("A.java");
        Files.writeString(input, "class A {}");
        Map<String, Object> source =
                Json.object(
                        "path",
                        "module/src/main/java/A.java",
                        "input",
                        input.toString(),
                        "hash",
                        Json.sha(input),
                        "module",
                        "module",
                        "world",
                        "main",
                        "scope",
                        "MAIN",
                        "publish",
                        true);
        return Json.object(
                "scratch",
                Files.createTempDirectory(temporary, "capture-").toString(),
                "contexts",
                List.of(
                        Json.object(
                                "context",
                                "module@MAIN",
                                "module",
                                "module",
                                "world",
                                "main",
                                "scope",
                                "MAIN",
                                "digest",
                                "test",
                                "sources",
                                List.of(source),
                                "resources",
                                List.of(),
                                "classpath",
                                List.of(),
                                "dependency_modules",
                                List.of(),
                                "source_roots",
                                List.of(root))));
    }

    @Test
    void actualRootsPreserveSealedFilePlan() throws Exception {
        var project = new CapturedProject(request("module/src/main/java"));
        assertEquals(1, project.roots().size());
        assertTrue(Files.isDirectory(project.roots().getFirst().path()));
        assertEquals(
                project.roots().getFirst().path(),
                project.moduleInputs().modules().getFirst().mainRoots().getFirst());
        assertEquals(1, project.sourcePlan(Set.of("main")).visibleFiles().size());
        Files.writeString(
                project.roots().getFirst().path().resolve("Hidden.java"), "class Hidden {}");
        assertEquals(1, project.sourcePlan(Set.of("main")).visibleFiles().size());
    }

    @Test
    void escapedAndIncorrectRootsAreRejected() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> new CapturedProject(request("../escape")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapturedProject(request("module/src/test/java")));
    }
}
