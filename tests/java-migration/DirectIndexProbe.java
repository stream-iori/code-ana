import com.anatomist.core.IndexTimings;
import com.anatomist.core.java.JavaAnalysisContext;
import dev.codeana.adapter.AnalysisObserver;
import dev.codeana.adapter.AnalysisSession;
import dev.codeana.common.FactArchive;
import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Direct JVM diagnostic of the production Java session: no Rust, no model dumps. */
public final class DirectIndexProbe implements AnalysisObserver {
    private final IndexTimings core = new IndexTimings();
    private final Map<String, Long> starts = new LinkedHashMap<>();
    private final Map<String, Long> nanos = new LinkedHashMap<>();
    private final List<Map<String, Object>> contexts = new ArrayList<>();
    private final Path output;
    private final int maximumContexts;
    private final long began = System.nanoTime();
    private String active = "";
    DirectIndexProbe(Path output, int maximumContexts) {
        this.output = output;
        this.maximumContexts = maximumContexts;
    }

    private static final class PrefixComplete extends RuntimeException {}

    @Override
    public IndexTimings timings() {
        return core;
    }

    @Override
    public void model(JavaAnalysisContext context) {
        if (contexts.size() >= maximumContexts) throw new PrefixComplete();
        contexts.add(
                Json.object(
                        "context", context.module() + "@" + context.scope(),
                        "units", context.units().size(),
                        "publish", context.publish().size(),
                        "elapsed_ms", (System.nanoTime() - began) / 1_000_000));
    }

    @Override
    public void phase(String phase, String context, boolean starting) {
        String key = phase + "/" + context;
        if (starting) {
            starts.put(key, System.nanoTime());
            active = key;
        } else {
            Long start = starts.remove(key);
            if (start != null) nanos.merge(phase, System.nanoTime() - start, Long::sum);
            if (!phase.equals("unit_projection")) save("running", null, null);
        }
    }

    private void save(String state, String failure, Map<String, Object> result) {
        Map<String, Object> millis = new TreeMap<>();
        nanos.forEach((key, value) -> millis.put(key, value / 1_000_000));
        var receipt =
                Json.object(
                        "state", state,
                        "elapsed_ms", (System.nanoTime() - began) / 1_000_000,
                        "active", active,
                        "phase_ms", millis,
                        "core_ms", core.millis(),
                        "contexts", contexts,
                        "failure", failure);
        if (result != null && result.get("facts") instanceof FactArchive archive) {
            receipt.put("facts", archive.size());
            receipt.put("archive_bytes", archive.bytes());
        }
        try {
            Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
            Json.writeTo(temporary, receipt, Json.MAX_BYTES);
            Files.move(
                    temporary, output,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        var probe =
                new DirectIndexProbe(
                        Path.of(args[1]),
                        args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE);
        var request = Json.map(Json.parse(Files.readAllBytes(Path.of(args[0]))));
        try (var session = new AnalysisSession(request, probe)) {
            try {
                var result = session.analyze();
                probe.save("finished", null, result);
            } catch (PrefixComplete complete) {
                probe.save("diagnostic_prefix_complete", null, null);
            } catch (Throwable failure) {
                probe.save("failed", failure.toString(), null);
                throw failure;
            }
        }
    }
}
