package com.anatomist.core;

import java.nio.file.Path;
import java.util.*;

/** Owns directory enumeration for one index invocation, including auxiliary source discovery. */
public final class JavaSourceDiscovery {
    private record Root(Path path, boolean generated, String policy, Set<String> excluded) {}

    private final Map<Root, List<Path>> inventories = new LinkedHashMap<>();
    private long rootWalks;
    private long candidateFiles;
    private IndexTimings timings;

    public void setTimings(IndexTimings value) {
        timings = value;
    }

    public Map<String, Long> counts() {
        return Map.of("source_root_walks", rootWalks, "source_candidate_files", candidateFiles);
    }

    List<Path> discover(ProjectScanner scanner, ScanPolicy policy, List<SourceRoot> roots) {
        ScanPolicy support = policy == null ? null : policy.support();
        Set<String> excluded = new HashSet<>(scanner.excludedDirs());
        if (support != null) excluded.addAll(support.excludedDirNames());
        String key = support == null ? "" : support.canonical(List.of(), List.of());
        ProjectScanner raw = new ProjectScanner(excluded, support);
        Set<Path> selected = new LinkedHashSet<>();
        for (SourceRoot source : roots) {
            Root root =
                    new Root(
                            source.path().toAbsolutePath().normalize(),
                            source.scope() == SourceScope.GENERATED,
                            key,
                            Set.copyOf(excluded));
            List<Path> files = inventories.get(root);
            if (files == null) {
                files =
                        inventories.entrySet().stream()
                                .filter(
                                        entry ->
                                                entry.getKey().generated() == root.generated()
                                                        && entry.getKey()
                                                                .policy()
                                                                .equals(root.policy())
                                                        && entry.getKey()
                                                                .excluded()
                                                                .equals(root.excluded())
                                                        && root.path()
                                                                .startsWith(entry.getKey().path()))
                                .findFirst()
                                .map(
                                        entry ->
                                                entry.getValue().stream()
                                                        .filter(
                                                                path ->
                                                                        path.startsWith(
                                                                                root.path()))
                                                        .toList())
                                .orElse(null);
                if (files == null) {
                    long started = System.nanoTime();
                    long before = raw.rootWalks();
                    files =
                            List.copyOf(
                                    raw.scanRoot(root.path(), root.generated(), Set.of(".java")));
                    rootWalks += raw.rootWalks() - before;
                    candidateFiles += files.size();
                    if (timings != null) timings.stop("source_root_walk", started);
                }
                inventories.put(root, files);
            }
            for (Path file : files) if (policy == null || policy.includes(file)) selected.add(file);
        }
        return selected.stream().sorted().toList();
    }
}
