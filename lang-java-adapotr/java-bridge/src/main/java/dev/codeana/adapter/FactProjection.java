package dev.codeana.adapter;

import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.model.*;
import dev.codeana.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Projects Anatomist's evidence into code-ana identities; performs no Java inference. */
public final class FactProjection {
    public static final String PRODUCER = "javac-java8-v1";
    private final CapturedProject project;
    private final FactArchive output;

    private record Emitted(String digest, int record) {}

    private final Map<String, Emitted> emitted = new HashMap<>();
    private final Map<String, Integer> relationOccurrences = new HashMap<>();
    private final Map<String, Object> owners = new TreeMap<>();
    private final Map<String, Symbol> symbols = new HashMap<>();
    private final Set<String> published = new HashSet<>();
    private final Map<String, List<Symbol>> rawSymbols = new HashMap<>();
    private CapturedProject.Context context;
    private Set<String> visibleWorlds = Set.of();
    private JavaAnalysisContext analysis;
    private JavaModel model;
    private JavaIdentities identities;
    private final Map<String, JavaModel.DeclarationRef> declarationRefs = new HashMap<>();
    private final Map<String, String> rawSites = new HashMap<>();
    private record BindingEvidence(Object span, String owner, Map<String, Object> attributes) {}
    private record SemanticSite(Object span, String id) {}
    private final Map<String, List<BindingEvidence>> pendingBindings = new HashMap<>();
    private final Map<String, List<SemanticSite>> semanticSites = new HashMap<>();
    private record ConfigurationOccurrence(Symbol symbol, Integer begin, Integer end, Integer ordinal) {}
    private final Map<String, List<ConfigurationOccurrence>> configurationOccurrences = new HashMap<>();
    private final Map<String, Map<String, Object>> externalMetadata = new HashMap<>();
    private final Map<String, String> externalSignatures = new HashMap<>();
    private final Map<String, String> injectionParameters = new HashMap<>();
    private final Map<String, JavaModel.DeclarationRef> pointDeclarations = new HashMap<>();
    private final TypeGraphProjection.Cache typeGraphs = new TypeGraphProjection.Cache();

    public void closeContext() {
        if (model != null) model.close();
        model = null;
        analysis = null;
        identities = null;
        declarationRefs.clear();
        rawSites.clear();
        pendingBindings.clear();
        semanticSites.clear();
        externalSignatures.clear();
        externalMetadata.clear();
        pointDeclarations.clear();
        relationOccurrences.clear();
        typeGraphs.clear();
    }

    private final LinkedHashMap<String, Positions> positions =
            new LinkedHashMap<>(8, .75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, Positions> entry) {
                    return size() > 4;
                }
            };

    private record Symbol(String raw, String signature, String world, String path, String id) {}

    private record Positions(String text, Utf8Offsets utf8, int[] starts) {}

    public FactProjection(CapturedProject project, FactArchive output) {
        this.project = project;
        this.output = output;
        project.contexts.forEach(
                c -> c.publish().forEach(p -> published.add(project.source(p).path())));
    }

    public void context(CapturedProject.Context value) {
        context = value;
        visibleWorlds =
                value.sources().stream()
                        .map(CapturedProject.Source::world)
                        .collect(java.util.stream.Collectors.toSet());
    }

    public void retain(Map<String, Object> fact) {
        Map<String, Object> attrs = Json.map(fact.get("attributes"));
        var source = project.sources.get(Json.string(fact, "source"));
        if (source == null || !(attrs.get("upstream_id") instanceof String raw)) return;
        if (!Set.of("declaration", "facet").contains(Json.string(fact, "kind"))) return;
        Symbol symbol =
                new Symbol(
                        raw,
                        Json.string(fact, "signature"),
                        source.world(),
                        source.path(),
                        Json.string(fact, "id"));
        symbols.putIfAbsent(source.world() + "\n" + raw, symbol);
        symbols.putIfAbsent(source.world() + "\n" + source.path() + "\n" + raw, symbol);
        rawSymbols.computeIfAbsent(raw, ignored -> new ArrayList<>()).add(symbol);
    }

    private record UnitKey(String path, String sourceHash, String contract) {}

    private record CatalogueKey(UnitKey unit, String raw) {}

    private record CatalogueValue(String signature, SymbolFact fact, long weight) {}

    private final LinkedHashMap<CatalogueKey, CatalogueValue> catalogueCache =
            new LinkedHashMap<>(128, .75f, true);
    private long catalogueBytes;
    private long catalogueHits, catalogueMisses, encodings, encodedBytes;

    public Map<String, Object> statistics() {
        return Json.object("fact_encodings", encodings, "encoded_fact_bytes", encodedBytes,
                "archive_records", output.size(), "archive_bytes", output.bytes(),
                "catalogue_hits", catalogueHits, "catalogue_misses", catalogueMisses,
                "catalogue_entries", catalogueCache.size(), "catalogue_weight_bytes", catalogueBytes);
    }

    public List<SymbolFact> inventory(JavaAnalysisContext analysis) {
        List<SymbolFact> catalogue = new ArrayList<>();
        closeContext();
        this.analysis = analysis;
        for (var unit : analysis.units())
            for (var binding : unit.bindings().values()) rememberBinding(binding, unit.types());
        model = new JavaModel(analysis);
        identities = new JavaIdentities(model);
        for (var unit : analysis.units()) {
            String contract;
            boolean cacheable = true;
            try { contract = JavaContract.fingerprint(unit); }
            catch (IllegalArgumentException failure) {
                if (!Objects.toString(failure.getMessage(), "").startsWith("contract exceeds")) throw failure;
                contract = ""; cacheable = false;
            }
            UnitKey unitKey =
                    new UnitKey(
                            unit.path().toString(),
                            unit.sourceHash(),
                            contract);
            for (var ref : model.declarations(unit.path())) {
                var d = model.declaration(ref);
                if (!Set.of(
                                "CLASS",
                                "INTERFACE",
                                "ENUM",
                                "ANNOTATION_TYPE",
                                "RECORD",
                                "METHOD",
                                "CONSTRUCTOR",
                                "FIELD",
                                "ENUM_CONSTANT",
                                "RECORD_COMPONENT")
                        .contains(d.symbol().kind())) continue;
                String raw = identities.declaration(ref);
                var key = new CatalogueKey(unitKey, raw);
                CatalogueValue value = cacheable ? catalogueCache.get(key) : null;
                if (value == null) catalogueMisses++; else catalogueHits++;
                if (value == null) {
                    String signature = raw.replace("...", "[]");
                    boolean sourceIdentity = sourceScopedIdentity(ref);
                    if (!sourceIdentity && !d.binaryName().isEmpty()) signature = d.binaryName();
                    else if (!sourceIdentity && Set.of("METHOD", "CONSTRUCTOR").contains(d.symbol().kind())
                            && d.symbol().declaredType() >= 0) {
                        var owner = model.parent(ref).map(model::declaration).orElse(null);
                        if (owner != null && !owner.binaryName().isEmpty())
                            signature =
                                    JavaTypeNames.binaryCallableId(
                                            unit.types(), d.symbol(), owner.binaryName());
                    } else if (!sourceIdentity && model.parent(ref).isPresent()) {
                        var owner = model.declaration(model.parent(ref).orElseThrow());
                        if (!owner.binaryName().isEmpty())
                            signature = owner.binaryName() + "#" + d.symbol().name();
                    }
                    if (d.symbol().kind().equals("CONSTRUCTOR")) {
                        int separator = signature.lastIndexOf('#'),
                                parameters = signature.indexOf('(', separator);
                        if (separator >= 0 && parameters >= 0)
                            signature =
                                    signature.substring(0, separator)
                                            + "#<init>"
                                            + signature.substring(parameters);
                    }
                    var captured = project.source(unit.path());
                    Map<String, Object> metadata =
                            Json.object("static", d.symbol().modifiers().contains("static"));
                    if (d.symbol().declaredType() >= 0) {
                        var type = unit.types().get(d.symbol().declaredType());
                        if (type.kind() == JavaType.Kind.EXECUTABLE)
                            metadata.put(
                                    "returnType",
                                    JavaTypeNames.erased(unit.types(), type.returns()));
                    }
                    var fact =
                            new SymbolFact(
                                    NodeKeyFactory.key(
                                            new SourceIdentity(
                                                    captured.module(),
                                                    SourceScope.valueOf(captured.scope())),
                                            raw),
                                    raw,
                                    d.symbol().kind(),
                                    captured.module(),
                                    captured.scope(),
                                    captured.path(),
                                    Json.write(metadata));
                    long weight =
                            256L
                                    + 2L
                                            * (unitKey.path().length()
                                                    + raw.length()
                                                    + signature.length()
                                                    + fact.metadata().length());
                    value = new CatalogueValue(signature, fact, weight);
                    if (cacheable && weight <= 32L << 20) {
                        catalogueCache.put(key, value);
                        catalogueBytes += weight;
                        while (catalogueCache.size() > 32768 || catalogueBytes > 32L << 20) {
                            var iterator = catalogueCache.entrySet().iterator();
                            var expired = iterator.next().getValue();
                            iterator.remove();
                            catalogueBytes -= expired.weight();
                        }
                    }
                }
                catalogue.add(value.fact());
                var captured = project.source(unit.path());
                register(raw, value.signature(), captured);
                declarationRefs.put(captured.world() + "\n" + raw, ref);
            }
        }
        return catalogue;
    }

    private boolean sourceScopedIdentity(JavaModel.DeclarationRef reference) {
        for (int depth = 0; depth <= 128; depth++) {
            var declaration = model.declaration(reference);
            if (Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                            .contains(declaration.symbol().kind())
                    && declaration.canonicalName().isEmpty()) return true;
            var parent = model.parent(reference);
            if (parent.isEmpty()) return false;
            reference = parent.orElseThrow();
        }
        throw new IllegalArgumentException("declaration identity nesting exceeds budget");
    }

    private Symbol register(String raw, String signature, CapturedProject.Source source) {
        String key = source.world() + "\n" + raw;
        Symbol existing = symbols.get(key);
        if (existing != null) return existing;
        Symbol s =
                new Symbol(
                        raw,
                        signature,
                        source.world(),
                        source.path(),
                        Json.key("symbol", source.world(), signature));
        symbols.put(key, s);
        rawSymbols.computeIfAbsent(raw, k -> new ArrayList<>()).add(s);
        return s;
    }

    private Symbol lookup(String raw, String source) {
        if (raw == null || raw.isEmpty()) return null;
        if (NodeKeyFactory.isKey(raw)) {
            var identity = NodeKeyFactory.identity(raw);
            String symbol = NodeKeyFactory.symbolId(raw);
            var candidates = rawSymbols.getOrDefault(symbol, List.of()).stream()
                    .filter(
                            v -> {
                                var unit = project.sources.get(v.path());
                                return unit.module().equals(identity.module())
                                        && (unit.scope().equals(identity.scope().name())
                                            || unit.resource() && project.contexts.stream().anyMatch(c ->
                                                c.module().equals(identity.module()) && c.scope() == identity.scope()
                                                && c.resources().stream().anyMatch(resource -> resource.world().equals(v.world()))));
                            })
                    .distinct().toList();
            if (candidates.isEmpty() && source != null
                    && project.sources.containsKey(source) && project.sources.get(source).resource()) {
                // Detached XML analyzers may qualify a raw resource ID using
                // their default module. The sealed inventory is authoritative
                // for resources outside Java source roots (module can be empty).
                // Match the exact raw ID and visible captured file, never a bean name.
                var resources = rawSymbols.getOrDefault(symbol, List.of()).stream()
                        .filter(v -> project.sources.get(v.path()).resource())
                        .filter(v -> context.resources().stream().anyMatch(r -> r.path().equals(v.path()))
                                || context.dependencies().contains(project.sources.get(v.path()).module())
                                    && "MAIN".equals(project.sources.get(v.path()).scope()))
                        .distinct().toList();
                if (resources.size() == 1) return resources.getFirst();
            }
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }
        var own = source == null ? null : project.sources.get(source);
        Symbol s = symbols.get(context.world() + "\n" + source + "\n" + raw);
        if (s == null) s = symbols.get((own == null ? context.world() : own.world()) + "\n" + raw);
        if (s != null) return s;
        List<Symbol> known = rawSymbols.getOrDefault(raw, List.of());
        List<Symbol> matches =
                known.stream().filter(v -> visibleWorlds.contains(v.world())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String target(String raw, String source) {
        if (raw == null || raw.isEmpty()) return "";
        Symbol s = lookup(raw, source);
        if (s != null && (published.contains(s.path()) || project.sources.get(s.path()).resource()))
            return s.id();
        String signature =
                s == null
                        ? externalSignatures.getOrDefault(
                                NodeKeyFactory.symbolId(raw),
                                NodeKeyFactory.symbolId(raw).replace("...", "[]"))
                        : s.signature();
        boolean resourceEvidence =
                source != null
                        && project.sources.containsKey(source)
                        && project.sources.get(source).resource();
        // Resource requests are model candidates, including unresolved placeholders.
        // Keep their identity separate from compiler-observed binary declarations.
        String id =
                resourceEvidence
                        ? Json.key("framework_external", context.world(), signature)
                        : Json.key("external", context.world(), signature);
        Map<String, Object> f = fact(id, "external_symbol", "");
        f.put("signature", signature);
        f.put("name", signature);
        f.put("origin", s == null ? "binary_metadata" : "captured_source");
        Map<String, Object> attrs = Json.object("source_available", false, "symbol", signature);
        attrs.putAll(externalMetadata.getOrDefault(signature, Map.of()));
        if (resourceEvidence) {
            f.put("origin", "framework_model");
            f.put("status", "possible");
            f.put("evidence_complete", false);
            attrs.put("runtime_confirmed", false);
            var candidates = rawSymbols.getOrDefault(NodeKeyFactory.symbolId(raw), List.of()).stream()
                    .filter(candidate -> project.sources.get(candidate.path()).resource())
                    .map(Symbol::id).distinct().sorted().toList();
            if (candidates.size() > 1) attrs.put("configuration_candidates", candidates);
        }
        if (s != null) {
            var unit = project.sources.get(s.path());
            attrs.put(
                    "source_origin",
                    Json.object(
                            "path",
                            unit.path(),
                            "hash",
                            unit.hash(),
                            "module",
                            unit.module(),
                            "world",
                            unit.world(),
                            "scope",
                            unit.scope()));
        }
        f.put("attributes", attrs);
        emit(f);
        return id;
    }

    public Map<String, Object> owner(String source) {
        var s = project.sources.get(source);
        return Json.object(
                "world",
                s == null ? context.world() : s.world(),
                "producer",
                PRODUCER,
                "kind",
                source.isEmpty() ? "context" : s != null && s.resource() ? "resource" : "source",
                "id",
                source.isEmpty() ? context.id() : source);
    }

    private Map<String, Object> fact(String id, String kind, String source) {
        return Json.object(
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
    }

    public void emit(Map<String, Object> fact) {
        String json;
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.FACT_JSON, 0, true)) {
            json = Json.write(fact);
        }
        byte[] encoded;
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.FACT_UTF8, 0, true)) {
            encoded = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        encodings++; encodedBytes += encoded.length;
        if (project.deferFactDedup) {
            try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.OWNER_KEY, 0, true)) {
                owners.put(Json.write(fact.get("owner")), fact.get("owner"));
            }
            output.addEncoded(encoded);
            return;
        }
        String digest = Json.sha(encoded);
        Emitted previous =
                emitted.putIfAbsent(Json.string(fact, "id"), new Emitted(digest, output.size()));
        if (previous != null && !previous.digest().equals(digest)) {
            try {
                Path evidence = project.diagnosticRoot.resolve("fact-conflict.json");
                Json.writeTo(
                        evidence,
                        Json.object(
                                "context",
                                context == null ? "" : context.id(),
                                "first",
                                output.get(previous.record()),
                                "second",
                                fact),
                        32L << 20);
            } catch (Exception failure) {
                var conflict = new IllegalStateException("CONFLICTING_FACT_ID: " + fact.get("id"));
                conflict.addSuppressed(failure);
                throw conflict;
            }
            throw new IllegalStateException("CONFLICTING_FACT_ID: " + fact.get("id"));
        }
        if (previous == null) {
            try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.OWNER_KEY, 0, true)) {
                owners.put(Json.write(fact.get("owner")), fact.get("owner"));
            }
            output.addEncoded(encoded);
        }
    }

    public List<Object> owners() {
        return List.copyOf(owners.values());
    }

    public void retainOwner(Map<String, Object> owner) {
        owners.put(Json.write(owner), owner);
    }

    private static Map<String, Object> metadata(String json) {
        return json == null || json.isBlank()
                ? new TreeMap<>()
                : new TreeMap<>(
                        Json.map(
                                Json.parse(
                                        json.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static String canonicalMetadata(String json) {
        return json == null || json.isBlank() ? json : Json.write(metadata(json));
    }

    public void unit(com.anatomist.extract.JavaModelExtractor.UnitFacts unit) {
        extraction(unit.facts());
        String source = project.source(unit.source()).path();
        // Calls are selected by Anatomist's extraction, including its constructor-statement
        // ranges and intrinsic array/enum handling. Raw javac trees remain evidence only.
        for (var evidence : pendingBindings.getOrDefault(source, List.of())) {
            var raw = Json.map(evidence.span());
            var matches = semanticSites.getOrDefault(source, List.of()).stream()
                    .filter(site -> {
                        var range = Json.map(site.span());
                        return Json.number(range, "start") <= Json.number(raw, "start")
                                && Json.number(range, "end") >= Json.number(raw, "end");
                    }).toList();
            // A nested invocation is also inside its enclosing invocation. Attach
            // evidence to the smallest original extraction range, never to an
            // arbitrary outer call. Equal ranges remain ambiguous.
            long width = matches.stream().mapToLong(site -> {
                var range = Json.map(site.span());
                return Json.number(range, "end") - Json.number(range, "start");
            }).min().orElse(-1);
            matches = matches.stream().filter(site -> {
                var range = Json.map(site.span());
                return Json.number(range, "end") - Json.number(range, "start") == width;
            }).toList();
            boolean call = matches.size() == 1;
            String parent = call ? matches.getFirst().id() : evidence.owner();
            var attrs = new TreeMap<>(evidence.attributes());
            attrs.put("schema_version", call ? 4 : 3);
            attrs.put("evidence_owner", parent);
            facet(source, evidence.span(), parent, call ? "java.binding" : "java.type", attrs);
        }
        pendingBindings.remove(source);
        semanticSites.remove(source);
        for (var d : unit.diagnostics()) diagnostic(d);
    }

    public void registerResources(CapturedProject.Context resourceContext, ExtractionResult result) {
        Map<String, Long> counts = result.nodes.stream().collect(java.util.stream.Collectors.groupingBy(
                node -> node.sourceFile + "\n" + node.id, java.util.stream.Collectors.counting()));
        for (var node : result.nodes) {
            var captured = project.sources.get(node.sourceFile);
            if (captured == null || !captured.resource()) continue;
            String key = resourceContext.world() + "\n" + node.sourceFile + "\n" + node.id;
            boolean multiple = counts.get(node.sourceFile + "\n" + node.id) > 1;
            var symbol = new Symbol(node.id, node.id, captured.world(), node.sourceFile,
                    multiple ? Json.key("configuration_occurrence", resourceContext.world(), node.sourceFile,
                            node.id, Objects.toString(node.sourceOrdinal, ""),
                            Json.write(span(node.sourceFile, node.beginLine, node.beginColumn, node.endLine, node.endColumn)))
                            : Json.key("configuration", resourceContext.world(), node.sourceFile, node.id));
            configurationOccurrences.computeIfAbsent(node.sourceFile + "\n" + node.id, ignored -> new ArrayList<>())
                    .add(new ConfigurationOccurrence(symbol, node.beginLine, node.endLine, node.sourceOrdinal));
            if (multiple) {
                symbols.remove(key);
                symbols.remove(captured.world() + "\n" + node.id);
                rawSymbols.computeIfAbsent(node.id, ignored -> new ArrayList<>()).add(symbol);
                continue;
            }
            if (symbols.putIfAbsent(key, symbol) == null) {
                symbols.putIfAbsent(captured.world() + "\n" + node.id, symbol);
                rawSymbols.computeIfAbsent(node.id, ignored -> new ArrayList<>()).add(symbol);
            }
        }
    }

    private Symbol configurationSymbol(Node node) {
        return configurationOccurrences.getOrDefault(node.sourceFile + "\n" + node.id, List.of()).stream()
                .filter(occurrence -> Objects.equals(occurrence.begin(), node.beginLine)
                        && Objects.equals(occurrence.end(), node.endLine)
                        && Objects.equals(occurrence.ordinal(), node.sourceOrdinal))
                .map(ConfigurationOccurrence::symbol).findFirst().orElse(null);
    }

    private String configurationTarget(String raw, String source, Integer line) {
        var occurrences = configurationOccurrences.getOrDefault(
                source + "\n" + NodeKeyFactory.symbolId(raw), List.of());
        // The resource analyzer may qualify a local node with its default
        // module before captured module metadata is attached. Its registered
        // source-local identity is authoritative for this same-file endpoint.
        if (occurrences.size() == 1) return occurrences.getFirst().symbol().id();
        if (occurrences.size() > 1 && line != null) {
            var candidates = occurrences.stream().filter(occurrence -> occurrence.begin() != null
                    && occurrence.end() != null && occurrence.begin() <= line && occurrence.end() >= line).toList();
            if (candidates.size() == 1) return candidates.getFirst().symbol().id();
        }
        return target(raw, source);
    }

    public void extraction(ExtractionResult result) {
        Map<String, Declaration> declarations = new HashMap<>();
        result.declarations.forEach(d -> declarations.put(d.symbolId, d));
        Map<String, String> parents = new HashMap<>();
        Map<String, List<Edge>> outgoing = new HashMap<>();
        for (Edge edge : result.edges) {
            outgoing.computeIfAbsent(NodeKeyFactory.symbolId(edge.sourceId), ignored -> new ArrayList<>())
                    .add(edge);
            if (Set.of("CONFIGURES", "XML_CONTAINS", "CONTAINS").contains(edge.relation))
                parents.put(NodeKeyFactory.symbolId(edge.targetId), edge.sourceId);
            if ("DEFINED_BY".equals(edge.relation) && !edge.isExternal)
                parents.putIfAbsent(NodeKeyFactory.symbolId(edge.sourceId), edge.targetId);
        }
        Map<String, Map<String, Map<String, Object>>> xml = new HashMap<>();
        for (Node node : result.nodes) {
            String source = node.sourceFile;
            if (source == null || !project.sources.containsKey(source))
                throw new IllegalArgumentException("FACT_SOURCE_OUTSIDE_CAPTURE");
            var captured = project.sources.get(source);
            boolean configuration =
                    "configuration".equals(node.domain)
                            || captured.resource()
                            || Set.of("BEAN", "ROUTE", "FRAMEWORK_REGISTRATION")
                                    .contains(Objects.toString(node.kind, ""));
            Symbol symbol;
            if (configuration) {
                symbol = configurationSymbol(node);
                if (symbol == null) symbol =
                        new Symbol(
                                node.id,
                                node.id,
                                captured.world(),
                                source,
                                Json.key("configuration", context.world(), source, node.id));
                String key = context.world() + "\n" + source + "\n" + node.id;
                if (!configurationOccurrences.containsKey(source + "\n" + node.id)
                        && symbols.putIfAbsent(key, symbol) == null)
                    rawSymbols.computeIfAbsent(node.id, k -> new ArrayList<>()).add(symbol);
            } else {
                symbol = lookup(node.id, source);
                if (symbol == null) symbol = register(node.id, node.id, captured);
            }
            Map<String, Object>
                    f = fact(symbol.id(), configuration ? "facet" : "declaration", source),
                    attrs = metadata(node.metadata);
            Declaration d = declarations.get(node.id);
            f.put("name", node.label == null ? node.id : node.label);
            f.put("signature", symbol.signature());
            f.put(
                    "span",
                    span(source, node.beginLine, node.beginColumn, node.endLine, node.endColumn));
            String parent =
                    d == null
                            ? Objects.toString(attrs.get("source_owner_id"), "")
                            : Objects.toString(d.declaringType, "");
            // CONTAINS preserves the immediate lexical owner of nested callbacks.
            // Declaration.declaringType is the enclosing callable/type metadata.
            if (!configuration || parent.isEmpty()) parent = parents.getOrDefault(node.id, parent);
            if (parent.isEmpty() && "FRAMEWORK_REGISTRATION".equals(node.kind)
                    && "spring.annotation.event".equals(attrs.get("mechanism"))) {
                var handlers=outgoing.getOrDefault(node.id,List.of()).stream()
                        .filter(edge->"HANDLES".equals(edge.relation) && !edge.isExternal)
                        .map(edge->edge.targetId).distinct().toList();
                if (handlers.size()==1) parent=handlers.getFirst();
            }
            if (!parent.isEmpty() && !parent.equals(node.id))
                f.put("parent", configurationTarget(parent, source, node.beginLine));
            attrs.put("upstream_id", node.id);
            attrs.put("upstream_metadata", canonicalMetadata(node.metadata));
            attrs.put("upstream_namespace", node.namespace);
            attrs.put("upstream_symbol_id", node.symbolId);
            attrs.put("upstream_qualified_name", node.qualifiedName);
            attrs.put("upstream_entity_kind", node.entityKind);
            attrs.put("upstream_package", node.pkg);
            attrs.put("upstream_module", node.module);
            attrs.put("upstream_scope", node.scope);
            attrs.put("domain", node.domain);
            attrs.put("language", node.language);
            attrs.put("provider_id", node.providerId);
            attrs.put("language_kind", node.languageKind);
            attrs.putIfAbsent("namespace", node.namespace);
            attrs.put("source_location", node.sourceLocation);
            attrs.put("source_ordinal", node.sourceOrdinal);
            attrs.put("upstream_position", XmlConfigurationProjection.position(
                    node.beginLine,node.beginColumn,node.endLine,node.endColumn));
            attrs.put("kind", node.kind);
            attrs.put("upstream_kind", node.kind);
            attrs.put("entity_kind", new com.anatomist.provider.java.JavaLanguageProvider().entityKind(node.kind));
            attrs.put("package", Objects.toString(node.pkg, ""));
            attrs.put("module", captured.module());
            attrs.put("scope", captured.scope());
            attrs.put("upstream_producer", node.producerId);
            if (d != null) {
                attrs.put("declaration_type_kind", d.typeKind);
                attrs.put("declaration_namespace", d.namespace);
                attrs.put("declaring_type", d.declaringType);
                attrs.put("nesting_depth", d.nestingDepth);
                attrs.put("direct_member", d.directMember);
                attrs.put("declaration_source_location", d.sourceLocation);
                attrs.put("declaration_position", XmlConfigurationProjection.position(
                        d.beginLine,d.beginColumn,d.endLine,d.endColumn));
                attrs.put("declaration_producer", d.producerId);
                attrs.put("declaration_kind", d.declarationKind);
                attrs.put("modifiers", d.modifiers);
                attrs.put("declared_modifiers", d.declaredModifiers);
                attrs.put("implicit_modifiers", d.implicitModifiers);
                attrs.put("visibility", d.visibility);
                attrs.put("synthetic", d.synthetic);
                f.put("declaration_resolved", d.bindingResolved);
                var ref = declarationRefs.get(captured.world() + "\n" + node.id);
                if (ref != null) {
                    var original = model.declaration(ref);
                    attrs.put("declaration_origin", original.origin().name());
                    f.put(
                            "origin",
                            original.origin() == JavaDeclaration.Origin.MODEL
                                    ? "lombok_model"
                                    : "javac");
                    f.put(
                            "evidence_complete",
                            original.complete()
                                    && original.origin() != JavaDeclaration.Origin.MODEL);
                }
            }
            attrs.put(
                    "element_kind", "SOURCE_REGION".equals(node.kind) ? "INITIALIZER" : node.kind);
            attrs.put("context", context.id());
            if (configuration) {
                attrs.put("schema_version", 1);
                String relation =
                        captured.resource() && attrs.containsKey("xmlKind")
                                ? "java.spring.xml"
                                : configurationFacet(node.kind, attrs);
                f.put("relation", relation);
                f.put("origin", "framework_model");
                f.put("status", "possible");
                f.put("evidence_complete", false);
                attrs.put("runtime_confirmed", false);
                attrs.put(
                        "position",
                        XmlConfigurationProjection.position(
                                node.beginLine, node.beginColumn, node.endLine, node.endColumn));
                if ("BEAN".equals(node.kind) && !attrs.containsKey("xmlKind")) {
                    String type =
                            Objects.toString(
                                    attrs.get("productClass"),
                                    Objects.toString(
                                            attrs.get("className"),
                                            Objects.toString(attrs.get("returnType"), "")));
                    attrs.put("type", type);
                    attrs.putIfAbsent("aliases", List.of(node.label));
                    attrs.put(
                            "priority_selection_unknown",
                            Boolean.TRUE.equals(attrs.get("prioritySelectionUnknown")));
                    attrs.put(
                            "configuration_constraints",
                            attrs.getOrDefault("configurationConstraints", List.of()));
                    if ("annotation".equals(attrs.get("source")))
                        attrs.put(
                                "annotation_evidence",
                                Json.object(
                                        "direct",
                                        attrs.get("annotationFqn"),
                                        "root",
                                        attrs.get("frameworkAnnotationRoot"),
                                        "meta_path",
                                        attrs.get("metaPath"),
                                        "resolution",
                                        attrs.get("annotationResolutionStatus"),
                                        "attributes",
                                        attrs.get("annotationAttributeStatus")));
                    if (captured.resource()) {
                        attrs.put("kind", "xml_bean");
                        attrs.put(
                                "xml_configuration_model",
                                xml.computeIfAbsent(
                                                source,
                                                ignored ->
                                                        XmlConfigurationProjection.describe(
                                                                captured,
                                                                (bl, bc, el, ec) ->
                                                                        span(
                                                                                source, bl, bc, el,
                                                                                ec)))
                                        .get(node.id + "\n" + node.sourceOrdinal));
                    }
                } else if (captured.resource()) {
                    String kind =
                            Objects.toString(
                                    attrs.get("xmlKind"), node.kind.toLowerCase(Locale.ROOT));
                    if ("ref".equals(kind)) kind = "bean_ref";
                    if ("XML_CALLABLE_REF".equals(node.kind)) {
                        String role = Objects.toString(attrs.get("role"), "");
                        kind = "factory".equals(role) ? "factory-method" : role;
                        Map<String, Object> ref = Json.map(attrs.get("symbolRef"));
                        attrs.put("member", ref.get("memberName"));
                        attrs.put("owner", ref.get("owner"));
                    }
                    attrs.put("kind", kind);
                    long count =
                            outgoing.getOrDefault(node.id, List.of()).stream()
                                    .filter(
                                            e ->
                                                    "XML_REFERS_TO".equals(e.relation)
                                                            && Objects.equals(e.sourceFile, source)
                                                            && (node.beginLine == null
                                                                || Objects.equals(e.beginLine, node.beginLine))
                                                            && !e.isExternal)
                                    .count();
                    attrs.put("candidate_count", attrs.getOrDefault("candidateCount", count));
                }
            }
            attrs.put("logical_key", Json.key("logical", captured.module(), symbol.signature()));
            attrs.put("source_available", f.get("span") != null);
            typedDeclaration(node.id, source, attrs);
            f.put("attributes", attrs);
            emit(f);
            if (configuration && "BEAN".equals(node.kind) && !attrs.containsKey("xmlKind")) {
                String type = Objects.toString(attrs.get("type"), "");
                if (!type.isEmpty()) {
                    reference(source, symbol.id(), target(type, source), "config_type", Map.of());
                    if (captured.resource() && attrs.get("className") instanceof String ownerType)
                        reference(
                                source,
                                symbol.id(),
                                target(ownerType, source),
                                "config_owner",
                                Map.of());
                }
            }
            if (configuration && "BEAN".equals(node.kind) && !captured.resource()) {
                for (Object name : CapturedProject.list(attrs.get("aliases"))) {
                    if (!(name instanceof String alias)
                            || alias.equals(node.label)
                            || alias.isEmpty()) continue;
                    Map<String, Object> aliasFact = new LinkedHashMap<>(f);
                    aliasFact.put(
                            "id", Json.key("bean_alias", context.world(), source, node.id, alias));
                    aliasFact.put("name", alias);
                    aliasFact.put("signature", "bean:" + alias);
                    emit(aliasFact);
                }
            }
            if (attrs.get("lombok") instanceof Map<?, ?> summary) {
                Map<String, Object> capability = new LinkedHashMap<>(Json.map(summary));
                capability.put("mode", "model");
                capability.put("schema_version", 1);
                capability.put("upstream_producer", "lombok-model");
                facet(source, f.get("span"), symbol.id(), "java.lombok", capability);
            }
            if (attrs.containsKey("type_model")) {
                Map<String, Object> ta =
                        Json.object(
                                "schema_version",
                                3,
                                "declared",
                                attrs.get("type_model"),
                                "return",
                                attrs.get("return_type_model"),
                                "parameters",
                                attrs.get("parameter_type_models"),
                                "java_type_graphs",
                                attrs.get("java_type_graphs"));
                facet(source, null, symbol.id(), "java.type", ta);
            }
            String relation =
                    switch (Objects.toString(node.kind, "")) {
                        case "METHOD", "CONSTRUCTOR", "LAMBDA", "METHOD_REF" -> "java.method";
                        case "BEAN" -> "java.bean";
                        case "ROUTE" -> "java.spring.mvc";
                        default -> captured.resource() ? "java.spring.xml" : null;
                    };
            if (relation != null && !configuration)
                facet(source, f.get("span"), symbol.id(), relation, attrs);
        }
        Set<String> files = new LinkedHashSet<>();
        result.nodes.forEach(n -> files.add(n.sourceFile));
        for (String file : files) {
            parameters(file);
            rawEvidence(file);
        }
        // A source site keeps its extracted javac binding when inferred reflection
        // targets share the span. Upstream extraction order is not evidence precedence.
        for (Edge edge : result.edges)
            if (extractedCall(edge)) edge(edge);
        for (Edge edge : result.edges)
            if (!extractedCall(edge)) edge(edge);
        for (Node node : result.nodes) {
            String source = node.sourceFile;
            if (!project.sources.get(source).resource()) continue;
            Map<String, Object> attrs = metadata(node.metadata);
            if ("bean".equals(attrs.get("xmlKind")) && attrs.get("bean") instanceof String name) {
                String bean =
                        com.anatomist.framework.spring.XmlBeanGraphExtractor.beanNodeId(
                                name, source);
                if (lookup(bean, source) != null)
                    reference(
                            source,
                            target(node.id, source),
                            target(bean, source),
                            "config_nested_bean",
                            Json.object("upstream_producer", node.producerId));
            }
            if (com.anatomist.framework.sofa.SofaXmlAnalyzer.NAMESPACE.equals(
                    attrs.get("namespace"))) {
                List<Object> children = CapturedProject.list(attrs.get("configuration"));
                for (int ordinal = 0; ordinal < children.size(); ordinal++) {
                    Map<String, Object> child = new TreeMap<>(Json.map(children.get(ordinal)));
                    child.put("schema_version", 1);
                    child.put("configuration_ordinal", ordinal);
                    child.put("upstream_producer", node.producerId);
                    child.put("runtime_confirmed", false);
                    Map<String, Object> f =
                            fact(
                                    Json.key(
                                            "framework_configuration",
                                            context.world(),
                                            source,
                                            node.id,
                                            Integer.toString(ordinal)),
                                    "facet",
                                    source);
                    f.put("parent", target(node.id, source));
                    f.put("relation", "java.sofa");
                    f.put("origin", "framework_model");
                    f.put("status", "possible");
                    f.put("evidence_complete", false);
                    f.put("attributes", child);
                    emit(f);
                }
            }
        }
        for (UnresolvedCallSite unresolved : result.unresolvedCallSites) {
            String source = unresolved.sourceFile();
            var attributes = new TreeMap<>(metadata(unresolved.metadata()));
            attributes.put("control_context", unresolved.context());
            site(
                    source,
                    span(
                            source,
                            unresolved.beginLine(),
                            unresolved.beginColumn(),
                            unresolved.endLine(),
                            unresolved.endColumn()),
                    target(unresolved.callerId(), source),
                    unresolved.syntaxTarget(),
                    unresolved.dispatchKind(),
                    attributes,
                    false,
                    false);
        }
        for (Annotation annotation : result.annotations) {
            Map<String, Object> attrs =
                    Json.object(
                            "annotation",
                            annotation.annotationFqn,
                            "raw_name",
                            annotation.rawName,
                            "values",
                            metadata(annotation.attributes),
                            "target_kind",
                            annotation.targetKind,
                            "target_path",
                            annotation.targetPath,
                            "resolution",
                            annotation.resolutionStatus,
                            "language", annotation.language,
                            "provider_id", annotation.providerId,
                            "mechanism", annotation.mechanism,
                            "source_location", annotation.sourceLocation,
                            "upstream_producer",
                            annotation.producerId);
            if ("exact".equals(annotation.resolutionStatus))
                attrs.put("resolved_type", annotation.annotationFqn);
            facet(
                    annotation.sourceFile,
                    span(
                            annotation.sourceFile,
                            annotation.beginLine,
                            annotation.beginColumn,
                            annotation.endLine,
                            annotation.endColumn),
                    target(annotation.nodeId, annotation.sourceFile),
                    "java.annotation",
                    attrs);
        }
        for (AnnotationMetaRelation annotation : result.annotationMetaRelations) {
            facet(
                    Objects.toString(annotation.sourceFile, ""),
                    null,
                    "",
                    "java.annotation.meta",
                    Json.object(
                            "annotation",
                            annotation.annotationFqn,
                            "meta_annotation",
                            annotation.metaAnnotationFqn,
                            "raw_name", annotation.rawName,
                            "language", annotation.language,
                            "provider_id", annotation.providerId,
                            "mechanism", annotation.mechanism,
                            "source_location", annotation.sourceLocation,
                            "upstream_producer", annotation.producerId,
                            "resolution",
                            annotation.resolutionStatus));
        }
        for (SemanticAnnotation annotation : result.semanticAnnotations) {
            String relation =
                    switch (Objects.toString(annotation.category, "")) {
                        case "INJECTION" -> "java.spring.injection";
                        default -> "java.spring.handler";
                    };
            facet(
                    annotation.sourceFile,
                    null,
                    target(annotation.nodeId, annotation.sourceFile),
                    relation,
                    Json.object(
                            "category",
                            annotation.category,
                            "label",
                            annotation.businessLabel,
                            "description",
                            annotation.businessDescription,
                            "domain",
                            annotation.domainContext,
                            "source", annotation.source,
                            "confidence",
                            annotation.confidence,
                            "upstream_producer",
                            annotation.producerId));
        }
    }

    private static String configurationFacet(String kind, Map<String, Object> attrs) {
        if ("sofa".equals(attrs.get("framework"))) return "java.sofa";
        if ("BEAN".equals(kind)) return "java.bean";
        if ("ROUTE".equals(kind)) return "java.spring.mvc";
        if ("FRAMEWORK_REGISTRATION".equals(kind)) return "java.spring.handler";
        return "java.spring.xml";
    }

    private void parameters(String source) {
        if (model == null || project.sources.get(source).resource() || !published.contains(source))
            return;
        var file = project.sources.get(source).file();
        for (var ref : model.declarations(file)) {
            var declaration = model.declaration(ref);
            if (!"PARAMETER".equals(declaration.symbol().kind())) continue;
            var parent = model.parent(ref).orElse(null);
            if (parent == null) continue;
            String owner = identities.declaration(parent);
            String name = declaration.symbol().name();
            String raw = owner + "/parameter:" + name;
            Symbol symbol =
                    register(
                            raw,
                            lookup(owner, source).signature() + "/parameter:" + name,
                            project.sources.get(source));
            Map<String, Object> fact = fact(symbol.id(), "declaration", source);
            fact.put("name", name);
            fact.put("signature", symbol.signature());
            fact.put("parent", target(owner, source));
            fact.put(
                    "span",
                    utf16Span(source, declaration.span().start(), declaration.span().end()));
            fact.put(
                    "origin",
                    declaration.origin() == JavaDeclaration.Origin.MODEL
                            ? "lombok_model"
                            : "javac");
            fact.put("declaration_resolved", declaration.complete());
            fact.put(
                    "evidence_complete",
                    declaration.complete() && declaration.origin() != JavaDeclaration.Origin.MODEL);
            Map<String, Object> attrs =
                    Json.object(
                            "upstream_id",
                            raw,
                            "kind",
                            "PARAMETER",
                            "element_kind",
                            "PARAMETER",
                            "module",
                            context.module(),
                            "scope",
                            context.scope().name(),
                            "modifiers",
                            declaration.symbol().modifiers(),
                            "declaration_origin",
                            declaration.origin().name(),
                            "source_available",
                            declaration.span().present(),
                            "context",
                            context.id());
            var graph = new TypeGraphProjection(model.unit(file).types(), typeGraphs);
            attrs.put("type_model", graph.reference(declaration.symbol().declaredType()));
            graph.attach(attrs);
            fact.put("attributes", attrs);
            emit(fact);
            pointDeclarations.put(symbol.id(), ref);
            injectionParameters.put(
                    context.world() + "\n" + source + "\n" + owner.replace('#', '.') + ":" + name,
                    symbol.id());
        }
    }

    private static boolean extractedCall(Edge edge) {
        return "CALLS".equals(edge.relation) && "EXTRACTED".equals(edge.confidence);
    }

    private void edge(Edge edge) {
        String source = Objects.toString(edge.sourceFile, "");
        if (!source.isEmpty() && !project.sources.containsKey(source))
            throw new IllegalArgumentException("EDGE_SOURCE_OUTSIDE_CAPTURE");
        Map<String, Object> attrs = metadata(edge.metadata);
        var captured = project.sources.get(source);
        var ownerReference = captured == null ? null : declarationRefs.get(
                captured.world() + "\n" + NodeKeyFactory.symbolId(edge.sourceId));
        boolean modeledOwner = model != null && ownerReference != null
                && model.declaration(ownerReference).origin() == JavaDeclaration.Origin.MODEL;
        boolean confirmed =
                "EXTRACTED".equals(edge.confidence)
                        && !modeledOwner
                        && !"source_fallback".equals(edge.resolution)
                        && !Boolean.FALSE.equals(attrs.get("bindingResolved"));
        boolean complete = confirmed && !Boolean.FALSE.equals(attrs.get("evidenceComplete"));
        String relation =
                switch (Objects.toString(edge.relation, "")) {
                    case "INHERITS" -> "extends";
                    case "HANDLES" -> "handles_possible";
                    case "WIRES" -> "wires_possible";
                    case "XML_REFERS_TO" -> edge.isExternal ? "unresolved_config" : "config";
                    case "REFERENCES" -> {
                        String selected =
                                edge.isExternal
                                        ? Objects.toString(
                                                edge.externalTargetSymbol,
                                                Objects.toString(edge.externalTargetFqn, ""))
                                        : Objects.toString(edge.targetId, "");
                        yield selected.contains("#") ? "reflection_lookup" : "type_reference";
                    }
                    case "BINDS_TO" ->
                            project.sources.containsKey(source)
                                            && project.sources.get(source).resource()
                                    ? "config_member"
                                    : "binds_to";
                    default -> Objects.toString(edge.relation, "").toLowerCase(Locale.ROOT);
                };
        attrs.put("upstream_producer", edge.producerId);
        attrs.put("upstream_metadata", canonicalMetadata(edge.metadata));
        attrs.put("upstream_mechanism", edge.mechanism);
        attrs.put("upstream_source_id", edge.sourceId);
        attrs.put("upstream_target_id", edge.targetId);
        attrs.put("upstream_is_external", edge.isExternal);
        attrs.put("external_target_fqn", edge.externalTargetFqn);
        attrs.put("external_target_symbol", edge.externalTargetSymbol);
        attrs.put("external_target_language", edge.externalTargetLanguage);
        attrs.put("external_target_provider_id", edge.externalTargetProviderId);
        attrs.put("semantic", edge.semantic);
        attrs.putIfAbsent("mechanism", edge.mechanism);
        attrs.put("language", edge.language);
        attrs.put("provider_id", edge.providerId);
        attrs.put("source_location", edge.sourceLocation);
        attrs.put("source_ordinal", edge.sourceOrdinal);
        attrs.put("syntax_target", edge.syntaxTarget);
        attrs.put("upstream_relation", edge.relation);
        attrs.put("upstream_position", XmlConfigurationProjection.position(
                edge.beginLine,edge.beginColumn,edge.endLine,edge.endColumn));
        attrs.put("resolution", edge.resolution);
        attrs.put("confidence", edge.confidence);
        attrs.put("call_kind", edge.callKind);
        attrs.put("control_context", edge.context);
        attrs.put("type_context", edge.context);
        attrs.put("receiver_static_type", edge.receiverStaticType);
        if ("WIRES".equals(edge.relation)) {
            confirmed = false;
            complete = false;
            attrs.put("runtime_confirmed", false);
            attrs.put("position", attrs.get("upstream_position"));
            if ("spring-xml".equals(edge.producerId)) attrs.put("via", "xml_configuration");
        }
        Object span = span(source, edge.beginLine, edge.beginColumn, edge.endLine, edge.endColumn);
        String parent = configurationTarget(edge.sourceId, source, edge.beginLine),
                target = "XML_REFERS_TO".equals(edge.relation)
                        ? target(edge.isExternal ? edge.externalTargetFqn : edge.targetId, source)
                        : configurationTarget(edge.isExternal ? edge.externalTargetFqn : edge.targetId, source, edge.beginLine);
        if ("INJECTS".equals(edge.relation)) {
            String site = Objects.toString(attrs.get("injectionSite"), "");
            String point = parent;
            if (site.startsWith("field:")) {
                Symbol field = lookup(edge.sourceId + "#" + site.substring(6), source);
                if (field != null) point = field.id();
            }
            if (site.startsWith("method:"))
                point =
                        injectionParameters.getOrDefault(
                                context.world() + "\n" + source + "\n" + site.substring(7), point);
            if (site.startsWith("constructor:"))
                point =
                        injectionParameters.getOrDefault(
                                context.world() + "\n" + source + "\n" + site.substring(12), point);
            String registration =
                    Json.key(
                            "injection",
                            context.world(),
                            source,
                            parent,
                            site,
                            Objects.toString(edge.beginLine, ""));
            Map<String, Object> f = fact(registration, "facet", source);
            f.put("parent", point);
            f.put("relation", "java.spring.injection");
            f.put("status", "possible");
            f.put("origin", "framework_model");
            f.put("evidence_complete", false);
            Map<String, Object> evidence = new TreeMap<>(attrs);
            evidence.put("schema_version", 1);
            evidence.put("role", site.contains(":") ? site.substring(0, site.indexOf(':')) : site);
            evidence.put("injection_point", attrs.get("injectionPointName"));
            evidence.put(
                    "implicit_single_constructor",
                    Boolean.TRUE.equals(attrs.get("implicitSingleConstructor")));
            evidence.put("shape", attrs.get("injectionShape"));
            evidence.put("collection_type", attrs.get("collectionType"));
            evidence.put("element_type", attrs.get("elementType"));
            evidence.put("resource_default_name", attrs.get("resourceDefaultName"));
            evidence.put("resource_name", attrs.get("resourceName"));
            evidence.put(
                    "resource_written_attributes",
                    attrs.getOrDefault("resourceAttributes", Map.of()));
            evidence.put(
                    "matching_unsupported", Boolean.TRUE.equals(attrs.get("matchingUnsupported")));
            var declaration = pointDeclarations.get(point);
            if (declaration == null && site.startsWith("field:"))
                declaration =
                        declarationRefs.get(
                                project.sources.get(source).world()
                                        + "\n"
                                        + NodeKeyFactory.symbolId(edge.sourceId)
                                        + "#"
                                        + site.substring(6));
            if (declaration != null && model != null) {
                var graph =
                        new TypeGraphProjection(
                                model.unit(declaration.source()).types(), typeGraphs);
                evidence.put(
                        "injected_type",
                        graph.reference(model.declaration(declaration).symbol().declaredType()));
                graph.attach(evidence);

            } else evidence.put("injected_type", Json.object("display", attrs.get("injectedType")));
            evidence.put("declaring_type", parent);
            if (edge.beginLine != null) evidence.put("source_line", edge.beginLine);
            f.put("attributes", evidence);
            emit(f);
            parent = registration;
            relation = "injects_possible";
            confirmed = false;
            complete = false;
        }
        if (relation.equals("calls") && span != null) {
            String site =
                    site(
                            source,
                            span,
                            parent,
                            edge.syntaxTarget,
                            edge.callKind,
                            attrs,
                            confirmed,
                            complete);
            Map<String, Object> call =
                    fact(Json.key("call-target", site, target), "call_target", source);
            call.put("span", span);
            call.put("parent", site);
            call.put("target", target);
            call.put("relation", "calls");
            call.put("status", confirmed ? "resolved" : "possible");
            call.put(
                    "origin",
                    confirmed
                            ? "javac"
                            : "REFLECTION".equals(edge.callKind) ? "reflection_model"
                            : attrs.containsKey("generator") ? "lombok_model" : "source_recovery");
            call.put("declaration_resolved", confirmed);
            call.put("evidence_complete", complete);
            attrs.put("binding_evidence", site);
            call.put("attributes", attrs);
            emit(call);
        } else {
            String relationKey = Json.key("relation", context.world(), source, parent, target,
                    relation, Json.write(span), Json.write(attrs));
            int occurrence = relationOccurrences.merge(relationKey, 1, Integer::sum) - 1;
            Map<String, Object> f =
                    fact(
                            Json.key("relation-occurrence", relationKey, Integer.toString(occurrence)),
                            Set.of("config", "type_reference", "reflection_lookup")
                                            .contains(relation)
                                    ? "reference"
                                    : "relation",
                            source);
            f.put("span", span);
            f.put("parent", parent);
            f.put("target", target);
            f.put("relation", relation);
            f.put("status", confirmed ? "complete" : "possible");
            f.put("evidence_complete", complete);
            f.put("origin", "overrides".equals(relation) && Boolean.TRUE.equals(attrs.get("modeledInput"))
                    ? "modeled_override" : "wires_possible".equals(relation) ? "framework_model" : modeledOwner ? "lombok_model"
                    : confirmed ? "javac" : "source_recovery");
            f.put("attributes", attrs);
            emit(f);
        }
    }

    private void reference(
            String source,
            String parent,
            String target,
            String relation,
            Map<String, Object> attrs) {
        Map<String, Object> fact =
                fact(
                        Json.key(
                                "framework_reference",
                                context.world(),
                                source,
                                parent,
                                target,
                                relation),
                        "reference",
                        source);
        fact.put("parent", parent);
        fact.put("target", target);
        fact.put("relation", relation);
        fact.put("origin", "framework_model");
        fact.put("status", "possible");
        fact.put("evidence_complete", false);
        fact.put("attributes", attrs);
        emit(fact);
    }

    private String site(
            String source,
            Object span,
            String parent,
            String name,
            String dispatch,
            Map<String, Object> attrs,
            boolean resolved,
            boolean complete) {
        String known = rawSites.get(source + "\n" + Json.write(span));
        if (known != null) return known;
        String id =
                Json.key(
                        "call-site",
                        context.world(),
                        source,
                        Json.write(span),
                        parent,
                        Objects.toString(dispatch, ""));
        Map<String, Object> f = fact(id, "call_site", source);
        f.put("parent", parent);
        f.put("span", span);
        f.put("name", Objects.toString(name, ""));
        f.put("status", resolved ? "resolved" : "unresolved");
        f.put("declaration_resolved", resolved);
        f.put("evidence_complete", complete);
        var siteAttributes = new TreeMap<>(attrs);
        var semanticRange = Json.map(span);
        var bindings = pendingBindings.getOrDefault(source, List.of()).stream().filter(binding -> {
            var range = Json.map(binding.span());
            return Json.number(range, "start") >= Json.number(semanticRange, "start")
                    && Json.number(range, "end") <= Json.number(semanticRange, "end");
        }).toList();
        long width = bindings.stream().mapToLong(binding -> {
            var range = Json.map(binding.span());
            return Json.number(range, "end") - Json.number(range, "start");
        }).max().orElse(-1);
        bindings = bindings.stream().filter(binding -> {
            var range = Json.map(binding.span());
            return Json.number(range, "end") - Json.number(range, "start") == width;
        }).toList();
        if (bindings.size() == 1) {
            var evidence = bindings.getFirst().attributes();
            if (evidence.get("lookup_name") instanceof String member && !member.isEmpty())
                siteAttributes.put("lookup_name", member);
            else if (evidence.get("selected_name") instanceof String member && !member.isEmpty())
                siteAttributes.put("lookup_name", member);
        }
        f.put("attributes", siteAttributes);
        emit(f);
        rawSites.put(source + "\n" + Json.write(span), id);
        semanticSites.computeIfAbsent(source, ignored -> new ArrayList<>())
                .add(new SemanticSite(span, id));
        return id;
    }

    public void diagnostic(IndexDiagnostic d) {
        String source = Objects.toString(d.sourceFile(), "");
        if (!source.isEmpty() && !project.sources.containsKey(source))
            throw new IllegalArgumentException("DIAGNOSTIC_SOURCE_OUTSIDE_CAPTURE");
        Map<String, Object> f =
                fact(
                        Json.key("diagnostic", context.world(), Json.write(d.toMap())),
                        "diagnostic",
                        source);
        f.put("name", d.code());
        f.put("status", "partial");
        f.put("evidence_complete", false);
        Map<String, Object> attributes = new TreeMap<>(d.toMap());
        attributes.put("upstream_severity", d.severity());
        attributes.put("severity", d.severity().toUpperCase(Locale.ROOT));
        if (analysis != null && "JAVAC_ANALYZE".equals(d.phase())) {
            var matching = analysis.diagnostics().stream().filter(compiler -> {
                String file = compiler.path() == null ? source : project.source(compiler.path()).path();
                return file.equals(source) && ("JAVAC_" + compiler.code()).equals(d.code())
                        && Objects.toString(d.sample(), "").startsWith("L" + compiler.line() + "C" + compiler.column() + ":");
            }).toList();
            if (matching.size() == 1) {
                var compiler = matching.getFirst();
                attributes.put("severity", compiler.kind());
                attributes.put("compiler_diagnostic", Json.object("path", source, "kind", compiler.kind(),
                        "code", compiler.code(), "line", compiler.line(), "column", compiler.column(), "message", compiler.message()));
            }
        }
        if ("SOURCE_ENCODING_INVALID".equals(d.code()))
            attributes.put("sha256", project.sources.get(source).hash());
        f.put("attributes", attributes);
        emit(f);
    }

    public void coverage(CapturedProject.Source source, boolean partial) {
        Map<String, Object> f =
                fact(
                        Json.key("coverage", source.world(), source.path()),
                        "coverage",
                        source.path());
        f.put("status", partial ? "partial" : "complete");
        f.put("evidence_complete", !partial);
        f.put(
                "attributes",
                Json.object(
                        "input_hash",
                        source.hash(),
                        "language",
                        source.resource() ? "xml" : "java"));
        emit(f);
    }

    public void provenance(String fingerprint) {
        Map<String, Object> f =
                fact(Json.key("provenance", context.world(), context.id()), "provenance", "");
        f.put(
                "attributes",
                Json.object(
                        "context",
                        context.id(),
                        "context_digest",
                        context.digest(),
                        "adapter",
                        fingerprint,
                        "java_model",
                        "javac-model-v50",
                        "graph_semantics_version", GraphSemantics.VERSION));
        emit(f);
    }

    private void facet(
            String source,
            Object span,
            String parent,
            String relation,
            Map<String, Object> fields) {
        Map<String, Object> attrs = new TreeMap<>(fields);
        attrs.putIfAbsent("schema_version", 1);
        Map<String, Object> f =
                fact(
                        Json.key(
                                "facet",
                                context.world(),
                                source,
                                parent,
                                relation,
                                Json.write(fields)),
                        "facet",
                        source);
        f.put("parent", parent);
        f.put("relation", relation);
        f.put("span", span);
        f.put("attributes", attrs);
        emit(f);
    }

    private void typedDeclaration(String raw, String source, Map<String, Object> attributes) {
        if (model == null) return;
        var ref = declarationRefs.get(project.sources.get(source).world() + "\n" + raw);
        if (ref == null) return;
        var d = model.declaration(ref);
        var unit = model.unit(ref.source());
        var graph = new TypeGraphProjection(unit.types(), typeGraphs);
        attributes.put("type_model", graph.reference(d.symbol().declaredType()));
        attributes.put("declaration_annotations", d.annotations().stream()
                .map(TypeGraphProjection::annotation).toList());
        if (d.constant() instanceof JavaBinaryType.Scalar scalar) {
            attributes.put("constant_model", BinaryMemberProjection.value(scalar));
            attributes.put("constant_value", BinaryMemberProjection.display(scalar.value()));
        }
        if (d.symbol().declaredType() >= 0) {
            var type = unit.types().get(d.symbol().declaredType());
            if (type.kind() == JavaType.Kind.EXECUTABLE) {
                attributes.put("parameter_names", d.members().stream()
                        .filter(member -> "PARAMETER".equals(member.symbol().kind()))
                        .map(member -> member.symbol().name()).toList());
                attributes.put("return_type_model", graph.reference(type.returns()));
                attributes.put(
                        "parameter_type_models",
                        type.parameters().stream().map(graph::reference).toList());
            }
        }
        graph.attach(attributes);
    }

    private void rememberBinding(JavaBinding binding, JavaTypes types) {
        if (!binding.declarationResolved()
                || binding.declaredType() < 0
                || !Set.of("METHOD", "CONSTRUCTOR").contains(binding.kind())) return;
        String raw = JavaTypeNames.binaryCallableId(types, binding.symbol(), binding.binaryOwner());
        String signature = raw;
        if ("CONSTRUCTOR".equals(binding.kind())) {
            signature =
                    raw.substring(0, raw.indexOf('#'))
                            + "#<init>"
                            + raw.substring(raw.indexOf('('));
            externalSignatures.putIfAbsent(raw, signature);
        }
        externalMetadata.putIfAbsent(
                signature,
                Json.object("modifiers", binding.flags(), "element_kind", binding.kind()));
    }

    public void binaryMetadata(com.anatomist.core.asmsolver.JavaBinaryRepository binaries) {
        for (var unit : analysis.units()) for (var binding : unit.bindings().values()) {
            if (!binding.declarationResolved() || binding.declaredType() < 0
                    || !Set.of("METHOD", "CONSTRUCTOR", "FIELD", "ENUM_CONSTANT").contains(binding.kind())) continue;
            if (!(model.type(binding.owner()) instanceof JavaModel.MissingType)) continue;
            var declaration = binaries.find(binding.binaryOwner());
            if (declaration.isEmpty()) continue;
            String raw;
            Map<String, Object> member;
            if (Set.of("FIELD", "ENUM_CONSTANT").contains(binding.kind())) {
                var matches = declaration.get().fields().stream()
                        .filter(f -> f.name().equals(binding.name()) && f.descriptor().equals(binding.descriptor())).toList();
                if (matches.size() != 1) continue;
                raw = binding.binaryOwner() + "#" + binding.name();
                member = BinaryMemberProjection.field(matches.getFirst());
            } else {
                var matches = declaration.get().methods().stream()
                        .filter(m -> m.name().equals("CONSTRUCTOR".equals(binding.kind()) ? "<init>" : binding.name())
                                && m.descriptor().equals(binding.descriptor())).toList();
                if (matches.size() != 1) continue;
                raw = JavaTypeNames.binaryCallableId(unit.types(), binding.symbol(), binding.binaryOwner());
                if ("CONSTRUCTOR".equals(binding.kind()))
                    raw = raw.substring(0, raw.indexOf('#')) + "#<init>" + raw.substring(raw.indexOf('('));
                member = BinaryMemberProjection.method(matches.getFirst());
            }
            var attributes = new TreeMap<>(externalMetadata.getOrDefault(raw, Map.of()));
            attributes.put("binary_member", member);
            attributes.put("modifiers", binding.flags());
            attributes.put("element_kind", binding.kind());
            externalMetadata.put(raw, attributes);
        }
    }

    private static String localBindingKey(JavaBinding binding) {
        return Json.key(
                binding.kind(),
                binding.owner(),
                binding.binaryOwner(),
                binding.name(),
                binding.descriptor());
    }

    private void rawEvidence(String source) {
        if (model == null || project.sources.get(source).resource()) return;
        var file = project.sources.get(source).file();
        var unit = model.unit(file);
        var syntax = model.syntax(file);
        Map<String, List<JavaNode>> locals = new HashMap<>();
        for (var node : syntax.nodes()) {
            var binding = unit.bindings().get(node.id());
            if (node.span().present()
                    && "VARIABLE".equals(node.kind())
                    && binding != null
                    && Set.of(
                                    "LOCAL_VARIABLE",
                                    "RESOURCE_VARIABLE",
                                    "EXCEPTION_PARAMETER",
                                    "PARAMETER",
                                    "BINDING_VARIABLE")
                            .contains(binding.kind()))
                locals.computeIfAbsent(localBindingKey(binding), ignored -> new ArrayList<>())
                        .add(node);
        }
        List<Object> treeRows = new ArrayList<>();
        for (var node : syntax.nodes()) {
            Map<String, Object> row =
                    Json.object(
                            "id",
                            node.id(),
                            "kind",
                            node.kind(),
                            "start",
                            node.start(),
                            "end",
                            node.end(),
                            "parent",
                            syntax.parent(node).map(JavaNode::id).orElse(-1),
                            "fields",
                            syntaxFields(node.fields()));
            treeRows.add(row);
            if (!node.span().present()) continue;
            JavaBinding binding = unit.bindings().get(node.id());
            boolean call =
                    Set.of("METHOD_INVOCATION", "NEW_CLASS", "MEMBER_REFERENCE")
                            .contains(node.kind());
            if (binding == null && !call) continue;
            String owner = identities.owner(file, node).orElse("");
            Object span = utf16Span(source, node.start(), node.end());
            if (binding != null
                    && "VARIABLE".equals(node.kind())
                    && Set.of(
                                    "LOCAL_VARIABLE",
                                    "RESOURCE_VARIABLE",
                                    "EXCEPTION_PARAMETER",
                                    "PARAMETER",
                                    "BINDING_VARIABLE")
                            .contains(binding.kind())) {
                // Local/lambda declarations are available in the raw compiler binding table.
                String parent = target(owner, source);
                String signature = owner + "#local:" + binding.name() + "@" + node.start();
                String id = Json.key("lexical_declaration", context.world(), source, signature);
                Map<String, Object> variable = fact(id, "declaration", source);
                variable.put("name", binding.name());
                variable.put("signature", signature);
                variable.put("parent", parent);
                variable.put("span", span);
                variable.put("declaration_resolved", binding.declarationResolved());
                variable.put("evidence_complete", binding.evidenceComplete());
                Map<String, Object> metadata =
                        Json.object(
                                "kind",
                                binding.kind(),
                                "element_kind",
                                binding.kind(),
                                "modifiers",
                                binding.flags(),
                                "context",
                                context.id(),
                                "module",
                                context.module(),
                                "scope",
                                context.scope().name(),
                                "tree_id",
                                node.id(),
                                "source_available",
                                true);
                var graph = new TypeGraphProjection(unit.types(), typeGraphs);
                metadata.put("type_model", graph.reference(binding.declaredType()));
                graph.attach(metadata);
                variable.put("attributes", metadata);
                boolean formal =
                        "PARAMETER".equals(binding.kind())
                                && injectionParameters.containsKey(
                                        context.world()
                                                + "\n"
                                                + source
                                                + "\n"
                                                + owner.replace('#', '.')
                                                + ":"
                                                + binding.name());
                if (!formal) emit(variable);
            }
            if (binding != null
                    && binding.declarationResolved()
                    && "IDENTIFIER".equals(node.kind())
                    && !owner.isEmpty()) {
                var matches = locals.getOrDefault(localBindingKey(binding), List.of());
                if (matches.size() == 1) {
                    var declaration = matches.getFirst();
                    String declarationOwner = identities.owner(file, declaration).orElse("");
                    String parameter =
                            injectionParameters.get(
                                    context.world()
                                            + "\n"
                                            + source
                                            + "\n"
                                            + declarationOwner.replace('#', '.')
                                            + ":"
                                            + binding.name());
                    String destination =
                            parameter != null
                                    ? parameter
                                    : Json.key(
                                            "lexical_declaration",
                                            context.world(),
                                            source,
                                            declarationOwner
                                                    + "#local:"
                                                    + binding.name()
                                                    + "@"
                                                    + declaration.start());
                    var reference =
                            fact(
                                    Json.key(
                                            "local_reference",
                                            context.world(),
                                            source,
                                            Integer.toString(node.id())),
                                    "reference",
                                    source);
                    reference.put("parent", target(owner, source));
                    reference.put("target", destination);
                    reference.put("span", span);
                    reference.put("relation", "local_reference");
                    reference.put("status", "complete");
                    reference.put("declaration_resolved", true);
                    reference.put("evidence_complete", binding.evidenceComplete());
                    reference.put(
                            "attributes",
                            Json.object("tree_id", node.id(), "raw_javac_binding", true));
                    emit(reference);
                }
            }
            Map<String, Object> attrs =
                    Json.object(
                            "tree_id",
                            node.id(),
                            "kind",
                            node.kind(),
                            "raw_javac_binding",
                            binding != null);
            attrs.put(
                    "mechanism",
                    node.kind().equals("NEW_CLASS")
                            ? "constructor"
                            : node.kind().equals("MEMBER_REFERENCE")
                                    ? "method_reference"
                                    : "invoke");
            if ("METHOD_INVOCATION".equals(node.kind())) {
                var selection = node.tree("getMethodSelect");
                if (selection != null)
                    attrs.put("lookup_name", selection.text("MEMBER_SELECT".equals(selection.kind())
                            ? "getIdentifier" : "getName"));
            } else if ("MEMBER_REFERENCE".equals(node.kind()))
                attrs.put("lookup_name", node.text("getName"));
            if (binding != null) {
                rememberBinding(binding, unit.types());
                attrs.put("selected_owner", binding.owner());
                attrs.put("selected_name", binding.name());
                attrs.put("selected_descriptor", binding.descriptor());
                attrs.put("parameter_types", binding.parameters());
                attrs.put("return_type", binding.returns());
                attrs.put("modifiers", binding.flags());
                var graph = new TypeGraphProjection(unit.types(), typeGraphs);
                attrs.put("expression_type_model", graph.reference(binding.expressionType()));
                attrs.put("declared_type_model", graph.reference(binding.declaredType()));
                attrs.put("selected_type_model", graph.reference(binding.selectedType()));
                graph.attach(attrs);
                attrs.put(
                        "parameter_types",
                        binding.parameters().stream().map(graph::spelling).toList());
                attrs.put("return_type", graph.spelling(binding.returns()));
                if (binding.kind().equals("CONSTRUCTOR") && binding.declaredType() >= 0) {
                    String raw =
                            JavaTypeNames.binaryCallableId(
                                    unit.types(), binding.symbol(), binding.binaryOwner());
                    externalSignatures.put(
                            raw,
                            raw.substring(0, raw.indexOf('#'))
                                    + "#<init>"
                                    + raw.substring(raw.indexOf('(')));
                }
            }
            boolean resolved = binding != null && binding.declarationResolved(),
                    complete = binding != null && binding.evidenceComplete();
            if (binding != null
                    && resolved
                    && !owner.isEmpty()
                    && Set.of("IDENTIFIER", "MEMBER_SELECT").contains(node.kind())
                    && Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE")
                            .contains(binding.kind())
                    && binding.declaredType() >= 0) {
                var type = unit.types().get(binding.declaredType());
                if (type.kind() == JavaType.Kind.DECLARED)
                    for (String relation : List.of("type_navigation")) {
                        Map<String, Object> ref =
                                fact(
                                        Json.key(
                                                relation,
                                                context.world(),
                                                source,
                                                Integer.toString(node.id())),
                                        "reference",
                                        source);
                        ref.put("span", span);
                        ref.put("parent", target(owner, source));
                        ref.put("target", target(type.name(), source));
                        ref.put("relation", relation);
                        ref.put("status", "resolved");
                        ref.put("declaration_resolved", true);
                        ref.put("evidence_complete", complete);
                        ref.put(
                                "attributes",
                                Json.object("tree_id", node.id(), "raw_javac_binding", true));
                        emit(ref);
                    }
            }
            String parent = owner.isEmpty() ? "" : target(owner, source);
            if (call && !owner.isEmpty()) {
                var select = node.tree("getMethodSelect");
                String name =
                        select != null && select.span().present()
                                ? positions(source)
                                        .text
                                        .substring((int) select.start(), (int) select.end())
                                : binding == null ? node.kind() : binding.name();
                attrs.put("syntax_target", name);
            }
            Map<String, Object> evidence = new TreeMap<>(attrs);
            evidence.put("schema_version", call && !owner.isEmpty() ? 4 : 3);
            evidence.put("evidence_owner", parent);
            evidence.put("resolved", resolved);
            evidence.put("complete", complete);
            if (call && !owner.isEmpty())
                pendingBindings.computeIfAbsent(source, ignored -> new ArrayList<>())
                        .add(new BindingEvidence(span, parent, evidence));
            else facet(source, span, parent, "java.type", evidence);
        }
        // Bounded partitions preserve all tree IDs without putting a whole context on the wire.
        int offset = 0, part = 0;
        while (offset < treeRows.size()) {
            int end = Math.min(treeRows.size(), offset + 256);
            Map<String, Object> attrs =
                    Json.object(
                            "schema_version",
                            2,
                            "source_hash",
                            project.sources.get(source).hash(),
                            "chunk_index",
                            part,
                            "node_offset",
                            offset,
                            "total_nodes",
                            treeRows.size(),
                            "last_chunk",
                            end == treeRows.size(),
                            "nodes",
                            treeRows.subList(offset, end));
            facet(source, null, "", "java.syntax", attrs);
            offset = end;
            part++;
        }
    }

    private static Object syntaxFields(Object value) {
        if (value instanceof JavaNode node) return Json.object("tree_id", node.id());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            map.forEach((k, v) -> out.put(k.toString(), syntaxFields(v)));
            return out;
        }
        if (value instanceof List<?> list)
            return list.stream().map(FactProjection::syntaxFields).toList();
        return value;
    }

    private Object utf16Span(String source, long start, long end) {
        if (start == -1 && end == -1) return null;
        Positions p = positions(source);
        long begin = p.utf8.offset(start), finish = p.utf8.offset(end);
        if (begin < 0 || finish < begin) throw new IllegalArgumentException("SOURCE_UTF16_SPAN");
        return Json.object("start", begin, "end", finish);
    }

    private Positions positions(String source) {
        return positions.computeIfAbsent(
                source,
                key -> {
                    try {
                        String text = Files.readString(project.sources.get(key).file());
                        List<Integer> starts = new ArrayList<>();
                        starts.add(0);
                        for (int i = 0; i < text.length(); i++) {
                            char ch = text.charAt(i);
                            if (ch == '\r') {
                                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                                starts.add(i + 1);
                            } else if (ch == '\n') starts.add(i + 1);
                        }
                        return new Positions(
                                text,
                                new Utf8Offsets(text),
                                starts.stream().mapToInt(Integer::intValue).toArray());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private Object span(String source, Integer bl, Integer bc, Integer el, Integer ec) {
        if (source.isEmpty()
                || bl == null
                || bc == null
                || el == null
                || ec == null
                || bl < 1
                || bc < 1
                || el < 1
                || ec < 1) return null;
        Positions p = positions(source);
        if (bl > p.starts.length || el > p.starts.length)
            throw new IllegalArgumentException("SOURCE_SPAN_LINE");
        int begin = p.starts[bl - 1] + bc - 1;
        int end =
                ec == Integer.MAX_VALUE
                        ? (el < p.starts.length ? p.starts[el] : p.text.length())
                        : p.starts[el - 1] + ec - (project.sources.get(source).resource() ? 1 : 0);
        if (project.sources.get(source).resource() && begin > 0) {
            // SAX startElement locators point just after the opening tag. Keep
            // those original coordinates in metadata; public spans start at '<'.
            int opening = p.text.lastIndexOf('<', begin - 1);
            if (opening < 0 || p.text.startsWith("</", opening)) return null;
            begin = opening;
        }
        if (begin < 0 || end < begin || end > p.text.length())
            throw new IllegalArgumentException("SOURCE_SPAN_RANGE");
        if (end < p.text.length()
                && end > 0
                && Character.isHighSurrogate(p.text.charAt(end - 1))
                && Character.isLowSurrogate(p.text.charAt(end))) end++;
        return Json.object("start", p.utf8.offset(begin), "end", p.utf8.offset(end));
    }
}
