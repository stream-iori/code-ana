package dev.codeana.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.process.ProcessConversation;
import com.anatomist.core.process.ProcessRunner;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeFailureEvidenceTest {
    @Test
    void childOomAndExitCodeSurviveTheNativeErrorEnvelope() throws Exception {
        var spec = new ProcessRunner.Spec(
                List.of("/bin/sh", "-c", "printf 'java.lang.OutOfMemoryError: fixture\\n' >&2; exit 17"),
                null, Map.of(), Duration.ofSeconds(5), false, null);
        try (var conversation = new ProcessConversation(spec)) {
            IOException failure = assertThrows(IOException.class, () -> conversation.exchange((input, output) -> {
                assertEquals(-1, input.read());
                throw new EOFException();
            }));
            String detail = NativeBridge.errorDetails(new IllegalStateException("JAVAC_SIGNATURE_FAILED: null", failure));
            assertTrue(detail.contains("OutOfMemoryError: fixture"), detail);
            assertTrue(detail.contains("exit=17"), detail);
            assertTrue(detail.contains("EOFException"), detail);
        }
    }

    @Test
    void oversizedPrimaryMessageDoesNotHideTheChildFailure() {
        var failure = new IllegalStateException("x".repeat(20000));
        failure.addSuppressed(new IOException("PROCESS_DIAGNOSTIC: exit=17; stderr=OutOfMemoryError"));
        String detail = NativeBridge.errorDetails(failure);
        assertTrue(detail.length() <= 8192);
        assertTrue(detail.contains("OutOfMemoryError"));
    }

    @Test
    void cyclicCausesAndLargeSuppressedGraphsStayBounded() {
        var first = new IOException("first");
        var second = new IOException("second");
        first.initCause(second); second.initCause(first);
        for (int i = 0; i < 100; i++) first.addSuppressed(new IOException("child" + i));
        String detail = NativeBridge.errorDetails(first);
        assertTrue(detail.length() <= 8192);
        assertTrue(detail.contains("first"));
        assertTrue(detail.contains("second"));
        assertFalse(detail.contains("child99"));
    }
}
