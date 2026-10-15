package dev.codeana.common;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerformanceTraceTest {
    @TempDir Path temporary;
    @Test void nestedScopesRestoreAndSamplesKeepExactCountsOnFailure() {
        try (var outer = new PerformanceTrace()) {
            for (int i = 0; i < 1025; i++)
                try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.FACT_JSON, 7, true)) {}
            try (var inner = new PerformanceTrace()) {
                try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.OWNER_KEY, 3, false)) {
                    assertThrows(IllegalStateException.class, () -> { throw new IllegalStateException("fixture"); });
                }
                assertEquals(1L, Json.map(Json.map(inner.snapshot().get("metrics")).get("owner_key")).get("calls"));
            }
            var metric = Json.map(Json.map(outer.snapshot().get("metrics")).get("fact_json"));
            assertEquals(1025L, metric.get("calls"));
            assertEquals(7175L, metric.get("bytes"));
            assertEquals(2L, metric.get("samples"));
            assertEquals(0L, Json.map(Json.map(outer.snapshot().get("metrics")).get("owner_key")).get("calls"));
        }
    }
    @Test void requestIdentityDoesNotDependOnObservation() throws Exception {
        var value=Json.object("name","中文😀","value",7);
        byte[] before=Json.encode(value);String identity=Json.key("symbol","中文😀","source");
        try(var trace=new PerformanceTrace()) {
            assertArrayEquals(before,Json.encode(value));
            assertEquals(identity,Json.key("symbol","中文😀","source"));
        }
    }
}
