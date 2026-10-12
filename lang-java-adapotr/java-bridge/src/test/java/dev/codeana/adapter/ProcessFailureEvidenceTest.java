package dev.codeana.adapter;

import com.anatomist.core.process.ProcessConversation;
import com.anatomist.core.process.ProcessRunner;
import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessFailureEvidenceTest {
    @Test
    void failedExchangeRetainsTheRealChildErrorAndExitCode() throws Exception {
        var spec = new ProcessRunner.Spec(
                List.of("/bin/sh", "-c", "printf 'java.lang.OutOfMemoryError: fixture\\n' >&2; exit 17"),
                null, Map.of(), Duration.ofSeconds(5), false, null);
        try (var conversation = new ProcessConversation(spec)) {
            IOException failure = assertThrows(IOException.class, () -> conversation.exchange((input, output) -> {
                assertEquals(-1, input.read());
                throw new EOFException("fixture exchange");
            }));
            var text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text));
            assertTrue(text.toString().contains("OutOfMemoryError: fixture"), text.toString());
            assertTrue(text.toString().contains("exit=17"), text.toString());
        }
    }
}
