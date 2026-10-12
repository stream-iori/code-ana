package com.anatomist.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete accounting for one Java parsing pass. */
public record ParseInventory(
        int scannedFiles, int attemptedFiles, int parsedFiles, Map<Path, List<String>> failures) {
    public ParseInventory {
        Map<Path, List<String>> copy = new LinkedHashMap<>();
        if (failures != null) {
            failures.forEach(
                    (path, messages) ->
                            copy.put(path, messages == null ? List.of() : List.copyOf(messages)));
        }
        failures = java.util.Collections.unmodifiableMap(copy);
    }

    public int failedFiles() {
        return failures.size();
    }

    public double completeness() {
        return scannedFiles == 0 ? 1.0d : (double) parsedFiles / scannedFiles;
    }

    public boolean complete() {
        return failedFiles() == 0 && parsedFiles == scannedFiles;
    }

    public static ParseInventory complete(int files) {
        return new ParseInventory(files, files, files, Map.of());
    }
}
