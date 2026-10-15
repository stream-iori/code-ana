package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;
import com.anatomist.core.javac.*;
import com.anatomist.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;

class JavacEmptyPublicationTest {
    @Test
    void emptyPublicationDoesNotCompileCapturedPrerequisites(@TempDir Path root) throws Exception {
        Path dependency = Files.writeString(root.resolve("Dependency.java"), "class Dependency { int value; }");
        Path cache = root.resolve("cache");
        Path home = Path.of(System.getProperty("user.home"), ".sdkman/candidates/java/8.0.492-zulu");
        var options = new JavacOptions(home, true, true, false, false, cache, 1, 512);
        var roots = List.of(new SourceRoot(root, "dependency", SourceScope.MAIN));
        var backend = new JavacBackend(root, JavacToolchain.resolve(options, 8, null), List.of(), roots, options);
        backend.setCapturedInputs(
                List.of(new JavacBackend.CapturedContext("dependency", SourceScope.MAIN, List.of(dependency), Set.of(), Set.of())),
                new JavaSourcePlan(List.of(), List.of(dependency), roots, Map.of(dependency, new SourceIdentity("dependency", SourceScope.MAIN))),
                new JavacMavenInputs(List.of(new JavacMavenInputs.Module("dependency", root, "dependency", 8, List.of(root), List.of(), List.of(), List.of(), "")), Map.of(), "fixture", true));
        var inventory = backend.analyzeFiles(List.of(), context -> fail("empty publication must not emit dependency models"));
        assertTrue(inventory.complete());
        assertTrue(backend.executionEvidence().isEmpty(), "empty target must not launch javac");
        assertTrue(backend.batchEvidence().isEmpty());
        assertFalse(Files.exists(cache), "empty target must not populate signature/runtime caches");
    }
}
