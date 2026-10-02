package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

/** Test-only small-budget cache assembly; all models and cache inputs come from real javac. */
public final class SignatureCacheProbe {
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static Map<String, Object> selected(Map<String, Object> context, Object source) {
        Map<String, Object> result = new LinkedHashMap<>(context);
        result.put("sources", List.of(source));
        return result;
    }
    private static long bytes(Path directory) throws Exception {
        long bytes = 0;
        try (var files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) if (Files.isRegularFile(file)) bytes += Files.size(file);
        }
        return bytes;
    }
    private static Path entry(Path root, String signature) throws Exception {
        try (var directories = Files.list(root)) {
            for (Path directory : (Iterable<Path>) directories::iterator) {
                Path catalogue = directory.resolve("declarations.json");
                if (!Files.isRegularFile(catalogue)) continue;
                for (Object raw : Json.list(Json.map(Json.parse(Json.read(catalogue))).get("declarations")))
                    if (signature.equals(Json.map(raw).get("signature"))) return directory;
            }
        }
        throw new AssertionError("missing cache declaration: " + signature);
    }
    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        Map<String, Object> context = Json.map(Json.list(request.get("contexts")).getFirst());
        List<Object> sources = Json.list(context.get("sources"));
        Path root = Path.of(Json.string(request, "cache_root"));
        String mode = Json.string(request, "cache_test");
        try (CompilerSession session = new CompilerSession(request)) {
            Map<String, Object> model = session.analyze(context);
            require(Json.list(model.get("diagnostics")).isEmpty(), "fixture attribution failed");
            SignatureCache initial = new SignatureCache(request);
            initial.publish(selected(context, sources.get(0)), model);
            initial.publish(selected(context, sources.get(1)), model);
            Path api = entry(root, "Api"), use = entry(root, "Use");
            long budget = bytes(root);
            SignatureCache cache = new SignatureCache(request, budget);
            String checksum;
            Path pinPath;
            try (SignatureCache.Pins pins = cache.pin(session)) {
                SignatureCache.Input pinned = pins.acquire(context, Json.map(sources.get(0)));
                require(pinned != null, "validated input did not pin");
                pinPath = pinned.archive(); checksum = Json.sha(pinPath);
                require(!pinPath.startsWith(root), "pin aliases evictable cache");
                require(!Json.list(pinned.catalogue().get("declarations")).isEmpty(), "missing source catalogue");
                switch (mode) {
                    case "lru" -> {
                        Files.setLastModifiedTime(use, FileTime.fromMillis(1));
                        cache.publish(selected(context, sources.get(2)), model);
                        require(Files.isDirectory(api) && !Files.exists(use), "recent input was evicted before old input");
                        require(cache.evictions() == 1 && bytes(root) <= budget, "LRU did not enforce budget");
                        require(entry(root, "Added") != null, "cache stopped writing at budget");
                        // Evict even the original backing this in-flight pin. Its snapshot survives.
                        Files.setLastModifiedTime(api, FileTime.fromMillis(1));
                        cache.publish(selected(context, sources.get(1)), model);
                        require(!Files.exists(api), "original entry was not evicted");
                        require(Json.sha(pinPath).equals(checksum), "eviction changed in-flight bytes");
                        Map<String, Object> compiler = selected(context, sources.get(1));
                        compiler.put("classpath", List.of(pinPath.toString()));
                        Map<String, Object> consumer = session.analyze(compiler);
                        require(Json.list(consumer.get("diagnostics")).isEmpty(), "javac cannot consume evicted pinned ABI");
                    }
                    case "corrupt" -> {
                        Files.writeString(api.resolve("declarations.json"), "corrupt catalogue");
                        require(pins.acquire(context, Json.map(sources.get(0))) == null, "corrupt proof was reused");
                        require(Json.sha(pinPath).equals(checksum), "shared mutation changed pin");
                        cache.publish(selected(context, sources.get(0)), model);
                        require(pins.acquire(context, Json.map(sources.get(0))) != null, "corrupt cache was not repaired");
                        Path abandoned = Files.createTempDirectory(root, ".signature-");
                        Files.writeString(abandoned.resolve("unfinished"), "partial commit");
                        cache.publish(selected(context, sources.get(0)), model);
                        require(!Files.exists(abandoned), "crashed writer temporary was not collected");
                    }
                    case "concurrent" -> {
                        ExecutorService pool = Executors.newFixedThreadPool(2);
                        try {
                            Future<?> writer = pool.submit(() -> {
                                try {
                                    for (int i = 0; i < 8; i++)
                                        cache.publish(selected(context, sources.get(i % 3)), model);
                                } catch (Exception error) { throw new RuntimeException(error); }
                            });
                            Future<?> reader = pool.submit(() -> {
                                try (SignatureCache.Pins other = cache.pin(session)) {
                                    for (int i = 0; i < 12; i++) {
                                        SignatureCache.Input input = other.acquire(context, Json.map(sources.get(i % 3)));
                                        if (input != null) require(Files.isRegularFile(input.archive()), "published read lacks bytes");
                                    }
                                } catch (Exception error) { throw new RuntimeException(error); }
                            });
                            writer.get(30, TimeUnit.SECONDS); reader.get(30, TimeUnit.SECONDS);
                            require(Json.sha(pinPath).equals(checksum) && cache.writeFailures() == 0,
                                    "concurrent access changed a pin or failed writes");
                            require(bytes(root) <= budget, "concurrent cache exceeds budget");
                        } finally { pool.shutdownNow(); require(pool.awaitTermination(5, TimeUnit.SECONDS), "cache threads leaked"); }
                    }
                    case "cancel" -> {
                        session.cancel();
                        try {
                            pins.acquire(context, Json.map(sources.get(1)));
                            throw new AssertionError("cancelled pin succeeded");
                        } catch (java.io.IOException cancelled) { require(cancelled.getMessage().equals("CANCELLED"), "wrong cancellation"); }
                    }
                    default -> throw new IllegalArgumentException(mode);
                }
            }
            require(!Files.exists(pinPath), "closed pin directory leaked");
            System.out.println(Json.write(Json.object("accepted", true, "mode", mode,
                    "evictions", cache.evictions(), "hits", cache.hits(), "budget", budget)));
        }
    }
}
