package com.anatomist.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Immutable selected and auxiliary inputs for one configured Java analysis environment. */
public record JavaSourcePlan(
        List<Path> indexedFiles,
        List<Path> visibleFiles,
        List<SourceRoot> roots,
        Map<Path, SourceIdentity> identities) {
    public JavaSourcePlan {
        indexedFiles = List.copyOf(indexedFiles);
        visibleFiles = List.copyOf(visibleFiles);
        roots = List.copyOf(roots);
        identities = Map.copyOf(identities);
    }
}
