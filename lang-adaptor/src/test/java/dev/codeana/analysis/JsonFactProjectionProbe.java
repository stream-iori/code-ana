package dev.codeana.analysis;

import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Java 8 production codec: selection must retain strict validation of skipped data. */
public final class JsonFactProjectionProbe {
    private static String failure(byte[] bytes, boolean projection) {
        try {
            if (projection) Json.factProjection(bytes); else Json.parse(bytes);
            throw new AssertionError("malformed JSON accepted");
        } catch (IllegalArgumentException expected) { return expected.getMessage(); }
    }
    private static void rejected(byte[] bytes) {
        String complete = failure(bytes, false), selected = failure(bytes, true);
        if (!complete.equals(selected)) throw new AssertionError(complete + " != " + selected);
    }
    public static void main(String[] args) throws Exception {
        for (String bad : Arrays.asList(
                "{\"attributes\":{\"skipped\":{\"k\":null,\"k\":1}}}",
                "{\"skipped\":[{\"x\":1,\"\\u0078\":2}]}",
                "{\"owner\":{\"world\":\"x\",\"other\":\"bad\\q\"}}",
                "{\"attributes\":{\"unused\":\"bad\\uZZZZ\"}}",
                "{\"skipped\":\"bad\ncontrol\"}",
                "{\"skipped\":\"unfinished\\",
                "{\"skipped\":[true,]}", "{\"skipped\":{\"k\":0,}}",
                "{\"skipped\":01}", "{\"skipped\":1e+}", "{} trailing"))
            rejected(bad.getBytes(StandardCharsets.UTF_8));
        rejected(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'});
        StringBuilder deep = new StringBuilder("{\"skipped\":");
        for (int i = 0; i <= Json.MAX_DEPTH; i++) deep.append('[');
        deep.append('0'); for (int i = 0; i <= Json.MAX_DEPTH; i++) deep.append(']'); deep.append('}');
        rejected(deep.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder many = new StringBuilder("{\"skipped\":[0");
        for (int i = 0; i < 1000000; i++) many.append(",0"); many.append("]}");
        rejected(many.toString().getBytes(StandardCharsets.UTF_8));
        List<Object> rows = Json.list(Json.parse(Json.read(Paths.get(args[0]))));
        List<Object> headers = new ArrayList<Object>();
        for (Object row : rows) {
            byte[] bytes = Json.write(row).getBytes(StandardCharsets.UTF_8);
            Object projected = FactHeaders.project(Json.factProjection(bytes));
            if (!projected.equals(FactHeaders.project(Json.parse(bytes))))
                throw new AssertionError("full/selected metadata differ");
            headers.add(projected);
        }
        Files.write(Paths.get(args[1]), Json.write(headers).getBytes(StandardCharsets.UTF_8));
        System.out.println("strict fact projection: " + rows.size() + " records; skipped duplicates, UTF-8, escapes, syntax, depth/node budgets passed");
    }
}
