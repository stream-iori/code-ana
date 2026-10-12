package com.anatomist.provider.java;

import com.anatomist.provider.LanguageProvider;
import com.anatomist.provider.ProviderDescriptor;
import com.anatomist.provider.ProviderSemanticAdapter;
import com.anatomist.provider.SymbolSelectorCodec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installed Java frontend descriptor and language-specific semantic mapping. */
public final class JavaLanguageProvider implements LanguageProvider {
    public static final String ID = "java-core";
    public static final String LANGUAGE = "java";

    public static final Set<String> OPERATIONS =
            Set.of(
                    "search",
                    "resolve",
                    "structure",
                    "type-relations",
                    "runtime-implementations",
                    "callable-relations",
                    "calls",
                    "call-sites",
                    "dispatch",
                    "annotations",
                    "references",
                    "trace",
                    "source",
                    "overview");

    private static final ProviderDescriptor DESCRIPTOR =
            new ProviderDescriptor(
                    ID,
                    LANGUAGE,
                    "1",
                    Set.of(".java"),
                    OPERATIONS,
                    Map.of(
                            "runtime-implementations",
                            List.of("OPEN_WORLD"),
                            "dispatch",
                            List.of("STATIC_CANDIDATES_ONLY")));
    private static final SymbolSelectorCodec SELECTORS = (selector, entityKind) -> selector;
    private static final ProviderSemanticAdapter SEMANTICS =
            () ->
                    Set.of(
                            "type-relations",
                            "runtime-implementations",
                            "callable-relations",
                            "dispatch");

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SymbolSelectorCodec selectors() {
        return SELECTORS;
    }

    @Override
    public ProviderSemanticAdapter semantics() {
        return SEMANTICS;
    }

    @Override
    public boolean ownsProducer(String producerId) {
        return producerId != null
                && (producerId.equals(ID)
                        || producerId.startsWith("java-")
                        || producerId.startsWith("lombok-")
                        || producerId.startsWith("spring-")
                        || producerId.startsWith("sofa-")
                        || producerId.equals("derived-wiring"));
    }

    @Override
    public String entityKind(String storageKind) {
        if (storageKind == null) return "entity";
        return switch (storageKind) {
            case "CLASS",
                    "INTERFACE",
                    "ENUM",
                    "RECORD",
                    "ANNOTATION",
                    "ANONYMOUS_CLASS",
                    "EXTERNAL_CLASS" ->
                    "type";
            case "METHOD", "CONSTRUCTOR", "LAMBDA", "METHOD_REF" -> "callable";
            case "FIELD", "ENUM_CONSTANT" -> "value";
            case "BEAN" -> "component";
            case "ARTIFACT" -> "artifact";
            case "XML_PROPERTY" -> "property";
            case "FRAMEWORK_REGISTRATION",
                    "XML_ENTRY",
                    "XML_LIST",
                    "XML_MAP",
                    "XML_VALUE",
                    "XML_REF",
                    "XML_IDREF",
                    "XML_NULL",
                    "XML_CONSTRUCTOR_ARG",
                    "XML_CALLABLE_REF" ->
                    "config_entity";
            default -> storageKind.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
