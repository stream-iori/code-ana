package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;
import com.anatomist.core.AnalysisContext;
import com.anatomist.core.HardResourceLimit;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavacCommentBudgetTest {
    private long possible(Path directory, String source) throws Exception {
        Path file = Files.writeString(Files.createTempFile(directory, "budget", ".java"), source);
        return JavacProtocol.commentPotential(file, Files.size(file), new byte[3], null);
    }

    @Test
    void ordinaryCodeAndSmallHeadersDoNotReserveWholeFileBodyMaps(@TempDir Path root) throws Exception {
        String code = "class A { int value=1/2; }\n".repeat(1000);
        assertEquals(0, possible(root, code));
        long header = possible(root, "/* header */\n" + code);
        assertTrue(header >= "/* header */".length());
        assertTrue(header < 32);
    }

    @Test
    void independentCandidatesCoverRealBlocksAfterFalseLineMarkersAndOverlappingOpeners(@TempDir Path root) throws Exception {
        String source = "String value=\"//\"; /* actual\n multiline\n body */";
        assertTrue(possible(root, source) >= source.length() - source.indexOf("/* actual"));
        assertEquals("/*/ still a block */".length(), possible(root, "/*/ still a block */"));
        assertTrue(possible(root, "// line\r\n/* block */") >= "// line/* block */".length());
        source = "class A { String s=\"" + "\\" + "u002f\"; }";
        assertEquals(source.length(), possible(root, source), "Unicode escapes require the conservative full-source bound");
    }

    @Test
    void sourceScanningUsesTheExistingStickyAccount(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("A.java"), "// inventory");
        var limits = AnalysisContext.Limits.defaults();
        var context = new AnalysisContext(new AnalysisContext.Limits(1, limits.maxNodes(), limits.maxPayloadBytes(), limits.maxDepth()));
        assertThrows(HardResourceLimit.class, () -> JavacProtocol.commentPotential(file, Files.size(file), new byte[2], context));
        assertThrows(HardResourceLimit.class, () -> context.charge(0, 0, 0));
    }
}
