package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ParserCacheBudgetsTest {
    @Test
    void configuredBudgetsAndOverridesDoNotDependOnAParserInstance() {
        List<String> keys =
                List.of(
                        "anatomist.typeCache.sourceMaxEntries",
                        "anatomist.typeCache.combinedMaxEntries",
                        "anatomist.typeCache.asmMaxBytes");
        Map<String, String> before = new LinkedHashMap<>();
        keys.forEach(key -> before.put(key, System.getProperty(key)));
        try {
            keys.forEach(System::clearProperty);
            var configured = new ParserResourceLimits(17, 31, 1234);
            assertEquals(
                    new ParserCacheBudgets(17, 31, 1234), ParserCacheBudgets.resolve(configured));
            assertTrue(ParserCacheBudgets.resolve(null).sourceEntries() > 0);
            System.setProperty(keys.get(0), "4294967296");
            System.setProperty(keys.get(1), "invalid");
            System.setProperty(keys.get(2), "-1");
            assertEquals(
                    new ParserCacheBudgets(17, 31, 1234), ParserCacheBudgets.resolve(configured));
            System.setProperty(keys.get(0), "19");
            System.setProperty(keys.get(1), "37");
            System.setProperty(keys.get(2), "2048");
            assertEquals(
                    new ParserCacheBudgets(19, 37, 2048), ParserCacheBudgets.resolve(configured));
            assertEquals(32, ParserCacheBudgets.defaultSourceEntries(2L << 30));
            assertEquals(256, ParserCacheBudgets.defaultSourceEntries(3L << 30));
            assertEquals(2048, ParserCacheBudgets.defaultCombinedEntries(2L << 30));
            assertEquals(20000, ParserCacheBudgets.defaultCombinedEntries(3L << 30));
        } finally {
            before.forEach(
                    (key, value) -> {
                        if (value == null) System.clearProperty(key);
                        else System.setProperty(key, value);
                    });
        }
    }
}
