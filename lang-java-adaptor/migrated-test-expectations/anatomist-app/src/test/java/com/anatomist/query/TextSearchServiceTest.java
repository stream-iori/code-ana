package com.anatomist.query;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.version.SnapshotException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class TextSearchServiceTest {
    @TempDir Path root;

    private List<Map<String, Object>> search(
            List<Path> files, String pattern, boolean regex, int limit, int context) {
        List<Map<String, Object>> rows = new ArrayList<>();
        var result =
                new TextSearchService()
                        .search(
                                root,
                                files,
                                new TextSearchService.Request(
                                        pattern, regex, false, limit, context, 10),
                                System.nanoTime() + 10_000_000_000L,
                                rows::add);
        assertEquals(rows.size(), result.emitted());
        return rows;
    }

    @Test
    void literalUnicodeContextAndRepeatedMatches() throws Exception {
        Path path = root.resolve("a:b 空格.java");
        Files.writeString(path, "before\r\n中文.* 中文.*\r\nafter\r\n");
        var rows = search(List.of(path), "中文.*", false, 10, 1);
        assertEquals(1, rows.size());
        assertEquals("a:b 空格.java", rows.getFirst().get("path"));
        assertEquals(2, rows.getFirst().get("line"));
        assertEquals("中文.* 中文.*", rows.getFirst().get("text"));
        assertEquals(
                Map.of("start_line", 1, "lines", List.of("before", "中文.* 中文.*", "after")),
                rows.getFirst().get("context"));
        assertEquals(
                Set.of("record", "path", "line", "text"),
                search(List.of(path), "中文", false, 10, 0).getFirst().keySet());
    }

    @Test
    void limitAndInvalidRegexEvenWithoutFiles() throws Exception {
        Path path = root.resolve("many.txt");
        Files.writeString(path, "hit\nhit\nhit\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        var result =
                new TextSearchService()
                        .search(
                                root,
                                List.of(path),
                                new TextSearchService.Request("hit", false, false, 1, 1, 10),
                                System.nanoTime() + 10_000_000_000L,
                                rows::add);
        assertTrue(result.truncated());
        assertEquals(1, rows.size());
        assertThrows(SnapshotException.class, () -> search(List.of(), "[", true, 10, 0));
        assertTrue(search(List.of(path), "missing", false, 10, 0).isEmpty());
    }

    @Test
    void closedOutputAndExpiredDeadlineFailWithoutHanging() throws Exception {
        Path path = root.resolve("many.txt");
        Files.writeString(path, "hit\n".repeat(10000));
        var request = new TextSearchService.Request("hit", false, false, 100000, 0, 10);
        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () ->
                                        new TextSearchService()
                                                .search(
                                                        root,
                                                        List.of(path),
                                                        request,
                                                        System.nanoTime() + 10_000_000_000L,
                                                        row -> {
                                                            throw new IllegalStateException(
                                                                    "closed output");
                                                        })));
        assertEquals(
                "TEXT_SEARCH_TIMEOUT",
                assertThrows(
                                SnapshotException.class,
                                () ->
                                        new TextSearchService()
                                                .search(root, List.of(path), request, 0, row -> {}))
                        .code());
    }
}
