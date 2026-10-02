package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.nio.file.*;
import java.util.*;

/** Test-only JVM runner for compiler/model/IR tests; never a product fallback. */
public final class AnalysisProbe {
    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        try (CompilerSession session = new CompilerSession(request)) {
            System.out.println(Json.write(JavaAnalysis.run(request, session)));
        }
    }
}
