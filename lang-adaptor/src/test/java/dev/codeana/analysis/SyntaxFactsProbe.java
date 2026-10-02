package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.RecordArchive;

import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** A file larger than the IPC frame remains lossless without retaining its decoded syntax. */
public final class SyntaxFactsProbe {
    public static void main(String[] args) throws Exception {
        int count = 6000;
        String payload = "汉字".repeat(700);
        List<Object> nodes =
                new AbstractList<>() {
                    @Override
                    public int size() {
                        return count;
                    }

                    @Override
                    public Object get(int index) {
                        return Json.object(
                                "id",
                                "n" + index,
                                "parent",
                                index == 0 ? "" : "n" + (index - 1),
                                "source",
                                "Large.java",
                                "payload",
                                payload);
                    }
                };
        Map<String, Object> context =
                Json.object(
                        "world",
                        "w",
                        "sources",
                        List.of(
                                Json.object(
                                        "path",
                                        "Large.java",
                                        "hash",
                                        "f".repeat(64),
                                        "publish",
                                        true)));
        Map<String, Object> model = Json.object("syntax", nodes);
        try (RecordArchive archive =
                RecordArchive.create(
                        Path.of(args[0]).resolve("syntax.bin"), 64L * 1024 * 1024, 1000)) {
            Map<String, Object>[] pending = new Map[] {null};
            SyntaxFacts.emit(
                    context,
                    model,
                    (id, path) -> {
                        if (pending[0] != null) archive.add(pending[0]);
                        pending[0] = Json.object("id", id, "source", path);
                        return pending[0];
                    });
            archive.add(pending[0]);
            archive.seal();
            if (archive.bytes() <= 24L * 1024 * 1024 || archive.size() < 20)
                throw new AssertionError("large-file fixture did not exercise partitioning");
            int offset = 0;
            for (int i = 0; i < archive.size(); i++) {
                Map<String, Object> part = Json.map(archive.get(i));
                Map<String, Object> attributes = Json.map(part.get("attributes"));
                if (Json.number(attributes, "chunk_index") != i
                        || Json.number(attributes, "node_offset") != offset
                        || Json.number(attributes, "total_nodes") != count
                        || !Boolean.valueOf(i == archive.size() - 1)
                                .equals(attributes.get("last_chunk")))
                    throw new AssertionError("partition metadata");
                for (Object raw : Json.list(attributes.get("nodes"))) {
                    if (!nodes.get(offset++).equals(raw))
                        throw new AssertionError("lost or changed node");
                }
            }
            if (offset != count) throw new AssertionError("lost nodes");
            Thread.currentThread().interrupt();
            try {
                SyntaxFacts.emit(
                        context,
                        model,
                        (id, path) -> {
                            throw new AssertionError("emitted after cancellation");
                        });
                throw new AssertionError("cancellation ignored");
            } catch (CancellationException expected) {
                // Cancellation must stop before a partition is emitted.
            } finally {
                Thread.interrupted();
            }
            System.out.println(
                    "syntax: "
                            + count
                            + " nodes / "
                            + archive.bytes()
                            + " bytes / "
                            + archive.size()
                            + " bounded parts; cancellation passed");
        }
    }
}
