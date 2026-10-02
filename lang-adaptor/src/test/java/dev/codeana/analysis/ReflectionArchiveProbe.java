package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.RecordArchive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The reflection pass must consume a large file without materializing its syntax graph. */
public final class ReflectionArchiveProbe {
    public static void main(String[] args) throws Exception {
        int count = 60000;
        Path root = Path.of(args[0]);
        try (RecordArchive archive =
                RecordArchive.create(root.resolve("reflection.bin"), 128L * 1024 * 1024, count)) {
            List<Integer> statements = new ArrayList<>();
            for (int i = 3; i <= count; i++) statements.add(i);
            for (int i = 1; i <= count; i++) {
                Map<String, Object> roles =
                        i == 1
                                ? Json.object("body", 2)
                                : i == 2 ? Json.object("statements", statements) : Map.of();
                archive.add(
                        Json.object(
                                "id",
                                i,
                                "parent",
                                i == 1 ? 0 : i == 2 ? 1 : 2,
                                "source",
                                "Large.java",
                                "kind",
                                i == 1 ? "METHOD" : i == 2 ? "BLOCK" : "EMPTY_STATEMENT",
                                "roles",
                                roles,
                                "symbol",
                                "",
                                "payload",
                                "汉".repeat(500) + i));
            }
            archive.seal();
            if (archive.bytes() < 90L * 1024 * 1024)
                throw new AssertionError("insufficient fixture");
            Map<String, Object> model =
                    Json.object(
                            "syntax",
                            archive,
                            "declarations",
                            List.of(),
                            "external",
                            new ArrayList<>(),
                            "diagnostics",
                            new ArrayList<>(),
                            "observations",
                            new ArrayList<>(
                                    List.of(
                                            Json.object(
                                                    "kind",
                                                    "call",
                                                    "source",
                                                    "Large.java",
                                                    "start",
                                                    0,
                                                    "end",
                                                    1,
                                                    "resolved",
                                                    true,
                                                    "target",
                                                    "java.lang.Class#forName(java.lang.String)"))));
            ReflectionModel.analyze(Json.object("classpath", List.of()), model, Path.of(args[1]));
            if (!Json.list(model.get("diagnostics")).isEmpty())
                throw new AssertionError("unexpected truncation");
            if (Json.list(model.get("observations")).size() != 1)
                throw new AssertionError("unexpected evidence");
            Thread.currentThread().interrupt();
            try {
                ReflectionModel.analyze(
                        Json.object("classpath", List.of()), model, Path.of(args[1]));
                throw new AssertionError("ignored cancellation");
            } catch (java.io.IOException cancelled) {
                if (!"CANCELLED".equals(cancelled.getMessage())) throw cancelled;
            } finally {
                Thread.interrupted();
            }
            System.out.println(
                    "reflection: "
                            + count
                            + " nodes / "
                            + archive.bytes()
                            + " bytes under 64 MiB heap, no truncation; cancellation passed");
        }
    }
}
