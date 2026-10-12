package com.anatomist.provider;

import com.anatomist.model.Annotation;
import com.anatomist.model.AnnotationMetaRelation;
import com.anatomist.model.Declaration;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

/** Fills the common IR fields emitted by legacy/provider-specific extractors. */
public final class FactDefaults {
    private FactDefaults() {}

    public static void normalize(ExtractionResult result) {
        LanguageProviderRegistry registry = LanguageProviderRegistry.builtIns();
        for (Node node : result.nodes) normalize(node, registry);
        for (Declaration declaration : result.declarations) normalize(declaration, registry);
        for (Edge edge : result.edges) normalize(edge, registry);
        for (Annotation annotation : result.annotations) normalize(annotation, registry);
        for (AnnotationMetaRelation relation : result.annotationMetaRelations) {
            normalize(relation, registry);
        }
    }

    public static void normalize(Node node) {
        normalize(node, LanguageProviderRegistry.builtIns());
    }

    private static void normalize(Node node, LanguageProviderRegistry registry) {
        LanguageProvider provider = registry.provider(node.providerId);
        if (provider == null && node.providerId == null) {
            provider = registry.providerForProducer(node.producerId);
        }
        if (provider == null && node.providerId == null)
            provider = registry.providerForLanguage("java");
        if (node.providerId == null && provider != null)
            node.providerId = provider.descriptor().providerId();
        if (node.entityKind == null && provider != null)
            node.entityKind = provider.entityKind(node.kind);
        if (node.languageKind == null && provider != null)
            node.languageKind = provider.languageKind(node.kind);
        if (node.domain == null)
            node.domain = configurationKind(node.entityKind) ? "configuration" : "language";
        if (node.language == null && "language".equals(node.domain) && provider != null) {
            node.language = provider.descriptor().language();
        }
        if (node.namespace == null) node.namespace = node.pkg;
    }

    public static void normalize(Declaration declaration) {
        normalize(declaration, LanguageProviderRegistry.builtIns());
    }

    private static void normalize(Declaration declaration, LanguageProviderRegistry registry) {
        LanguageProvider provider = registry.provider(declaration.providerId);
        if (provider == null && declaration.providerId == null) {
            provider = registry.providerForProducer(declaration.producerId);
        }
        if (provider == null && declaration.providerId == null) {
            provider = registry.providerForLanguage("java");
        }
        if (declaration.providerId == null && provider != null) {
            declaration.providerId = provider.descriptor().providerId();
        }
        if (declaration.entityKind == null && provider != null) {
            declaration.entityKind = provider.entityKind(declaration.kind);
        }
        if (declaration.languageKind == null && provider != null) {
            declaration.languageKind = provider.languageKind(declaration.kind);
        }
        if (declaration.domain == null) declaration.domain = "language";
        if (declaration.language == null && provider != null) {
            declaration.language = provider.descriptor().language();
        }
        if (declaration.namespace == null && declaration.qualifiedName != null) {
            int member = declaration.qualifiedName.indexOf('#');
            String owner =
                    member < 0
                            ? declaration.qualifiedName
                            : declaration.qualifiedName.substring(0, member);
            int dot = owner.lastIndexOf('.');
            declaration.namespace = dot < 0 ? "" : owner.substring(0, dot);
        }
    }

    public static void normalize(Edge edge) {
        normalize(edge, LanguageProviderRegistry.builtIns());
    }

    public static void normalize(Annotation annotation) {
        normalize(annotation, LanguageProviderRegistry.builtIns());
    }

    private static void normalize(Annotation annotation, LanguageProviderRegistry registry) {
        LanguageProvider provider = registry.provider(annotation.providerId);
        if (provider == null && annotation.providerId == null) {
            provider = registry.providerForProducer(annotation.producerId);
        }
        if (provider == null && annotation.providerId == null) {
            provider = registry.providerForLanguage("java");
        }
        if (annotation.providerId == null && provider != null) {
            annotation.providerId = provider.descriptor().providerId();
        }
        if (annotation.language == null && provider != null) {
            annotation.language = provider.descriptor().language();
        }
        if (annotation.mechanism == null && annotation.language != null) {
            annotation.mechanism = annotation.language + ".annotation";
        }
    }

    public static void normalize(AnnotationMetaRelation relation) {
        normalize(relation, LanguageProviderRegistry.builtIns());
    }

    private static void normalize(
            AnnotationMetaRelation relation, LanguageProviderRegistry registry) {
        LanguageProvider provider = registry.provider(relation.providerId);
        if (provider == null && relation.providerId == null) {
            provider = registry.providerForProducer(relation.producerId);
        }
        if (provider == null && relation.providerId == null) {
            provider = registry.providerForLanguage("java");
        }
        if (relation.providerId == null && provider != null) {
            relation.providerId = provider.descriptor().providerId();
        }
        if (relation.language == null && provider != null) {
            relation.language = provider.descriptor().language();
        }
        if (relation.mechanism == null && relation.language != null) {
            relation.mechanism = relation.language + ".annotation.meta";
        }
    }

    private static void normalize(Edge edge, LanguageProviderRegistry registry) {
        LanguageProvider provider = registry.provider(edge.providerId);
        if (provider == null && edge.providerId == null) {
            provider = registry.providerForProducer(edge.producerId);
        }
        if (provider == null && edge.providerId == null)
            provider = registry.providerForLanguage("java");
        if (edge.providerId == null && provider != null)
            edge.providerId = provider.descriptor().providerId();
        if (edge.language == null && provider != null)
            edge.language = provider.descriptor().language();
        if (edge.semantic == null)
            edge.semantic =
                    GraphConstants.Relation.IMPLEMENTS.equals(edge.relation)
                            ? "CONFORMS_TO"
                            : edge.relation;
        if (edge.mechanism == null && edge.relation != null && provider != null) {
            edge.mechanism =
                    provider.descriptor().language()
                            + "."
                            + edge.relation.toLowerCase(java.util.Locale.ROOT);
        }
        if (edge.externalTargetSymbol == null) edge.externalTargetSymbol = edge.externalTargetFqn;
        if (edge.isExternal && edge.externalTargetLanguage == null && provider != null) {
            edge.externalTargetLanguage = provider.descriptor().language();
        }
        if (edge.isExternal && edge.externalTargetProviderId == null && provider != null) {
            edge.externalTargetProviderId = provider.descriptor().providerId();
        }
    }

    private static boolean configurationKind(String entityKind) {
        return "artifact".equals(entityKind)
                || "component".equals(entityKind)
                || "config_entity".equals(entityKind)
                || "property".equals(entityKind);
    }
}
