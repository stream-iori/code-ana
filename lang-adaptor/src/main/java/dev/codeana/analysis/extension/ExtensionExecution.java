package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.*;

/** One recovery boundary. Errors and cancellation propagate; only callback failures degrade. */
public final class ExtensionExecution {
    private ExtensionExecution() {}
    @FunctionalInterface interface Action { void run() throws Exception; }
    static boolean run(ExtensionPoint e, String stage, ExtensionContext context, ExtensionReport report,
            List<Object> destination, String subject, Action action) throws Exception {
        return run(e, stage, context, report, destination, subject, "", action);
    }
    static boolean run(ExtensionPoint e, String stage, ExtensionContext context, ExtensionReport report,
            List<Object> destination, String subject, String identity, Action action) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EXTENSION_CANCELLED");
        long started = System.nanoTime();
        try { action.run(); return true; }
        catch (InterruptedException | java.io.InterruptedIOException | java.util.concurrent.CancellationException
                | ExtensionPoint.GateFailure terminal) { throw terminal; }
        catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EXTENSION_CANCELLED");
            report.failure(e, stage, failure);
            failed(e, stage, context, report, destination, subject, identity, failure);
            return false;
        } finally { report.timing(e, stage, System.nanoTime() - started); }
    }
    private static void failed(ExtensionPoint e, String stage, ExtensionContext context, ExtensionReport report, List<Object> output,
            String subject, String identity, Exception failure) {
        Map<String, String> affected = new TreeMap<>();
        for (Object raw : Json.list(context.configuration().get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            if (Boolean.TRUE.equals(source.get("publish")))
                affected.put(Json.string(source, "path"), Json.string(source, "world"));
        }
        Set<String> sourcePaths = new HashSet<>(affected.keySet());
        for (Object raw : Json.list(context.configuration().get("resources"))) {
            Map<String, Object> unit = Json.map(Json.map(raw).get("unit"));
            affected.put(Json.string(unit, "path"), Json.string(unit, "world"));
        }
        if (!subject.isEmpty()) affected.keySet().retainAll(Set.of(subject));
        if (affected.isEmpty()) affected.put("", Json.string(context.configuration(), "world"));
        for (var entry : affected.entrySet()) for (String kind : List.of("diagnostic", "coverage")) {
            String path = entry.getKey();
            Map<String, Object> attributes = Json.object("context", context.configuration().get("context"),
                    "extension_id", e.id(), "extension_version", e.version(), "stage", stage,
                    "code", "EXTENSION_CALLBACK_FAILED", "exception", failure.getClass().getName(),
                    "reason", "Extension output discarded");
            if (!identity.isEmpty()) attributes.put("subject", identity);
            if (kind.equals("diagnostic")) { attributes.put("severity", "warning"); attributes.put("count", 1); }
            Map<String, Object> fact = Json.object("id", Json.key("extension-failure", entry.getValue(), e.id(), stage, path, kind, identity),
                    "kind", kind, "owner", Json.object("world", entry.getValue(), "producer", e.producer(),
                            "kind", path.isEmpty() ? "context" : sourcePaths.contains(path) ? "source" : "resource",
                            "id", path.isEmpty() ? context.configuration().get("context") : path),
                    "source", path, "span", null, "name", "", "signature", "", "parent", "", "target", "",
                    "relation", "", "status", "partial", "origin", e.producer(), "evidence_complete", false,
                    "declaration_resolved", false, "attributes", attributes);
            if (report.newFailureFact(Json.string(fact, "id"))) output.add(fact);
        }
    }
}
