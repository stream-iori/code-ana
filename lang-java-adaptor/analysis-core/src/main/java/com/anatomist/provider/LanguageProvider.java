package com.anatomist.provider;

import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SourceRoot;

import java.nio.file.Path;
import java.util.List;

/** Compile-time language frontend SPI. It deliberately exposes no parser-specific AST types. */
public interface LanguageProvider {
    ProviderDescriptor descriptor();

    SymbolSelectorCodec selectors();

    ProviderSemanticAdapter semantics();

    default boolean ownsProducer(String producerId) {
        return descriptor().providerId().equals(producerId);
    }

    default SourceInventory discover(ProjectScanner scanner, List<SourceRoot> roots) {
        List<Path> files = scanner.scanSourceRoots(roots, descriptor().fileExtensions());
        return new SourceInventory(descriptor().providerId(), descriptor().language(), files);
    }

    default boolean claims(Path source) {
        if (source == null || source.getFileName() == null) return false;
        String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return descriptor().fileExtensions().stream().anyMatch(name::endsWith);
    }

    /** Map a provider-owned storage kind to the public language-neutral entity kind. */
    String entityKind(String languageKind);

    /** Stable, namespaced facet value such as {@code java.record}. */
    default String languageKind(String storageKind) {
        return descriptor().language() + "." + storageKind.toLowerCase(java.util.Locale.ROOT);
    }
}
