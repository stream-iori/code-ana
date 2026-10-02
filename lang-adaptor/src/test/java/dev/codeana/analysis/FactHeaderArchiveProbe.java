package dev.codeana.analysis;

import dev.codeana.common.*;

import java.nio.file.*;
import java.util.*;

/** Also compiled and executed with the shipped Java 8 bridge classes. */
public final class FactHeaderArchiveProbe {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Map<String, Object> fact(int index, String payload) {
        return Json.object(
                "id",
                "id" + index,
                "kind",
                "declaration",
                "signature",
                "Api#field" + index,
                "source",
                "src/Api.java",
                "owner",
                Json.object("world", "app"),
                "attributes",
                Json.object("generated", index % 2 == 0, "payload", payload));
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Map<String, Object> first = fact(0, "original");
        long budget = 8 + Json.write(FactHeaders.project(first)).getBytes("UTF-8").length;
        Path path = root.resolve("limited.bin"), sidecar = root.resolve("limited.bin.headers");
        String checksum;
        try (RecordArchive rows = RecordArchive.createFacts(path, 1L << 20, 10, budget)) {
            rows.add(first);
            first.put("id", "mutated");
            for (int i = 1; i < 10; i++) rows.add(fact(i, "after quota"));
            rows.seal();
            require(Files.size(sidecar) == budget, "header quota not applied");
            for (int i = 0; i < rows.size(); i++)
                require(
                        rows.factHeader(i).equals(FactHeaders.project(rows.get(i))),
                        "prefix/fallback mismatch");
            require(rows.factHeader(0).get("id").equals("id0"), "caller mutation changed header");
            try {
                rows.factHeader(0).put("id", "bad");
                throw new AssertionError("mutable metadata");
            } catch (UnsupportedOperationException expected) {
            }
            try {
                rows.factHeader(10);
                throw new AssertionError("invalid index accepted");
            } catch (IndexOutOfBoundsException expected) {
            }
            Thread.currentThread().interrupt();
            try {
                rows.factHeader(0);
                throw new AssertionError("cancel ignored");
            } catch (java.io.UncheckedIOException expected) {
                require(expected.getCause().getMessage().equals("CANCELLED"), "wrong cancel");
            } finally {
                Thread.interrupted();
            }
            require(rows.factKind(9).equals("declaration") && rows.factWorld(9).equals("app"), "routing fallback");
            Set<Long> selected = Collections.singleton(FactHeaders.identityHash("id9"));
            for (int i = 0; i < rows.size(); i++)
                require(rows.mightHaveId(i, selected) == (i == 9), "identity routing");
            checksum = Json.sha(path);
        }
        require(!Files.exists(sidecar), "header sidecar leaked");
        try (RecordArchive reopened = RecordArchive.open(path, 1L << 20, 10, checksum)) {
            for (int i = 0; i < 10; i++) {
                require(reopened.factKind(i).equals("declaration") && reopened.factWorld(i).equals("app"), "reopened routing");
                require(
                        reopened.factHeader(i).equals(FactHeaders.project(reopened.get(i))),
                        "CAF1 reopen mismatch");
            }
        }
        Path collision = root.resolve("collision.bin"),
                sentinel = root.resolve("collision.bin.headers");
        Files.write(sentinel, "untouched".getBytes("UTF-8"));
        try {
            RecordArchive.createFacts(collision, 1L << 20, 10);
            throw new AssertionError("collision accepted");
        } catch (FileAlreadyExistsException expected) {
        }
        require(!Files.exists(collision), "failed creation leaked primary archive");
        require(
                new String(Files.readAllBytes(sentinel), "UTF-8").equals("untouched"),
                "existing sidecar overwritten");

        char[] chars = new char[32768];
        Arrays.fill(chars, 'a');
        String payload = new String(chars);
        try (RecordArchive rows =
                RecordArchive.createFacts(root.resolve("large.bin"), 256L << 20, 6000)) {
            for (int i = 0; i < 6000; i++) rows.add(fact(i, payload));
            rows.seal();
            require(rows.bytes() > (180L << 20), "fixture too small");
            for (int i = 0; i < 6000; i++)
                require(rows.factHeader(i).get("id").equals("id" + i), "large index lost row");
            try (RecordArchive copy = RecordArchive.createFacts(root.resolve("copy.bin"), 256L << 20, 6000, 4)) {
                copy.addAll(rows); copy.seal();
                require(copy.size() == rows.size() && copy.bytes() == rows.bytes(), "copy changed multiplicity/size");
                require(Json.sha(copy.path()).equals(Json.sha(rows.path())), "copy changed encoded bytes");
                require(copy.factHeader(5999).equals(rows.factHeader(5999)), "copy fallback lost header");
                require(copy.factKind(5999).equals("declaration") && copy.factWorld(5999).equals("app"), "copy routing");
                require(copy.mightHaveId(5999, Collections.singleton(FactHeaders.identityHash("id5999"))), "copy identity routing");
                Path output = root.resolve("encoded-output.json");
                long encodedBytes = Json.writeTo(output, Json.object("facts", copy), 256L << 20);
                require(encodedBytes == copy.bytes() - 4L - 4L * copy.size() + copy.size() - 1L + 12,
                        "encoded output changed length");
                System.out.println("accepted: strict encoded 197 MB JSON output under 32 MiB heap");
            }
            FactHeaders.View reverse = new FactHeaders.View() {
                public int size() { return rows.size(); }
                public Object get(int index) { throw new AssertionError("copy decoded large graph"); }
                public Map<String,Object> factHeader(int index) { return rows.factHeader(5999-index); }
                public void copyRecord(int index, RecordArchive out) { rows.copyRecord(5999-index,out); }
            };
            try (RecordArchive copy = RecordArchive.createFacts(root.resolve("reverse.bin"), 256L << 20, 6001, 4)) {
                copy.addAll(FactHeaders.joined(reverse, Arrays.<Object>asList(fact(6000,"appended"))));
                require(copy.size()==6001 && copy.factHeader(0).get("id").equals("id5999")
                        && copy.factHeader(6000).get("id").equals("id6000"), "view copy changed order");
            }
            try (RecordArchive limited = RecordArchive.create(root.resolve("copy-limit.bin"), 4, 0)) {
                try { rows.copyRecord(0,limited); throw new AssertionError("copy ignored budget"); }
                catch (java.io.UncheckedIOException expected) { require(limited.size()==0 && limited.bytes()==4,"failed copy committed"); }
                Thread.currentThread().interrupt();
                try { rows.copyRecord(0,limited); throw new AssertionError("copy ignored cancellation"); }
                catch (java.io.UncheckedIOException expected) { require(expected.getCause().getMessage().equals("CANCELLED"),"copy cancellation"); }
                finally { Thread.interrupted(); }
            }
            try { rows.addAll(rows); throw new AssertionError("self copy accepted"); }
            catch (IllegalArgumentException expected) {}
            System.out.println(
                    "accepted: 6000 facts / "
                            + rows.bytes()
                            + " bytes under 32 MiB; prefix, fallback, immutable headers,"
                            + " cancellation and cleanup");
        }
        require(!Files.exists(root.resolve("large.bin.headers")), "large sidecar leaked");
        try (RecordArchive rows = RecordArchive.createFacts(root.resolve("routing.bin"), 4L << 20, 5000, 4)) {
            for (int i = 0; i < 5000; i++) {
                Map<String,Object> f = fact(i, "");
                f.put("kind", i == 4999 ? "custom-kind" : "call_target");
                Json.map(f.get("owner")).put("world", "世界" + i);
                rows.add(f);
            }
            rows.seal();
            for (int i = 0; i < rows.size(); i++) {
                require(rows.factKind(i).equals(i == 4999 ? "custom-kind" : "call_target"), "unknown kind lost");
                require(rows.factWorld(i).equals("世界" + i), "world dictionary quota lost fact");
            }
            List<Object> joined = FactHeaders.joined(rows, Arrays.<Object>asList(fact(5000, "")));
            require(FactHeaders.kind(joined, 4999).equals("custom-kind") && FactHeaders.world(joined, 5000).equals("app"), "view routing lost");
            Thread.currentThread().interrupt();
            try { rows.factKind(0); throw new AssertionError("routing ignored cancel"); }
            catch (java.io.UncheckedIOException expected) {}
            finally { Thread.interrupted(); }
            try { rows.factWorld(5000); throw new AssertionError("routing ignored range"); }
            catch (IndexOutOfBoundsException expected) {}
        }
        RecordArchive closed = RecordArchive.createFacts(root.resolve("closed-routing.bin"), 1024, 1, 4);
        closed.add(fact(0, "")); closed.close();
        try { closed.factKind(0); throw new AssertionError("routing ignored close"); }
        catch (IllegalStateException expected) {}
        System.out.println("accepted: compact routing, 5000 worlds / bounded fallback, custom kinds, copies and views");
    }
}
