package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Product reader assertions and detached output for the independent frozen reader comparison. */
public final class BinaryMetadataProbe {
    private static long weight(BinaryIndex index) throws Exception {
        var field = BinaryIndex.class.getDeclaredField("retainedClassWeight");
        field.setAccessible(true); return field.getLong(index);
    }
    public static void main(String[] args) throws Exception {
        Map<String, Object> context = Json.object("classpath", List.of(args[0]));
        Map<String, Object> outputs = Json.object();
        try (BinaryIndex index = new BinaryIndex(context, Path.of(args[1]), 0)) {
            for (String name : List.of("p.Api", "p.Mark", "p.Nested", "p.TypeUse", "p.Outer$Inner")) {
                Map<String, Object> one = index.readClass(name), two = index.readClass(name);
                if (one == null || one == two || !one.equals(two) || weight(index) != 0)
                    throw new AssertionError("uncached complete reads: " + name);
                outputs.put(name, one);
            }
            if (!index.readClass("p.Outer.Inner").equals(outputs.get("p.Outer$Inner")))
                throw new AssertionError("proven dotted nested class");
        }
        try (BinaryIndex index = new BinaryIndex(context, Path.of(args[1]), 8192)) {
            Map<String, Object> small = index.readClass("p.Nested");
            if (small != index.readClass("p.Nested") || weight(index) > 8192)
                throw new AssertionError("small cache hit");
            index.readClass("absent.One"); index.readClass("absent.Two");
            if (weight(index) > 8192 || small == index.readClass("p.Nested"))
                throw new AssertionError("weighted negative LRU eviction");
            Map<String, Object> large = index.readClass("p.Api");
            if (large == index.readClass("p.Api") || !large.equals(index.readClass("p.Api")) || weight(index) > 8192)
                throw new AssertionError("large declarations consumed complete and uncached");
            if (Json.map(large.get("members")).size() < 2103) throw new AssertionError("members truncated");
        }
        try (BinaryIndex index = new BinaryIndex(Json.object("classpath", List.of(args[2])), Path.of(args[1]))) {
            try { index.readClass("p.Forged"); throw new AssertionError("forged binary identity"); }
            catch (java.io.IOException expected) {
                if (!expected.getMessage().startsWith("BINARY_CLASS_NAME_MISMATCH")) throw expected;
            }
        }
        Files.writeString(Path.of(args[3]), Json.write(outputs));
        System.out.println("binary weighted-cache/complete-members/nested-identity/name-mismatch: passed");
    }
}
