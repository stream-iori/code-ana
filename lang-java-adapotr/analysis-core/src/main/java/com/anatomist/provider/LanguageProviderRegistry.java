package com.anatomist.provider;

import com.anatomist.provider.java.JavaLanguageProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reflection-free registry so the same provider set works on the JVM and in native-image. */
public final class LanguageProviderRegistry {
    private static final LanguageProviderRegistry BUILT_INS =
            new LanguageProviderRegistry(List.of(new JavaLanguageProvider()));

    private final Map<String, LanguageProvider> byId;
    private final Map<String, List<LanguageProvider>> byLanguage;

    public LanguageProviderRegistry(List<? extends LanguageProvider> providers) {
        Map<String, LanguageProvider> ids = new LinkedHashMap<>();
        Map<String, java.util.ArrayList<LanguageProvider>> languages = new LinkedHashMap<>();
        if (providers != null) {
            for (LanguageProvider provider : providers) {
                if (provider == null) continue;
                String id = provider.descriptor().providerId();
                if (ids.putIfAbsent(id, provider) != null) {
                    throw new IllegalArgumentException("duplicate language provider: " + id);
                }
                languages
                        .computeIfAbsent(
                                provider.descriptor().language(),
                                ignored -> new java.util.ArrayList<>())
                        .add(provider);
            }
        }
        this.byId = Map.copyOf(ids);
        Map<String, List<LanguageProvider>> immutable = new LinkedHashMap<>();
        languages.forEach((language, values) -> immutable.put(language, List.copyOf(values)));
        this.byLanguage = Map.copyOf(immutable);
    }

    public static LanguageProviderRegistry builtIns() {
        return BUILT_INS;
    }

    public List<LanguageProvider> providers() {
        return List.copyOf(byId.values());
    }

    public LanguageProvider requireProvider(String providerId) {
        LanguageProvider provider = provider(providerId);
        if (provider == null)
            throw new IllegalArgumentException("PROVIDER_NOT_INSTALLED: " + providerId);
        return provider;
    }

    public LanguageProvider provider(String providerId) {
        return providerId == null ? null : byId.get(providerId.trim());
    }

    public List<LanguageProvider> providersForLanguage(String language) {
        return language == null
                ? List.of()
                : byLanguage.getOrDefault(
                        language.trim().toLowerCase(java.util.Locale.ROOT), List.of());
    }

    public LanguageProvider providerForLanguage(String language) {
        List<LanguageProvider> providers = providersForLanguage(language);
        return providers.size() == 1 ? providers.getFirst() : null;
    }

    public LanguageProvider providerForProducer(String producerId) {
        if (producerId == null || producerId.isBlank()) return null;
        for (LanguageProvider provider : byId.values()) {
            if (provider.ownsProducer(producerId)) return provider;
        }
        return null;
    }
}
