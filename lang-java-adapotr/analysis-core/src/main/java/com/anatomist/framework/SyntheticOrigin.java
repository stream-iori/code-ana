package com.anatomist.framework;

import com.anatomist.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provenance for declarations synthesized into the declaration model. */
public record SyntheticOrigin(
        String producerId,
        String generator,
        String generatorMode,
        String generatedFrom,
        String confidence,
        boolean bodyAvailable) {
    @SuppressWarnings("unchecked")
    public String mergeMetadata(String existing) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (existing != null && !existing.isBlank()) {
            try {
                Object parsed = Json.parseTree(existing);
                if (parsed instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> metadata.put(String.valueOf(key), value));
                }
            } catch (RuntimeException ignored) {
                // Existing extractor metadata should be valid; retain provenance even if it is not.
            }
        }
        metadata.put("isSynthetic", true);
        metadata.put("generator", generator);
        metadata.put("generatorMode", generatorMode);
        metadata.put("generatedFrom", generatedFrom);
        metadata.put("confidence", confidence);
        metadata.put("bodyAvailable", bodyAvailable);
        return Json.writeCompact(metadata);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generator", generator);
        out.put("generator_mode", generatorMode);
        out.put("generated_from", generatedFrom);
        out.put("confidence", confidence);
        out.put("body_available", bodyAvailable);
        return out;
    }
}
