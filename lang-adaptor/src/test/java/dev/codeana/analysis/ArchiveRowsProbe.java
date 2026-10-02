package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.common.*;

import java.nio.file.*;
import java.util.*;

public final class ArchiveRowsProbe {
    interface Checked {
        void run() throws Exception;
    }

    static void reject(Checked r) throws Exception {
        try {
            r.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("accepted mutable/closed view");
    }

    static Map<String, Object> attrs(List<Object> rows, int index) {
        return Json.map(Json.map(rows.get(index)).get("attributes"));
    }

    static void same(Object a, Object b) {
        if (!Json.write(a).equals(Json.write(b))) throw new AssertionError("row equivalence");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        spilledSnapshots(root);
        Path file = root.resolve("rows.bin");
        Map<String, Object> model = Json.object("version", 6, "context", "rows");
        for (String section :
                List.of(
                        "sources",
                        "declarations",
                        "observations",
                        "syntax",
                        "external",
                        "annotation_types",
                        "diagnostics")) model.put(section, new ArrayList<>());
        for (int i = 0; i < 300; i++)
            Json.list(model.get("declarations"))
                    .add(
                            Json.object(
                                    "id",
                                    i,
                                    "attributes",
                                    Json.object(
                                            "n",
                                            i,
                                            "decimal",
                                            0.125,
                                            "children",
                                            new ArrayList<>(
                                                    List.of(
                                                            Json.object("value", 1),
                                                            Json.object("value", 2))),
                                            "type",
                                            Json.object(
                                                    "version",
                                                    2,
                                                    "kind",
                                                    "DECLARED",
                                                    "element",
                                                    "X"))));
        ModelArchive.write(file, model, 1048576);
        String checksum = Json.sha(file);
        Map<String, Object> dense = ModelArchive.read(file, checksum, 1048576, "rows");
        ModelArchive.OpenModel open = ModelArchive.open(file, checksum, 1048576, "rows");
        List<Object> rows = Json.list(open.model().get("declarations")),
                expected = Json.list(dense.get("declarations"));
        if (!(rows instanceof ArchiveRows)) throw new AssertionError("not lazy");
        same(rows, expected);
        Map<String, Object> first = attrs(rows, 0);
        Object type = first.get("type");
        List<Object> snapshot = Json.list(ExtensionContext.freeze(rows));
        for (int i = 1; i < 300; i++) attrs(rows, i).get("n");
        System.gc();
        first.put("n", 999);
        attrs(expected, 0).put("n", 999);
        same(rows, expected);
        if (((Number) attrs(snapshot, 0).get("n")).intValue() != 0)
            throw new AssertionError("callback saw later writes");
        reject(() -> attrs(snapshot, 0).put("n", 2));
        reject(() -> Json.map(type).put("element", "Y"));
        List<Object> children = Json.list(first.get("children"));
        Map<String, Object> child = Json.map(children.get(0));
        children.remove(0);
        child.put("detached", true);
        Json.list(attrs(expected, 0).get("children")).remove(0);
        same(rows, expected);
        if (Json.map(children.get(0)).containsKey("detached"))
            throw new AssertionError("list alias shifted");
        Json.map(rows.get(1)).put("alias", first);
        Json.map(expected.get(1)).put("alias", attrs(expected, 0));
        Json.map(Json.map(rows.get(1)).get("alias")).put("shared", true);
        attrs(expected, 0).put("shared", true);
        same(rows, expected);
        Map<String, Object> alias = first;
        Json.map(rows.get(0)).put("attributes", new LinkedHashMap<>(Map.of("replacement", true)));
        Json.map(expected.get(0))
                .put("attributes", new LinkedHashMap<>(Map.of("replacement", true)));
        alias.put("after_replacement", true);
        Json.map(Json.map(expected.get(1)).get("alias")).put("after_replacement", true);
        same(rows, expected);
        for (var entry : attrs(rows, 2).entrySet())
            if (entry.getKey().equals("n")) entry.setValue(33);
        attrs(expected, 2).put("n", 33);
        same(rows, expected);
        for (var iterator = attrs(rows, 3).keySet().iterator(); iterator.hasNext(); )
            if (iterator.next().equals("decimal")) iterator.remove();
        attrs(expected, 3).remove("decimal");
        same(rows, expected);
        rows.add(0, Json.object("id", -1, "attributes", new LinkedHashMap<>()));
        expected.add(0, Json.object("id", -1, "attributes", new LinkedHashMap<>()));
        same(rows, expected);
        rows.removeIf(v -> ((Number) Json.map(v).get("id")).longValue() % 13 == 0);
        expected.removeIf(v -> ((Number) Json.map(v).get("id")).longValue() % 13 == 0);
        same(rows, expected);
        rows.set(0, Json.object("id", -2));
        expected.set(0, Json.object("id", -2));
        same(rows, expected);
        Path updated = root.resolve("updated.bin");
        ModelArchive.write(updated, open.model(), 1048576);
        same(open.model(), ModelArchive.read(updated, Json.sha(updated), 1048576, "rows"));
        open.close();
        reject(() -> rows.get(1));
        reject(() -> first.get("n"));
        reject(() -> children.size());
        reject(() -> snapshot.get(0));
        System.out.println(
                "lazy row mutation/eviction/alias/entry/list/snapshot/archive/lifetime: passed");
    }

    private static void spilledSnapshots(Path root) throws Exception {
        Map<String, Object> model = Json.object("version", 6, "context", "spilled");
        for (String section : List.of("sources", "declarations", "observations", "syntax", "external", "annotation_types", "diagnostics"))
            model.put(section, new ArrayList<>());
        Json.list(model.get("declarations")).add(Json.object("attributes", Json.object("n", 0)));
        Path file = root.resolve("spilled.bin");
        ModelArchive.write(file, model, 1L << 20);
        List<Object> snapshot;
        try (ModelArchive.OpenModel opened = ModelArchive.open(file, Json.sha(file), 1L << 20, "spilled")) {
            List<Object> rows = Json.list(opened.model().get("declarations"));
            Map<String, Object> alias = Json.object("value", "before");
            attrs(rows, 0).put("alias", alias);
            Map<String, Object> appended = Json.object("attributes", Json.object("nested", new ArrayList<>(List.of(alias))));
            rows.add(appended);
            String captured = Json.write(rows);
            snapshot = Json.list(ExtensionContext.freeze(rows));
            alias.put("value", "after");
            attrs(rows, 0).put("n", 1);
            Json.list(Json.map(appended.get("attributes")).get("nested")).clear();
            rows.remove(0);
            if (!Json.write(snapshot).equals(captured))
                throw new AssertionError("spilled snapshot changed through an edited/appended alias");
            reject(() -> Json.map(attrs(snapshot, 0).get("alias")).put("value", "forged"));
            reject(() -> Json.list(attrs(snapshot, 1).get("nested")).clear());
            attrs(rows, 0).put("oversize", "x".repeat(2 << 20));
            reject(() -> ExtensionContext.freeze(rows));
            try {
                Thread.currentThread().interrupt();
                reject(() -> ExtensionContext.freeze(rows));
            } finally { Thread.interrupted(); }
            if (!Json.write(snapshot).equals(captured)) throw new AssertionError("failed snapshot damaged an earlier snapshot");
        }
        reject(() -> snapshot.get(0));
        try (var paths = Files.list(root)) {
            if (paths.anyMatch(path -> path.getFileName().toString().startsWith("model-rows-snapshot-")))
                throw new AssertionError("snapshot spool leaked on model close or failed capture");
        }
    }
}
