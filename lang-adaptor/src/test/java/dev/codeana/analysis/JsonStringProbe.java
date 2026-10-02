package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Java 8 codec compared to independently encoded Python JSON strings. */
public final class JsonStringProbe {
    public static void main(String[] args) throws Exception {
        Object values = Json.parse(Json.read(Paths.get(args[0])));
        Path output = Paths.get(args[1]);
        Json.writeTo(output, values, 8L << 20);
        if (!Arrays.equals(
                Files.readAllBytes(output), Json.write(values).getBytes(StandardCharsets.UTF_8)))
            throw new AssertionError("streaming and in-memory encoding differ");
        String[] invalid = {
            "\"",
            "\"abc",
            "\"abc\\",
            "\"a\\u",
            "\"a\\u12\"",
            "\"a\\q\"",
            "\"a\\uZZZZ\"",
            "\"a\u0001b\"",
            "{\"a\":1,\"a\":2}",
            "1e+",
            "01",
            "[1,]"
        };
        for (String token : invalid) {
            try {
                Json.parse(token.getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("invalid JSON accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        byte[][] invalidUtf8 = {
            {(byte) 0xc0, (byte) 0xaf},
            {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
            {(byte) 0xf0, (byte) 0x9f}
        };
        for (byte[] bytes : invalidUtf8) {
            try {
                Json.parse(bytes);
                throw new AssertionError("invalid UTF8 accepted");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().equals("INVALID_UTF8")) throw expected;
            }
        }
        if (!Json.parse("\"\\uD83D\\uDE00\"".getBytes(StandardCharsets.UTF_8)).equals("😀"))
            throw new AssertionError("escaped supplementary code point lost");
        System.out.println(
                "accepted: "
                        + Json.list(values).size()
                        + " independently encoded strings, long plain/escaped runs, all controls,"
                        + " UTF8, streaming and invalid input rejection");
    }
}
