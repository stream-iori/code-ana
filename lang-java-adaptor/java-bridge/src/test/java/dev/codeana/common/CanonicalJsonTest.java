package dev.codeana.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void canonicalOrderingHandlesDifferentMapImplementationsAndRejectsNonStringKeys() {
        Map<String, Object> expected = new TreeMap<>();
        Map<String, Object> reversed = new LinkedHashMap<>();
        for (String key : List.of("中", "a", "😀", "aa", "A")) expected.put(key, List.of(key, Json.object("z", 1, "a", true)));
        var keys = new ArrayList<>(expected.keySet());
        Collections.reverse(keys);
        for (String key : keys) reversed.put(key, expected.get(key));
        assertEquals(Json.write(expected), Json.write(reversed));
        assertEquals(Json.write(expected), Json.write(new HashMap<>(expected)));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of(1, "invalid")));
    }
    @Test
    void nestedObjectOrderCannotChangeEncodedFactIdentity() {
        var first = Json.object("sample_details", Json.object("line", 1, "raw_javac", Json.object("code", "bad", "column", 4)));
        var second = Json.object("sample_details", Json.object("raw_javac", Json.object("column", 4, "code", "bad"), "line", 1));
        assertEquals(first, second);
        assertEquals(Json.write(first), Json.write(second));
        assertEquals(Json.key("diagnostic", Json.write(first)), Json.key("diagnostic", Json.write(second)));
        assertNotEquals(Json.write(List.of(first, second)), Json.write(List.of(first)));
    }
    @Test void jacksonTransportMatchesFrozenCanonicalUtf8AndNumberBytes() {
        var samples=List.of(Json.object("unicode","😀中\n\uD800x\uDC00","controls","\u0000\u000b\b\r\t"),
            Json.object("decimal",new java.math.BigDecimal("1.2300"),"wide",new java.math.BigInteger("123456789012345678901234567890"),"float",Float.valueOf("3.14")),
            Json.object("z",List.of(1,true),"a",Map.of("中",2,"a","v")));
        var frozen=List.of("eyJjb250cm9scyI6Ilx1MDAwMFx1MDAwYlxiXHJcdCIsInVuaWNvZGUiOiLwn5iA5LitXG4/eD8ifQ==",
            "eyJkZWNpbWFsIjoxLjIzMDAsImZsb2F0IjozLjE0LCJ3aWRlIjoxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTB9",
            "eyJhIjp7ImEiOiJ2Iiwi5LitIjoyfSwieiI6WzEsdHJ1ZV19");
        for(int n=0;n<samples.size();n++)assertArrayEquals(Base64.getDecoder().decode(frozen.get(n)),Json.encode(samples.get(n)));
    }
    @Test void jacksonTransportKeepsExactNumbersAndRejectsDuplicateKeysAndInvalidUtf8() {
        var value=Json.map(Json.parse("{\"wide\":123456789012345678901234567890,\"decimal\":1.2300,\"small\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertInstanceOf(java.math.BigInteger.class,value.get("wide"));
        assertEquals(new java.math.BigDecimal("1.2300"),value.get("decimal"));
        assertInstanceOf(Long.class,value.get("small"));
        for(String invalid:List.of("{\"a\":1,\"a\":2}","[1,]","[1]null","01","1e"))
            assertThrows(IllegalArgumentException.class,()->Json.parse(invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->Json.parse(new byte[]{34,(byte)0xc0,(byte)0xaf,34}));
    }
    @Test void transportAdmissionHappensBeforeTheFinalUsageReceiptIsFrozen() {
        var budget=new com.anatomist.core.AnalysisContext(com.anatomist.core.AnalysisContext.Limits.defaults());
        var output=Json.object("version",11,"archive","x".repeat(1024));
        Json.admit(output,budget);
        long admitted=((Number)budget.usage().get("payload_bytes")).longValue();assertTrue(admitted>65536);
        output.put("analysis_usage",budget.receipt());
        byte[] bytes=Json.encode(output);
        var receipt=Json.map(Json.map(Json.parse(bytes)).get("analysis_usage"));
        assertEquals(admitted,Json.number(Json.map(receipt.get("usage")),"payload_bytes"));
        assertEquals(admitted,((Number)budget.usage().get("payload_bytes")).longValue());
    }
}
