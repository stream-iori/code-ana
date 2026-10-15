package dev.codeana.runner;

import dev.codeana.adapter.AnalysisSession;
import dev.codeana.adapter.AnalysisTelemetry;
import dev.codeana.common.GraphArchive;
import dev.codeana.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Explicit JVM development entry: request/result 10, task-owned GRA1 output. */
public final class JvmRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("request.json result.json required");
        Path resultPath = Path.of(args[1]);
        if (Files.exists(resultPath)) throw new IllegalArgumentException("RESULT_ALREADY_EXISTS");
        Map<String, Object> request = Json.map(Json.parse(Files.readAllBytes(Path.of(args[0]))));
        request.put("defer_fact_dedup", true);
        AnalysisTelemetry telemetry = new AnalysisTelemetry();
        int terminalState = -1;
        String failureDetail = null;
        try (AnalysisSession session = new AnalysisSession(request, telemetry)) {
            Thread cancellation = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (Files.exists(resultPath.resolveSibling("cancel"))) {
                            session.cancel();
                            return;
                        }
                        Thread.sleep(20);
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }, "codeana-jvm-cancel-monitor");
            cancellation.setDaemon(true);
            cancellation.start();
            Thread shutdown = new Thread(session::cancel, "codeana-jvm-cancel");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                Map<String, Object> result = session.analyze();
                GraphArchive facts = (GraphArchive) result.remove("facts");
                telemetry.phase("archive_seal", "", true);
                result.put("graph_archive", facts.export(resultPath.resolveSibling("graph.rows")));
                telemetry.phase("archive_seal", "", false);
                Json.writeTo(resultPath, result, 64L << 20);
                terminalState = 1;
            } finally {
                cancellation.interrupt();
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        } catch (Exception failure) {
            var detail = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(detail));
            failureDetail = detail.toString();
            Json.writeTo(resultPath, Json.object("error", detail.toString()), 64L << 20);
            throw failure;
        } finally {
            telemetry.save(Path.of(Json.string(request, "scratch")).resolve("native-timings.json"), terminalState, failureDetail);
        }
    }
}
