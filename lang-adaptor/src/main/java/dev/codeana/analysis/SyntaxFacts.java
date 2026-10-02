package dev.codeana.analysis;

import dev.codeana.common.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/** Source-owned syntax partitions; every original node and cross-part parent ID is preserved. */
final class SyntaxFacts {
    private static final int TARGET_BYTES = 1024 * 1024;
    private static final int MAX_NODE_BYTES = RecordArchive.MAX_RECORD_BYTES - 65536;

    static void emit(
            Map<String, Object> context,
            Map<String, Object> model,
            BiFunction<String, String, Map<String, Object>> fact) {
        Map<String, String> hashes = new HashMap<>();
        for (Object raw : Json.list(context.get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            if (Boolean.TRUE.equals(source.get("publish")))
                hashes.put(Json.string(source, "path"), Json.string(source, "hash"));
        }
        List<Object> syntax = Json.list(model.get("syntax"));
        Map<String, List<Integer>> bySource = new TreeMap<>();
        for (int i = 0; i < syntax.size(); i++) {
            String source = Json.string(Json.map(syntax.get(i)), "source");
            if (hashes.containsKey(source))
                bySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(i);
        }
        String world = Json.string(context, "world");
        for (var entry : bySource.entrySet()) {
            String path = entry.getKey();
            int part = 0, offset = 0, bytes = 0;
            boolean partitioned = false;
            List<Object> nodes = new ArrayList<>();
            for (int position : entry.getValue()) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("CANCELLED");
                Object node = syntax.get(position);
                // Type roots can be shared or recursive; measure the same closed graph
                // representation used by fact delivery instead of expanding lazy views.
                int length = Json.write(TypeEvidence.encode(Json.object("node", node)))
                        .getBytes(StandardCharsets.UTF_8).length;
                if (length > MAX_NODE_BYTES)
                    throw new IllegalArgumentException(
                            "SYNTAX_NODE_BUDGET source="
                                    + path
                                    + " bytes="
                                    + length
                                    + " limit="
                                    + MAX_NODE_BYTES);
                if (!nodes.isEmpty() && length > TARGET_BYTES - bytes) {
                    partitioned = true;
                    emitPart(
                            fact,
                            world,
                            path,
                            hashes.get(path),
                            nodes,
                            part++,
                            offset,
                            entry.getValue().size(),
                            false);
                    offset += nodes.size();
                    nodes = new ArrayList<>();
                    bytes = 0;
                }
                nodes.add(node);
                bytes += length + 1;
            }
            if (partitioned)
                emitPart(
                        fact,
                        world,
                        path,
                        hashes.get(path),
                        nodes,
                        part,
                        offset,
                        entry.getValue().size(),
                        true);
            else {
                Map<String, Object> row = fact.apply(Json.key("syntax", world, path), path);
                row.put("relation", "java.syntax");
                row.put("attributes", Json.object("schema_version", 1, "nodes", nodes));
            }
        }
    }

    private static void emitPart(
            BiFunction<String, String, Map<String, Object>> factory,
            String world,
            String path,
            String hash,
            List<Object> nodes,
            int part,
            int offset,
            int total,
            boolean last) {
        Map<String, Object> row =
                factory.apply(Json.key("syntax", world, path, Integer.toString(part)), path);
        row.put("relation", "java.syntax");
        row.put(
                "attributes",
                Json.object(
                        "schema_version",
                        2,
                        "source_hash",
                        hash,
                        "chunk_index",
                        part,
                        "node_offset",
                        offset,
                        "total_nodes",
                        total,
                        "last_chunk",
                        last,
                        "nodes",
                        nodes));
    }
}
