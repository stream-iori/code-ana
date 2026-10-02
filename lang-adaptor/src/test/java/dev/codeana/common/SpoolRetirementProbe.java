package dev.codeana.common;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Source retirement must release original owners while keeping readable, appendable model rows. */
public final class SpoolRetirementProbe {
    private static void noCompilerOwners(ModelArchive.RowSpool spool) throws Exception {
        Field encoderField = spool.getClass().getDeclaredField("encoder");
        encoderField.setAccessible(true);
        Object encoder = encoderField.get(spool);
        Field graphsField = encoder.getClass().getDeclaredField("graphs");
        graphsField.setAccessible(true);
        Object graphs = graphsField.get(encoder);
        Field identitiesField = graphs.getClass().getDeclaredField("identities");
        identitiesField.setAccessible(true);
        if (!((Map<?,?>) identitiesField.get(graphs)).isEmpty())
            throw new AssertionError("original compiler graph owner retained");
    }

    private static String row(ModelArchive.RowSpool spool, int index) {
        return Json.write(TypeEvidence.encode(Json.map(spool.get(index))));
    }

    private static void rejectsRetirement(ModelArchive.RowSpool spool, String reason) {
        try {
            spool.retireCompilerIdentities();
            throw new AssertionError(reason + " spool accepted retirement");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().equals("MODEL_SPOOL_" + reason)) throw expected;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("spool-retirement-");
        try {
            try (ModelArchive.RowSpool spool = new ModelArchive.RowSpool(root, "proof", 65536)) {
                TypeGraph graph = new TypeGraph(Arrays.asList(Json.object("kind", "DECLARED",
                        "complete", true, "element", "A", "arguments", Collections.emptyList())));
                spool.add(Json.object("type", graph.view(0)));
                spool.retireCompilerIdentities();
                noCompilerOwners(spool);
                String before = row(spool, 0);
                spool.retireCompilerIdentities();
                spool.add(Json.object("type", graph.view(0)));
                spool.retireCompilerIdentities();
                noCompilerOwners(spool);
                if (spool.size() != 2 || !before.equals(row(spool, 1)))
                    throw new AssertionError("retired graph rows changed after append");
                spool.add(Json.object("type", Json.object("version", 2, "kind", "INT", "complete", true)));
                spool.retireCompilerIdentities();
                if (!"INT".equals(Json.map(Json.map(spool.get(2)).get("type")).get("kind")))
                    throw new AssertionError("legacy tree row changed");
                spool.seal();
                rejectsRetirement(spool, "SEALED");
                spool.close();
                rejectsRetirement(spool, "CLOSED");
            }
            try (java.util.stream.Stream<Path> files = Files.list(root)) {
                if (files.count() != 0) throw new AssertionError("row spool leaked files");
            }
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                });
            }
        }
        System.out.println("source retirement preserves graph/tree rows, append, lifecycle and cleanup");
    }
}
