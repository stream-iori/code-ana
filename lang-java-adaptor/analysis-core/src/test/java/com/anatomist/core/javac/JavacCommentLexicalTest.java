package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual fixed Java 8 javac lexer, not a host-JDK imitation. */
class JavacCommentLexicalTest {
    @TempDir static Path directory;
    private static Path home;
    private static String classpath;
    record Segment(int bodyStart, int bodyEnd, int sourceStart, int sourceEnd) {}
    record Comment(String kind, int start, int end, String body, List<Segment> segments) {}

    @BeforeAll
    static void compileProbe() throws Exception {
        home = Path.of(System.getProperty("java8.home", System.getProperty("user.home") + "/.sdkman/candidates/java/8.0.492-zulu"));
        Path worker = Path.of("analysis-core/target/worker-classes").toAbsolutePath();
        Path classes = directory.resolve("classes");
        Files.createDirectory(classes);
        String compileClasspath = worker + File.pathSeparator + home.resolve("lib/tools.jar");
        Process compile = new ProcessBuilder(home.resolve("bin/javac").toString(), "-cp", compileClasspath,
                "-d", classes.toString(), "analysis-core/src/test/worker/com/anatomist/worker/CommentsProbe.java")
                .redirectErrorStream(true).redirectOutput(directory.resolve("compile.log").toFile()).start();
        assertTrue(compile.waitFor(30, TimeUnit.SECONDS), "probe compilation timeout");
        assertEquals(0, compile.exitValue(), Files.readString(directory.resolve("compile.log")));
        classpath = classes + File.pathSeparator + compileClasspath;
    }

    private Process probe(String source, long budget, int cancelAfter) throws Exception {
        return probe(source, budget, cancelAfter, home, 8);
    }

    private Process probe(String source, long budget, int cancelAfter, Path runtime, int release) throws Exception {
        Path input = Files.createTempFile(directory, "input", ".java");
        Files.writeString(input, source);
        var command = new ArrayList<String>();
        command.add(runtime.resolve("bin/java").toString());
        if (!runtime.equals(home)) command.addAll(JavacToolchain.compilerAccessOptions(25));
        command.addAll(List.of("-cp", classpath, "com.anatomist.worker.CommentsProbe", input.toString(),
                Long.toString(budget), Integer.toString(cancelAfter), Integer.toString(release)));
        Process process = new ProcessBuilder(command)
                .redirectError(directory.resolve("probe.log").toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            fail("probe timeout");
        }
        return process;
    }

    private List<Comment> scan(String source) throws Exception {
        Process process = probe(source, 64L << 20, -1);
        assertEquals(0, process.exitValue(), Files.readString(directory.resolve("probe.log")));
        var result = new ArrayList<Comment>();
        try (var in = new DataInputStream(process.getInputStream())) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String kind = text(in);
                int start = in.readInt(), end = in.readInt();
                String body = text(in);
                int segments = in.readInt();
                var map = new ArrayList<Segment>();
                int lastBody = 0, lastSource = start;
                for (int j = 0; j < segments; j++) {
                    var segment = new Segment(in.readInt(), in.readInt(), in.readInt(), in.readInt());
                    assertEquals(lastBody, segment.bodyStart());
                    assertTrue(segment.bodyEnd() > segment.bodyStart());
                    assertTrue(segment.sourceStart() >= lastSource && segment.sourceEnd() <= end);
                    assertTrue(segment.sourceEnd() > segment.sourceStart());
                    assertFalse(Character.isLowSurrogate(body.charAt(segment.bodyStart())), "UTF-16 map must not split a scalar");
                    if (segment.bodyEnd() < body.length())
                        assertFalse(Character.isLowSurrogate(body.charAt(segment.bodyEnd())));
                    lastBody = segment.bodyEnd();
                    lastSource = segment.sourceEnd();
                    map.add(segment);
                }
                assertEquals(body.length(), lastBody);
                result.add(new Comment(kind, start, end, body, List.copyOf(map)));
            }
            assertEquals(-1, in.read());
        }
        return result;
    }

    private static String text(DataInputStream in) throws Exception {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    @Test
    void originalFixtureHasEightCommentsAndNoLiteralFalsePositive() throws Exception {
        String source = Files.readString(Path.of("fixtures/comment-indexing/Comments.java"));
        var comments = scan(source);
        assertEquals(8, comments.size());
        assertEquals(List.of("line", "doc", "doc", "line", "line", "line", "line", "block"),
                comments.stream().map(Comment::kind).toList());
        assertEquals(List.of(1L, 4L, 6L, 9L, 10L, 12L, 15L, 21L),
                comments.stream().map(c -> 1L + source.substring(0, c.start()).chars().filter(ch -> ch == '\n').count()).toList());
        assertTrue(comments.stream().noneMatch(c -> c.body().contains("不是注释")));
    }

    @Test
    void eofLineAndEmptyDocCommentsSurviveSentinelWithoutLeakingIt() throws Exception {
        String source = "/**/ /* */ // 末尾库存";
        var comments = scan(source);
        assertEquals(3, comments.size());
        assertEquals("", comments.get(0).body());
        assertEquals(" 末尾库存", comments.getLast().body());
        assertEquals(source.length(), comments.getLast().end());
        assertEquals("// 末尾库存", source.substring(comments.getLast().start(), comments.getLast().end()));
    }

    @Test
    void unicodeDelimitersAndTerminatorUseCompleteOriginalEscapeRanges() throws Exception {
        String slash = "\\" + "u002f", newline = "\\" + "uu000a";
        String source = slash + slash + " 库存" + newline + "class A{}";
        Comment comment = scan(source).getFirst();
        assertEquals(0, comment.start());
        assertEquals(source.indexOf(newline), comment.end());
        assertEquals(" 库存", comment.body());
        String next = "\\" + "u0063";
        source = "/* 库存 */" + next + "lass A{}";
        assertEquals(source.indexOf(next), scan(source).getFirst().end());
        source = slash + "* 库存 *" + slash;
        assertEquals(source.length(), scan(source).getFirst().end());
    }

    @Test
    void unicodeEligibilityNonRecursiveEscapesAndBackslashesMatchJavac() throws Exception {
        String u = "\\" + "u";
        String source = "// " + u + "5e93" + u + "5b58 " + "\\\\u0061 " + u + "005cu0062";
        Comment comment = scan(source).getFirst();
        assertEquals(" 库存 \\\\u0061 \\u0062", comment.body());
        var escaped = comment.segments().stream().filter(s -> s.sourceEnd() - s.sourceStart() > s.bodyEnd() - s.bodyStart()).toList();
        assertEquals(3, escaped.size());
        assertEquals(u + "5e93", source.substring(escaped.getFirst().sourceStart(), escaped.getFirst().sourceEnd()));
    }

    @Test
    void docStarsCrlfAndEscapedEmojiKeepDiscontinuousScalarMapping() throws Exception {
        String u = "\\" + "u";
        String source = "/**\r\n * 库存" + u + "d83d" + u + "de00\r\n * count\r\n */";
        Comment comment = scan(source).getFirst();
        assertEquals("\r\n库存😀\r\ncount\r\n ", comment.body());
        var emoji = comment.segments().stream().filter(s -> comment.body().substring(s.bodyStart(), s.bodyEnd()).equals("😀")).findFirst().orElseThrow();
        assertEquals(u + "d83d" + u + "de00", source.substring(emoji.sourceStart(), emoji.sourceEnd()));
        assertFalse(comment.body().contains("*"));
        var unpaired = scan("// " + u + "d800").getFirst();
        assertEquals(" " + u + "d800", unpaired.body());
    }

    @Test
    void stringsCharacterLiteralsAndSyntaxBindingErrorsDoNotHideRealComments() throws Exception {
        String source = "class A { String s=\"// false /* false */\"; char c='/'; Missing x; /* 保留 */ }";
        var comments = scan(source);
        assertEquals(1, comments.size());
        assertEquals(" 保留 ", comments.getFirst().body());
    }

    @Test
    void budgetAndCancellationHooksStopExtractionWithoutPartialDelivery() throws Exception {
        Process budget = probe("// 库存", 1, -1);
        assertNotEquals(0, budget.exitValue());
        assertTrue(Files.readString(directory.resolve("probe.log")).contains("COMMENT_BUDGET"));
        Process cancelled = probe("// 库存", 64L << 20, 3);
        assertNotEquals(0, cancelled.exitValue());
        assertTrue(Files.readString(directory.resolve("probe.log")).contains("COMMENT_CANCELLED"));
    }

    @Test
    void compilerSideLexerRunsOnHost25WithoutDependingOnItsUnicodeReaderLayout() throws Exception {
        String u = "\\" + "u";
        String source = u + "002f" + u + "002f 库存😀" + u + "000a class A{}";
        Process java8 = probe(source, 64L << 20, -1);
        assertEquals(0, java8.exitValue());
        Process java25 = probe(source, 64L << 20, -1, Path.of(System.getProperty("java.home")), 8);
        assertEquals(0, java25.exitValue(), Files.readString(directory.resolve("probe.log")));
        assertArrayEquals(java8.getInputStream().readAllBytes(), java25.getInputStream().readAllBytes());
    }
}
