package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.ContentHash;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class JavacSignatureCacheIT {
    @Test
    void sessionProofRejectsContentReplacementEvenWithPreservedTimestampsAndNewChecksums(
            @TempDir Path project) throws Exception {
        Path source =
                Files.writeString(project.resolve("Example.java"), "class Example { int value; }");
        var units = analyze(project, source);
        String key = ContentHash.sha256("session-proof");
        var ready =
                assertInstanceOf(
                        JavacSignatureCache.Ready.class,
                        JavacSignatureCache.publish(project.resolve("cache"), key, units, 25));
        Path archive = ready.artifact().archive();
        Path catalogue = archive.resolveSibling("declarations.bin");
        Path manifest = archive.resolveSibling("manifest.json");
        Path checksum = archive.resolveSibling("manifest.sha256");
        try (var session = new JavacSignatureCache.Session()) {
            assertEquals(
                    units.stream().map(JavaDeclarationView::of).toList(),
                    session.catalogue(ready.artifact()).orElseThrow());
            assertTrue(session.valid(ready.artifact()));
            assertTrue(session.archiveUnchanged(ready.artifact()));
            assertEquals(
                    units.stream().map(JavaDeclarationView::of).toList(),
                    session.catalogue(ready.artifact()).orElseThrow());
            var time = Files.getLastModifiedTime(catalogue);
            var bytes = Files.readAllBytes(catalogue);
            bytes[bytes.length - 1] ^= 1;
            Files.write(catalogue, bytes);
            Files.setLastModifiedTime(catalogue, time);
            var values = new LinkedHashMap<String, Object>();
            ((Map<?, ?>) Json.parseTree(Files.readString(manifest)))
                    .forEach((name, value) -> values.put(name.toString(), value));
            values.put("catalogue_sha256", ContentHash.sha256(catalogue));
            Files.writeString(manifest, Json.writeCompact(values));
            Files.writeString(checksum, ContentHash.sha256(manifest));
            assertFalse(session.valid(ready.artifact()));
            // The retained header has not changed in memory; publication still rejects the
            // catalogue replacement, even though the worker input archive remains identical.
            assertTrue(session.archiveUnchanged(ready.artifact()));
            assertTrue(session.catalogue(ready.artifact()).isEmpty());
            assertThrows(IllegalArgumentException.class, session::verifyInputs);
            var archiveTime = Files.getLastModifiedTime(archive);
            var archiveBytes = Files.readAllBytes(archive);
            archiveBytes[archiveBytes.length - 1] ^= 1;
            Files.write(archive, archiveBytes);
            Files.setLastModifiedTime(archive, archiveTime);
            assertFalse(session.archiveUnchanged(ready.artifact()));
        }
        assertInstanceOf(
                JavacSignatureCache.Ready.class,
                JavacSignatureCache.publish(project.resolve("cache"), key, units, 25));
        try (var session = new JavacSignatureCache.Session()) {
            assertTrue(session.catalogue(ready.artifact()).isPresent());
            var time = Files.getLastModifiedTime(source);
            Files.writeString(source, "class Example { long value; }");
            Files.setLastModifiedTime(source, time);
            assertTrue(session.catalogue(ready.artifact()).isEmpty());
        }
    }

    @Test
    void signaturesPublishAtomicallyReuseOnlyVerifiedContentsAndRebuildCorruption(
            @TempDir Path directory) throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path source =
                Files.writeString(
                        project.resolve("Example.java"),
                        "class Example<T extends Number> { static final int VALUE = 7; T echo(T value) { return value; } }");
        List<JavaUnit> units = analyze(project, source);
        Path cache = directory.resolve("cache");
        String fingerprint =
                ContentHash.sha256(
                        "toolchain/worker/model/classpath/source: "
                                + units.getFirst().sourceHash());
        var produced =
                assertInstanceOf(
                        JavacSignatureCache.Ready.class,
                        JavacSignatureCache.publish(cache, fingerprint, units, 25));
        assertFalse(produced.cached());
        assertEquals(1, produced.artifact().types());
        assertTrue(JavacSignatureCache.load(cache, fingerprint).isPresent());
        assertTrue(
                assertInstanceOf(
                                JavacSignatureCache.Ready.class,
                                JavacSignatureCache.publish(cache, fingerprint, units, 25))
                        .cached());
        Path archive = produced.artifact().archive();
        Path manifest = archive.resolveSibling("manifest.json");
        var values = (Map<?, ?>) Json.parseTree(Files.readString(manifest));
        assertEquals(Boolean.TRUE, values.get("index_only"));
        assertEquals(fingerprint, values.get("input"));
        assertEquals(ContentHash.sha256(archive), values.get("archive_sha256"));
        Path catalogue = archive.resolveSibling("declarations.bin");
        String expectedCatalogue = ContentHash.sha256(catalogue);
        assertEquals(expectedCatalogue, values.get("catalogue_sha256"));
        assertEquals(
                units.stream().map(JavaDeclarationView::of).toList(),
                JavacSignatureCache.catalogue(produced.artifact()).orElseThrow());
        Files.writeString(catalogue, "truncated catalogue");
        assertTrue(JavacSignatureCache.load(cache, fingerprint).isEmpty());
        assertFalse(
                assertInstanceOf(
                                JavacSignatureCache.Ready.class,
                                JavacSignatureCache.publish(cache, fingerprint, units, 25))
                        .cached());
        assertEquals(expectedCatalogue, ContentHash.sha256(catalogue));
        Files.writeString(catalogue, "invalid structural catalogue");
        Map<String, Object> damaged = new LinkedHashMap<>();
        values.forEach((key, value) -> damaged.put(key.toString(), value));
        damaged.put("catalogue_sha256", ContentHash.sha256(catalogue));
        Files.writeString(manifest, Json.writeCompact(damaged));
        Files.writeString(archive.resolveSibling("manifest.sha256"), ContentHash.sha256(manifest));
        assertTrue(JavacSignatureCache.load(cache, fingerprint).isPresent());
        assertTrue(JavacSignatureCache.catalogue(produced.artifact()).isEmpty());
        assertFalse(
                assertInstanceOf(
                                JavacSignatureCache.Ready.class,
                                JavacSignatureCache.publish(cache, fingerprint, units, 25))
                        .cached());
        assertEquals(expectedCatalogue, ContentHash.sha256(catalogue));
        String expectedArchive = ContentHash.sha256(archive);
        Files.writeString(archive, "truncated archive");
        assertTrue(JavacSignatureCache.load(cache, fingerprint).isEmpty());
        assertFalse(
                assertInstanceOf(
                                JavacSignatureCache.Ready.class,
                                JavacSignatureCache.publish(cache, fingerprint, units, 25))
                        .cached());
        assertEquals(expectedArchive, ContentHash.sha256(archive));
        Files.writeString(manifest, "{broken");
        assertTrue(JavacSignatureCache.load(cache, fingerprint).isEmpty());
        assertInstanceOf(
                JavacSignatureCache.Ready.class,
                JavacSignatureCache.publish(cache, fingerprint, units, 25));
        assertThrows(
                IllegalArgumentException.class, () -> JavacSignatureCache.load(cache, "../unsafe"));
        try (var paths = Files.walk(cache)) {
            assertTrue(
                    paths.noneMatch(
                            path -> path.getFileName().toString().startsWith(".signature-")));
        }
        assertTrue(Files.notExists(project.resolve("target")));
    }

    @Test
    void incompleteDeclarationsAndChangedSnapshotsNeverCreateReusableOutputs(
            @TempDir Path directory) throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path source =
                Files.writeString(
                        project.resolve("Example.java"), "class Example { Missing header; }");
        List<JavaUnit> units = analyze(project, source);
        Path cache = directory.resolve("cache");
        String key = ContentHash.sha256("incomplete");
        assertInstanceOf(
                JavacSignatureCache.Unavailable.class,
                JavacSignatureCache.publish(cache, key, units, 25));
        assertTrue(JavacSignatureCache.load(cache, key).isEmpty());
        Files.writeString(source, "class Example { int header; }");
        List<JavaUnit> valid = analyze(project, source);
        Files.writeString(source, "class Example { long header; }");
        assertThrows(
                IllegalArgumentException.class,
                () -> JavacSignatureCache.publish(cache, ContentHash.sha256("changed"), valid, 25));
        assertTrue(JavacSignatureCache.load(cache, ContentHash.sha256("changed")).isEmpty());
    }

    private static List<JavaUnit> analyze(Path project, Path source) {
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
        return contexts.getFirst().units();
    }
}
