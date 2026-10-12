import dev.codeana.adapter.CapturedProject;
import dev.codeana.adapter.FactProjection;
import dev.codeana.common.FactArchive;
import dev.codeana.common.Json;
import dev.codeana.common.PerformanceTrace;
import java.nio.file.*;
import java.util.*;

/** Real FactProjection.emit control/observed pair; JVM fixture, not Native speed proof. */
class PerformanceJavaCalibration {
    static Map<String, Object> trial(Path root, Map<String, Object> fact, int records, boolean observed) throws Exception {
        Files.createDirectory(root);
        var project = new CapturedProject(Json.object("scratch", root.toString(), "contexts", List.of(), "defer_fact_dedup", true));
        Path destination = root.resolve("sealed.caf");
        String identity = "";
        Map<String, Object> diagnostics = null;
        long elapsed;
        try (var trace = observed ? new PerformanceTrace() : null;
             var archive = new FactArchive(root.resolve("pending.caf"), 8L << 30, records, false)) {
            var projection = new FactProjection(project, archive);
            long began = System.nanoTime();
            for (int i = 0; i < records; i++) {
                identity = Json.key("symbol", (String) fact.get("source"), (String) fact.get("signature"));
                projection.emit(fact);
            }
            archive.export(destination);
            elapsed = System.nanoTime() - began;
            if (trace != null) diagnostics = trace.snapshot();
        }
        String sha = Json.sha(destination);
        long bytes = Files.size(destination);
        Files.delete(destination);
        return Json.object("seconds", elapsed / 1e9, "records", records, "bytes", bytes,
                "sha256", sha, "identity", identity, "diagnostics", diagnostics);
    }
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectory(output);
        var fact = Json.map(Json.parse(Files.readAllBytes(input)));
        trial(output.resolve("warm-off"), fact, 100000, false);
        trial(output.resolve("warm-on"), fact, 100000, true);
        var off = trial(output.resolve("control"), fact, 1000000, false);
        var on = trial(output.resolve("observed"), fact, 1000000, true);
        if (!off.get("sha256").equals(on.get("sha256")) || !off.get("identity").equals(on.get("identity")))
            throw new IllegalStateException("CALIBRATION_RESULT_DRIFT");
        double overhead = ((Number) on.get("seconds")).doubleValue() / ((Number) off.get("seconds")).doubleValue() - 1;
        var report = Json.object("status", overhead <= .03 ? "passed" : "failed", "control", off, "observed", on,
                "overhead_fraction", overhead, "maximum_overhead_fraction", .03,
                "scope", "one warmed JVM real-fact projection/identity/CAF pair; not full-project or Native overhead proof");
        Json.writeTo(output.resolve("receipt.json"), report, 8L << 20);
        System.out.println(Json.write(report));
        if (overhead > .03) System.exit(1);
    }
}
