package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Compare compiler payload sizes while keeping the actual compiler/model path. */
public final class DependencyViewProbe {
    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        try (CompilerSession session = new CompilerSession(request)) {
            Map<String, Object> model = session.analyze(Json.map(Json.list(request.get("contexts")).getFirst()));
            long bytes;
            try (var paths = Files.walk(Path.of(Json.string(request, "scratch")))) {
                bytes = paths.filter(p -> p.getFileName().toString().startsWith("input-model-"))
                        .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { throw new RuntimeException(e); } }).sum();
            }
            System.out.println(Json.write(Json.object("syntax", Json.list(model.get("syntax")).size(),
                    "observations", Json.list(model.get("observations")).size(), "bytes", bytes)));
        }
    }
}
