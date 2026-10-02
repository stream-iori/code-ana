package dev.codeana.analysis;

import dev.codeana.common.Json;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.jar.*;

/** Content-verified, index-only Java 8 declarations. Unsupported shapes stay as source inputs. */
final class SignatureCache {
    private static final String FORMAT = "java8-source-signature-v9-model6-callbacks";
    private static final long MAX_ENTRY = 8L * 1024 * 1024;
    private static final long MAX_TOTAL = 256L * 1024 * 1024;
    private static final long MAX_CATALOGUES = 64L * 1024 * 1024;
    // File locks coordinate processes; the cancellable local lock prevents overlapping locks.
    private static final java.util.concurrent.locks.ReentrantLock CACHE_LOCK = SignatureStores.LOCK;
    private final Path root;
    private final String profile;
    private final boolean enabled;
    private final java.util.concurrent.atomic.AtomicInteger writeFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger hits =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger evictions =
            new java.util.concurrent.atomic.AtomicInteger();
    private final long maxTotal;

    SignatureCache(Map<String, Object> request) {
        this(request, MAX_TOTAL);
    }

    /** Smaller limits are used by test-only probes; production retains its existing budget. */
    SignatureCache(Map<String, Object> request, long maxTotal) {
        if (maxTotal < 1 || maxTotal > MAX_TOTAL)
            throw new IllegalArgumentException("SIGNATURE_CACHE_BUDGET");
        this.maxTotal = maxTotal;
        enabled =
                Boolean.TRUE.equals(request.get("signature_cache"))
                        && request.get("cache_root") instanceof String
                        && request.get("profile") instanceof String;
        root = enabled ? Path.of(Json.string(request, "cache_root")) : null;
        profile = enabled ? Json.string(request, "profile") : "";
    }

    boolean enabled() {
        return enabled;
    }

    int writeFailures() {
        return writeFailures.get();
    }

    int hits() {
        return hits.get();
    }

    int evictions() {
        return evictions.get();
    }

    private String key(Map<String, Object> context, Map<String, Object> source) {
        return Json.sha(
                Json.write(
                                List.of(
                                        FORMAT,
                                        profile,
                                        context.get("module"),
                                        context.get("scope"),
                                        context.get("world"),
                                        source.get("path"),
                                        source.get("hash"),
                                        context.getOrDefault("extension_fingerprint", "")))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private Path verified(Map<String, Object> context, Map<String, Object> source) {
        if (!enabled) return null;
        try {
            String key = key(context, source);
            Path directory = root.resolve(key);
            Path manifest = directory.resolve("manifest.json");
            Path archive = directory.resolve("signatures.jar");
            Path catalogue = directory.resolve("declarations.json");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(catalogue, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifest) > 65536
                    || Files.size(archive) > MAX_ENTRY
                    || Files.size(catalogue) > MAX_ENTRY) return null;
            Map<String, Object> proof = Json.map(Json.parse(Json.read(manifest)));
            if (!FORMAT.equals(proof.get("format"))
                    || !key.equals(proof.get("key"))
                    || !Json.sha(archive).equals(proof.get("archive_sha256"))
                    || !Json.sha(catalogue).equals(proof.get("catalogue_sha256"))) return null;
            try (JarFile jar = new JarFile(archive.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                int count = 0;
                long inflated = 0;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (++count > 2048 || !entry.getName().endsWith(".class")) return null;
                    byte[] bytes;
                    try (var input = jar.getInputStream(entry)) {
                        bytes = input.readNBytes((int) MAX_ENTRY + 1);
                    }
                    inflated += bytes.length;
                    if (inflated > MAX_ENTRY) return null;
                    final boolean[] code = {false};
                    ClassReader reader = new ClassReader(bytes);
                    if (!entry.getName().equals(reader.getClassName() + ".class")) return null;
                    reader.accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitCode() {
                                            code[0] = true;
                                        }
                                    };
                                }
                            },
                            0);
                    if (code[0]) return null;
                }
                if (count != Json.number(proof, "classes")) return null;
            }
            return archive;
        } catch (Exception invalid) {
            return null;
        }
    }

    record Input(Path archive, Map<String, Object> catalogue) {}

    /** A task-owned immutable pin. Evicting the shared entry cannot alter javac inputs. */
    final class Pins implements AutoCloseable {
        private final CompilerSession session;
        private final Path directory;
        private long bytes, catalogueBytes;
        private boolean closed;

        private Pins(CompilerSession session) throws IOException {
            this.session = session;
            directory = session.newInputDirectory("signatures-");
        }

        Input acquire(Map<String, Object> context, Map<String, Object> source) throws IOException {
            if (closed) throw new IllegalStateException("SIGNATURE_PIN_CLOSED");
            session.check();
            if (!enabled) return null;
            try (CacheLock local = localLock(session)) {
                try (FileChannel channel =
                                FileChannel.open(
                                        SignatureStores.lockPath(root),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE,
                                        LinkOption.NOFOLLOW_LINKS);
                        FileLock ignored = lock(channel, session)) {
                    SignatureStores.checkRoots(root);
                    Path archive = verified(context, source);
                    if (archive == null) return null;
                    Path entry = archive.getParent();
                    long size = entryBytes(entry);
                    long catalogueSize = Files.size(entry.resolve("declarations.json"));
                    if (size > MAX_TOTAL - bytes || catalogueSize > MAX_CATALOGUES - catalogueBytes)
                        return null;
                    Path pin = Files.createTempDirectory(directory, "input-");
                    if (!session.reserveSignaturePins(size)) {
                        deleteTree(pin);
                        return null;
                    }
                    boolean retained = false;
                    try {
                        for (String name :
                                List.of("signatures.jar", "declarations.json", "manifest.json")) {
                            session.check();
                            Files.copy(entry.resolve(name), pin.resolve(name));
                        }
                        Map<String, Object> catalogue =
                                Json.map(Json.parse(Json.read(pin.resolve("declarations.json"))));
                        Files.setLastModifiedTime(entry, FileTime.from(java.time.Instant.now()));
                        bytes += size;
                        catalogueBytes += catalogueSize;
                        hits.incrementAndGet();
                        retained = true;
                        return new Input(pin.resolve("signatures.jar"), catalogue);
                    } finally {
                        if (!retained) {
                            try {
                                deleteTree(pin);
                            } finally {
                                session.releaseSignaturePins(size);
                            }
                        }
                    }
                } catch (IOException | IllegalArgumentException unavailable) {
                    session.check();
                    return null;
                }
            } catch (IOException unavailable) {
                session.check();
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                deleteTree(directory);
            } finally {
                session.releaseSignaturePins(bytes);
                bytes = 0;
            }
        }
    }

    Pins pin(CompilerSession session) throws IOException {
        return new Pins(session);
    }

    private static final class CacheLock implements AutoCloseable {
        @Override
        public void close() {
            CACHE_LOCK.unlock();
        }
    }

    private static void check(CompilerSession session, long end) throws IOException {
        if (session != null) session.check();
        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
        if (System.nanoTime() > end) throw new IOException("SIGNATURE_CACHE_LOCK_TIMEOUT");
    }

    private static CacheLock localLock(CompilerSession session) throws IOException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (true) {
            check(session, end);
            try {
                if (CACHE_LOCK.tryLock(25, java.util.concurrent.TimeUnit.MILLISECONDS))
                    return new CacheLock();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("CANCELLED", interrupted);
            }
        }
    }

    private static FileLock lock(FileChannel channel, CompilerSession session) throws IOException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (true) {
            check(session, end);
            FileLock lock = channel.tryLock();
            if (lock != null) return lock;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("CANCELLED", interrupted);
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        }
    }

    void publish(Map<String, Object> context, Map<String, Object> model) throws IOException {
        if (!enabled) return;
        try (CacheLock local = localLock(null)) {
            SignatureStores.checkRoots(root);
            Files.createDirectories(root);
            try (FileChannel channel =
                            FileChannel.open(
                                    SignatureStores.lockPath(root),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = lock(channel, null)) {
                SignatureStores.checkRoots(root);
                publishLocked(context, model);
            }
        } catch (IOException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw unavailable;
            // Optional derived data cannot invalidate completed source analysis.
            writeFailures.incrementAndGet();
        }
    }

    private void publishLocked(Map<String, Object> context, Map<String, Object> model)
            throws IOException {
        // The root is exclusively locked: scan its budget once, then charge each commit.
        // Scanning the whole cache for every source made a module write quadratic.
        Inventory inventory = inventory();
        Map<String, List<Map<String, Object>>> bySource = new TreeMap<>();
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            bySource.computeIfAbsent(
                            Json.string(declaration, "source"), ignored -> new ArrayList<>())
                    .add(declaration);
        }
        for (Object raw : Json.list(context.get("sources"))) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
            Map<String, Object> source = Json.map(raw);
            String path = Json.string(source, "path");

            List<Map<String, Object>> declarations = bySource.getOrDefault(path, List.of());
            Map<String, byte[]> classes;
            try {
                classes = project(declarations, model);
            } catch (RuntimeException incomplete) {
                continue;
            }
            if (classes.isEmpty() || classes.size() > 2048) continue;
            String key = key(context, source);
            if (verified(context, source) != null) continue;
            Path temporary = Files.createTempDirectory(root, ".signature-");
            try {
                Path archive = temporary.resolve("signatures.jar");
                try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
                    for (Map.Entry<String, byte[]> item : classes.entrySet()) {
                        JarEntry entry = new JarEntry(item.getKey().replace('.', '/') + ".class");
                        entry.setTime(0);
                        output.putNextEntry(entry);
                        output.write(item.getValue());
                        output.closeEntry();
                    }
                }
                Path catalogue = temporary.resolve("declarations.json");
                // javac synthesizes some source members (for example enum.valueOf) without
                // source declaration trees. Preserve those models as well as source headers.
                List<Object> sourceExternal = new ArrayList<>();
                for (Object external : Json.list(model.get("external"))) {
                    String signature = Json.string(Json.map(external), "signature");
                    int separator = signature.indexOf('#');
                    String owner = separator < 0 ? signature : signature.substring(0, separator);
                    if (classes.containsKey(owner)) sourceExternal.add(external);
                }
                Files.writeString(
                        catalogue,
                        Json.write(
                                Json.object(
                                        "declarations", declarations, "external", sourceExternal)),
                        StandardCharsets.UTF_8);
                if (Files.size(archive) > MAX_ENTRY || Files.size(catalogue) > MAX_ENTRY) continue;
                Files.writeString(
                        temporary.resolve("manifest.json"),
                        Json.write(
                                Json.object(
                                        "format",
                                        FORMAT,
                                        "key",
                                        key,
                                        "classes",
                                        classes.size(),
                                        "archive_sha256",
                                        Json.sha(archive),
                                        "catalogue_sha256",
                                        Json.sha(catalogue))),
                        StandardCharsets.UTF_8);
                long entryBytes =
                        Files.size(archive)
                                + Files.size(catalogue)
                                + Files.size(temporary.resolve("manifest.json"));
                Path destination = root.resolve(key);
                // Corrupt derived entries are replaceable. They never override SOURCE evidence.
                inventory.remove(destination);
                if (entryBytes > maxTotal || !inventory.makeRoom(entryBytes)) continue;
                if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                        inventory.add(destination, entryBytes);
                    } catch (FileAlreadyExistsException concurrent) {
                        /* Another task published it. */
                    }
                }
            } finally {
                deleteTree(temporary);
            }
        }
    }

    private record Entry(Path path, long bytes, FileTime access) {}

    private final class Inventory {
        private long bytes;
        private final Map<Path, Entry> entries = new HashMap<>();
        private final PriorityQueue<Entry> oldest =
                new PriorityQueue<>(
                        Comparator.comparing(Entry::access)
                                .thenComparing(e -> e.path().toString()));

        void add(Path path, long size) throws IOException {
            Entry entry =
                    new Entry(
                            path, size, Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS));
            entries.put(path, entry);
            oldest.add(entry);
            bytes += size;
        }

        void remove(Path path) throws IOException {
            Entry entry = entries.get(path);
            if (entry == null) return;
            deleteTree(path);
            entries.remove(path);
            bytes -= entry.bytes();
            evictions.incrementAndGet();
        }

        boolean makeRoom(long size) throws IOException {
            while (size > maxTotal - bytes && !oldest.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
                Entry entry = oldest.remove();
                if (entries.get(entry.path()) == entry) remove(entry.path());
            }
            return size <= maxTotal - bytes;
        }
    }

    private static long entryBytes(Path directory) throws IOException {
        long bytes = 0;
        int count = 0;
        try (var files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (++count > 8) throw new IOException("SIGNATURE_ENTRY_LAYOUT");
                if (Files.isSymbolicLink(file)) throw new IOException("SIGNATURE_ENTRY_SYMLINK");
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    bytes = Math.addExact(bytes, Files.size(file));
            }
        }
        return bytes;
    }

    private Inventory inventory() throws IOException {
        Inventory inventory = new Inventory();
        int count = 0;
        for (Path store : SignatureStores.roots(root)) {
            if (!Files.exists(store, LinkOption.NOFOLLOW_LINKS)) continue;
            try (DirectoryStream<Path> directories = Files.newDirectoryStream(store)) {
                for (Path path : directories) {
                    if (++count > 100000) throw new IOException("SIGNATURE_CACHE_ENTRY_BUDGET");
                    if (Files.isSymbolicLink(path))
                        throw new IOException("SIGNATURE_CACHE_SYMLINK");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (path.getFileName().toString().startsWith(".signature-")
                                || path.getFileName().toString().startsWith(".module-")) {
                            // Exclusive lock proves no writer is using a previous partial commit.
                            deleteTree(path);
                        } else if (path.getFileName().toString().matches("[0-9a-f]{64}")) {
                            inventory.add(path, entryBytes(path));
                        } else throw new IOException("SIGNATURE_CACHE_LAYOUT");
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        inventory.bytes = Math.addExact(inventory.bytes, Files.size(path));
                    }
                }
            }
        }
        inventory.makeRoom(0);
        return inventory;
    }

    private static Map<String, byte[]> project(
            List<Map<String, Object>> declarations, Map<String, Object> model) {
        return SignatureProjection.project(declarations);
    }
}
