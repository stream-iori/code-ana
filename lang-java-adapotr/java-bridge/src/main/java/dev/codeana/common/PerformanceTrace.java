package dev.codeana.common;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, thread-owned diagnostic counters. Never part of facts or the native ABI. */
public final class PerformanceTrace implements AutoCloseable {
    public enum Metric {
        FACT_JSON, FACT_UTF8, OWNER_KEY, IDENTITY_KEY, ARCHIVE_APPEND,
        ARCHIVE_WRITE, ARCHIVE_FLUSH, ARCHIVE_FORCE, ARCHIVE_MOVE, ARCHIVE_SHA
    }
    private static final ThreadLocal<PerformanceTrace> ACTIVE = new ThreadLocal<>();
    private static final Scope NOOP = new Scope(null, 0, 0);
    private final long[][] values = new long[Metric.values().length][6];
    private final PerformanceTrace previous;
    private final Map<String, Object> runtimeBefore;
    private boolean closed;

    public PerformanceTrace() {
        previous = ACTIVE.get();
        ACTIVE.set(this);
        runtimeBefore = runtime();
    }

    public static Scope start(Metric metric, long bytes, boolean sampled) {
        PerformanceTrace trace = ACTIVE.get();
        if (trace == null) return NOOP;
        long[] value = trace.values[metric.ordinal()];
        value[5] = sampled ? 1024 : 1;
        long ordinal = value[0]++;
        value[1] += bytes;
        if (sampled && (ordinal & 1023) != 0) return NOOP;
        value[2]++;
        value[4] += bytes;
        return new Scope(value, System.nanoTime(), sampled ? 1024 : 1);
    }

    public static final class Scope implements AutoCloseable {
        private final long[] value;
        private final long started;
            private boolean closed;
        private Scope(long[] value, long started, int stride) {
            this.value = value; this.started = started;
        }
        public void close() {
            if (value != null && !closed) {
                value[3] += System.nanoTime() - started;
                closed = true;
            }
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (Metric metric : Metric.values()) {
            long[] v = values[metric.ordinal()];
            metrics.put(metric.name().toLowerCase(java.util.Locale.ROOT), Json.object(
                    "calls", v[0], "bytes", v[1], "samples", v[2],
                    "measured_ns", v[3], "sampled_bytes", v[4], "sampling_stride", v[5]));
        }
        return Json.object("schema_version", 1, "sample_stride", 1024,
                "scope", "analysis thread; sampled intervals are not exact phase totals",
                "metrics", metrics, "runtime_before", runtimeBefore, "runtime_after", runtime());
    }

    public static Map<String, Object> runtime() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var thread = ManagementFactory.getThreadMXBean();
            long cpu = thread.isCurrentThreadCpuTimeSupported() ? thread.getCurrentThreadCpuTime() : -1;
            result.put("thread_cpu_available", cpu >= 0);
            result.put("thread_cpu_ns", cpu >= 0 ? cpu : null);
        } catch (Throwable unsupported) {
            result.put("thread_cpu_available", false);
            result.put("thread_cpu_ns", null);
        }
        try {
            var thread = ManagementFactory.getThreadMXBean();
            long allocated = thread instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()
                    ? allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
            result.put("thread_allocated_available", allocated >= 0);
            result.put("thread_allocated_bytes", allocated >= 0 ? allocated : null);
        } catch (Throwable unsupported) {
            result.put("thread_allocated_available", false);
            result.put("thread_allocated_bytes", null);
        }
        try {
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            result.put("heap_used_bytes", heap.getUsed());
            result.put("heap_max_bytes", heap.getMax());
        } catch (Throwable unsupported) { result.put("heap_used_bytes", null); }
        try {
            var collectors = ManagementFactory.getGarbageCollectorMXBeans();
            var gc = new java.util.ArrayList<Object>();
            for (var c : collectors) gc.add(Json.object("name", c.getName(),
                    "count", c.getCollectionCount(), "time_ms", c.getCollectionTime()));
            result.put("gc_available", !gc.isEmpty() && collectors.stream().allMatch(c -> c.getCollectionTime() >= 0));
            result.put("gc", gc);
        } catch (Throwable unsupported) {
            result.put("gc_available", false);
            result.put("gc", java.util.List.of());
        }
        return result;
    }

    public void close() {
        if (!closed) { ACTIVE.set(previous); closed = true; }
    }
}
