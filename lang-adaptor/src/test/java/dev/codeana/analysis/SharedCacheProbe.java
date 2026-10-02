package dev.codeana.analysis;

import dev.codeana.common.*;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

public final class SharedCacheProbe {
    static void require(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
    }

    static long bytes(Path root) throws Exception {
        if (!Files.exists(root)) return 0;
        try (var paths = Files.walk(root)) {
            long n = 0;
            for (Path p : paths.filter(Files::isRegularFile).toList()) n += Files.size(p);
            return n;
        }
    }

    static List<Path> entries(Path root) throws Exception {
        if (!Files.exists(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory).toList();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        List<Object> contexts = Json.list(request.get("contexts"));
        Path files = Path.of(Json.string(request, "cache_root"));
        Path modules = files.resolveSibling(files.getFileName() + "-modules");
        Map<String, Object> lib = Json.map(contexts.get(0)),
                added = Json.map(contexts.get(1)),
                use = Json.map(contexts.get(2));
        try (CompilerSession session =
                new CompilerSession(
                        request,
                        request.get("pin_limit") instanceof Number n
                                ? n.longValue()
                                : 256L << 20)) {
            Map<String, Object> first = session.analyze(lib), next = session.analyze(added);
            SignatureCache warmFiles = new SignatureCache(request);
            warmFiles.publish(lib, first);
            try (ModuleSignatures warmModules = new ModuleSignatures(request, contexts, session)) {
                warmModules.publish(lib, first);
            }
            long budget = bytes(files) + bytes(modules);
            require(entries(files).size() == 1 && entries(modules).size() == 1, "warm entries");
            SignatureCache fileCache = new SignatureCache(request, budget);
            try (ModuleSignatures moduleCache =
                            new ModuleSignatures(request, contexts, session, budget, 256L << 20);
                    SignatureCache.Pins pins = fileCache.pin(session)) {
                SignatureCache.Input filePin =
                        pins.acquire(lib, Json.map(Json.list(lib.get("sources")).getFirst()));
                require(filePin != null, "file pin");
                Map<String, Object> consumer = new LinkedHashMap<>(use);
                List<Object> sources = new ArrayList<>();
                for (Object s : Json.list(lib.get("sources")))
                    sources.add(
                            Json.object(
                                    "path",
                                    Json.map(s).get("path"),
                                    "input",
                                    Json.map(s).get("input"),
                                    "hash",
                                    Json.map(s).get("hash"),
                                    "world",
                                    Json.map(s).get("world"),
                                    "module",
                                    Json.map(s).get("module"),
                                    "scope",
                                    "MAIN",
                                    "publish",
                                    false));
                sources.addAll(Json.list(use.get("sources")));
                consumer.put("sources", sources);
                Path abandonedFile = Files.createDirectory(files.resolve(".signature-abandoned"));
                Path abandonedModule = Files.createDirectory(modules.resolve(".module-abandoned"));
                Files.writeString(abandonedFile.resolve("partial"), "not committed");
                Files.writeString(abandonedModule.resolve("partial"), "not committed");
                try (ModuleSignatures.Lease modulePin = moduleCache.prepare(consumer)) {
                    require(
                            !Files.exists(abandonedFile) && !Files.exists(abandonedModule),
                            "shared lock did not clean abandoned commits");
                    require(modulePin.reduced(), "module pin");
                    String fileHash = Json.sha(filePin.archive()),
                            moduleHash = Json.sha(Path.of((String) modulePin.archives.getFirst()));
                    if (request.get("pin_limit") instanceof Number) {
                        require(session.signaturePinBytes() == budget, "combined pins not charged");
                        require(
                                pins.acquire(
                                                lib,
                                                Json.map(Json.list(lib.get("sources")).getFirst()))
                                        == null,
                                "combined pins did not reject excess");
                    }
                    Path oldModule = entries(modules).getFirst();
                    Files.setLastModifiedTime(oldModule, FileTime.fromMillis(1));
                    fileCache.publish(added, next);
                    require(!Files.exists(oldModule), "file writer did not evict oldest module");
                    require(
                            bytes(files) + bytes(modules) <= budget,
                            "file publish exceeded shared budget");
                    for (Path p : entries(files))
                        Files.setLastModifiedTime(p, FileTime.fromMillis(1));
                    moduleCache.publish(added, next);
                    require(
                            moduleCache.report().get("module_cache_evictions") instanceof Number n
                                    && n.intValue() > 0,
                            "module writer did not evict old file");
                    require(
                            bytes(files) + bytes(modules) <= budget,
                            "module publish exceeded shared budget");
                    require(
                            fileHash.equals(Json.sha(filePin.archive()))
                                    && moduleHash.equals(
                                            Json.sha(
                                                    Path.of(
                                                            (String)
                                                                    modulePin.archives
                                                                            .getFirst()))),
                            "cross-store eviction altered pin");
                    Map<String, Object> consumed = session.analyze(modulePin.compiler);
                    require(
                            Json.list(consumed.get("diagnostics")).isEmpty()
                                    && modulePin.restore(consumed),
                            "evicted module cannot compile/restore");
                    session.releaseModel(consumed);
                }
            }
            require(session.signaturePinBytes() == 0, "closed combined pins not released");
            session.releaseModel(first);
            session.releaseModel(next);
            System.out.println(
                    Json.write(
                            Json.object(
                                    "accepted",
                                    true,
                                    "budget",
                                    budget,
                                    "persisted",
                                    bytes(files) + bytes(modules))));
        }
    }
}
