package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import dev.codeana.common.FactHeaders;

import java.util.*;

/** Transactional output; ownership, namespaces and graph links are verified before visibility. */
public final class FactSink {
    private final List<Object> facts = new ArrayList<>();

    public void add(Map<String, Object> fact) {
        if (facts.size() >= 100000) throw new IllegalArgumentException("EXTENSION_FACT_BUDGET");
        facts.add(ExtensionContext.mutableMap(fact));
    }

    public void addAll(List<Object> input) {
        for (Object fact : input) add(Json.map(fact));
    }

    List<Object> validate(
            ExtensionPoint extension, ExtensionContext context, List<Object> existing) {
        Set<String> wanted = new HashSet<>();
        for (Object raw : facts) {
            Map<String,Object> fact = Json.map(raw);
            for (String field : List.of("id", "parent", "target")) {
                Object value = fact.get(field);
                if (value instanceof String && !((String) value).isEmpty()) wanted.add((String) value);
            }
        }
        return validate(extension, context, new ValidationIndex(existing, wanted));
    }

    /** Share only verified identity strings, not decoded records, across callbacks in a stage. */
    static final class ValidationIndex {
        private final Set<String> ids = new HashSet<>();
        private int count;

        ValidationIndex(List<Object> existing) {
            this(existing, true);
        }

        ValidationIndex(List<Object> existing, boolean identitiesNeeded) {
            count = existing.size();
            if (!identitiesNeeded) return;
            for (int index = 0; index < existing.size(); index++)
                ids.add(Json.string(dev.codeana.common.FactHeaders.get(existing, index), "id"));
        }

        ValidationIndex(List<Object> existing, Set<String> wanted) {
            count = existing.size();
            if (wanted.isEmpty()) return;
            Set<Long> hashes = new HashSet<>();
            for (String identity : wanted) hashes.add(FactHeaders.identityHash(identity));
            for (int index = 0; index < existing.size(); index++) {
                if (!FactHeaders.mightHaveId(existing, index, hashes)) continue;
                String identity = Json.string(FactHeaders.get(existing, index), "id");
                if (wanted.contains(identity)) ids.add(identity);
            }
        }

        void added(List<Object> verified) {
            for (Object raw : verified) ids.add(Json.string(Json.map(raw), "id"));
            count += verified.size();
        }
    }

    List<Object> validate(
            ExtensionPoint extension, ExtensionContext context, ValidationIndex existing) {
        long limit =
                context.configuration().containsKey("fact_limit")
                        ? Json.number(context.configuration(), "fact_limit")
                        : 100000;
        if (limit < 1 || limit > 5000000)
            throw new IllegalArgumentException("JAVA_FACT_LIMIT_RANGE");
        if (facts.size() > limit - existing.count)
            throw new IllegalArgumentException("EXTENSION_FACT_BUDGET");
        Map<String, String> sources = new HashMap<>();
        for (Object raw : Json.list(context.configuration().get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            if (Boolean.TRUE.equals(source.get("publish")))
                sources.put(Json.string(source, "path"), Json.string(source, "world"));
        }
        for (Object raw : Json.list(context.configuration().get("resources"))) {
            Map<String, Object> unit = Json.map(Json.map(raw).get("unit"));
            sources.put(Json.string(unit, "path"), Json.string(unit, "world"));
        }
        Set<String> ids = new HashSet<>();
        for (Object raw : facts) {
            Map<String, Object> f = Json.map(raw), owner = Json.map(f.get("owner"));
            String source = Json.string(f, "source"), kind = Json.string(f, "kind");
            if (!extension.producer().equals(owner.get("producer"))
                    || !Set.of(
                                    "facet",
                                    "reference",
                                    "relation",
                                    "dependency",
                                    "coverage",
                                    "diagnostic",
                                    "provenance",
                                    "external_symbol")
                            .contains(kind)
                    || !Objects.equals(
                            owner.get("id"),
                            source.isEmpty() ? context.configuration().get("context") : source)
                    || !(owner.get("world") instanceof String)
                    || !source.isEmpty() && !sources.containsKey(source)
                    || !Objects.equals(
                            owner.get("world"),
                            source.isEmpty()
                                    ? context.configuration().get("world")
                                    : sources.get(source))
                    || !Objects.equals(
                            owner.get("kind"),
                            source.isEmpty()
                                    ? "context"
                                    : sources.containsKey(source)
                                                    && Json.list(
                                                                    context.configuration()
                                                                            .get("sources"))
                                                            .stream()
                                                            .map(Json::map)
                                                            .anyMatch(
                                                                    s ->
                                                                            source.equals(
                                                                                            s.get(
                                                                                                    "path"))
                                                                                    && Boolean.TRUE
                                                                                            .equals(
                                                                                                    s
                                                                                                            .get(
                                                                                                                    "publish")))
                                            ? "source"
                                            : "resource")
                    || Boolean.TRUE.equals(f.get("declaration_resolved"))
                    || existing.ids.contains(Json.string(f, "id"))
                    || !ids.add(Json.string(f, "id")))
                throw new IllegalArgumentException("EXTENSION_FACT_OWNERSHIP_OR_EVIDENCE");
            Map<String, Object> a = Json.map(f.get("attributes"));
            if ("external_symbol".equals(kind)
                    && (!source.isEmpty()
                            || f.get("span") != null
                            || Json.string(f, "signature").isEmpty()
                            || !"possible".equals(f.get("status"))
                            || !extension.producer().equals(f.get("origin"))
                            || !Boolean.FALSE.equals(a.get("source_available"))
                            || !"binary_metadata".equals(a.get("binding_origin"))
                            || Boolean.TRUE.equals(f.get("evidence_complete"))))
                throw new IllegalArgumentException("EXTENSION_EXTERNAL_SYMBOL_EVIDENCE");
            if ("facet".equals(kind)) {
                Integer version = extension.facets().get(Json.string(f, "relation"));
                if (version == null
                        || !(a.get("schema_version") instanceof Number n)
                        || n.intValue() != version)
                    throw new IllegalArgumentException("EXTENSION_FACET_SCHEMA");
            }
            a.put("context", context.configuration().get("context"));
            a.put("extension_id", extension.id());
            a.put("extension_version", extension.version());
        }
        for (Object raw : facts)
            for (String field : List.of("parent", "target")) {
                String id = Json.string(Json.map(raw), field);
                if (!id.isEmpty() && !ids.contains(id) && !existing.ids.contains(id))
                    throw new IllegalArgumentException("EXTENSION_DANGLING_LINK");
            }
        return facts;
    }
}
