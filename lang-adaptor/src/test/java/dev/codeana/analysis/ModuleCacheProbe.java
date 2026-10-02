package dev.codeana.analysis;

import dev.codeana.common.*;

import java.nio.file.*;
import java.util.*;

public final class ModuleCacheProbe {
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static long bytes(Path dir) throws Exception {
        long n = 0;
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) n += Files.size(file);
        }
        return n;
    }

    static long weight(Map<String, Object> model) {
        long n = 0;
        for (String section : List.of("declarations", "external", "annotation_types"))
            for (Object row : Json.list(model.get(section)))
                n += 4L * Json.write(row).length() + 256;
        return n;
    }

    static Map<String, Object> consumer(Map<String, Object> parent, Map<String, Object> client) {
        Map<String, Object> result = new LinkedHashMap<>(client);
        List<Object> sources = new ArrayList<>(Json.list(parent.get("sources")));
        sources.addAll(Json.list(client.get("sources")));
        result.put("sources", sources);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        List<Object> contexts = Json.list(request.get("contexts"));
        Path root = Path.of(Json.string(request, "cache_root") + "-modules");
        List<Map<String, Object>> models = new ArrayList<>();
        try (CompilerSession session = new CompilerSession(request)) {
            try (ModuleSignatures initial = new ModuleSignatures(request, contexts, session)) {
                for (int i = 0; i < 4; i++) {
                    Map<String, Object> context = Json.map(contexts.get(i));
                    Map<String, Object> model = session.analyze(context);
                    models.add(model);
                    initial.publish(context, model);
                }
            }
            List<Path> entries;
            try (var dirs = Files.list(root)) {
                entries = dirs.filter(Files::isDirectory).toList();
            }
            require(entries.size() == 4, "four persisted modules");
            long budget = 1;
            for (Path entry : entries)
                budget = Math.max(budget, Files.size(entry.resolve("catalogue.bin")) * 8);
            for (var model : models) budget = Math.max(budget, weight(model));
            Map<String, Object> client = Json.map(contexts.get(4));
            try (ModuleSignatures cache =
                    new ModuleSignatures(request, contexts, session, 256L << 20, budget)) {
                for (int round = 0; round < 2; round++)
                    for (int i = 0; i < 4; i++) {
                        Map<String, Object> context = consumer(Json.map(contexts.get(i)), client);
                        try (ModuleSignatures.Lease lease = cache.prepare(context)) {
                            require(lease.reduced(), "reload did not reduce input");
                            Map<String, Object> model = session.analyze(lease.compiler);
                            require(
                                    Json.list(model.get("diagnostics")).isEmpty(),
                                    "NoCode attribution failed");
                            require(lease.restore(model), "SOURCE restore failed");
                            session.releaseModel(model);
                        }
                    }
                require(
                        Json.number(cache.report(), "module_catalogue_evictions") > 0,
                        "no LRU eviction");
                require(
                        Json.number(cache.report(), "module_catalogue_weight") <= budget,
                        "catalogue budget exceeded");
                // Hold one catalogue while admitting others. The held source/JAR and headers stay
                // usable.
                try (ModuleSignatures.Lease held =
                        cache.prepare(consumer(Json.map(contexts.get(0)), client))) {
                    require(held.reduced(), "failed initial lease");
                    String checksum = Json.sha(Path.of((String) held.archives.getFirst()));
                    for (int i = 1; i < 4; i++)
                        try (ModuleSignatures.Lease next =
                                cache.prepare(consumer(Json.map(contexts.get(i)), client))) {
                            require(
                                    Json.number(cache.report(), "module_catalogue_weight")
                                            <= budget,
                                    "pinned budget exceeded");
                        }
                    // Evict shared backing files; admission already copied and validated task-owned
                    // inputs.
                    try (var files = Files.walk(root)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                            if (!file.equals(root)
                                    && !file.getFileName().toString().equals(".lock"))
                                Files.delete(file);
                    }
                    require(
                            checksum.equals(Json.sha(Path.of((String) held.archives.getFirst()))),
                            "shared eviction invalidated pin");
                    Map<String, Object> model = session.analyze(held.compiler);
                    require(held.restore(model), "eviction invalidated catalogue");
                    session.releaseModel(model);
                    Thread.currentThread().interrupt();
                    try {
                        cache.prepare(consumer(Json.map(contexts.get(1)), client));
                        throw new AssertionError("cancellation swallowed");
                    } catch (java.io.IOException expected) {
                        require("CANCELLED".equals(expected.getMessage()), "wrong cancellation");
                    } finally {
                        Thread.interrupted();
                    }
                }
            }
            for (var model : models) session.releaseModel(model);
        }
        System.out.println(Json.write(Json.object("accepted", true)));
    }
}
