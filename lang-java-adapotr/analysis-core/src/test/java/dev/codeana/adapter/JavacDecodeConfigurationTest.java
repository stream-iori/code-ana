package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;
import com.anatomist.core.javac.*;
import com.anatomist.core.java.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;

class JavacDecodeConfigurationTest {
    private static JavacBackend backend(Path root) throws Exception {
        Path home = Path.of(System.getProperty("user.home"), ".sdkman/candidates/java/8.0.492-zulu");
        var options = new JavacOptions(home, true, true, false, false, root.resolve("cache"), 1, 512);
        return new JavacBackend(JavacToolchain.resolve(options, 8, null), List.of(), List.of(root), options);
    }

    @Test
    void explicitDecodeBudgetIsAppliedAndKeepsModels(@TempDir Path root) throws Exception {
        StringBuilder text = new StringBuilder("class Example {\n");
        for (int i = 0; i < 200; i++) text.append("int method" + i + "(int value) { return value + " + i + "; }\n");
        Path source = Files.writeString(root.resolve("Example.java"), text.append("}\n").toString());
        var small = backend(root);
        small.setDecodeBudgetBytes(64L << 10);
        var failure = assertThrows(IllegalStateException.class, () -> small.analyzeFiles(List.of(source), unit -> {}));
        assertTrue(failure.getMessage().contains("JAVAC_DECODE_BUDGET_EXCEEDED"));
        var normal = new ArrayList<JavaAnalysisContext>();
        var expanded = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend(root).analyzeFiles(List.of(source), normal::add).complete());
        var large = backend(root);
        large.setDecodeBudgetBytes(2L << 30);
        assertTrue(large.analyzeFiles(List.of(source), expanded::add).complete());
        assertEquals(normal.getFirst().units().getFirst().tree(), expanded.getFirst().units().getFirst().tree());
        assertEquals(normal.getFirst().units().getFirst().bindings(), expanded.getFirst().units().getFirst().bindings());
        assertEquals(normal.getFirst().units().getFirst().declarationInventory(), expanded.getFirst().units().getFirst().declarationInventory());
    }

    @Test
    void invalidDecodeBudgetsAreRejectedBeforeWork(@TempDir Path root) throws Exception {
        var backend = backend(root);
        for (long invalid : new long[] {0, 65535, (2L << 30) + 1})
            assertThrows(IllegalArgumentException.class, () -> backend.setDecodeBudgetBytes(invalid));
        assertFalse(Files.exists(root.resolve("cache")));
    }
}
