package com.anatomist.provider;

import java.nio.file.Path;
import java.util.List;

/** Deterministic source inventory claimed by one language provider. */
public record SourceInventory(String providerId, String language, List<Path> files) {
    public SourceInventory {
        files = files == null ? List.of() : files.stream().distinct().sorted().toList();
    }
}
