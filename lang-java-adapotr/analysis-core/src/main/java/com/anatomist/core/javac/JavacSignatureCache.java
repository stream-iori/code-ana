package com.anatomist.core.javac;

import com.anatomist.core.ContentHash;
import com.anatomist.core.IndexTimings;
import com.anatomist.core.asmsolver.*;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Content-verified, atomically published index-only module signatures. Never a build artifact. */
final class JavacSignatureCache {
    static final String VERSION = "source-signatures-v4";
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_TYPES = 100_000;

    sealed interface Outcome permits Ready, Unavailable {}

    record Artifact(Path archive, String inputFingerprint, int types, int release) {}

    record Ready(Artifact artifact, boolean cached, Session.Snapshot proof) implements Outcome {}

    record Unavailable(List<String> reasons) implements Outcome {
        Unavailable {
            reasons = List.copyOf(reasons);
        }
    }

    /** Reuses validation only for identical bytes within one indexing session. */
    static final class Session implements AutoCloseable {
        private record Snapshot(
                String manifest, String checksum, String archive, String catalogue) {}

        private final Map<Artifact, Snapshot> verified = new LinkedHashMap<>();
        private Path capturedRoot;

        void capturedRoot(Path root) { capturedRoot = root; }

        void published(Ready ready) throws IOException {
            if (!Optional.of(ready.proof()).equals(snapshot(ready.artifact())))
                throw new IOException("signature cache changed after publication");
            if (verified.size() < 4096) verified.putIfAbsent(ready.artifact(), ready.proof());
        }

        Optional<List<JavaUnit>> catalogue(Artifact artifact) throws IOException {
            var before = snapshot(artifact);
            if (before.isEmpty()) return Optional.empty();
            var previous = verified.get(artifact);
            if (previous != null && !previous.equals(before.get())) return Optional.empty();
            if (previous == null && verified.size() >= 4096) return Optional.empty();
            Optional<List<JavaUnit>> units;
            if (previous == null) units = JavacSignatureCache.catalogue(artifact, capturedRoot);
            else {
                try {
                    units =
                            Optional.of(
                                    restorePaths(JavacCatalogueCodec.read(
                                            artifact.archive().resolveSibling("declarations.bin")), capturedRoot));
                    for (var unit : units.get())
                        if (!unit.sourceHash().equals(ContentHash.sha256(unit.path())))
                            return Optional.empty();
                } catch (RuntimeException | IOException invalid) {
                    return Optional.empty();
                }
            }
            if (units.isEmpty() || !before.equals(snapshot(artifact))) return Optional.empty();
            verified.putIfAbsent(artifact, before.get());
            return units;
        }

        boolean valid(Artifact artifact) throws IOException {
            var expected = verified.get(artifact);
            if (expected == null) return catalogue(artifact).isPresent();
            return Optional.of(expected).equals(snapshot(artifact));
        }

        /** Retained detached headers are owned memory; only this archive enters the next worker. */
        boolean archiveUnchanged(Artifact artifact) throws IOException {
            var expected = verified.get(artifact);
            if (expected == null) return valid(artifact);
            return Files.isRegularFile(artifact.archive())
                    && Files.size(artifact.archive()) <= MAX_BYTES
                    && expected.archive().equals(ContentHash.sha256(artifact.archive()));
        }

        void verifyInputs() throws IOException {
            for (var entry : verified.entrySet())
                if (!Optional.of(entry.getValue()).equals(snapshot(entry.getKey())))
                    throw new IllegalArgumentException(
                            "signature cache changed during javac session: "
                                    + entry.getKey().archive());
        }

        private static Optional<Snapshot> snapshot(Artifact artifact) throws IOException {
            Path archive = artifact.archive(),
                    manifest = archive.resolveSibling("manifest.json"),
                    checksum = archive.resolveSibling("manifest.sha256"),
                    catalogue = archive.resolveSibling("declarations.bin");
            if (!Files.isRegularFile(manifest)
                    || !Files.isRegularFile(archive)
                    || !Files.isRegularFile(checksum)
                    || !Files.isRegularFile(catalogue)) return Optional.empty();
            if (Files.size(manifest) > 16L * 1024 * 1024
                    || Files.size(archive) > MAX_BYTES
                    || Files.size(catalogue) > JavacCatalogueCodec.MAX_BYTES
                    || Files.size(checksum) != 64) return Optional.empty();
            return Optional.of(
                    new Snapshot(
                            ContentHash.sha256(manifest),
                            Files.readString(checksum),
                            ContentHash.sha256(archive),
                            ContentHash.sha256(catalogue)));
        }

        @Override
        public void close() {
            verified.clear();
        }
    }

    private JavacSignatureCache() {}

    private static Optional<Ready> checkedReady(Artifact artifact, boolean cached)
            throws IOException {
        return checkedReady(artifact, cached, null);
    }

    private static Optional<Ready> checkedReady(Artifact artifact, boolean cached, Path root) throws IOException {
        return checkedDirectory(artifact.archive().getParent(), artifact.inputFingerprint(), cached, root)
                .filter(ready -> ready.artifact().equals(artifact));
    }

    private static Optional<Ready> checkedDirectory(
            Path directory, String fingerprint, boolean cached) throws IOException {
        return checkedDirectory(directory, fingerprint, cached, null);
    }

    private static Optional<Ready> checkedDirectory(Path directory, String fingerprint, boolean cached, Path root) throws IOException {
        var artifact = new Artifact(directory.resolve("signatures.jar"), fingerprint, 0, 0);
        var before = Session.snapshot(artifact);
        if (before.isEmpty()) return Optional.empty();
        var validated = loadDirectory(directory, fingerprint);
        if (validated.isEmpty()
                || readCatalogue(validated.get(), root).isEmpty()
                || !before.equals(Session.snapshot(artifact))) return Optional.empty();
        return Optional.of(new Ready(validated.get(), cached, before.get()));
    }

    static Optional<Artifact> load(Path cache, String fingerprint) throws IOException {
        return load(cache, fingerprint, null);
    }

    static Optional<Artifact> load(Path cache, String fingerprint, Path root) throws IOException {
        validateFingerprint(fingerprint);
        Path directory = location(cache, fingerprint, root);
        return loadDirectory(directory, fingerprint);
    }

    private static Optional<Artifact> loadDirectory(Path directory, String fingerprint)
            throws IOException {
        Path manifest = directory.resolve("manifest.json"),
                archive = directory.resolve("signatures.jar"),
                checksum = directory.resolve("manifest.sha256"),
                catalogue = directory.resolve("declarations.bin");
        if (!Files.isRegularFile(manifest)
                || !Files.isRegularFile(archive)
                || !Files.isRegularFile(checksum)
                || !Files.isRegularFile(catalogue)) return Optional.empty();
        try {
            if (Files.size(manifest) > 16L * 1024 * 1024
                    || Files.size(archive) > MAX_BYTES
                    || Files.size(catalogue) > JavacCatalogueCodec.MAX_BYTES
                    || Files.size(checksum) != 64
                    || !ContentHash.sha256(manifest).equals(Files.readString(checksum)))
                return Optional.empty();
            Object tree = Json.parseTree(Files.readString(manifest));
            if (!(tree instanceof Map<?, ?> values)
                    || !VERSION.equals(values.get("format"))
                    || !fingerprint.equals(values.get("input"))
                    || !Boolean.TRUE.equals(values.get("index_only"))
                    || !(values.get("release") instanceof Number release)
                    || !Set.of(8L, 17L, 25L).contains(release.longValue())
                    || !(values.get("sources") instanceof List<?> sources)
                    || !validSources(sources)
                    || !ContentHash.sha256(archive).equals(values.get("archive_sha256"))
                    || !ContentHash.sha256(catalogue).equals(values.get("catalogue_sha256"))
                    || !(values.get("classes") instanceof Map<?, ?> expected)
                    || expected.size() > MAX_TYPES) return Optional.empty();
            Set<String> found = new HashSet<>();
            long bytes = 0;
            try (var jar = new JarFile(archive.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class")
                            || !found.add(entry.getName())
                            || found.size() > MAX_TYPES) return Optional.empty();
                    byte[] content;
                    try (var input = jar.getInputStream(entry)) {
                        content = input.readNBytes(Math.toIntExact(MAX_BYTES - bytes + 1));
                    }
                    bytes += content.length;
                    if (bytes > MAX_BYTES || !hash(content).equals(expected.get(entry.getName())))
                        return Optional.empty();
                    String name =
                            new org.objectweb.asm.ClassReader(content).getClassName() + ".class";
                    if (!name.equals(entry.getName())) return Optional.empty();
                }
            }
            return found.size() == expected.size()
                    ? Optional.of(
                            new Artifact(archive, fingerprint, found.size(), release.intValue()))
                    : Optional.empty();
        } catch (RuntimeException | IOException invalid) {
            return Optional.empty();
        }
    }

    static Optional<List<JavaUnit>> catalogue(Artifact artifact) throws IOException {
        return catalogue(artifact, null);
    }

    private static Optional<List<JavaUnit>> catalogue(Artifact artifact, Path root) throws IOException {
        Path directory = artifact.archive().getParent();
        var verified = loadDirectory(directory, artifact.inputFingerprint());
        if (verified.isEmpty() || verified.get().release() != artifact.release())
            return Optional.empty();
        return readCatalogue(artifact, root);
    }

    private static Optional<List<JavaUnit>> readCatalogue(Artifact artifact, Path root) throws IOException {
        Path directory = artifact.archive().getParent();
        try {
            var units = JavacCatalogueCodec.read(directory.resolve("declarations.bin"));
            Object manifest = Json.parseTree(Files.readString(directory.resolve("manifest.json")));
            if (!(manifest instanceof Map<?, ?> values)
                    || !(values.get("sources") instanceof List<?> sources)
                    || sources.size() != units.size()) return Optional.empty();
            Map<String, JavaUnit> own = new HashMap<>();
            for (JavaUnit unit : units)
                if (own.putIfAbsent(unit.path().toString(), unit) != null) return Optional.empty();
            for (Object source : sources) {
                if (!(source instanceof Map<?, ?> item)) return Optional.empty();
                var unit = own.get(item.get("path"));
                if (unit == null
                        || !unit.sourceHash().equals(item.get("sha256"))
                        || !JavaContract.fingerprint(unit).equals(item.get("contract")))
                    return Optional.empty();

            }
            var restored = restorePaths(units, root);
            for (var unit : restored) JavaSource.read(unit);
            return Optional.of(restored);
        } catch (RuntimeException | IOException invalid) {
            return Optional.empty();
        }
    }

    private static final Path CAPTURED = Path.of("/__codeana_captured__");

    private static JavaUnit withPath(JavaUnit unit, Path path) {
        return new JavaUnit(path, unit.tree(), unit.processing(), unit.bindings(), unit.types(),
                unit.declarations(), unit.declarationInventory(), unit.sourceHash(), unit.extensions());
    }

    private static List<JavaUnit> storePaths(List<JavaUnit> units, Path root) {
        if (root == null) return units;
        Path base = root.toAbsolutePath().normalize();
        return units.stream().map(unit -> {
            Path path = unit.path().toAbsolutePath().normalize();
            if (!path.startsWith(base)) throw new IllegalArgumentException("signature source outside capture");
            return withPath(unit, CAPTURED.resolve(base.relativize(path)));
        }).toList();
    }

    private static List<JavaUnit> restorePaths(List<JavaUnit> units, Path root) {
        if (root == null) return units;
        Path base = root.toAbsolutePath().normalize();
        return units.stream().map(unit -> {
            Path path = unit.path();
            if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(CAPTURED))
                throw new IllegalArgumentException("signature capture identity");
            Path restored = base.resolve(CAPTURED.relativize(path)).normalize();
            if (!restored.startsWith(base)) throw new IllegalArgumentException("signature path escape");
            return withPath(unit, restored);
        }).toList();
    }

    private static boolean validSources(List<?> sources) {
        if (sources.size() > 100_000) return false;
        Set<String> paths = new HashSet<>();
        for (Object source : sources) {
            if (!(source instanceof Map<?, ?> values)
                    || !(values.get("path") instanceof String path)
                    || !Path.of(path).isAbsolute()
                    || !paths.add(path)
                    || !(values.get("sha256") instanceof String hash)
                    || !hash.matches("[0-9a-f]{64}")
                    || !(values.get("contract") instanceof String contract)
                    || !contract.matches("[0-9a-f]{64}")) return false;
        }
        return true;
    }

    static Outcome publish(Path cache, String fingerprint, List<JavaUnit> units, int release)
            throws IOException {
        return publish(cache, fingerprint, units, release, null);
    }

    static Outcome publish(
            Path cache, String fingerprint, List<JavaUnit> units, int release, IndexTimings timings)
            throws IOException {
        return publish(cache, fingerprint, units, release, timings, null);
    }

    static Outcome publish(Path cache, String fingerprint, List<JavaUnit> units, int release,
            IndexTimings timings, Path root) throws IOException {
        validateFingerprint(fingerprint);
        long started = System.nanoTime();
        for (JavaUnit unit : units) JavaSource.read(unit);
        var cached = checkedDirectory(location(cache, fingerprint, root), fingerprint, true, root);
        if (cached.isPresent() && cached.get().artifact().release() == release) return cached.get();
        if (timings != null) timings.stop("javac_signature_publish_inputs", started);
        started = System.nanoTime();
        var projection = JavaSignatureTypes.project(units, release);
        if (projection instanceof JavaSignatureTypes.Incomplete incomplete)
            return new Unavailable(incomplete.reasons());
        var types = ((JavaSignatureTypes.Complete) projection).types();
        if (timings != null) timings.stop("javac_signature_project", started);
        if (types.size() > MAX_TYPES)
            return new Unavailable(List.of("signature type count exceeds budget"));
        Path destination = location(cache, fingerprint, root);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempDirectory(destination.getParent(), ".signature-");
        try (var owned = new JavacCompilationCache.Directory(temporary)) {
            started = System.nanoTime();
            Path archive = owned.path().resolve("signatures.jar");
            Map<String, String> hashes = new TreeMap<>();
            long bytes = 0;
            try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
                for (JavaBinaryType type : types) {
                    byte[] content = JavaBinaryWriter.signature(type);
                    bytes += content.length;
                    if (bytes > MAX_BYTES)
                        return new Unavailable(List.of("signature archive exceeds byte budget"));
                    String name = type.name().replace('.', '/') + ".class";
                    if (name.startsWith("/")
                            || name.contains("..")
                            || hashes.putIfAbsent(name, hash(content)) != null)
                        return new Unavailable(
                                List.of("invalid or duplicate binary name: " + name));
                    var entry = new JarEntry(name);
                    entry.setTime(0);
                    output.putNextEntry(entry);
                    output.write(content);
                    output.closeEntry();
                }
            } catch (org.objectweb.asm.ClassTooLargeException tooLarge) {
                return new Unavailable(
                        List.of("signature constant pool exceeds class-file budget"));
            }
            if (Files.size(archive) > MAX_BYTES)
                return new Unavailable(List.of("compressed signature archive exceeds byte budget"));
            if (timings != null) timings.stop("javac_signature_write_archive", started);
            started = System.nanoTime();
            List<JavaUnit> compact = storePaths(units.stream().map(JavaDeclarationView::of).toList(), root);
            Path catalogue = owned.path().resolve("declarations.bin");
            try {
                JavacCatalogueCodec.write(catalogue, compact);
            } catch (JavacCatalogueCodec.BudgetExceeded tooLarge) {
                return new Unavailable(List.of(tooLarge.getMessage()));
            }
            if (timings != null) timings.stop("javac_signature_write_catalogue", started);
            started = System.nanoTime();
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", VERSION);
            manifest.put("index_only", true);
            manifest.put("input", fingerprint);
            manifest.put("release", release);
            manifest.put("archive_sha256", ContentHash.sha256(archive));
            manifest.put("catalogue_sha256", ContentHash.sha256(catalogue));
            manifest.put("classes", hashes);
            manifest.put(
                    "sources",
                    storePaths(units, root).stream()
                            .map(
                                    unit ->
                                            Map.of(
                                                    "path",
                                                    unit.path().toString(),
                                                    "sha256",
                                                    unit.sourceHash(),
                                                    "contract",
                                                    JavaContract.fingerprint(unit)))
                            .toList());
            Files.writeString(temporary.resolve("manifest.json"), Json.writeCompact(manifest));
            Files.writeString(
                    temporary.resolve("manifest.sha256"),
                    ContentHash.sha256(temporary.resolve("manifest.json")));
            if (timings != null) timings.stop("javac_signature_write_manifest", started);
            started = System.nanoTime();
            // Reuse the same complete validator before any directory becomes visible.
            var validated = checkedDirectory(temporary, fingerprint, false, root);
            if (validated.isEmpty()) throw new IOException("signature output validation failed");
            if (timings != null) timings.stop("javac_signature_validate_output", started);
            var concurrent = load(cache, fingerprint, root);
            if (concurrent.isPresent() && concurrent.get().release() == release) {
                var ready = checkedReady(concurrent.get(), true, root);
                if (ready.isPresent()) return ready.get();
            }
            if (Files.exists(destination))
                try (var ignored = new JavacCompilationCache.Directory(destination)) {
                    /* remove a corrupt entry */
                }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException concurrentPublisher) {
                var winner = load(cache, fingerprint, root);
                if (winner.isPresent() && winner.get().release() == release) {
                    var ready = checkedReady(winner.get(), true, root);
                    if (ready.isPresent()) return ready.get();
                }
                throw concurrentPublisher;
            }
            return new Ready(
                    new Artifact(
                            destination.resolve("signatures.jar"),
                            fingerprint,
                            validated.get().artifact().types(),
                            release),
                    false,
                    validated.get().proof());
        }
    }

    private static Path location(Path cache, String fingerprint) {
        return location(cache, fingerprint, null);
    }

    private static Path location(Path cache, String fingerprint, Path root) {
        return root == null ? cache.resolve("signatures").resolve(VERSION).resolve(fingerprint) : cache.resolve(fingerprint);
    }

    private static void validateFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid signature input fingerprint");
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
