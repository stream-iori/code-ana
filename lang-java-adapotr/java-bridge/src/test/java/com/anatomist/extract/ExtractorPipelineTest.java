package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class ExtractorPipelineTest {
    @Test
    void stampsSourceFileOnEveryJavaFact(@TempDir Path directory) throws Exception {
        var result =
                JavaFactsTestSupport.extract(
                        directory,
                        "A.java",
                        "package p; @Deprecated class A { int value; void run() { value++; helper(); } void helper() {} }");
        assertFalse(result.nodes.isEmpty());
        assertFalse(result.edges.isEmpty());
        assertFalse(result.annotations.isEmpty());
        assertTrue(
                result.nodes.stream().allMatch(n -> "A.java".equals(n.sourceFile)),
                "node origin missing");
        assertTrue(
                result.edges.stream().allMatch(e -> "A.java".equals(e.sourceFile)),
                "edge origin missing");
        assertTrue(
                result.annotations.stream().allMatch(a -> "A.java".equals(a.sourceFile)),
                "annotation origin missing");
    }
}
