package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.anatomist.core.java.JavaContract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class JavaContractFingerprintTest {
    @TempDir Path directory;

    @Test
    void ignoresCommentsBodiesAndInitializers() throws Exception {
        String before =
                """
                package p;
                class A {
                    // old comment
                    int value = 1;
                    A() { value = 2; }
                    int compute(String input) { return input.length(); }
                }
                """;
        String after =
                """
                package p;
                class A {
                    // new comment
                    int value = 99;
                    A() { throw new IllegalStateException(); }
                    int compute(String input) { int x = 3; return x; }
                }
                """;

        assertEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenTypeContractChanges() throws Exception {
        String baseline = "package p; class A { int compute(String input) { return 1; } }";

        assertNotEquals(
                fingerprint(baseline),
                fingerprint("package p; class A { long compute(String input) { return 1; } }"));
        assertNotEquals(
                fingerprint(baseline),
                fingerprint(
                        "package p; class A extends Base { int compute(String input) { return 1; } }"));
        assertNotEquals(
                fingerprint(baseline),
                fingerprint(
                        "package p; @Deprecated class A { int compute(String input) { return 1; } }"));
    }

    private String fingerprint(String source) throws Exception {
        var context = JavaFactsTestSupport.analyze(directory, "A.java", source);
        return JavaContract.fingerprint(context.units().getFirst());
    }
}
