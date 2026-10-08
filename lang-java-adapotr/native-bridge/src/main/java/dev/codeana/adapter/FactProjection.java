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
    private final Map<String, String> emitted = new HashMap<>();
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
    private final Map<String, Map<String, Object>> externalMetadata = new HashMap<>();
    private final Map<String, String> externalSignatures = new HashMap<>();
    private final Map<String, String> injectionParameters = new HashMap<>();
    private final Map<String, JavaModel.DeclarationRef> pointDeclarations = new HashMap<>();

    public void closeContext() {
        if (model != null) model.close();
        model = null;
        analysis = null;
        identities = null;
        declarationRefs.clear();
        rawSites.clear();
        externalSignatures.clear();
        externalMetadata.clear();
        pointDeclarations.clear();
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
        Symbol symbol = new Symbol(raw, Json.string(fact, "signature"), source.world(), source.path(), Json.string(fact, "id"));
        symbols.putIfAbsent(source.world() + "\n" + raw, symbol);
        symbols.putIfAbsent(source.world() + "\n" + source.path() + "\n" + raw, symbol);
        rawSymbols.computeIfAbsent(raw, ignored -> new ArrayList<>()).add(symbol);
    }

    public List<SymbolFact> inventory(JavaAnalysisContext analysis) {
        List<SymbolFact> catalogue = new ArrayList<>();
        closeContext();
        this.analysis = analysis;
        for (var unit : analysis.units())
            for (var binding : unit.bindings().values()) rememberBinding(binding, unit.types());
        model = new JavaModel(analysis);
        identities = new JavaIdentities(model);
        {
            for (var unit : analysis.units())
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
                    String raw = identities.declaration(ref), signature = raw.replace("...", "[]");
                    if (!d.binaryName().isEmpty()) signature = d.binaryName();
                    else if (Set.of("METHOD", "CONSTRUCTOR").contains(d.symbol().kind())
                            && d.symbol().declaredType() >= 0) {
                        var owner = model.parent(ref).map(model::declaration).orElse(null);
                        if (owner != null && !owner.binaryName().isEmpty())
                            signature =
                                    JavaTypeNames.binaryCallableId(
                                            unit.types(), d.symbol(), owner.binaryName());
                    } else if (model.parent(ref).isPresent()) {
                        var owner = model.declaration(model.parent(ref).orElseThrow());
                        if (!owner.binaryName().isEmpty())
                            signature = owner.binaryName() + "#" + d.symbol().name();
                    }
                    if (d.symbol().kind().equals("CONSTRUCTOR")) {
                        int separator = signature.indexOf('#'),
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
                    catalogue.add(
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
                                    Json.write(metadata)));
                    register(raw, signature, project.source(unit.path()));
                    declarationRefs.put(project.source(unit.path()).world() + "\n" + raw, ref);
                }
        }
        return catalogue;
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
            return rawSymbols.getOrDefault(symbol, List.of()).stream()
                    .filter(
                            v -> {
                                var unit = project.sources.get(v.path());
                                return unit.module().equals(identity.module())
                                        && unit.scope().equals(identity.scope().name());
                            })
                    .findFirst()
                    .orElse(null);
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
        boolean resourceEvidence = source != null && project.sources.containsKey(source)
                && project.sources.get(source).resource();
        // Resource requests are model candidates, including unresolved placeholders.
        // Keep their identity separate from compiler-observed binary declarations.
        String id = resourceEvidence ? Json.key("framework_external", context.world(), signature)
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
        String digest =
                Json.sha(Json.write(fact).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String previous = emitted.putIfAbsent(Json.string(fact, "id"), digest);
        if (previous != null && !previous.equals(digest))
            throw new IllegalStateException("CONFLICTING_FACT_ID: " + fact.get("id"));
        if (previous == null) {
            owners.put(Json.write(fact.get("owner")), fact.get("owner"));
            output.add(fact);
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

    public void unit(com.anatomist.extract.JavaModelExtractor.UnitFacts unit) {
        extraction(unit.facts());
        for (var d : unit.diagnostics()) diagnostic(d);
    }

    public void extraction(ExtractionResult result) {
        Map<String, Declaration> declarations = new HashMap<>();
        result.declarations.forEach(d -> declarations.put(d.symbolId, d));
        Map<String, String> parents = new HashMap<>();
        Map<String, List<Edge>> outgoing = new HashMap<>();
        for (Edge edge : result.edges) {
            outgoing.computeIfAbsent(edge.sourceId, ignored -> new ArrayList<>()).add(edge);
            if (Set.of("CONFIGURES", "XML_CONTAINS", "CONTAINS").contains(edge.relation)) parents.put(edge.targetId, edge.sourceId);
            if ("DEFINED_BY".equals(edge.relation) && !edge.isExternal) parents.putIfAbsent(edge.sourceId, edge.targetId);
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
                symbol =
                        new Symbol(
                                node.id,
                                node.id,
                                captured.world(),
                                source,
                                Json.key("configuration", context.world(), source, node.id));
                String key = context.world() + "\n" + source + "\n" + node.id;
                if (symbols.putIfAbsent(key, symbol) == null)
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
            if (parent.isEmpty()) parent = parents.getOrDefault(node.id, "");
            if (!parent.isEmpty() && !parent.equals(node.id))
                f.put("parent", target(parent, source));
            attrs.put("upstream_id", node.id);
            attrs.put("kind", node.kind);
            attrs.put("upstream_kind", node.kind);
            attrs.put("module", captured.module());
            attrs.put("scope", captured.scope());
            attrs.put("upstream_producer", node.producerId);
            if (d != null) {
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
            attrs.put("element_kind", "SOURCE_REGION".equals(node.kind) ? "INITIALIZER" : node.kind);
            attrs.put("context", context.id());
            if (configuration) {
                attrs.put("schema_version", 1);
                String relation = captured.resource() && attrs.containsKey("xmlKind") ? "java.spring.xml" : configurationFacet(node.kind, attrs);
                f.put("relation", relation);
                f.put("origin", "framework_model");
                f.put("status", "possible");
                f.put("evidence_complete", false);
                attrs.put("runtime_confirmed", false);
                attrs.put("position", XmlConfigurationProjection.position(node.beginLine, node.beginColumn, node.endLine, node.endColumn));
                if ("BEAN".equals(node.kind) && !attrs.containsKey("xmlKind")) {
                    String type = Objects.toString(attrs.get("productClass"), Objects.toString(attrs.get("className"), Objects.toString(attrs.get("returnType"), "")));
                    attrs.put("type", type);
                    attrs.putIfAbsent("aliases", List.of(node.label));
                    attrs.put("priority_selection_unknown", Boolean.TRUE.equals(attrs.get("prioritySelectionUnknown")));
                    attrs.put("configuration_constraints", attrs.getOrDefault("configurationConstraints", List.of()));
                    if ("annotation".equals(attrs.get("source"))) attrs.put("annotation_evidence", Json.object(
                            "direct", attrs.get("annotationFqn"), "root", attrs.get("frameworkAnnotationRoot"), "meta_path", attrs.get("metaPath"),
                            "resolution", attrs.get("annotationResolutionStatus"), "attributes", attrs.get("annotationAttributeStatus")));
                    if (captured.resource()) {
                        attrs.put("kind", "xml_bean");
                        attrs.put("xml_configuration_model", xml.computeIfAbsent(source, ignored -> XmlConfigurationProjection.describe(captured,
                                (bl, bc, el, ec) -> span(source, bl, bc, el, ec))).get(node.id));
                    }
                } else if (captured.resource()) {
                    String kind = Objects.toString(attrs.get("xmlKind"), node.kind.toLowerCase(Locale.ROOT));
                    if ("ref".equals(kind)) kind = "bean_ref";
                    if ("XML_CALLABLE_REF".equals(node.kind)) {
                        String role = Objects.toString(attrs.get("role"), "");
                        kind = "factory".equals(role) ? "factory-method" : role;
                        Map<String, Object> ref = Json.map(attrs.get("symbolRef"));
                        attrs.put("member", ref.get("memberName"));
                        attrs.put("owner", ref.get("owner"));
                    }
                    attrs.put("kind", kind);
                    long count = outgoing.getOrDefault(node.id, List.of()).stream().filter(e -> "XML_REFERS_TO".equals(e.relation) && !e.isExternal).count();
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
                    if (captured.resource() && attrs.get("className") instanceof String ownerType) reference(source, symbol.id(), target(ownerType, source), "config_owner", Map.of());
                }
            }
            if (configuration && "BEAN".equals(node.kind) && !captured.resource()) {
                for (Object name : CapturedProject.list(attrs.get("aliases"))) {
                    if (!(name instanceof String alias) || alias.equals(node.label) || alias.isEmpty()) continue;
                    Map<String, Object> aliasFact = new LinkedHashMap<>(f);
                    aliasFact.put("id", Json.key("bean_alias", context.world(), source, node.id, alias));
                    aliasFact.put("name", alias); aliasFact.put("signature", "bean:" + alias); emit(aliasFact);
                }
            }
            if (attrs.get("lombok") instanceof Map<?, ?> summary) {
                Map<String, Object> capability = new LinkedHashMap<>(Json.map(summary));
                capability.put("mode", "model"); capability.put("schema_version", 1);
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
        for (String file : files) { parameters(file); rawEvidence(file); }
        for (Edge edge : result.edges) edge(edge);
        for (Node node : result.nodes) {
            String source = node.sourceFile;
            if (!project.sources.get(source).resource()) continue;
            Map<String, Object> attrs = metadata(node.metadata);
            if ("bean".equals(attrs.get("xmlKind")) && attrs.get("bean") instanceof String name) {
                String bean = com.anatomist.framework.spring.XmlBeanGraphExtractor.beanNodeId(name, source);
                if (lookup(bean, source) != null)
                    reference(source, target(node.id, source), target(bean, source),
                            "config_nested_bean", Json.object("upstream_producer", node.producerId));
            }
            if (com.anatomist.framework.sofa.SofaXmlAnalyzer.NAMESPACE.equals(attrs.get("namespace"))) {
                List<Object> children = CapturedProject.list(attrs.get("configuration"));
                for (int ordinal = 0; ordinal < children.size(); ordinal++) {
                    Map<String, Object> child = new TreeMap<>(Json.map(children.get(ordinal)));
                    child.put("schema_version", 1);
                    child.put("configuration_ordinal", ordinal);
                    child.put("upstream_producer", node.producerId);
                    child.put("runtime_confirmed", false);
                    Map<String, Object> f = fact(Json.key("framework_configuration", context.world(),
                            source, node.id, Integer.toString(ordinal)), "facet", source);
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
                    metadata(unresolved.metadata()),
                    false,
                    false);
        }
        for (Annotation annotation : result.annotations) {
            Map<String, Object> attrs =
                    Json.object(
                            "name",
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
                            "upstream_producer",
                            annotation.producerId);
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
        if (model == null || project.sources.get(source).resource() || !published.contains(source)) return;
        var file = project.sources.get(source).file();
        for (var ref : model.declarations(file)) {
            var declaration = model.declaration(ref);
            if (!"PARAMETER".equals(declaration.symbol().kind())) continue;
            var parent = model.parent(ref).orElse(null);
            if (parent == null) continue;
            String owner = identities.declaration(parent);
            String name = declaration.symbol().name();
            String raw = owner + "/parameter:" + name;
            Symbol symbol = register(raw, lookup(owner, source).signature() + "/parameter:" + name, project.sources.get(source));
            Map<String, Object> fact = fact(symbol.id(), "declaration", source);
            fact.put("name", name); fact.put("signature", symbol.signature()); fact.put("parent", target(owner, source));
            fact.put("span", utf16Span(source, declaration.span().start(), declaration.span().end()));
            fact.put("origin", declaration.origin() == JavaDeclaration.Origin.MODEL ? "lombok_model" : "javac");
            fact.put("declaration_resolved", declaration.complete()); fact.put("evidence_complete", declaration.complete() && declaration.origin() != JavaDeclaration.Origin.MODEL);
            Map<String, Object> attrs = Json.object("upstream_id", raw, "kind", "PARAMETER", "element_kind", "PARAMETER",
                    "module", context.module(), "scope", context.scope().name(), "modifiers", declaration.symbol().modifiers(),
                    "declaration_origin", declaration.origin().name(), "source_available", declaration.span().present(), "context", context.id());
            var graph = new TypeGraphProjection(model.unit(file).types());
            attrs.put("type_model", graph.reference(declaration.symbol().declaredType())); graph.attach(attrs);
            fact.put("attributes", attrs); emit(fact);
            pointDeclarations.put(symbol.id(), ref);
            injectionParameters.put(context.world() + "\n" + source + "\n" + owner.replace('#', '.') + ":" + name, symbol.id());
        }
    }

    private void edge(Edge edge) {
        String source = Objects.toString(edge.sourceFile, "");
        if (!source.isEmpty() && !project.sources.containsKey(source))
            throw new IllegalArgumentException("EDGE_SOURCE_OUTSIDE_CAPTURE");
        Map<String, Object> attrs = metadata(edge.metadata);
        boolean confirmed =
                "EXTRACTED".equals(edge.confidence)
                        && !"source_fallback".equals(edge.resolution)
                        && !Boolean.FALSE.equals(attrs.get("bindingResolved"));
        boolean complete = confirmed && !Boolean.FALSE.equals(attrs.get("evidenceComplete"));
        String relation =
                switch (Objects.toString(edge.relation, "")) {
                    case "INHERITS" -> "extends";
                    case "HANDLES" -> "handles_possible";
                    case "XML_REFERS_TO" -> edge.isExternal ? "unresolved_config" : "config";
                    case "REFERENCES" -> {
                        String selected = edge.isExternal ? Objects.toString(edge.externalTargetSymbol, Objects.toString(edge.externalTargetFqn, "")) : Objects.toString(edge.targetId, "");
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
        attrs.put("resolution", edge.resolution);
        attrs.put("confidence", edge.confidence);
        attrs.put("call_kind", edge.callKind);
        attrs.put("control_context", edge.context);
        attrs.put("type_context", edge.context);
        attrs.put("receiver_static_type", edge.receiverStaticType);
        Object span = span(source, edge.beginLine, edge.beginColumn, edge.endLine, edge.endColumn);
        String parent = target(edge.sourceId, source),
                target = target(edge.isExternal ? edge.externalTargetFqn : edge.targetId, source);
        if ("INJECTS".equals(edge.relation)) {
            String site = Objects.toString(attrs.get("injectionSite"), "");
            String point = parent;
            if (site.startsWith("field:")) {
                Symbol field = lookup(edge.sourceId + "#" + site.substring(6), source);
                if (field != null) point = field.id();
            }
            if (site.startsWith("method:")) point = injectionParameters.getOrDefault(
                    context.world() + "\n" + source + "\n" + site.substring(7), point);
            if (site.startsWith("constructor:")) point = injectionParameters.getOrDefault(
                    context.world() + "\n" + source + "\n" + site.substring(12), point);
            String registration = Json.key("injection", context.world(), source, parent, site, Objects.toString(edge.beginLine, ""));
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
            evidence.put("implicit_single_constructor", Boolean.TRUE.equals(attrs.get("implicitSingleConstructor")));
            evidence.put("shape", attrs.get("injectionShape"));
            evidence.put("collection_type", attrs.get("collectionType")); evidence.put("element_type", attrs.get("elementType"));
            evidence.put("resource_default_name", attrs.get("resourceDefaultName")); evidence.put("resource_name", attrs.get("resourceName"));
            evidence.put("resource_written_attributes", attrs.getOrDefault("resourceAttributes", Map.of()));
            evidence.put("matching_unsupported", Boolean.TRUE.equals(attrs.get("matchingUnsupported")));
            var declaration = pointDeclarations.get(point);
            if (declaration == null && site.startsWith("field:")) declaration = declarationRefs.get(project.sources.get(source).world() + "\n" + edge.sourceId + "#" + site.substring(6));
            if (declaration != null && model != null) {
                var graph = new TypeGraphProjection(model.unit(declaration.source()).types());
                evidence.put("injected_type", graph.reference(model.declaration(declaration).symbol().declaredType())); graph.attach(evidence);

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
                            : attrs.containsKey("generator") ? "lombok_model" : "source_recovery");
            call.put("declaration_resolved", confirmed);
            call.put("evidence_complete", complete);
            attrs.put("binding_evidence", site);
            call.put("attributes", attrs);
            emit(call);
        } else {
            Map<String, Object> f =
                    fact(
                            Json.key(
                                    "relation",
                                    context.world(),
                                    source,
                                    parent,
                                    target,
                                    relation,
                                    Json.write(span),
                                    Json.write(attrs)),
                            Set.of("config", "type_reference", "reflection_lookup").contains(relation) ? "reference" : "relation",
                            source);
            f.put("span", span);
            f.put("parent", parent);
            f.put("target", target);
            f.put("relation", relation);
            f.put("status", confirmed ? "complete" : "possible");
            f.put("evidence_complete", complete);
            f.put("origin", confirmed ? "javac" : "source_recovery");
            f.put("attributes", attrs);
            emit(f);
        }
    }

    private void reference(String source, String parent, String target, String relation, Map<String, Object> attrs) {
        Map<String, Object> fact = fact(Json.key("framework_reference", context.world(), source, parent, target, relation), "reference", source);
        fact.put("parent", parent); fact.put("target", target); fact.put("relation", relation);
        fact.put("origin", "framework_model"); fact.put("status", "possible"); fact.put("evidence_complete", false);
        fact.put("attributes", attrs); emit(fact);
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
        f.put("attributes", new TreeMap<>(attrs));
        emit(f);
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
                        "javac-model-v50"));
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
        var graph = new TypeGraphProjection(unit.types());
        attributes.put("type_model", graph.reference(d.symbol().declaredType()));
        if (d.symbol().declaredType() >= 0) {
            var type = unit.types().get(d.symbol().declaredType());
            if (type.kind() == JavaType.Kind.EXECUTABLE) {
                attributes.put("return_type_model", graph.reference(type.returns()));
                attributes.put(
                        "parameter_type_models",
                        type.parameters().stream().map(graph::reference).toList());
            }
        }
        graph.attach(attributes);
    }

    private void rememberBinding(JavaBinding binding, JavaTypes types) {
        if (!binding.declarationResolved() || binding.declaredType() < 0
                || !Set.of("METHOD", "CONSTRUCTOR").contains(binding.kind())) return;
        String raw = JavaTypeNames.binaryCallableId(types, binding.symbol(), binding.binaryOwner());
        String signature = raw;
        if ("CONSTRUCTOR".equals(binding.kind())) {
            signature = raw.substring(0, raw.indexOf('#')) + "#<init>" + raw.substring(raw.indexOf('('));
            externalSignatures.putIfAbsent(raw, signature);
        }
        externalMetadata.putIfAbsent(signature,
                Json.object("modifiers", binding.flags(), "element_kind", binding.kind()));
    }

    private static String localBindingKey(JavaBinding binding) {
        return Json.key(binding.kind(), binding.owner(), binding.binaryOwner(), binding.name(), binding.descriptor());
    }

    private void rawEvidence(String source) {
        if (model == null || project.sources.get(source).resource()) return;
        var file = project.sources.get(source).file();
        var unit = model.unit(file);
        var syntax = model.syntax(file);
        Map<String, List<JavaNode>> locals = new HashMap<>();
        for (var node : syntax.nodes()) {
            var binding = unit.bindings().get(node.id());
            if (node.span().present() && "VARIABLE".equals(node.kind()) && binding != null
                    && Set.of("LOCAL_VARIABLE", "RESOURCE_VARIABLE", "EXCEPTION_PARAMETER", "PARAMETER", "BINDING_VARIABLE").contains(binding.kind()))
                locals.computeIfAbsent(localBindingKey(binding), ignored -> new ArrayList<>()).add(node);
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
            if (binding != null && "VARIABLE".equals(node.kind())
                    && Set.of("LOCAL_VARIABLE", "RESOURCE_VARIABLE", "EXCEPTION_PARAMETER", "PARAMETER", "BINDING_VARIABLE").contains(binding.kind())) {
                // Local/lambda declarations are available in the raw compiler binding table.
                String parent = target(owner, source);
                String signature = owner + "#local:" + binding.name() + "@" + node.start();
                String id = Json.key("lexical_declaration", context.world(), source, signature);
                Map<String, Object> variable = fact(id, "declaration", source);
                variable.put("name", binding.name()); variable.put("signature", signature); variable.put("parent", parent); variable.put("span", span);
                variable.put("declaration_resolved", binding.declarationResolved()); variable.put("evidence_complete", binding.evidenceComplete());
                Map<String, Object> metadata = Json.object("kind", binding.kind(), "element_kind", binding.kind(), "modifiers", binding.flags(),
                        "context", context.id(), "module", context.module(), "scope", context.scope().name(), "tree_id", node.id(), "source_available", true);
                var graph = new TypeGraphProjection(unit.types()); metadata.put("type_model", graph.reference(binding.declaredType())); graph.attach(metadata);
                variable.put("attributes", metadata);
                boolean formal = "PARAMETER".equals(binding.kind()) && injectionParameters.containsKey(context.world() + "\n" + source + "\n" + owner.replace('#', '.') + ":" + binding.name());
                if (!formal) emit(variable);
            }
            if (binding != null && binding.declarationResolved() && "IDENTIFIER".equals(node.kind())
                    && !owner.isEmpty()) {
                var matches = locals.getOrDefault(localBindingKey(binding), List.of());
                if (matches.size() == 1) {
                    var declaration = matches.getFirst();
                    String declarationOwner = identities.owner(file, declaration).orElse("");
                    String parameter = injectionParameters.get(context.world() + "\n" + source + "\n"
                            + declarationOwner.replace('#', '.') + ":" + binding.name());
                    String destination = parameter != null ? parameter : Json.key("lexical_declaration", context.world(), source,
                            declarationOwner + "#local:" + binding.name() + "@" + declaration.start());
                    var reference = fact(Json.key("local_reference", context.world(), source, Integer.toString(node.id())), "reference", source);
                    reference.put("parent", target(owner, source)); reference.put("target", destination);
                    reference.put("span", span); reference.put("relation", "local_reference"); reference.put("status", "complete");
                    reference.put("declaration_resolved", true); reference.put("evidence_complete", binding.evidenceComplete());
                    reference.put("attributes", Json.object("tree_id", node.id(), "raw_javac_binding", true));
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
            if (binding != null) {
                rememberBinding(binding, unit.types());
                attrs.put("selected_owner", binding.owner());
                attrs.put("selected_name", binding.name());
                attrs.put("selected_descriptor", binding.descriptor());
                attrs.put("parameter_types", binding.parameters());
                attrs.put("return_type", binding.returns());
                attrs.put("modifiers", binding.flags());
                var graph = new TypeGraphProjection(unit.types());
                attrs.put("expression_type_model", graph.reference(binding.expressionType()));
                attrs.put("declared_type_model", graph.reference(binding.declaredType()));
                attrs.put("selected_type_model", graph.reference(binding.selectedType()));
                graph.attach(attrs);
                attrs.put("parameter_types", binding.parameters().stream().map(graph::spelling).toList());
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
                                        "relation",
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
                parent = site(source, span, parent, name, node.kind(), attrs, resolved, complete);
                rawSites.put(source + "\n" + Json.write(span), parent);
            }
            Map<String, Object> evidence = new TreeMap<>(attrs);
            evidence.put("schema_version", call && !owner.isEmpty() ? 4 : 3);
            evidence.put("evidence_owner", parent);
            evidence.put("resolved", resolved);
            evidence.put("complete", complete);
            facet(
                    source,
                    span,
                    parent,
                    call && !owner.isEmpty() ? "java.binding" : "java.type",
                    evidence);
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
        if (begin < 0 || end < begin || end > p.text.length())
            throw new IllegalArgumentException("SOURCE_SPAN_RANGE");
        if (end < p.text.length()
                && end > 0
                && Character.isHighSurrogate(p.text.charAt(end - 1))
                && Character.isLowSurrogate(p.text.charAt(end))) end++;
        return Json.object("start", p.utf8.offset(begin), "end", p.utf8.offset(end));
    }
}
