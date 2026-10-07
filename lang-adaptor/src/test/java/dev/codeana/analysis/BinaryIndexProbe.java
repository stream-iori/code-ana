package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Real low-descriptor-pressure lookup; test code never participates in product analysis. */
public final class BinaryIndexProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), javaHome = Path.of(args[1]);
        List<String> jars = new ArrayList<>();
        for (int i = 0; i < 256; i++) jars.add(root.resolve(i + ".jar").toString());
        Map<String, Object> context = Json.object("classpath", jars);
        BinaryIndex index = new BinaryIndex(context, javaHome);
        try (index) {
            // Each class is in a different archive. Revisit after both caches must have evicted it.
            for (int round = 0; round < 2; round++) for (int i = 0; i < 256; i++) {
                String name = "p" + i + ".Api";
                Map<String, Object> result = index.readClass(name);
                Map<String, Object> field = Json.map(Json.map(result.get("members")).get(name + "#MARK"));
                if (!Integer.valueOf(i).equals(field.get("constant"))) throw new AssertionError(name);
                Map<String,Object> members = Json.map(result.get("members"));
                if (!Double.valueOf(0.125).equals(Json.map(members.get(name+"#FRACTION")).get("constant")))
                    throw new AssertionError("finite binary double");
                for (String fieldName : List.of("NAN", "INFINITY"))
                    if (!Json.map(Json.map(members.get(name+"#"+fieldName)).get("constant")).containsKey("non_finite"))
                        throw new AssertionError("tagged binary non-finite");
                // The full declaration crosses JSON/archive boundaries without invalid JSON numbers.
                Json.parse(Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (index.readClass("absent" + i + ".Missing") != null) throw new AssertionError("missing");
            }
            Map<String, Object> duplicate = index.readClass("dup.Api");
            if (!Integer.valueOf(0).equals(Json.map(Json.map(duplicate.get("members")).get("dup.Api#MARK")).get("constant")))
                throw new AssertionError("classpath order");
            Map<String, Object> platform = index.readClass("java.lang.String");
            if (platform == null || !"platform".equals(Json.map(platform.get("artifact_origin")).get("kind")))
                throw new AssertionError("platform provenance");
            Map<String, Object> origin = Json.map(duplicate.get("artifact_origin"));
            if (!Integer.valueOf(0).equals(origin.get("index")) || !Json.sha(root.resolve("0.jar")).equals(origin.get("sha256")))
                throw new AssertionError("exact classpath provenance");
            Thread.currentThread().interrupt();
            try { index.readClass("java.lang.String"); throw new AssertionError("cancel cached lookup"); }
            catch (IOException expected) { if (!"CANCELLED".equals(expected.getMessage())) throw expected; }
            finally { Thread.interrupted(); }
        }
        try { index.readClass("p0.Api"); throw new AssertionError("use after close"); }
        catch (IOException expected) { if (!"BINARY_INDEX_CLOSED".equals(expected.getMessage())) throw expected; }
        Collections.reverse(jars);
        // Shared immutable catalogues must never reuse a consumer's classpath positions/maps.
        try(BinaryCatalogue catalogue=new BinaryCatalogue(); BinaryCatalogue.Scope scope=catalogue.bind()) {
            Map<String,Object> expected;
            try(BinaryIndex first=new BinaryIndex(context,javaHome)) {
                expected=first.readClass("dup.Api");
            }
            long scans=Json.number(catalogue.metrics(),"scans"),hashes=Json.number(catalogue.metrics(),"hashes");
            try(BinaryIndex second=new BinaryIndex(context,javaHome)) {
                if(!Json.write(expected).equals(Json.write(second.readClass("dup.Api"))))
                    throw new AssertionError("shared catalogue changed declarations");
                if(Json.number(catalogue.metrics(),"scans")!=scans || Json.number(catalogue.metrics(),"hashes")!=hashes)
                    throw new AssertionError("shared catalogue rescanned/hashed sealed inputs");
            }
            List<String> forward=new ArrayList<>(jars); Collections.reverse(forward);
            try(BinaryIndex reordered=new BinaryIndex(Json.object("classpath",forward),javaHome)) {
                Map<String,Object> duplicate=reordered.readClass("dup.Api");
                if(!Integer.valueOf(0).equals(Json.map(Json.map(duplicate.get("members")).get("dup.Api#MARK")).get("constant")))
                    throw new AssertionError("shared catalogue lost ordered shadowing");
                if(!Integer.valueOf(0).equals(Json.map(duplicate.get("artifact_origin")).get("index")))
                    throw new AssertionError("shared catalogue leaked producer position");
            }
            Path mutable=root.resolve("mutable.jar");
            java.nio.file.Files.copy(root.resolve("0.jar"),mutable);
            Map<String,Object> mutableContext=Json.object("classpath",List.of(mutable.toString()));
            try(BinaryIndex before=new BinaryIndex(mutableContext,javaHome)) { before.readClass("dup.Api"); }
            java.nio.file.Files.copy(root.resolve("1.jar"),mutable,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.setLastModifiedTime(mutable,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+1000));
            try(BinaryIndex after=new BinaryIndex(mutableContext,javaHome)) {
                if(!Integer.valueOf(1).equals(Json.map(Json.map(after.readClass("dup.Api").get("members")).get("dup.Api#MARK")).get("constant")))
                    throw new AssertionError("changed archive reused stale catalogue");
            }
            Thread.currentThread().interrupt();
            try { new BinaryIndex(context,javaHome);throw new AssertionError("cancel shared catalogue"); }
            catch(IOException expectedFailure) { if(!"CANCELLED".equals(expectedFailure.getMessage()))throw expectedFailure; }
            finally { Thread.interrupted(); }
        }
        try(BinaryCatalogue catalogue=new BinaryCatalogue(0); BinaryCatalogue.Scope scope=catalogue.bind();
                BinaryIndex uncached=new BinaryIndex(context,javaHome)) {
            if(uncached.readClass("dup.Api")==null || Json.number(catalogue.metrics(),"bytes")!=0)
                throw new AssertionError("zero budget lost exact lookup");
        }
        try (BinaryIndex reversed = new BinaryIndex(context, javaHome)) {
            Map<String, Object> duplicate = reversed.readClass("dup.Api");
            if (!Integer.valueOf(1).equals(Json.map(Json.map(duplicate.get("members")).get("dup.Api#MARK")).get("constant")))
                throw new AssertionError("reverse classpath order");
        }
        Thread.currentThread().interrupt();
        try { new BinaryIndex(context, javaHome); throw new AssertionError("cancel catalogue"); }
        catch (IOException expected) { if (!"CANCELLED".equals(expected.getMessage())) throw expected; }
        finally { Thread.interrupted(); }
        List<String> invalid = new ArrayList<>(jars); invalid.add(root.resolve("invalid.jar").toString());
        try { new BinaryIndex(Json.object("classpath", invalid), javaHome); throw new AssertionError("invalid archive"); }
        catch (java.util.zip.ZipException expected) { }
        // Failed construction must not consume the available descriptors either.
        try (BinaryIndex retry = new BinaryIndex(context, javaHome)) {
            if (retry.readClass("p0.Api") == null) throw new AssertionError("retry");
        }
        // Package catalogue exhaustion must preserve exact lookup and classpath precedence.
        try (BinaryIndex fallback = new BinaryIndex(Json.object("classpath", List.of(
                root.resolve("budget.jar").toString(), root.resolve("0.jar").toString())), javaHome)) {
            Map<String, Object> duplicate = fallback.readClass("dup.Api");
            if (!Integer.valueOf(1).equals(Json.map(Json.map(duplicate.get("members")).get("dup.Api#MARK")).get("constant")))
                throw new AssertionError("catalogue fallback order");
            if (fallback.readClass("p0.Api") == null || fallback.readClass("missing.Api") != null)
                throw new AssertionError("catalogue fallback lookup");
        }
        // A valid local stream is still readable when a legacy ZIP64 central directory is rejected.
        try (var direct = new java.util.zip.ZipFile(root.resolve("tolerant.jar").toFile())) {
            throw new AssertionError("fixture did not trigger central directory rejection");
        } catch (java.util.zip.ZipException expected) { }
        try (BinaryIndex tolerant = new BinaryIndex(Json.object("classpath", List.of(
                root.resolve("tolerant.jar").toString(),root.resolve("1.jar").toString())), javaHome)) {
            Map<String,Object> result=tolerant.readClass("dup.Api");
            if(!Integer.valueOf(0).equals(Json.map(Json.map(result.get("members")).get("dup.Api#MARK")).get("constant")))
                throw new AssertionError("tolerant archive lost classpath precedence");
            if(tolerant.readClass("p0.Api")==null || tolerant.readClass("absent.Missing")!=null)
                throw new AssertionError("tolerant archive lookup");
        }
        for (String file : List.of("tolerant-crc.jar","tolerant-bomb.jar")) {
            try (FrozenArchive archive = new FrozenArchive(root.resolve(file))) {
                archive.entries();throw new AssertionError("invalid/bomb local stream accepted");
            } catch (IOException expected) {
                if(file.contains("bomb") && !expected.getMessage().contains("ZIP_DECOMPRESSION_BUDGET"))throw expected;
            }
        }
        FrozenArchive closedArchive=new FrozenArchive(root.resolve("tolerant.jar"));
        var stream=closedArchive.getInputStream(closedArchive.getEntry("dup/Api.class"));closedArchive.close();
        try {stream.read();throw new AssertionError("archive close left a borrowed stream alive");}
        catch(IOException expected) { }
        System.out.println("legacy ZIP64 local-stream lookup/order/CRC/decompression-budget/lifetime: passed");
        System.out.println("256 JAR lookup/eviction/order/platform/cancel/close/retry: passed");
    }
}
