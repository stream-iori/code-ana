package com.anatomist.core.javac;

import com.anatomist.core.ContentHash;
import com.anatomist.core.java.JavaContract;
import com.anatomist.json.Json;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Ordered content identities: paths, scope, dependency order and toolchain are semantic inputs. */
final class JavacSignatureInputs {
    record FileInput(String path, String sha256) {}

    private JavacSignatureInputs() {}

    static String fingerprint(
            JavacContexts.Context context,
            String environment,
            int release,
            List<FileInput> classpath,
            List<FileInput> model)
            throws IOException {
        return fingerprint(
                context, environment, release, classpath, model, files(context.sources()));
    }

    static String fingerprint(
            JavacContexts.Context context,
            String environment,
            int release,
            List<FileInput> classpath,
            List<FileInput> model,
            List<FileInput> sources) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("format", JavacSignatureCache.VERSION);
        key.put("contract", JavaContract.VERSION);
        key.put("environment", environment);
        key.put("release", release);
        key.put("module", context.module());
        key.put("scope", context.scope().name());
        key.put("dependencies", context.dependencyModules().stream().sorted().toList());
        key.put("sources", values(sources));
        key.put("classpath", values(classpath));
        key.put("module_model", values(model));
        return ContentHash.sha256(Json.writeCompact(key));
    }

    private static List<Map<String, String>> values(List<FileInput> files) {
        return files.stream()
                .map(file -> Map.of("path", file.path(), "sha256", file.sha256()))
                .toList();
    }

    static List<FileInput> files(List<Path> paths) throws IOException {
        List<FileInput> values = new ArrayList<>();
        List<Path> contentFiles = new ArrayList<>();
        for (Path supplied : paths) {
            Path path = supplied.toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                List<Path> contents;
                try (var entries = Files.walk(path)) {
                    contents = entries.filter(Files::isRegularFile).sorted().toList();
                }
                // Include the directory itself so empty directories and classpath order matter.
                values.add(new FileInput(path.toString(), "directory"));
                for (Path file : contents) {
                    values.add(new FileInput(file.toString(), ""));
                    contentFiles.add(file);
                }
            } else {
                boolean regular = Files.isRegularFile(path);
                values.add(new FileInput(path.toString(), regular ? "" : "missing"));
                if (regular) contentFiles.add(path);
            }
        }
        List<String> hashes = ContentHash.sha256Files(contentFiles);
        int next = 0;
        for (int index = 0; index < values.size(); index++) {
            FileInput value = values.get(index);
            if (value.sha256().isEmpty())
                values.set(index, new FileInput(value.path(), hashes.get(next++)));
        }
        return List.copyOf(values);
    }
}
