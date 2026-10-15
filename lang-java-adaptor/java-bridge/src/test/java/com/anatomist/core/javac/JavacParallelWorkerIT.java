package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

class JavacParallelWorkerIT {
    @Test
    void independentCompilersOverlapInsideOneProcessWithIdenticalModels(@TempDir Path root)
            throws Exception {
        List<List<Path>> sources = new ArrayList<>();
        for (int lane = 0; lane < 2; lane++) {
            Path directory = Files.createDirectory(root.resolve("lane" + lane));
            List<Path> files = new ArrayList<>();
            for (int i = 0; i < 120; i++)
                files.add(
                        Files.writeString(
                                directory.resolve("Type" + i + ".java"),
                                "package lane"
                                        + lane
                                        + "; class Type"
                                        + i
                                        + " { java.util.Map<String,java.util.List<Integer>> values; String value(int n) { return String.valueOf(n); } }"));
            sources.add(files);
        }
        Path jar = root.resolve("worker.jar");
        try (InputStream resource =
                JavacBackend.class.getResourceAsStream("/META-INF/anatomist/javac-worker.jar")) {
            Files.copy(Objects.requireNonNull(resource), jar);
        }
        for (int release : List.of(8, 17, 25)) {
            List<List<JavaUnit>> baseline = null;
            for (int threads : List.of(1, 2)) {
                var options =
                        new JavacOptions(
                                JavacBackendIT.home(release),
                                true,
                                true,
                                false,
                                false,
                                root.resolve("cache"));
                var toolchain = JavacToolchain.resolve(options, release, null);
                List<String> command =
                        List.of(
                                toolchain.home().resolve("bin/java").toString(),
                                "-Xmx1024m",
                                "-cp",
                                jar.toString()
                                        + (release == 8
                                                ? File.pathSeparator
                                                        + toolchain.home().resolve("lib/tools.jar")
                                                : ""),
                                "com.anatomist.worker.JavacWorker",
                                Integer.toString(threads));
                try (var session = new JavacWorkerSession(command, root, Duration.ofMinutes(10));
                        var left =
                                new JavacDeclarationCache(32L * 1024 * 1024)
                                        .lease(
                                                sources.get(0),
                                                Set.copyOf(sources.get(0)),
                                                true,
                                                "test");
                        var right =
                                new JavacDeclarationCache(32L * 1024 * 1024)
                                        .lease(
                                                sources.get(1),
                                                Set.copyOf(sources.get(1)),
                                                true,
                                                "test")) {
                    List<JavacBatchProtocol.Request> requests = new ArrayList<>();
                    for (int lane = 0; lane < 2; lane++) {
                        var lease = lane == 0 ? left : right;
                        var files = sources.get(lane);
                        Path scratch =
                                Files.createDirectory(
                                        root.resolve("run" + release + "-" + threads + "-" + lane));
                        requests.add(
                                new JavacBatchProtocol.Request(
                                        lane + 1,
                                        0,
                                        lane,
                                        out ->
                                                JavacProtocol.request(
                                                        out, toolchain, files, List.of(), false,
                                                        null, null, scratch, lease),
                                        scratch.resolve("received.bin")));
                    }
                    var results = session.executeBatch(requests);
                    assertEquals(1, session.launches());
                    assertTrue(
                            session.batches().getFirst().transportBytes()
                                    < session.batches().getFirst().resultBytes());
                    assertEquals(0L, session.batches().getFirst().toMap().get("result_copy_bytes"));
                    var a = results.get(0).execution();
                    var b = results.get(1).execution();
                    assertEquals(a.pid(), b.pid());
                    boolean overlap =
                            Math.max(a.started(), b.started())
                                    < Math.min(a.finished(), b.finished());
                    assertEquals(threads == 2, overlap, results.toString());
                    boolean compilerOverlap =
                            Math.max(a.parseStarted(), b.parseStarted())
                                    < Math.min(a.analyzeFinished(), b.analyzeFinished());
                    assertEquals(threads == 2, compilerOverlap, results.toString());
                    if (threads == 2) assertNotEquals(a.thread(), b.thread());
                    List<List<JavaUnit>> models = new ArrayList<>();
                    for (int lane = 0; lane < 2; lane++) {
                        List<JavaUnit> units = new ArrayList<>();
                        {
                            var response =
                                    JavacProtocol.response(
                                            results.get(lane),
                                            units::add,
                                            lane == 0 ? left : right);
                            assertEquals(120, response.units());
                            assertTrue(response.failure().isEmpty(), response.failure());
                            assertTrue(
                                    response.diagnostics().isEmpty(),
                                    response.diagnostics().toString());
                        }
                        models.add(units);
                    }
                    if (baseline == null) baseline = models;
                    else assertEquals(baseline, models);
                    System.out.println(
                            "parallel-prototype release="
                                    + release
                                    + " threads="
                                    + threads
                                    + " executions="
                                    + results.stream()
                                            .map(JavacBatchProtocol.Result::execution)
                                            .toList());
                }
            }
        }
    }
}
