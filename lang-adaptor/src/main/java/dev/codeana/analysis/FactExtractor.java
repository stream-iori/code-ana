package dev.codeana.analysis;

import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.RecordArchive;
import dev.codeana.common.TypeEvidence;
import dev.codeana.common.Utf8Offsets;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Native-side extraction from detached compiler data; never depends on javac classes. */
public final class FactExtractor {
    public static final String PRODUCER = "javac-java8-v1";
    private final Map<String, Object> context;
    private final Map<String, Map<String, Object>> sources = new TreeMap<>();
    private final Map<String, Utf8Offsets> positions = new HashMap<>();
    private final Map<String, String> symbols = new HashMap<>();
    private final Map<String, Integer> facts = new TreeMap<>();
    private final RecordArchive rows;
    private Map<String, Object> pending;
    private final Set<String> incomplete = new HashSet<>();
    private final Set<String> knownSymbols = new HashSet<>();
    private final String world;
    private final String contextDigest;
    private final Map<String, Integer> sourceSequences = new HashMap<>();
    private int sequence;

    public FactExtractor(Map<String, Object> context, String contextDigest, RecordArchive rows)
            throws Exception {
        this.context = context;
        this.contextDigest = contextDigest;
        this.rows = rows;
        world = Json.string(context, "world");
        if (context.get("known_signatures") instanceof List)
            for (Object signature : Json.list(context.get("known_signatures"))) {
                String id = symbol((String) signature);
                knownSymbols.add(id);
            }
        for (Object raw : Json.list(context.get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            String path = Json.string(source, "path");
            sources.put(path, source);
            byte[] bytes = Json.read(Path.of(Json.string(source, "input")));
            if (!Json.sha(bytes).equals(source.get("hash")))
                throw new IllegalStateException("CAPTURE_HASH_MISMATCH");
            String text;
            try {
                text =
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                .toString();
            } catch (java.nio.charset.CharacterCodingException invalid) {
                // The compiler reports the original hash/path and omits this source.
                // Preserve its owner for diagnostics, coverage and future replacement.
                incomplete.add(path);
                continue;
            }
            positions.put(path, new Utf8Offsets(text));
        }
    }

    private boolean publish(String source) {
        return sources.containsKey(source)
                && Boolean.TRUE.equals(sources.get(source).get("publish"));
    }

    private Map<String, Object> owner(String source) {
        return source.isEmpty()
                ? Json.object(
                        "world",
                        world,
                        "producer",
                        PRODUCER,
                        "kind",
                        "context",
                        "id",
                        context.get("context"))
                : Json.object(
                        "world",
                        sources.get(source).get("world"),
                        "producer",
                        PRODUCER,
                        "kind",
                        "source",
                        "id",
                        source);
    }

    private String symbol(String signature) {
        return signature.isEmpty()
                ? ""
                : symbols.computeIfAbsent(signature, s -> Json.key("symbol", world, s));
    }

    private Map<String, Object> span(String path, Map<String, Object> node) {
        long start = Json.number(node, "start"), end = Json.number(node, "end");
        if (start < 0 || end < 0) return null;
        Utf8Offsets map = positions.get(path);
        if (map == null || start > end || map.offset(start) < 0 || map.offset(end) < 0)
            throw new IllegalStateException("UTF16_SPAN_INVALID");
        return Json.object("start", map.offset(start), "end", map.offset(end));
    }

    private Map<String, Object> fact(String id, String kind, String source) {
        if (facts.size() >= rows.recordLimit())
            throw new IllegalStateException(
                    "FACT_BUDGET context="
                            + context.get("context")
                            + " count="
                            + facts.size()
                            + " limit="
                            + rows.recordLimit());
        flush();
        Map<String, Object> f =
                Json.object(
                        "id",
                        id,
                        "kind",
                        kind,
                        "owner",
                        owner(source),
                        "source",
                        source,
                        "span",
                        null,
                        "name",
                        "",
                        "signature",
                        "",
                        "parent",
                        "",
                        "target",
                        "",
                        "relation",
                        "",
                        "status",
                        "complete",
                        "origin",
                        "javac",
                        "evidence_complete",
                        true,
                        "declaration_resolved",
                        false,
                        "attributes",
                        new TreeMap<String, Object>());
        if (facts.putIfAbsent(id, rows.size()) != null)
            throw new IllegalStateException("DUPLICATE_FACT: " + id);
        pending = f;
        return f;
    }

    /** Callers finish a row before requesting the next row; only compact identities remain live. */
    private void flush() {
        if (pending != null) {
            if (Boolean.TRUE.equals(context.get("type_graph")))
                pending.put("attributes",TypeEvidence.encode(Json.map(pending.get("attributes"))));
            rows.add(pending);
            pending = null;
        }
    }

    private Map<String, Object> occurrence(String kind, Map<String, Object> node) {
        String path = Json.string(node, "source");
        String id =
                Json.key(
                        "occurrence",
                        world,
                        path,
                        kind,
                        String.valueOf(node.get("start")),
                        String.valueOf(node.get("end")),
                        Integer.toString(sourceSequences.merge(path, 1, Integer::sum)));
        Map<String, Object> f = fact(id, kind, path);
        f.put("span", span(path, node));
        f.put("parent", symbol(Json.string(node, "parent")));
        f.put("attributes", new TreeMap<>(Json.map(node.get("attributes"))));
        return f;
    }

    public List<Object> extract(Map<String, Object> model) throws Exception {
        SourceTypeReferences sourceTypes = new SourceTypeReferences(model);
        Map<String, Map<String, Object>> callSites = new HashMap<>();
        Map<String, List<String>> annotationOrigins = new HashMap<>();
        // Locals in auxiliary method bodies are compiler inputs, not dependency API. Retain
        // any actually referenced local and its owner chain; keep all type/member headers for
        // framework lookup, including lifecycle methods with no ordinary Java call site.
        Set<String> requiredAuxiliary = new HashSet<>();
        Map<String, String> parents = new HashMap<>();
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> d = Json.map(raw);
            parents.put(Json.string(d, "signature"), Json.string(d, "parent"));
            String original = (String) Json.map(d.get("attributes")).get("source_declaration_origin");
            if (original != null && publish(Json.string(d, "source")))
                annotationOrigins.computeIfAbsent(original, unused -> new ArrayList<>())
                        .add(Json.string(d, "signature"));
        }
        for (Object raw : Json.list(model.get("observations"))) {
            Map<String, Object> n = Json.map(raw);
            if (!publish(Json.string(n, "source"))) continue;
            for (String key : List.of("parent", "target", "modeled_target")) {
                String signature = Json.string(n.containsKey(key) ? n : Map.of(key, ""), key);
                while (!signature.isEmpty() && requiredAuxiliary.add(signature))
                    signature = parents.getOrDefault(signature, "");
            }
        }
        // Establish source symbol identities before any references. Auxiliary declarations get
        // explicit source-unavailable external records and are never silently published as source.
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> d = Json.map(raw);
            String path = Json.string(d, "source"), sig = Json.string(d, "signature");
            symbols.put(
                    sig,
                    Json.key(
                            "symbol",
                            publish(path) ? Json.string(sources.get(path), "world") : world,
                            sig));
        }
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> d = Json.map(raw);
            String path = Json.string(d, "source"), sig = Json.string(d, "signature");
            if (!publish(path)) {
                String kind = Json.string(Json.map(d.get("attributes")), "element_kind");
                if (Set.of("PARAMETER", "LOCAL_VARIABLE", "EXCEPTION_PARAMETER",
                                "RESOURCE_VARIABLE", "INITIALIZER", "LAMBDA", "METHOD_REF")
                        .contains(kind) && !requiredAuxiliary.contains(sig)) continue;
                Map<String, Object> attributes = new TreeMap<>(Json.map(d.get("attributes")));
                if (sources.containsKey(path)) {
                    Map<String, Object> source = sources.get(path);
                    attributes.put(
                            "source_origin",
                            Json.object(
                                    "path",
                                    path,
                                    "hash",
                                    source.get("hash"),
                                    "world",
                                    source.get("world"),
                                    "module",
                                    source.get("module"),
                                    "scope",
                                    source.get("scope")));
                }
                external(sig, Json.string(d, "name"), attributes);
                continue;
            }
            Map<String, Object> f = fact(symbol(sig), "declaration", path);
            f.put("name", d.get("name"));
            f.put("signature", sig);
            f.put("parent", symbol(Json.string(d, "parent")));
            f.put("span", span(path, d));
            Map<String, Object> attrs = new TreeMap<>(Json.map(d.get("attributes")));
            attrs.put(
                    "logical_key",
                    Json.key("logical", Json.string(sources.get(path), "module"), sig));
            attrs.put("generated", d.get("generated"));
            attrs.put("source_available", f.get("span") != null);
            f.put("attributes", attrs);
            f.put("declaration_resolved", !attrs.containsKey("extension_producer"));
            if (attrs.containsKey("extension_producer")) {
                f.put("origin", attrs.get("binding_origin"));
                f.put("evidence_complete", false);
            }
            if (attrs.containsKey("type_model")) {
                Map<String, Object> type = fact(Json.key("type", sig, world), "facet", path);
                type.put("parent", f.get("id"));
                type.put("relation", "java.type");
                type.put(
                        "attributes",
                        Json.object(
                                "schema_version", Boolean.TRUE.equals(context.get("type_graph")) ? 3 : 2,
                                "declared", attrs.get("type_model"),
                                "return", attrs.get("return_type_model"),
                                "parameters", attrs.get("parameter_type_models")));
            }
            if (attrs.containsKey("extension_producer")) {
                int index = 0;
                for (Object annotation :
                        attrs.get("annotations") instanceof List
                                ? Json.list(attrs.get("annotations"))
                                : List.of()) {
                    Map<String, Object> value = Json.map(annotation);
                    Map<String, Object> generated =
                            fact(
                                    Json.key(
                                            "modeled-annotation",
                                            world,
                                            sig,
                                            Integer.toString(index++)),
                                    "facet",
                                    path);
                    generated.put("parent", f.get("id"));
                    generated.put("relation", "java.annotation");
                    generated.put("origin", attrs.get("binding_origin"));
                    generated.put("evidence_complete", false);
                    generated.put("status", "modeled");
                    generated.put(
                            "attributes",
                            Json.object(
                                    "schema_version",
                                    2,
                                    "resolved_type",
                                    value.get("type"),
                                    "explicit",
                                    value.get("explicit"),
                                    "effective",
                                    value.get("effective"),
                                    "retention",
                                    value.get("retention"),
                                    "generated",
                                    true,
                                    "generated_from",
                                    attrs.get("generated_from"),
                                    "annotation_origin",
                                    "static_declaration_overlay"));
                }
            }
        }
        for (Object raw : Json.list(model.get("external"))) {
            Map<String, Object> e = Json.map(raw);
            external(
                    Json.string(e, "signature"),
                    Json.string(e, "name"),
                    Json.map(e.get("attributes")));
        }
        for (Object raw : Json.list(model.get("observations"))) {
            Map<String, Object> n = Json.map(raw);
            String path = Json.string(n, "source");
            if (!publish(path)) continue;
            String kind = Json.string(n, "kind");
            if (kind.equals("relation")) {
                Map<String,Object> position=sourceTypes.hierarchyPosition(n);
                if(!position.isEmpty()) {
                    n=new TreeMap<>(n);
                    n.put("start",position.get("start"));n.put("end",position.get("end"));
                }
            }
            if (kind.equals("call")) {
                Map<String, Object> site = occurrence("call_site", n);
                callSites.put(
                        path + ':' + n.get("start") + ':' + n.get("end"),
                        Map.of("id", site.get("id")));
                boolean resolved = Boolean.TRUE.equals(n.get("resolved"));
                boolean complete = Boolean.TRUE.equals(n.get("complete"));
                site.put("name", n.get("name"));
                site.put("status", resolved ? "resolved" : "unresolved");
                site.put("evidence_complete", complete);
                Map<String, Object> binding =
                        fact(Json.key("binding", (String) site.get("id")), "facet", path);
                binding.put("parent", site.get("id"));
                binding.put("relation", "java.binding");
                // The call site owns the complete selected/declared/expression type evidence.
                // Facets and confirmed targets point to that owner instead of copying its graph.
                binding.put("attributes", Json.object("schema_version", Boolean.TRUE.equals(context.get("type_graph")) ? 4 : 3,
                        "evidence_owner", site.get("id"), "resolved", resolved, "complete", complete));
                binding.put("evidence_complete", complete);
                if (resolved && !n.containsKey("modeled_target")
                        && !Boolean.TRUE.equals(Json.map(n.get("attributes")).get("recovery_target_blocked"))) {
                    Map<String, Object> target =
                            fact(Json.key("target", (String) site.get("id")), "call_target", path);
                    target.put("parent", site.get("id"));
                    target.put("target", symbol(Json.string(n, "target")));
                    target.put("status", "resolved");
                    target.put("declaration_resolved", true);
                    target.put("evidence_complete", complete);
                    target.put("attributes", Json.object("binding_evidence", site.get("id")));
                }
                if (n.containsKey("modeled_target")) {
                    Map<String, Object> target =
                            fact(Json.key("target", (String) site.get("id")), "call_target", path);
                    target.put("parent", site.get("id"));
                    target.put("target", symbol(Json.string(n, "modeled_target")));
                    target.put("status", "possible");
                    target.put(
                            "origin",
                            Json.map(site.get("attributes"))
                                    .getOrDefault("binding_origin", "lombok_model"));
                    target.put("evidence_complete", false);
                    target.put(
                            "declaration_resolved", !"lombok_model".equals(target.get("origin")));
                    target.put("attributes", new TreeMap<>(Json.map(site.get("attributes"))));
                }
                if (!complete) incomplete.add(path);
                Map<String, Object> dep =
                        fact(Json.key("dependency", (String) site.get("id")), "dependency", path);
                dep.put("parent", site.get("id"));
                dep.put("target", resolved ? symbol(Json.string(n, "target")) : "");
                dep.put("relation", resolved ? "binding" : "failed_lookup");
                dep.put(
                        "attributes",
                        Json.object("lookup", n.get("name"), "context", context.get("context")));
            } else if (kind.equals("reflection")) {
                Map<String, Object> attributes = new TreeMap<>(Json.map(n.get("attributes")));
                Map<String, Object> site =
                        callSites.get(path + ':' + n.get("start") + ':' + n.get("end"));
                Map<String, Object> reflected = occurrence("facet", n);
                reflected.put("relation", "java.reflection");
                reflected.put("origin", "reflection_model");
                reflected.put("evidence_complete", false);
                reflected.put("status", "possible");
                if (site != null) reflected.put("parent", site.get("id"));
                attributes.put("schema_version", 1);
                reflected.put("attributes", attributes);
                Map<String, Object> edge =
                        occurrence(
                                "call".equals(n.get("role")) && site != null
                                        ? "call_target"
                                        : "reference",
                                n);
                edge.put("target", symbol(Json.string(n, "target")));
                edge.put("origin", "reflection_model");
                edge.put("status", "possible");
                edge.put("evidence_complete", false);
                edge.put("attributes", attributes);
                if ("call_target".equals(edge.get("kind"))) edge.put("parent", site.get("id"));
                else edge.put("relation", "reflection_lookup");
            } else if (kind.equals("reference") || kind.equals("relation")) {
                Map<String, Object> f = occurrence(kind, n);
                f.put("target", symbol(Json.string(n, "target")));
                if ("modeled_override".equals(Json.map(n.get("attributes")).get("binding_origin"))) {
                    f.put("origin", "modeled_override");
                    f.put("status", "possible");
                    f.put("evidence_complete", false);
                } else if ("modeled_enum_label".equals(Json.map(n.get("attributes")).get("binding_origin"))) {
                    f.put("origin", "source_recovery");
                    f.put("status", "possible");
                    f.put("evidence_complete", false);
                } else if (Boolean.FALSE.equals(n.get("resolved"))) {
                    f.put("status", "unresolved");
                    f.put("evidence_complete", false);
                    incomplete.add(path);
                }
                if (n.containsKey("relation")) f.put("relation", n.get("relation"));
                if (n.containsKey("name")) f.put("name", n.get("name"));
                String access = (String) Json.map(f.get("attributes")).get("field_access");
                if (access != null) {
                    for (String role :
                            access.equals("read_write")
                                    ? List.of("reads", "writes")
                                    : List.of(access.equals("write") ? "writes" : "reads")) {
                        String id = Json.key("field-access", Json.string(f, "id"), role);
                        Map<String, Object> occurrence = fact(id, "reference", path);
                        Map<String, Object> copy = new TreeMap<>(f);
                        copy.put("id", id);
                        copy.put("relation", role);
                        occurrence.putAll(copy);
                    }
                } else if ("type".equals(Json.map(f.get("attributes")).get("reference_kind"))) {
                    f.put("relation", "type_navigation");
                }
            } else if (kind.equals("annotation")) {
                Map<String, Object> f = occurrence("facet", n);
                f.put("relation", "java.annotation");
                Map<String, Object> a = Json.map(f.get("attributes"));
                a.put("schema_version", 2);
                a.put("name", n.get("name"));
                a.put("resolved_type", n.get("target"));
                // Keep the exact written span/values while disclosing the modeled owner.
                Map<String, Object> original = new TreeMap<>(f);
                for (String target : annotationOrigins.getOrDefault(Json.string(n,"parent"),List.of())) {
                    // Builder.Default controls generation on the original field. It is
                    // not a source annotation of the generated builder storage field.
                    if (Set.of("lombok.Builder$Default", "lombok.Builder.Default")
                            .contains(Json.string(n, "target"))) continue;
                    String id = Json.key("source-annotation-projection", Json.string(original,"id"), target);
                    Map<String, Object> projected = fact(id,"facet",path);
                    projected.putAll(original); projected.put("id",id); projected.put("parent",symbol(target));
                    projected.put("origin","source_projection"); projected.put("status","modeled");
                    projected.put("evidence_complete",false);
                    Map<String,Object> attrs = new TreeMap<>(Json.map(original.get("attributes")));
                    attrs.put("annotation_origin","source_backed_generated_declaration");
                    attrs.put("generated",true); attrs.put("generated_from",n.get("parent"));
                    projected.put("attributes",attrs);
                }
            }
        }
        generatedTypeReferences(model,sourceTypes);
        SyntaxFacts.emit(context, model, (id, path) -> fact(id, "facet", path));
        sourceTypeReferences(sourceTypes);
        for (Object raw : Json.list(model.get("diagnostics"))) {
            Map<String, Object> d = Json.map(raw);
            String path = Json.string(d, "source");
            // Context diagnostics also cover errors in auxiliary semantic inputs.
            String publishPath = publish(path) ? path : "";
            Map<String, Object> f =
                    fact(
                            Json.key("diagnostic", world, Integer.toString(sequence++)),
                            "diagnostic",
                            publishPath);
            f.put("name", d.get("code"));
            f.put("status", d.get("severity"));
            f.put("attributes", new TreeMap<>(d));
            if (!publishPath.isEmpty()) f.put("span", span(path, d));
            if (d.get("severity").equals("ERROR")) {
                if (publishPath.isEmpty()) incomplete.addAll(sources.keySet());
                else incomplete.add(path);
            }
            if (Set.of("REFLECTION_FLOW_BUDGET", "MODELED_OVERRIDE_INCOMPLETE").contains(d.get("code")) && !publishPath.isEmpty())
                incomplete.add(path);
            if (Set.of("TYPE_MODEL_DEPTH_BUDGET", "TYPE_MODEL_NODE_BUDGET").contains(d.get("code"))) {
                if (publishPath.isEmpty()) incomplete.addAll(sources.keySet());
                else incomplete.add(path);
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : sources.entrySet()) {
            String path = e.getKey();
            if (!publish(path)) continue;
            Map<String, Object> f = fact(Json.key("coverage", world, path), "coverage", path);
            boolean complete = !incomplete.contains(path);
            f.put("status", complete ? "complete" : "partial");
            f.put("evidence_complete", complete);
            f.put(
                    "attributes",
                    Json.object(
                            "source_hash",
                            e.getValue().get("hash"),
                            "context",
                            context.get("context"),
                            "capabilities",
                            List.of(
                                    "declarations",
                                    "calls",
                                    "references",
                                    "relations",
                                    "annotations",
                                    "field_access",
                                    "type_references",
                                    "control_context",
                                    "bounded_reflection")));
            Map<String, Object> p = fact(Json.key("provenance", world, path), "provenance", path);
            p.put(
                    "attributes",
                    Json.object(
                            "source_hash",
                            e.getValue().get("hash"),
                            "compiler",
                            "javac8",
                            "model_version",
                            ModelArchive.VERSION,
                            "processor_execution",
                            false));
        }
        Map<String, Object> contextFact =
                fact(Json.key("context", world, Json.string(context, "context")), "provenance", "");
        contextFact.put(
                "attributes",
                Json.object(
                        "context", context.get("context"),
                        "context_digest", contextDigest,
                        "extension_fingerprint", context.getOrDefault("extension_fingerprint", ""),
                        "artifact_inputs",
                                Json.list(context.get("classpath")).stream()
                                        .map(p -> Path.of((String) p).getFileName().toString())
                                        .toList(),
                        "semantic_sources",
                                sources.values().stream()
                                        .map(s -> List.of(s.get("path"), s.get("hash")))
                                        .toList()));
        // Never make a dangling recovered/compiler-error symbol appear resolved.
        flush();
        rows.seal();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> f = rows.factHeader(index);
            for (String field : List.of("parent", "target")) {
                String id = (String) f.get(field);
                if (!id.isEmpty() && !facts.containsKey(id) && !knownSymbols.contains(id))
                    throw new IllegalStateException("MODEL_DANGLING_" + field + ": " + f);
            }
        }
        List<Integer> positions = new ArrayList<>(facts.values());
        return new FactHeaders.View() {
            @Override
            public int size() {
                return positions.size();
            }

            @Override
            public Object get(int index) {
                return rows.get(positions.get(index));
            }

            @Override
            public Map<String, Object> factHeader(int index) {
                return rows.factHeader(positions.get(index));
            }

            @Override
            public String factKind(int index) { return rows.factKind(positions.get(index)); }

            @Override
            public String factWorld(int index) { return rows.factWorld(positions.get(index)); }

            @Override
            public boolean mightHaveId(int index, Set<Long> wanted) {
                return rows.mightHaveId(positions.get(index), wanted);
            }

            @Override
            public void copyRecord(int index, RecordArchive destination) {
                rows.copyRecord(positions.get(index), destination);
            }
        };
    }

    private void generatedTypeReferences(Map<String, Object> model, SourceTypeReferences sourceTypes) {
        Set<String> neededOrigins = new HashSet<>();
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw), attrs = Json.map(declaration.get("attributes"));
            if (!publish(Json.string(declaration, "source")) || !attrs.containsKey("extension_producer")) continue;
            Object origin = attrs.getOrDefault("source_declaration_origin", attrs.get("generated_from"));
            if (origin instanceof String) neededOrigins.add((String) origin);
        }
        Map<String, Map<String, Object>> origins = new HashMap<>();
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            String signature = Json.string(declaration, "signature");
            if (neededOrigins.contains(signature))
                origins.put(signature, Json.object("source", declaration.get("source"),
                        "start", declaration.get("start"), "end", declaration.get("end")));
        }
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            String path = Json.string(declaration, "source");
            if (!publish(path)) continue;
            String signature = Json.string(declaration, "signature");
            Map<String, Object> attrs = Json.map(declaration.get("attributes"));
            Map<String, Object> sourceOrigin = origins.get(attrs.getOrDefault("source_declaration_origin", attrs.get("generated_from")));
            int[] occurrence = {0};
            GeneratedTypeReferences.emit(declaration, use -> {
                if(sourceTypes.excluded(use.target()))return;
                String target = symbol(use.target());
                boolean available = facts.containsKey(target) || knownSymbols.contains(target);
                Map<String, Object> reference = fact(Json.key("generated-type-reference", world,
                        signature, Integer.toString(occurrence[0]++)), "reference", path);
                reference.put("parent", symbol(signature));
                if (sourceOrigin != null && path.equals(sourceOrigin.get("source")))
                    reference.put("span", span(path, sourceOrigin));
                reference.put("target", available ? target : "");
                reference.put("relation", "type_reference");
                reference.put("status", available ? "possible" : "unresolved");
                reference.put("origin", attrs.get("binding_origin"));
                reference.put("evidence_complete", false);
                reference.put("attributes", Json.object("reference_kind", "type",
                        "type_context", use.context(), "generic_depth", use.genericDepth(),
                        "target_signature", use.target(), "generated", true,
                        "binding_origin", attrs.get("binding_origin"),
                        "source_declaration_origin", attrs.get("source_declaration_origin"),
                        "span_origin", "generated_declaration_source_origin",
                        "reason", available ? "" : "target_declaration_unavailable"));
            });
        }
    }

    private void sourceTypeReferences(SourceTypeReferences sourceTypes) {
        sourceTypes.emit(use -> {
            String path = Json.string(use.owner(), "source");
            if (!publish(path)) return;
            String signature = Json.string(use.owner(), "signature");
            String target = symbol(use.target());
            boolean available = facts.containsKey(target) || knownSymbols.contains(target);
            if (!available) external(use.target(), use.target().substring(use.target().lastIndexOf('.')+1),
                    Json.object("element_kind","TYPE","binding_origin","javac_type","declared_type",use.target()));
            Map<String,Object> f = fact(Json.key("source-type-reference", world, path, signature,
                    Integer.toString(use.ordinal())), "reference", path);
            f.put("parent", symbol(signature));
            f.put("target", target);
            f.put("relation", "type_reference");
            f.put("span", span(path, use.position()));
            f.put("attributes", Json.object("reference_kind", "type", "type_context", use.context(),
                    "generic_depth", use.depth(), "target_signature", use.target()));
            if (!use.proof().isEmpty()) {
                f.put("origin", "javac_generated_signatures");
                Json.map(f.get("attributes")).put("signature_attribution", Json.object(
                        "evidence_owner", symbol(signature), "signature_sha256", use.proof().get("signature_sha256"),
                        "auxiliary_inputs_sha256", use.proof().get("auxiliary_inputs_sha256")));
            }
            if (use.recovered()) {
                f.put("origin", "source_recovery");f.put("status", "possible");f.put("evidence_complete", false);
                Json.map(f.get("attributes")).put("modeled_input", true);
            }
        });
        for (Map<String,Object> diagnostic : sourceTypes.diagnostics()) {
            String path=Json.string(diagnostic,"source");
            if(!publish(path))continue;
            incomplete.add(path);
            Map<String,Object> f=fact(Json.key("type-reference-budget",world,path,Json.string(diagnostic,"owner")),"diagnostic",path);
            f.put("name",diagnostic.get("code"));f.put("status","WARNING");
            f.put("span",span(path,diagnostic));f.put("attributes",diagnostic);
        }
    }

    private void external(String sig, String name, Map<String, Object> attrs) {
        String id = symbol(sig);
        if (facts.containsKey(id) || knownSymbols.contains(id)) return;
        Map<String, Object> f = fact(id, "external_symbol", "");
        f.put("signature", sig);
        f.put("name", name);
        Map<String, Object> a = new TreeMap<>(attrs);
        if (a.containsKey("extension_producer")) {
            f.put("origin", a.get("binding_origin"));
            f.put("evidence_complete", false);
        }
        if ("binary_metadata".equals(a.get("binding_origin"))) f.put("status", "possible");
        a.put("source_available", false);
        // Ordered CP belongs to the context. Repeating hundreds of entries for every auxiliary
        // declaration made real-project output quadratic. Keep only the proven origin here.
        Map<String, Object> origin =
                a.get("artifact_origin") instanceof Map
                        ? Json.map(a.get("artifact_origin"))
                        : Map.of();
        a.put(
                "artifact_inputs",
                origin.containsKey("name") && !a.containsKey("source_origin")
                        ? List.of(origin.get("name"))
                        : List.of());
        f.put("attributes", a);
    }
}
