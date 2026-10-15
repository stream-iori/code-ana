package com.anatomist.provider;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, parser-independent description of one language frontend. */
public record ProviderDescriptor(
        String providerId,
        String language,
        String version,
        Set<String> fileExtensions,
        Set<String> operations,
        Map<String, List<String>> limitations) {
    public ProviderDescriptor {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is required");
        }
        providerId = providerId.trim();
        language = language.trim().toLowerCase(java.util.Locale.ROOT);
        version = version == null || version.isBlank() ? "1" : version.trim();
        fileExtensions =
                fileExtensions == null
                        ? Set.of()
                        : fileExtensions.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isEmpty())
                                .map(value -> value.startsWith(".") ? value : "." + value)
                                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        operations =
                operations == null
                        ? Set.of()
                        : operations.stream()
                                .filter(java.util.Objects::nonNull)
                                .map(String::trim)
                                .filter(value -> !value.isEmpty())
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        limitations =
                limitations == null
                        ? Map.of()
                        : limitations.entrySet().stream()
                                .collect(
                                        java.util.stream.Collectors.toUnmodifiableMap(
                                                Map.Entry::getKey,
                                                entry -> List.copyOf(entry.getValue())));
    }

    public boolean supports(String operation) {
        return operation != null && operations.contains(operation);
    }
}
