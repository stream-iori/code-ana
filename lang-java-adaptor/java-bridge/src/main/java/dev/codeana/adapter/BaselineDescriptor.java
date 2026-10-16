package dev.codeana.adapter;

import com.anatomist.model.*;

import dev.codeana.common.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Verified baseline identity and the semantic records used by an incremental attempt. */
record BaselineDescriptor(
        Map<String, String> contexts,
        Set<String> changedSources,
        List<File> files,
        List<SymbolFact> symbols,
        List<Relation> relations) {
    record File(String path, String world) {}

    record Relation(String sourceFile, TypeRelationFact fact) {}

    static final class Input {
        private final Object raw;
        private final Path scratch;

        Input(Object raw, Path scratch) {
            this.raw = raw;
            this.scratch = scratch;
        }

        BaselineDescriptor read() throws IOException {
            return parse(raw, scratch);
        }
    }

    static BaselineDescriptor parse(Object raw, Path scratch) throws IOException {
        Map<String, Object> summary = raw instanceof Map<?, ?> ? Json.map(raw) : Map.of();
        if (summary.get("path") instanceof String path) {
            Path source = Path.of(path).toRealPath();
            if (!source.startsWith(scratch.toRealPath()))
                throw new IOException("GRAPH_BASELINE_PATH");
            byte[] bytes = Json.read(source);
            if (bytes.length != Json.number(summary, "bytes")
                    || !Json.sha(bytes).equals(Json.string(summary, "sha256")))
                throw new IOException("GRAPH_BASELINE_IDENTITY");
            summary = Json.map(Json.parse(bytes));
        }
        Map<String, String> contexts = new HashMap<>();
        if (summary.get("contexts") instanceof Map<?, ?>)
            Json.map(summary.get("contexts")).forEach((k, v) -> contexts.put(k, (String) v));
        Set<String> changed = new HashSet<>(ContextOptions.strings(summary.get("changed_sources")));
        List<File> files = new ArrayList<>();
        for (Object value : CapturedProject.list(summary.get("files"))) {
            var row = Json.map(value);
            files.add(new File(Json.string(row, "path"), Json.string(row, "world")));
        }
        List<SymbolFact> symbols = new ArrayList<>();
        for (Object value : CapturedProject.list(summary.get("nodes"))) {
            var n = Json.map(value);
            symbols.add(
                    new SymbolFact(
                            Json.string(n, "id"),
                            Json.string(n, "symbol_id"),
                            Json.string(n, "kind"),
                            Json.string(n, "module"),
                            Json.string(n, "scope"),
                            Json.string(n, "source_file"),
                            Objects.toString(n.get("metadata"), "{}")));
        }
        List<Relation> relations = new ArrayList<>();
        for (Object value : CapturedProject.list(summary.get("type_relations"))) {
            var n = Json.map(value);
            relations.add(
                    new Relation(
                            Json.string(n, "source_file"),
                            new TypeRelationFact(
                                    Json.string(n, "source_symbol"),
                                    Json.string(n, "target_symbol"),
                                    Json.string(n, "relation"))));
        }
        return new BaselineDescriptor(
                Map.copyOf(contexts),
                Set.copyOf(changed),
                List.copyOf(files),
                List.copyOf(symbols),
                List.copyOf(relations));
    }
}
