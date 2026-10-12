package dev.codeana.common;

import java.io.IOException;
import java.util.Map;

/** Bounded fact/archive output policy; independent of 64 MiB control JSON and 8 MiB records. */
public final class AnalysisLimits {
    public final long bytes;
    public final int facts;

    public AnalysisLimits(Map<String, Object> request) throws IOException {
        bytes =
                exact(
                        request,
                        "result_bytes",
                        Json.MAX_BYTES,
                        64L * 1024,
                        8L * 1024 * 1024 * 1024,
                        "JAVA_RESULT_BYTES_RANGE");
        facts = (int) exact(request, "fact_limit", 100000, 1, 5000000, "JAVA_FACT_LIMIT_RANGE");
    }

    private static long exact(
            Map<String, Object> request,
            String key,
            long defaultValue,
            long min,
            long max,
            String reason)
            throws IOException {
        long value = defaultValue;
        if (request.containsKey(key)) {
            try {
                value = new java.math.BigDecimal(request.get(key).toString()).longValueExact();
            } catch (RuntimeException invalid) {
                throw new IOException(reason, invalid);
            }
        }
        if (value < min || value > max) throw new IOException(reason);
        return value;
    }
}
