package dev.codeana.analysis.extension;

import dev.codeana.common.IndexedRecords;
import dev.codeana.common.Json;

import java.util.*;

/** Small producer-owned registration builder. Static configuration is never a confirmed call. */
final class FrameworkFacts {
    private final ExtensionPoint extension;
    private final ExtensionContext context;
    private final FactSink sink;
    private final Set<String> publication = new HashSet<>();
    final IndexedRecords declarations;
    final IndexedRecords declarationsById;
    private final SortedSet<String> publishedSignatures = new TreeSet<>();

    FrameworkFacts(ExtensionPoint extension, ExtensionContext context, FactSink sink) {
        this.extension = extension;
        this.context = context;
        this.sink = sink;
        declarations = new IndexedRecords(context.facts());
        declarationsById = new IndexedRecords(context.facts());
        for (Object raw : Json.list(context.configuration().get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            if (Boolean.TRUE.equals(source.get("publish")))
                publication.add(Json.string(source, "path"));
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < context.facts().size(); i++) {
            String kind = context.factKind(i);
            if (!"declaration".equals(kind) && !"external_symbol".equals(kind)) continue;
            Map<String, Object> f = context.factHeader(i);
            if (Set.of("declaration", "external_symbol").contains(f.get("kind"))) {
                String signature = Json.string(f, "signature");
                int rank =
                        ("declaration".equals(f.get("kind")) ? 2 : 0)
                                + (Objects.equals(
                                                context.configuration().get("world"),
                                                f.get("world"))
                                        ? 1
                                        : 0);
                if (!ranks.containsKey(signature) || rank > ranks.get(signature)) {
                    ranks.put(signature, rank);
                    declarations.index(signature, i);
                    if ("declaration".equals(f.get("kind"))
                            && publication.contains(f.get("source"))
                            && !Boolean.TRUE.equals(f.get("generated")))
                        publishedSignatures.add(signature);
                    else publishedSignatures.remove(signature);
                }
                declarationsById.index(Json.string(f, "id"), i);
            }
        }
    }

    Iterable<Map<String, Object>> published() {
        return () -> publishedSignatures.stream().map(declarations::get).iterator();
    }

    Map<String, Object> fact(
            Map<String, Object> declaration, String kind, String namespace, String discriminator) {
        Map<String, Object> owner = new TreeMap<>(Json.map(declaration.get("owner")));
        owner.put("producer", extension.producer());
        return Json.object(
                "id",
                Json.key(
                        "framework-registration",
                        Json.string(owner, "world"),
                        extension.id(),
                        Json.string(declaration, "id"),
                        namespace,
                        discriminator),
                "kind",
                kind,
                "owner",
                owner,
                "source",
                declaration.get("source"),
                "span",
                declaration.get("span"),
                "name",
                "",
                "signature",
                "",
                "parent",
                declaration.get("id"),
                "target",
                "",
                "relation",
                namespace,
                "status",
                "modeled",
                "origin",
                extension.producer(),
                "evidence_complete",
                false,
                "declaration_resolved",
                false,
                "attributes",
                new TreeMap<String, Object>());
    }

    String registration(
            Map<String, Object> declaration,
            String namespace,
            String discriminator,
            String name,
            Map<String, Object> attributes) {
        Map<String, Object> f = fact(declaration, "facet", namespace, discriminator);
        f.put("name", name);
        attributes.put("schema_version", 1);
        attributes.put("runtime_confirmed", false);
        attributes.put("selection", "possible");
        f.put("attributes", attributes);
        sink.add(f);
        return Json.string(f, "id");
    }

    void handles(Map<String, Object> declaration, String registration, Map<String, Object> target) {
        if (target == null) return;
        Map<String, Object> f = fact(declaration, "relation", "handles_possible", registration);
        f.put("parent", registration);
        f.put("target", target.get("id"));
        sink.add(f);
    }

    void gap(Map<String, Object> declaration, String reason) {
        Map<String, Object> f = fact(declaration, "coverage", "", reason);
        f.put("status", "partial");
        f.put("attributes", Json.object("reason", reason));
        sink.add(f);
    }

    void add(Map<String, Object> fact) {
        sink.add(fact);
    }

    static Map<String, Object> writtenAttributes(Map<String, Object> declaration, String annotation) {
        Map<String, Object> written = new LinkedHashMap<>();
        for (Object raw : Json.list(Json.map(declaration.get("attributes"))
                .getOrDefault("source_annotation_expressions", List.of()))) {
            Map<String, Object> expression = Json.map(raw);
            if (annotation.equals(expression.get("type")))
                written.putAll(Json.map(expression.get("attributes")));
        }
        return written;
    }

    void annotationLimits(dev.codeana.analysis.ModelAnnotations annotations) {
        Set<String> limited = annotations.limitedSources();
        if (!limited.isEmpty())
            for (Map<String, Object> d : published())
                if ((limited.contains("") || limited.contains(Json.string(d, "source")))
                        && Set.of("CLASS", "INTERFACE")
                                .contains(Json.map(d.get("attributes")).get("element_kind")))
                    gap(d, "ANNOTATION_QUERY_BUDGET");
    }
}
