package com.anatomist.json;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pin the wire format of {@link Json} byte-for-byte against Jackson's {@code DefaultPrettyPrinter}
 * (the format every golden file in {@code tests/scenarios/} was captured with).
 */
class JsonTest {

    // ── primitives ────────────────────────────────────────────────────

    @Test
    void compact_writesPrimitives() {
        assertEquals("null", Json.writeCompact(null));
        assertEquals("true", Json.writeCompact(true));
        assertEquals("false", Json.writeCompact(false));
        assertEquals("42", Json.writeCompact(42));
        assertEquals("42", Json.writeCompact(42L));
        assertEquals("3.14", Json.writeCompact(3.14));
        assertEquals("\"hello\"", Json.writeCompact("hello"));
    }

    @Test
    void compact_escapesStrings() {
        assertEquals("\"a\\\"b\"", Json.writeCompact("a\"b"));
        assertEquals("\"a\\\\b\"", Json.writeCompact("a\\b"));
        assertEquals("\"a\\nb\"", Json.writeCompact("a\nb"));
        assertEquals("\"a\\tb\"", Json.writeCompact("a\tb"));
        assertEquals("\"a\\rb\"", Json.writeCompact("a\rb"));
        // Control chars below 0x20 escape to backslash u four hex
        assertEquals("\"\\u0001\"", Json.writeCompact(String.valueOf((char) 1)));
        // Forward slash NOT escaped (Jackson default)
        assertEquals("\"/api/x\"", Json.writeCompact("/api/x"));
        // Non-ASCII passes through (Jackson default)
        assertEquals("\"中\"", Json.writeCompact("中"));
    }

    // ── pretty (Jackson DefaultPrettyPrinter parity) ──────────────────

    @Test
    void pretty_objectHasSpaceBeforeColon() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", "x");
        assertEquals("{\n  \"a\" : 1,\n  \"b\" : \"x\"\n}", Json.writePretty(m));
    }

    @Test
    void pretty_emptyObjectIsInline() {
        assertEquals("{ }", Json.writePretty(new LinkedHashMap<>()));
    }

    @Test
    void pretty_emptyArrayIsInline() {
        assertEquals("[ ]", Json.writePretty(new ArrayList<>()));
    }

    @Test
    void pretty_arrayOfPrimitivesOneLine() {
        // Jackson default: arrays of scalars use space-separated inline style
        assertEquals("[ 1, 2, 3 ]", Json.writePretty(Arrays.asList(1, 2, 3)));
    }

    @Test
    void pretty_nestedObjectsIndentTwoSpaces() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("x", 1);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("inner", inner);
        String expected = "{\n  \"inner\" : {\n    \"x\" : 1\n  }\n}";
        assertEquals(expected, Json.writePretty(outer));
    }

    @Test
    void pretty_arrayOfObjectsOpensBracketWithSpace() {
        // Matches DefaultPrettyPrinter: `[ {\n  ...\n}, {\n  ...\n} ]`
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("y", 2);
        List<Object> list = Arrays.asList(a, b);
        String expected = "[ {\n  \"x\" : 1\n}, {\n  \"y\" : 2\n} ]";
        assertEquals(expected, Json.writePretty(list));
    }

    @Test
    void pretty_arrayInsideObject_alignsCorrectly() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("items", Arrays.asList(a));
        String expected = "{\n  \"items\" : [ {\n    \"x\" : 1\n  } ]\n}";
        assertEquals(expected, Json.writePretty(outer));
    }

    // ── parseTree ─────────────────────────────────────────────────────

    @Test
    void parseTree_primitives() {
        assertNull(Json.parseTree("null"));
        assertEquals(Boolean.TRUE, Json.parseTree("true"));
        assertEquals(Boolean.FALSE, Json.parseTree("false"));
        assertEquals(42L, Json.parseTree("42"));
        assertEquals(3.14, Json.parseTree("3.14"));
        assertEquals("hello", Json.parseTree("\"hello\""));
    }

    @Test
    void parseTree_stringEscapes() {
        assertEquals("a\"b", Json.parseTree("\"a\\\"b\""));
        assertEquals("a\nb", Json.parseTree("\"a\\nb\""));
        assertEquals("a\\b", Json.parseTree("\"a\\\\b\""));
        assertEquals("中", Json.parseTree("\"\\u4e2d\""));
    }

    @Test
    void parseTree_emptyAndNested() {
        assertEquals(Map.of(), Json.parseTree("{}"));
        assertEquals(List.of(), Json.parseTree("[]"));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1L);
        expected.put("b", Arrays.asList("x", "y"));
        assertEquals(expected, Json.parseTree("{\"a\":1,\"b\":[\"x\",\"y\"]}"));
    }

    @Test
    void parseTree_preservesInsertionOrder() {
        Object tree = Json.parseTree("{\"z\":1,\"a\":2,\"m\":3}");
        assertInstanceOf(LinkedHashMap.class, tree);
        assertIterableEquals(
                Arrays.asList("z", "a", "m"),
                new ArrayList<>(((Map<String, Object>) tree).keySet()));
    }

    @Test
    void parseTree_handlesWhitespace() {
        String src = "{\n  \"a\" : 1,\n  \"b\" : [ 2, 3 ]\n}";
        Object tree = Json.parseTree(src);
        assertInstanceOf(Map.class, tree);
        Map<?, ?> m = (Map<?, ?>) tree;
        assertEquals(1L, m.get("a"));
        assertEquals(Arrays.asList(2L, 3L), m.get("b"));
    }

    // ── round-trip parity ────────────────────────────────────────────

    @Test
    void parseAndRewritePretty_isStable() {
        String original = "{\n  \"q\" : \"test\",\n  \"results\" : [ {\n    \"a\" : 1\n  } ]\n}";
        assertEquals(original, Json.writePretty(Json.parseTree(original)));
    }

    // ── canonical form (sorted keys; GoldenFileIT) ────────────────────

    @Test
    void writeCanonical_sortsMapKeysRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1);
        inner.put("a", 2);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("y", inner);
        outer.put("b", 1);
        String got = Json.writeCanonical(outer);
        assertEquals("{\n  \"b\" : 1,\n  \"y\" : {\n    \"a\" : 2,\n    \"z\" : 1\n  }\n}", got);
    }
    @Test
    void cyclesAndDepthFailButSharedObjectsRemainValid() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        for (java.util.function.Function<Object, String> writer : List.<java.util.function.Function<Object, String>>of(
                Json::writeCompact, Json::writePretty, Json::writeCanonical)) {
            assertThrows(IllegalArgumentException.class, () -> writer.apply(cycle));
            List<Object> shared = new ArrayList<>();
            shared.add("ok");
            assertEquals(List.of(List.of("ok"), List.of("ok")), Json.parseTree(writer.apply(List.of(shared, shared))));
            Object nested = "leaf";
            for (int depth = 0; depth < 256; depth++) nested = List.of(nested);
            Object boundary = nested;
            assertDoesNotThrow(() -> writer.apply(boundary));
            assertThrows(IllegalArgumentException.class, () -> writer.apply(List.of(boundary)));
        }
    }

    @Test
    void rejectsInvalidJsonAndNonFiniteNumbers() {
        for (String invalid : List.of("01", "[1,]", "{\"a\":1,}", "true false", "\"raw\nnewline\"", "1e999"))
            assertThrows(IllegalArgumentException.class, () -> Json.parseTree(invalid));
        assertThrows(IllegalArgumentException.class, () -> Json.writeCompact(Double.NaN));
        assertEquals("\"\\u001f\"", Json.writeCompact("\u001f"));
    }
    @Test
    void listAndIndirectCyclesAreRejectedInEveryWriter() {
        List<Object> list = new ArrayList<>(); list.add(list);
        Map<String, Object> map = new LinkedHashMap<>();
        List<Object> link = new ArrayList<>(); map.put("child", link); link.add(map);
        for (java.util.function.Function<Object, String> writer : List.<java.util.function.Function<Object, String>>of(
                Json::writeCompact, Json::writePretty, Json::writeCanonical)) {
            assertThrows(IllegalArgumentException.class, () -> writer.apply(list));
            assertThrows(IllegalArgumentException.class, () -> writer.apply(map));
        }
    }

    @Test
    void codecCyclesAndFreshExpansionAreBounded() {
        class Echo {}
        class Fresh {}
        JsonCodecRegistry.register(Echo.class, new JsonCodec<Echo>() {
            public Object toTree(Echo value) { return value; }
            public Echo fromTree(Object tree) { return new Echo(); }
        });
        JsonCodecRegistry.register(Fresh.class, new JsonCodec<Fresh>() {
            public Object toTree(Fresh value) { return new Fresh(); }
            public Fresh fromTree(Object tree) { return new Fresh(); }
        });
        for (java.util.function.Function<Object, String> writer : List.<java.util.function.Function<Object, String>>of(
                Json::writeCompact, Json::writePretty, Json::writeCanonical)) {
            assertThrows(IllegalArgumentException.class, () -> writer.apply(new Echo()));
            assertThrows(IllegalArgumentException.class, () -> writer.apply(new Fresh()));
        }
    }

    @Test
    void sharedCodecAndCanonicalBytesKeepLegacyBehavior() {
        class Box {}
        JsonCodecRegistry.register(Box.class, new JsonCodec<Box>() {
            public Object toTree(Box value) {
                var map = new LinkedHashMap<String, Object>(); map.put("z", 1); map.put("a", 2); return map;
            }
            public Box fromTree(Object tree) { return new Box(); }
        });
        Box shared = new Box();
        assertEquals("[{\"z\":1,\"a\":2},{\"z\":1,\"a\":2}]", Json.writeCompact(List.of(shared, shared)));
        assertEquals(Json.writePretty(shared), Json.writeCanonical(shared));
    }
    @Test void productionCodecRequiresAllocationAwareConversionBeforeCallingLegacyCode() {
        class Legacy {}
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        JsonCodecRegistry.register(Legacy.class,new JsonCodec<Legacy>() {
            public Object toTree(Legacy value){called.set(true);return java.util.Map.of();}
            public Legacy fromTree(Object value){return new Legacy();}
        });
        var budget=new com.anatomist.core.AnalysisContext(com.anatomist.core.AnalysisContext.Limits.defaults());
        assertThrows(com.anatomist.core.FatalAnalysisFailure.class,()->Json.writeCompact(new Legacy(),budget));
        assertFalse(called.get());
    }
    @Test void allocationAwareCodecUsesTheOriginalTaskLedger() {
        class Bounded {}
        JsonCodecRegistry.register(Bounded.class,new JsonCodec<Bounded>() {
            public Object toTree(Bounded value){throw new AssertionError("diagnostic codec called");}
            public Bounded fromTree(Object value){return new Bounded();}
            public Object toTree(Bounded value,com.anatomist.core.AnalysisContext budget){budget.charge(1,1,256);return java.util.Map.of("a",1);}
        });
        var budget=new com.anatomist.core.AnalysisContext(com.anatomist.core.AnalysisContext.Limits.defaults());
        assertEquals("{\"a\":1}",Json.writeCompact(new Bounded(),budget));
        assertTrue(((Number)budget.usage().get("payload_bytes")).longValue()>256);
    }
}
