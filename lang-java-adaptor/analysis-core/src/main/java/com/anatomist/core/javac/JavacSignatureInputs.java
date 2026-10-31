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
        return fingerprint(context, environment, release, classpath, model, sources, null);
    }

    static String fingerprint(JavacContexts.Context context, String environment, int release,
            List<FileInput> classpath, List<FileInput> model, List<FileInput> sources,
            com.anatomist.core.AnalysisContext analysis) {
        if (analysis != null) analysis.charge(1, 1, 512);
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("format", JavacSignatureCache.VERSION);
        key.put("contract", JavaContract.VERSION);
        key.put("environment", environment);
        key.put("release", release);
        key.put("module", context.module());
        key.put("scope", context.scope().name());
        key.put("dependencies", context.dependencyModules().stream().sorted().toList());
        key.put("sources", values(sources, analysis));
        key.put("classpath", values(classpath, analysis));
        key.put("module_model", values(model, analysis));
        String encoded = Json.writeCompact(key, analysis);
        if (analysis != null) analysis.charge(1, 1, 256L + 3L * encoded.length());
        return ContentHash.sha256(encoded);
    }

    private static List<Map<String, String>> values(List<FileInput> files, com.anatomist.core.AnalysisContext analysis) {
        if (analysis != null) analysis.charge(files.size(), files.size(), 64L * files.size());
        return files.stream()
                .map(file -> {
                    if (analysis != null) analysis.charge(1, 1, 192);
                    Map<String, String> identity = new LinkedHashMap<>();
                    identity.put("path", file.path());
                    identity.put("sha256", file.sha256());
                    return identity;
                })
                .toList();
    }

    static List<FileInput> files(List<Path> paths) throws IOException { return files(paths, null); }

    static List<FileInput> files(List<Path> paths, com.anatomist.core.AnalysisContext analysis) throws IOException {
        List<FileInput> values = new ArrayList<>();
        List<Path> contentFiles = new ArrayList<>();
        for (Path supplied : paths) {
            if (analysis != null) analysis.charge(1, 1, 256L + 6L * supplied.toString().length());
            Path path = supplied.toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                List<Path> contents;
                try (var entries = Files.walk(path)) {
                    contents = entries.peek(entry -> {
                        if (analysis != null) analysis.charge(1, 1, 256L + 6L * entry.toString().length());
                    }).filter(Files::isRegularFile).sorted().toList();
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
        List<String> hashes;
        if (analysis == null) hashes = ContentHash.sha256Files(contentFiles);
        else {
            analysis.charge(contentFiles.size(), contentFiles.size(), 64L * contentFiles.size());
            hashes = new ArrayList<>(contentFiles.size());
            for (Path file : contentFiles) hashes.add(ContentHash.sha256(file, analysis));
        }
        int next = 0;
        for (int index = 0; index < values.size(); index++) {
            FileInput value = values.get(index);
            if (value.sha256().isEmpty())
                values.set(index, new FileInput(value.path(), hashes.get(next++)));
        }
        return List.copyOf(values);
    }
}
