package com.anatomist.core;

/** Parser-independent cache budgets, including the existing command-line property overrides. */
public record ParserCacheBudgets(int sourceEntries, long combinedEntries, long asmBytes) {
    private static final long LARGE_HEAP = 3L * 1024 * 1024 * 1024;

    public static ParserCacheBudgets resolve(ParserResourceLimits configured) {
        ParserResourceLimits limits =
                configured == null ? ParserResourceLimits.DEFAULT : configured;
        long heap = Runtime.getRuntime().maxMemory();
        int source =
                limits.sourceEntries() == 0 ? defaultSourceEntries(heap) : limits.sourceEntries();
        long sourceOverride = positiveProperty("anatomist.typeCache.sourceMaxEntries", source);
        return new ParserCacheBudgets(
                sourceOverride > Integer.MAX_VALUE ? source : (int) sourceOverride,
                positiveProperty(
                        "anatomist.typeCache.combinedMaxEntries",
                        limits.combinedEntries() == 0
                                ? defaultCombinedEntries(heap)
                                : limits.combinedEntries()),
                positiveProperty("anatomist.typeCache.asmMaxBytes", limits.asmMaxBytes()));
    }

    static int defaultSourceEntries(long heap) {
        return heap < LARGE_HEAP ? 32 : 256;
    }

    static long defaultCombinedEntries(long heap) {
        return heap < LARGE_HEAP ? 2048 : 20000;
    }

    private static long positiveProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
