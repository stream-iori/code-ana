package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Timing diagnostics remain bounded, task-owned and independent of compiler children. */
public final class CompilerPhaseProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Map<String, Object> request = Json.object("scratch", root.toString(), "java_home", args[1],
                "bridge", args[2]);
        Path scratch;
        try (CompilerSession session = new CompilerSession(request)) {
            try (var paths = Files.list(root)) {
                scratch = paths.filter(p -> p.getFileName().toString().startsWith("compiler-")).findFirst().orElseThrow();
            }
            String context = "模块😀@MAIN", phase = "extract";
            session.mark(context, phase);
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Thread thread = new Thread(() -> { for (int n = 0; n < 1500; n++) session.mark(context, phase); });
                threads.add(thread); thread.start();
            }
            for (Thread thread : threads) thread.join();
            Path journal = scratch.resolve("phases.ndjson");
            List<String> lines = Files.readAllLines(journal);
            if (lines.size() != 4096 || Files.size(journal) > 1024 * 1024) throw new AssertionError("journal bound");
            long previous = -1;
            for (String line : lines) {
                Map<String, Object> row = Json.map(Json.parse(line.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                long elapsed = Json.number(row, "elapsed_nanos");
                if (elapsed < previous || !context.equals(row.get("context")) || !phase.equals(row.get("phase")))
                    throw new AssertionError("monotonic complete unicode record");
                previous = elapsed;
            }
            session.mark("x".repeat(5000), "y".repeat(5000));
            Map<String, Object> failure = Json.map(Json.parse(session.heapFailure()));
            if (Json.string(failure, "context").length() != 1024 || Json.string(failure, "phase").length() != 512)
                throw new AssertionError("bounded failure receipt");
        }
        if (Files.exists(scratch)) throw new AssertionError("task journal leaked");
        // An unavailable diagnostic path must not prevent analysis or its failure receipt.
        try (CompilerSession session = new CompilerSession(request)) {
            try (var paths = Files.list(root)) { scratch = paths.findFirst().orElseThrow(); }
            Files.createDirectory(scratch.resolve("phases.ndjson"));
            session.mark("healthy", "compiler_model");
            if (!"healthy".equals(Json.map(Json.parse(session.heapFailure())).get("context")))
                throw new AssertionError("diagnostic changed receipt");
        }
        System.out.println("phase journal unicode/concurrency/bounds/cleanup/unavailable: passed");
    }
}
