package dev.codeana.analysis;

import dev.codeana.common.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.*;

/** Verified, task-pinned module NoCode inputs. All failures leave sealed SOURCE available. */
final class ModuleSignatures implements AutoCloseable {
    private static final String FORMAT = "java8-module-signature-v5-model6-original-callbacks";
    private static final long MAX_PART = 64L << 20,
            MAX_DISK = 256L << 20,
            MAX_CATALOGUE_WEIGHT = 256L << 20;
    private static final ReentrantLock LOCAL_LOCK = SignatureStores.LOCK;
    private final CompilerSession session;
    private final long maxDisk, maxCatalogueWeight;
    private final Path root, pins, fileRoot;
    private final String profile, bridgeHash;
    private final Map<String, Map<String, Object>> worlds = new HashMap<>();
    private final Map<Path, String> inputHashes = new HashMap<>();
    private final Map<String, List<String>> fallback = new TreeMap<>();
    private int hits, published, used, evictions, catalogueEvictions;
    private long catalogueWeight, pinBytes;
    private final LinkedHashMap<String, Catalogue> decoded = new LinkedHashMap<>(16, .75f, true);

    private static final class Catalogue {
        final ModelArchive.OpenModel model;
        final long weight;
        int users;

        Catalogue(ModelArchive.OpenModel model, long weight) {
            this.model = model;
            this.weight = weight;
        }
    }

    private boolean closed;
    private final Set<String> sourceOnly = new HashSet<>();
    private final Map<String, Pinned> taskPins = new HashMap<>();

    private record Pinned(Path directory, String checksum, String context) {}

    ModuleSignatures(Map<String, Object> request, List<Object> contexts, CompilerSession session)
            throws IOException {
        this(request, contexts, session, MAX_DISK, MAX_CATALOGUE_WEIGHT);
    }

    ModuleSignatures(
            Map<String, Object> request,
            List<Object> contexts,
            CompilerSession session,
            long maxDisk,
            long maxCatalogueWeight)
            throws IOException {
        if (maxDisk < 1
                || maxDisk > MAX_DISK
                || maxCatalogueWeight < 1
                || maxCatalogueWeight > MAX_CATALOGUE_WEIGHT)
            throw new IllegalArgumentException("MODULE_CACHE_BUDGET");
        this.maxDisk = maxDisk;
        this.maxCatalogueWeight = maxCatalogueWeight;
        this.session = session;
        boolean enabled =
                Boolean.TRUE.equals(request.get("signature_cache"))
                        && request.get("cache_root") instanceof String
                        && request.get("profile") instanceof String;
        Path file = enabled ? Path.of(Json.string(request, "cache_root")) : null;
        fileRoot = file;
        root = enabled ? file.resolveSibling(file.getFileName() + "-modules") : null;
        pins = enabled ? session.newInputDirectory("module-signatures-") : null;
        profile = enabled ? Json.string(request, "profile") : "";
        bridgeHash = enabled ? Json.sha(Path.of(Json.string(request, "bridge"))) : "";
        for (Object raw : contexts) {
            Map<String, Object> ctx = Json.map(raw);
            worlds.put(Json.string(ctx, "world"), ctx);
        }
    }

    boolean enabled() {
        return root != null;
    }

    Map<String, Object> report() {
        return Json.object(
                "module_signature_hits",
                hits,
                "module_signatures_published",
                published,
                "module_archives_used",
                used,
                "module_cache_evictions",
                evictions,
                "module_catalogue_evictions",
                catalogueEvictions,
                "module_catalogue_weight",
                catalogueWeight,
                "module_pin_bytes",
                pinBytes,
                "module_signature_fallbacks",
                fallback);
    }

    private void reason(Map<String, Object> ctx, String why) {
        fallback.computeIfAbsent(Json.string(ctx, "context"), ignored -> new ArrayList<>())
                .add(why);
    }

    private static List<Object> ownSources(Map<String, Object> ctx) {
        List<Object> own = new ArrayList<>();
        String world = Json.string(ctx, "world");
        for (Object raw : Json.list(ctx.get("sources")))
            if (world.equals(Json.map(raw).get("world"))) own.add(raw);
        return own;
    }

    private static List<Object> proofSources(Map<String, Object> ctx) {
        List<Object> proof = new ArrayList<>();
        for (Object raw : Json.list(ctx.get("sources"))) {
            Map<String, Object> s = Json.map(raw);
            proof.add(List.of(s.get("path"), s.get("hash"), s.get("world")));
        }
        return proof;
    }

    private String inputHash(Path file) throws IOException {
        session.check();
        String hash = inputHashes.get(file);
        if (hash == null) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("SIGNATURE_INPUT_NOT_REGULAR");
            hash = Json.sha(file);
            inputHashes.put(file, hash);
        }
        return hash;
    }

    private String key(Map<String, Object> ctx) throws IOException {
        List<Object> cp = new ArrayList<>();
        for (Object raw : Json.list(ctx.get("classpath"))) {
            Path file = Path.of((String) raw);
            cp.add(List.of(file.getFileName().toString(), inputHash(file)));
        }
        return Json.sha(
                Json.write(
                                List.of(
                                        FORMAT,
                                        profile,
                                        bridgeHash,
                                        session.javaHome().toString(),
                                        ctx.get("module"),
                                        ctx.get("world"),
                                        ctx.get("digest"),
                                        Boolean.TRUE.equals(ctx.get("type_graph")),
                                        ctx.getOrDefault("extension_fingerprint", ""),
                                        proofSources(ctx),
                                        cp))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private List<Object> classpathIdentity(Map<String, Object> context) throws IOException {
        List<Object> identity = new ArrayList<>();
        for (Object value : Json.list(context.get("classpath"))) {
            Path file = Path.of((String) value);
            identity.add(List.of(file.getFileName().toString(), inputHash(file)));
        }
        return identity;
    }

    private void verifySources(Map<String, Object> ctx) throws IOException {
        for (Object raw : ownSources(ctx)) {
            Map<String, Object> source = Json.map(raw);
            if (!Objects.equals(
                    Json.sha(Path.of(Json.string(source, "input"))), source.get("hash")))
                throw new IOException("SOURCE_HASH_MISMATCH");
        }
    }

    List<List<Map<String, Object>>> batches(List<Map<String, Object>> work, int parallelism)
            throws IOException {
        List<List<Map<String, Object>>> result = new ArrayList<>();
        Map<String, Map<String, Object>> remaining = new TreeMap<>();
        for (var context : work) remaining.put(Json.string(context, "world"), context);
        while (!remaining.isEmpty()) {
            session.check();
            List<String> ready = new ArrayList<>();
            for (var entry : remaining.entrySet()) {
                boolean blocked = false;
                for (Object raw : Json.list(entry.getValue().get("sources"))) {
                        String parent = Json.string(Json.map(raw), "world");
                        if (!parent.equals(entry.getKey()) && remaining.containsKey(parent)) {
                            blocked = true;
                            break;
                        }
                    }
                if (!blocked) ready.add(entry.getKey());
            }
            if (ready.isEmpty()) {
                ready.addAll(remaining.keySet());
                sourceOnly.addAll(ready);
                for (String world : ready)
                    reason(remaining.get(world), "cyclic semantic input worlds; use SOURCE");
            }
            for (int start = 0; start < ready.size(); start += parallelism) {
                List<Map<String, Object>> batch = new ArrayList<>();
                for (int i = start; i < Math.min(ready.size(), start + parallelism); i++)
                    batch.add(remaining.remove(ready.get(i)));
                result.add(batch);
            }
        }
        return result;
    }

    List<Map<String, Object>> order(List<Map<String, Object>> work) throws IOException {
        List<Map<String, Object>> ordered = new ArrayList<>();
        // The serial and pooled paths must consume exactly the same dependency layers.
        // DFS changes independent-world order and handles cycles differently from batches.
        for (List<Map<String, Object>> batch : batches(work, 1)) ordered.addAll(batch);
        return ordered;
    }

    private static boolean original(List<Object> declarations, Map<String, Object> model) {
        for (Object raw : declarations) {
            Map<String, Object> a = Json.map(Json.map(raw).get("attributes"));
            if (a.containsKey("extension_producer")) return false;
        }
        return Json.list(model.get("diagnostics")).isEmpty();
    }

    synchronized void publish(Map<String, Object> ctx, Map<String, Object> model)
            throws IOException {
        publish(ctx, model, Json.list(model.get("declarations")));
    }

    synchronized void publish(
            Map<String, Object> ctx, Map<String, Object> model, List<Object> compilerDeclarations)
            throws IOException {
        if (!enabled()
                || sourceOnly.contains(Json.string(ctx, "world"))
                || !"MAIN".equals(ctx.get("scope"))) return;
        if (!original(compilerDeclarations, model)) {
            reason(ctx, "incomplete compiler model or non-original declaration; use SOURCE");
            return;
        }
        Set<String> own = new HashSet<>();
        for (Object raw : ownSources(ctx)) own.add(Json.string(Json.map(raw), "path"));
        if (own.isEmpty()) return;
        List<Map<String, Object>> declarations = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (Object raw : compilerDeclarations) {
            Map<String, Object> d = Json.map(raw);
            if (own.contains(d.get("source"))) {
                declarations.add(d);
                found.add(Json.string(d, "source"));
            }
        }
        // Empty compilation units cannot be inferred from declaration count; sources are sealed
        // separately.
        Map<String, byte[]> classes;
        try {
            classes = SignatureProjection.project(declarations);
        } catch (RuntimeException unsupported) {
            reason(ctx, unsupported.getMessage());
            return;
        }
        if (classes.isEmpty()) return;
        try (Locked lock = lock()) {
            String key = key(ctx);
            Path destination = root.resolve(key);
            if (verified(destination, key, ctx)) return;
            Path temp = Files.createTempDirectory(root, ".module-");
            try {
                try (JarOutputStream out =
                        new JarOutputStream(
                                Files.newOutputStream(temp.resolve("signatures.jar")))) {
                    long size = 0;
                    for (var entry : classes.entrySet()) {
                        session.check();
                        size += entry.getValue().length;
                        if (size > MAX_PART)
                            throw new IOException("MODULE_SIGNATURE_ARCHIVE_BUDGET");
                        JarEntry row = new JarEntry(entry.getKey().replace('.', '/') + ".class");
                        row.setTime(0);
                        out.putNextEntry(row);
                        out.write(entry.getValue());
                        out.closeEntry();
                    }
                }
                Map<String, Object> catalogue =
                        Json.object("version", ModelArchive.VERSION, "context", ctx.get("context"));
                catalogue.put("sources", ownSources(ctx));
                // Snapshot predates BinaryIndex and model extensions, including constructor edits.
                // The consumer runs its own extension configuration on restored SOURCE evidence.
                catalogue.put("declarations", new ArrayList<>(declarations));
                // External uses in omitted method bodies must remain visible as in full SOURCE
                // extraction.
                // Modeled override targets belong to published methods, not the original
                // compiler catalogue. Importing them would add unreferenced symbols in a
                // consumer that reuses this module without publishing its methods.
                catalogue.put("external", Json.list(model.get("external")).stream()
                        .filter(raw -> !"modeled-override-v1".equals(Json.map(Json.map(raw)
                                .get("attributes")).get("extension_producer")))
                        .toList());
                catalogue.put("annotation_types", model.get("annotation_types"));
                for (String empty : List.of("observations", "syntax", "diagnostics"))
                    catalogue.put(empty, List.of());
                ModelArchive.write(temp.resolve("catalogue.bin"), catalogue, MAX_PART);
                Files.writeString(
                        temp.resolve("manifest.json"),
                        Json.write(
                                Json.object(
                                        "format",
                                        FORMAT,
                                        "key",
                                        key,
                                        "sources",
                                        proofSources(ctx),
                                        "archive_sha256",
                                        Json.sha(temp.resolve("signatures.jar")),
                                        "catalogue_sha256",
                                        Json.sha(temp.resolve("catalogue.bin")),
                                        "context",
                                        ctx.get("context"))));
                long bytes = size(temp);
                if (bytes > maxDisk) {
                    reason(ctx, "module entry exceeds disk budget");
                    return;
                }
                makeRoom(bytes, destination);
                delete(destination);
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
                published++;
            } finally {
                delete(temp);
            }
        } catch (IOException | RuntimeException unavailable) {
            session.check();
            reason(ctx, "module cache write unavailable: " + unavailable.getMessage());
        }
    }

    private boolean verified(Path directory, String key, Map<String, Object> ctx)
            throws IOException {
        Path manifest = directory.resolve("manifest.json"),
                archive = directory.resolve("signatures.jar"),
                catalogue = directory.resolve("catalogue.bin");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(catalogue, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifest) > 65536
                || Files.size(archive) > MAX_PART
                || Files.size(catalogue) > MAX_PART) return false;
        Map<String, Object> proof = Json.map(Json.parse(Json.read(manifest)));
        if (!FORMAT.equals(proof.get("format"))
                || !key.equals(proof.get("key"))
                || !Objects.equals(ctx.get("context"), proof.get("context"))
                || !Objects.equals(proofSources(ctx), proof.get("sources"))
                || !Json.sha(archive).equals(proof.get("archive_sha256"))
                || !Json.sha(catalogue).equals(proof.get("catalogue_sha256"))) return false;
        try (JarFile jar = new JarFile(archive.toFile())) {
            long inflated = 0;
            int count = 0;
            var rows = jar.entries();
            while (rows.hasMoreElements()) {
                session.check();
                JarEntry row = rows.nextElement();
                long length = row.getSize();
                if (++count > 100000
                        || length < 0
                        || length > MAX_PART - inflated
                        || !row.getName().endsWith(".class")) return false;
                inflated += length;
                try (InputStream in = jar.getInputStream(row)) {
                    org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(in);
                    if (!row.getName().equals(reader.getClassName() + ".class")) return false;
                    final boolean[] code = {false};
                    reader.accept(
                            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                                public org.objectweb.asm.MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return new org.objectweb.asm.MethodVisitor(
                                            org.objectweb.asm.Opcodes.ASM9) {
                                        public void visitCode() {
                                            code[0] = true;
                                        }
                                    };
                                }
                            },
                            0);
                    if (code[0]) return false;
                }
            }
        }
        return true;
    }

    synchronized Lease prepare(Map<String, Object> ctx) throws IOException {
        Lease lease = new Lease(ctx);
        if (!enabled() || sourceOnly.contains(Json.string(ctx, "world"))) return lease;
        Map<String, Set<String>> byWorld = new TreeMap<>();
        String current = Json.string(ctx, "world");
        for (Object raw : Json.list(ctx.get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            String world = Json.string(source, "world");
            if (!world.equals(current))
                byWorld.computeIfAbsent(world, ignored -> new TreeSet<>())
                        .add(Json.string(source, "path"));
        }
        for (var dependency : byWorld.entrySet()) {
            Map<String, Object> parent = worlds.get(dependency.getKey());
            if (parent == null || !"MAIN".equals(parent.get("scope"))) continue;
            Set<String> expected = new TreeSet<>();
            for (Object raw : ownSources(parent)) expected.add(Json.string(Json.map(raw), "path"));
            if (!classpathIdentity(parent).equals(classpathIdentity(ctx))) {
                reason(ctx, "upstream compile classpath differs; use SOURCE");
                continue;
            }
            if (!expected.equals(dependency.getValue())) {
                reason(ctx, "partial upstream module inventory; use SOURCE");
                continue;
            }
            try {
                String key = key(parent);
                Pinned pinned = taskPins.get(key);
                verifySources(parent);
                if (pinned == null)
                    try (Locked lock = lock()) {
                        Path directory = root.resolve(key);
                        if (!verified(directory, key, parent)) continue;
                        long incoming = size(directory);
                        if (incoming > maxDisk - pinBytes) {
                            reason(ctx, "task module pins exceed budget; use SOURCE");
                            continue;
                        }
                        Path pin = Files.createTempDirectory(pins, "input-");
                        if (!session.reserveSignaturePins(incoming)) {
                            delete(pin);
                            reason(ctx, "combined file/module pin budget; use SOURCE");
                            continue;
                        }
                        boolean retained = false;
                        try {
                            for (String file :
                                    List.of("signatures.jar", "catalogue.bin", "manifest.json")) {
                                session.check();
                                Files.copy(directory.resolve(file), pin.resolve(file));
                            }
                            if (!verified(pin, key, parent))
                                throw new IOException("MODULE_PIN_PROOF");
                            Map<String, Object> proof =
                                    Json.map(Json.parse(Json.read(pin.resolve("manifest.json"))));
                            pinned =
                                    new Pinned(
                                            pin,
                                            Json.string(proof, "catalogue_sha256"),
                                            Json.string(parent, "context"));
                            taskPins.put(key, pinned);
                            pinBytes += incoming;
                            retained = true;
                            Files.setLastModifiedTime(
                                    directory, FileTime.from(java.time.Instant.now()));
                        } finally {
                            if (!retained) {
                                try {
                                    delete(pin);
                                } finally {
                                    session.releaseSignaturePins(incoming);
                                }
                            }
                        }
                    }
                Catalogue catalogue = acquire(key, pinned);
                if (catalogue == null) {
                    reason(ctx, "catalogue memory is pinned or exceeds budget; use SOURCE");
                    continue;
                }
                lease.catalogues.add(catalogue);
                lease.archives.add(pinned.directory().resolve("signatures.jar").toString());
                lease.omitted.addAll(expected);
                hits++;
            } catch (IOException | RuntimeException unavailable) {
                session.check();
                reason(ctx, "module cache read unavailable: " + unavailable.getMessage());
            }
        }
        lease.selected();
        return lease;
    }

    private boolean catalogueRoom(long incoming) throws IOException {
        if (incoming > maxCatalogueWeight) return false;
        for (var iterator = decoded.entrySet().iterator();
                incoming > maxCatalogueWeight - catalogueWeight && iterator.hasNext(); ) {
            session.check();
            Catalogue old = iterator.next().getValue();
            if (old.users != 0) continue;
            old.model.close();
            iterator.remove();
            catalogueWeight -= old.weight;
            catalogueEvictions++;
        }
        return incoming <= maxCatalogueWeight - catalogueWeight;
    }

    private Catalogue acquire(String key, Pinned pin) throws IOException {
        Catalogue existing = decoded.get(key);
        if (existing != null) {
            existing.users++;
            return existing;
        }
        Path path = pin.directory().resolve("catalogue.bin");
        // Reserve before decoding. A caller's live catalogues are never evicted.
        if (!catalogueRoom(Math.multiplyExact(Files.size(path), 8L))) return null;
        ModelArchive.OpenModel model =
                ModelArchive.open(path, pin.checksum(), MAX_PART, pin.context());
        boolean retained = false;
        try {
            long weight = weight(model.model());
            if (!catalogueRoom(weight)) return null;
            Catalogue value = new Catalogue(model, weight);
            value.users = 1;
            decoded.put(key, value);
            catalogueWeight += weight;
            retained = true;
            return value;
        } finally {
            if (!retained) model.close();
        }
    }

    private static Object copyHeader(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("MODULE_HEADER_DEPTH");
        if (value instanceof ModelArchive.ImmutableType || TypeGraph.isView(value)) return value;
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (var entry : Json.map(value).entrySet())
                copy.put(entry.getKey(), copyHeader(entry.getValue(), depth + 1));
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : Json.list(value)) copy.add(copyHeader(item, depth + 1));
            return copy;
        }
        return value;
    }

    private static Map<String, Object> mutableHeader(Map<String, Object> value) {
        return Json.map(copyHeader(value, 0));
    }

    private static long weight(Map<String, Object> catalogue) {
        long bytes = 0;
        for (String section : List.of("declarations", "external", "annotation_types"))
            for (Object raw : Json.list(catalogue.get(section))) {
                bytes = Math.addExact(bytes, 4L * Json.write(raw).length() + 256L);
            }
        return bytes;
    }

    final class Lease implements AutoCloseable {
        final Map<String, Object> original;
        Map<String, Object> compiler;
        final List<Catalogue> catalogues = new ArrayList<>();
        final List<Object> archives = new ArrayList<>();
        final Set<String> omitted = new HashSet<>();

        Lease(Map<String, Object> original) {
            this.original = original;
            compiler = original;
        }

        boolean reduced() {
            return !omitted.isEmpty();
        }

        void selected() {
            if (!reduced()) return;
            compiler = new LinkedHashMap<>(original);
            List<Object> sources = new ArrayList<>(), cp = new ArrayList<>(archives);
            cp.addAll(Json.list(original.get("classpath")));
            for (Object raw : Json.list(original.get("sources")))
                if (!omitted.contains(Json.map(raw).get("path"))) sources.add(raw);
            compiler.put("sources", sources);
            compiler.put("classpath", cp);
        }

        boolean restore(Map<String, Object> model) {
            synchronized (ModuleSignatures.this) {
                return restoreLocked(model);
            }
        }

        private boolean restoreLocked(Map<String, Object> model) {
            if (!reduced()) return true;
            Set<String> names = new HashSet<>(), signatures = new HashSet<>();
            for (Object raw : Json.list(model.get("declarations"))) {
                Map<String, Object> d = Json.map(raw), a = Json.map(d.get("attributes"));
                if (a.get("binary_name") instanceof String
                        && !names.add((String) a.get("binary_name"))) return false;
                signatures.add(Json.string(d, "signature"));
            }
            Map<String, Map<String, Object>> headers = new LinkedHashMap<>();
            Set<String> enums = new HashSet<>();
            for (var catalogue : catalogues)
                for (Object raw : Json.list(catalogue.model.model().get("declarations"))) {
                    Map<String, Object> d = Json.map(raw), a = Json.map(d.get("attributes"));
                    if (a.get("binary_name") instanceof String
                            && !names.add((String) a.get("binary_name"))) return false;
                    if (!signatures.add(Json.string(d, "signature"))) return false;
                    headers.put(Json.string(d, "signature"), d);
                    if ("ENUM".equals(a.get("element_kind")))
                        enums.add(Json.string(d, "signature"));
                }
            List<Object> external = Json.list(model.get("external"));
            external.removeIf(raw -> headers.containsKey(Json.map(raw).get("signature")));
            Set<String> seen = new HashSet<>();
            for (Object raw : external) {
                Map<String, Object> e = Json.map(raw);
                String signature = Json.string(e, "signature");
                seen.add(signature);
                for (String enumeration : enums)
                    if (signature.equals(enumeration + "#valueOf(java.lang.String)"))
                        Json.map(e.get("attributes")).put("parameter_names", List.of("name"));
            }
            for (var catalogue : catalogues) {
                for (Object raw : Json.list(catalogue.model.model().get("external")))
                    if (!"modeled-override-v1".equals(Json.map(Json.map(raw)
                            .get("attributes")).get("extension_producer"))
                            && seen.add(Json.string(Json.map(raw), "signature")))
                        external.add(mutableHeader(Json.map(raw)));
                Json.list(model.get("annotation_types"))
                        .addAll(Json.list(catalogue.model.model().get("annotation_types")));
            }
            for (var header : headers.values())
                Json.list(model.get("declarations")).add(mutableHeader(header));
            for (Object raw : Json.list(model.get("observations"))) {
                Map<String, Object> row = Json.map(raw), header = headers.get(row.get("target"));
                if (header != null && "call".equals(row.get("kind"))) {
                    Map<String, Object> a = Json.map(row.get("attributes")),
                            h = Json.map(header.get("attributes"));
                    a.put("declared_type", h.get("declared_type"));
                    a.put("declared_type_model", h.get("type_model"));
                }
            }
            used += catalogues.size();
            return true;
        }

        public void close() {
            synchronized (ModuleSignatures.this) {
                for (var catalogue : catalogues) catalogue.users--;
                catalogues.clear();
            }
        }
    }

    private final class Locked implements AutoCloseable {
        final FileChannel channel;
        final FileLock file;

        Locked(FileChannel channel, FileLock file) {
            this.channel = channel;
            this.file = file;
        }

        public void close() throws IOException {
            try {
                file.close();
            } finally {
                try {
                    channel.close();
                } finally {
                    LOCAL_LOCK.unlock();
                }
            }
        }
    }

    private Locked lock() throws IOException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean acquired = false;
        FileChannel channel = null;
        try {
            while (!acquired) {
                session.check();
                if (System.nanoTime() > end) throw new IOException("MODULE_CACHE_LOCK_TIMEOUT");
                try {
                    acquired = LOCAL_LOCK.tryLock(25, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("CANCELLED", e);
                }
            }
            SignatureStores.checkRoots(fileRoot);
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("MODULE_CACHE_SYMLINK");
            channel =
                    FileChannel.open(
                            SignatureStores.lockPath(fileRoot),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            while (true) {
                session.check();
                FileLock file = channel.tryLock();
                if (file != null) {
                    try {
                        SignatureStores.checkRoots(fileRoot);
                        for (Path store : SignatureStores.roots(fileRoot)) {
                            if (!Files.exists(store, LinkOption.NOFOLLOW_LINKS)) continue;
                            try (var partials =
                                    Files.newDirectoryStream(
                                            store,
                                            path ->
                                                    path.getFileName()
                                                                    .toString()
                                                                    .startsWith(".module-")
                                                            || path.getFileName()
                                                                    .toString()
                                                                    .startsWith(".signature-"))) {
                                for (Path partial : partials) {
                                    session.check();
                                    if (Files.isSymbolicLink(partial))
                                        throw new IOException("MODULE_CACHE_SYMLINK");
                                    delete(partial);
                                }
                            }
                        }
                    } catch (IOException | RuntimeException failed) {
                        file.close();
                        throw failed;
                    }
                    return new Locked(channel, file);
                }
                if (System.nanoTime() > end) throw new IOException("MODULE_CACHE_LOCK_TIMEOUT");
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("CANCELLED", e);
                }
            }
        } catch (IOException | RuntimeException failed) {
            if (channel != null) channel.close();
            if (acquired) LOCAL_LOCK.unlock();
            throw failed;
        }
    }

    private static long size(Path directory) throws IOException {
        long bytes = 0;
        try (var files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isSymbolicLink(file)) throw new IOException("MODULE_CACHE_SYMLINK");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    bytes = Math.addExact(bytes, Files.size(file));
            }
        }
        return bytes;
    }

    private void makeRoom(long incoming, Path replaced) throws IOException {
        List<Path> entries = new ArrayList<>();
        long bytes = 0;
        int count = 0;
        for (Path store : SignatureStores.roots(fileRoot)) {
            if (!Files.exists(store, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var directories = Files.newDirectoryStream(store)) {
                for (Path path : directories) {
                    session.check();
                    if (++count > 100000) throw new IOException("MODULE_CACHE_ENTRY_BUDGET");
                    if (Files.isSymbolicLink(path)) throw new IOException("MODULE_CACHE_SYMLINK");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (path.getFileName().toString().startsWith(".module-")
                                || path.getFileName().toString().startsWith(".signature-")) {
                            continue;
                        }
                        if (!path.getFileName().toString().matches("[0-9a-f]{64}"))
                            throw new IOException("MODULE_CACHE_LAYOUT");
                        if (path.equals(replaced)) {
                            delete(path);
                            continue;
                        }
                        entries.add(path);
                        bytes = Math.addExact(bytes, size(path));
                    }
                }
            }
        }
        entries.sort(
                Comparator.comparing(
                        path -> {
                            try {
                                return Files.getLastModifiedTime(path);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }));
        for (Path path : entries) {
            if (incoming <= maxDisk - bytes) break;
            long removed = size(path);
            delete(path);
            bytes -= removed;
            evictions++;
        }
        if (incoming > maxDisk - bytes) throw new IOException("MODULE_CACHE_DISK_BUDGET");
    }

    private static void delete(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }

    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Catalogue catalogue : decoded.values())
            try {
                catalogue.model.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        decoded.clear();
        catalogueWeight = 0;
        try {
            try {
                if (pins != null) delete(pins);
            } finally {
                session.releaseSignaturePins(pinBytes);
                pinBytes = 0;
            }
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
