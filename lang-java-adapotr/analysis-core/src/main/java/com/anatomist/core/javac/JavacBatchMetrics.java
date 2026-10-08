package com.anatomist.core.javac;

import java.util.LinkedHashMap;
import java.util.Map;

/** One completed worker exchange; JSON names are defined only at the output boundary. */
public record JavacBatchMetrics(
        long generation,
        long batchId,
        long heapUsed,
        long heapMaximum,
        long heapPoolPeakSum,
        long gcCount,
        long gcMillis,
        long resultBytes,
        long exchangeNanos,
        long transportBytes) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generation", generation);
        out.put("batch_id", batchId);
        out.put("heap_used", heapUsed);
        out.put("heap_maximum", heapMaximum);
        out.put("heap_pool_peak_sum", heapPoolPeakSum);
        out.put("gc_count", gcCount);
        out.put("gc_millis", gcMillis);
        out.put("result_bytes", resultBytes);
        out.put("exchange_nanos", exchangeNanos);
        out.put("transport_bytes", transportBytes);
        out.put("result_copy_bytes", 0L);
        return out;
    }
}
