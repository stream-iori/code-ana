package dev.codeana.analysis;

import dev.codeana.common.IndexedRecords;
import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;

import java.util.*;

/** Bounded, build-time framework rules. Configuration relationships never become source calls. */
public final class FrameworkAnalysis {
    private final Map<String, Object> context;
    private final String stage;
    private final IndexedRecords declarations;
    private ModelAnnotations annotations;
    private final Map<String, Map<String, Object>> sources = new TreeMap<>();
    private final List<Object> facts = new ArrayList<>();
    private final Set<String> gaps = new TreeSet<>();
    private final Map<String, Integer> sequences = new HashMap<>();

    private FrameworkAnalysis(Map<String, Object> context, List<Object> core, String stage) {
        this.context = context;
        this.stage = stage;
        declarations = new IndexedRecords(core);
        for (Object raw : Json.list(context.get("sources"))) {
            Map<String, Object> s = Json.map(raw);
            if (Boolean.TRUE.equals(s.get("publish"))) sources.put(Json.string(s, "path"), s);
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < core.size(); i++) {
            String kind = FactHeaders.kind(core, i);
            if (kind.equals("declaration") || kind.equals("external_symbol")) {
                Map<String, Object> header = FactHeaders.get(core, i);
                declarations.index(Json.string(header, "id"), i);
                String signature = Json.string(header, "signature");
                int rank = (kind.equals("declaration") ? 2 : 0)
                        + (Objects.equals(context.get("world"), header.get("world")) ? 1 : 0);
                Integer previous = ranks.get(signature);
                if (previous == null || rank > previous) {
                    ranks.put(signature, rank);
                    declarations.index(signature, i);
                }
            }
        }
    }

    /** Shared context lookup; each invocation owns only one file's rows and candidate facts. */
    public static final class UnitSession {
        private final FrameworkAnalysis lookup;
        public UnitSession(Map<String,Object> context, Map<String,Object> model, List<Object> core, String stage) {
            this(context,model,core,stage,null);
        }
        public UnitSession(Map<String,Object> context, Map<String,Object> model, List<Object> core,
                String stage, ModelAnnotations sharedAnnotations) {
            lookup = new FrameworkAnalysis(context, core, stage);
            if (!stage.equals("lombok")) lookup.annotations = sharedAnnotations == null
                    ? new ModelAnnotations(model) : sharedAnnotations;
        }
        public List<Object> extract(Map<String,Object> unit, String path) throws Exception {
            FrameworkAnalysis file = new FrameworkAnalysis(lookup, path);
            if (file.stage.equals("lombok")) file.lombok(unit); else file.spring(unit);
            for (Object raw : file.facts)
                Json.map(Json.map(raw).get("attributes")).put("context", file.context.get("context"));
            return file.facts;
        }
    }

    private FrameworkAnalysis(FrameworkAnalysis lookup, String path) {
        context = lookup.context; stage = lookup.stage; declarations = lookup.declarations;
        annotations = lookup.annotations;
        Map<String,Object> source = lookup.sources.get(path);
        if (source == null) throw new IllegalArgumentException("FRAMEWORK_UNIT_NOT_PUBLISHED");
        sources.put(path, source);
    }

    public static List<Object> extract(
            Map<String, Object> context, Map<String, Object> model, List<Object> core, String stage)
            throws Exception {
        if (stage.equals("project")) {
            List<Object> project = new ArrayList<>();
            if (Boolean.TRUE.equals(context.get("spring"))
                    && Boolean.TRUE.equals(context.get("spring_xml")))
                project.addAll(SpringXmlAnalysis.extract(context, core));
            if (Boolean.TRUE.equals(context.get("sofa")))
                project.addAll(SofaXmlAnalysis.extract(context, core));
            for (Object raw : project)
                Json.map(Json.map(raw).get("attributes")).put("context", context.get("context"));
            return project;
        }
        FrameworkAnalysis analyzer = new FrameworkAnalysis(context, core, stage);
        if (stage.equals("lombok")) analyzer.lombok(model);
        if (!stage.equals("lombok")) analyzer.spring(model);
        for (Object raw : analyzer.facts)
            Json.map(Json.map(raw).get("attributes")).put("context", context.get("context"));
        return analyzer.facts;
    }

    private Map<String, Object> fact(String kind, String producer, String path, String world) {
        if (facts.size() >= 100000) throw new IllegalStateException("FRAMEWORK_FACT_BUDGET");
        String sequenceKey = world + "\u0000" + producer + "\u0000" + path + "\u0000" + kind;
        String id =
                Json.key(
                        "framework",
                        world,
                        producer,
                        stage,
                        path,
                        kind,
                        Integer.toString(sequences.merge(sequenceKey, 1, Integer::sum)));
        Map<String, Object> f =
                Json.object(
                        "id",
                        id,
                        "kind",
                        kind,
                        "owner",
                        Json.object(
                                "world",
                                world,
                                "producer",
                                producer,
                                "kind",
                                sources.containsKey(path) ? "source" : "resource",
                                "id",
                                path),
                        "source",
                        path,
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
                        "modeled",
                        "origin",
                        producer,
                        "evidence_complete",
                        false,
                        "declaration_resolved",
                        false,
                        "attributes",
                        new TreeMap<String, Object>());
        facts.add(f);
        return f;
    }

    private Map<String, Object> facet(
            String producer,
            String path,
            String world,
            String namespace,
            String parent,
            Map<String, Object> attributes) {
        Map<String, Object> f = fact("facet", producer, path, world);
        f.put("parent", parent);
        f.put("relation", namespace);
        attributes.put("schema_version", 1);
        f.put("attributes", attributes);
        return f;
    }

    private void coverage(
            String producer, String path, String world, boolean complete, String reason) {
        Map<String, Object> f = fact("coverage", producer, path, world);
        f.put("status", complete ? "complete" : "partial");
        f.put("evidence_complete", complete);
        f.put("attributes", Json.object("reason", reason, "context", context.get("context")));
    }

    private void lombok(Map<String, Object> model) {
        for (Object raw : Json.list(model.get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            String path = Json.string(declaration, "source");
            if (!sources.containsKey(path)) continue;
            Map<String, Object> summary = LombokModel.summary(declaration);
            if (summary.isEmpty()) continue;
            Map<String, Object> target = declarations.get(Json.string(declaration, "signature"));
            if (target == null) continue;
            String world = Json.string(sources.get(path), "world");
            facet(
                    "lombok-model-v1",
                    path,
                    world,
                    "java.lombok",
                    Json.string(target, "id"),
                    new TreeMap<>(summary));
            boolean complete = "complete".equals(summary.get("coverage"));
            coverage(
                    "lombok-model-v1",
                    path,
                    world,
                    complete,
                    complete
                            ? "Static declaration signatures modeled"
                            : "Static declaration model has capability gaps");
            if (!complete
                    && Boolean.TRUE.equals(context.get("lombok_strict"))
                    && Set.of("CLASS", "ENUM")
                            .contains(Json.map(declaration.get("attributes")).get("element_kind")))
                throw new dev.codeana.analysis.extension.ExtensionPoint.GateFailure(
                        "LOMBOK_STRICT_INCOMPLETE: " + path);
        }
    }

    private void bean(
            String name,
            String type,
            Map<String, Object> declaration,
            String source,
            String world,
            String origin) {
        if (name == null) return;
        Map<String, Object> f =
                facet(
                        origin,
                        source,
                        world,
                        "java.bean",
                        declaration == null ? "" : Json.string(declaration, "id"),
                        Json.object(
                                "name",
                                name,
                                "type",
                                type,
                                "selection",
                                "possible",
                                "runtime_confirmed",
                                false));
        f.put("name", name);
    }

    private static String defaultName(String type) {
        String s = type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1);
        return s.isEmpty()
                        || (s.length() > 1
                                && Character.isUpperCase(s.charAt(0))
                                && Character.isUpperCase(s.charAt(1)))
                ? s
                : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private List<String> beanNames(ModelAnnotations.Match match, String fallback, String path) {
        if (!match.complete()) {
            gaps.add(path);
            return List.of();
        }
        for (String field : List.of("name", "value")) {
            Object raw = match.values().get(field);
            if (raw == null) continue;
            List<String> names =
                    ModelAnnotations.strings(raw).stream()
                            .filter(n -> !n.isEmpty())
                            .distinct()
                            .toList();
            if (names.stream().anyMatch(n -> n.contains("${") || n.contains("#{"))) {
                gaps.add(path);
                return List.of();
            }
            if (!names.isEmpty()) return names;
            if (!(raw instanceof String)
                    && (!(raw instanceof Map) || !"array".equals(Json.map(raw).get("kind")))) {
                gaps.add(path);
                return List.of();
            }
        }
        return List.of(fallback);
    }

    private void annotatedBean(
            ModelAnnotations.Match match,
            String fallback,
            String type,
            Map<String, Object> declaration,
            String path,
            String world,
            ModelAnnotations annotations) {
        List<String> names = beanNames(match, fallback, path);
        for (String name : names) {
            bean(name, type, declaration, path, world, "spring-model-v1");
            Map<String, Object> attributes = Json.map(Json.map(facts.getLast()).get("attributes"));
            attributes.put("annotation_evidence", match.evidence());
            attributes.put("aliases", names);
            beanConstraints(attributes, declaration, path, annotations);
            String signature = Json.string(declaration, "signature");
            annotations
                    .first(signature, Set.of("org.springframework.context.annotation.Primary"))
                    .ifPresent(
                            primary -> {
                                attributes.put("primary", true);
                                attributes.put("primary_evidence", primary.evidence());
                            });
            annotations
                    .first(
                            signature,
                            Set.of(
                                    "org.springframework.beans.factory.annotation.Qualifier",
                                    "javax.inject.Named",
                                    "jakarta.inject.Named"))
                    .ifPresent(
                            qualifier -> {
                                Object value = qualifier.values().get("value");
                                if (value instanceof String text && !text.isBlank())
                                    attributes.put("qualifier", text);
                                attributes.put("qualifier_evidence", qualifier.evidence());
                            });
        }
    }

    // Adapted from frozen SpringJavaModelComponents.decorate; constraints are disclosed,
    // never evaluated as runtime container selection.
    private void beanConstraints(Map<String, Object> attributes, Map<String, Object> declaration,
                                 String path, ModelAnnotations annotations) {
        if (annotations.first(Json.string(declaration, "signature"),
                Set.of("javax.annotation.Priority", "jakarta.annotation.Priority")).isPresent())
            attributes.put("priority_selection_unknown", true);
        Set<String> supported = Set.of("org.springframework.context.annotation.Profile",
                "org.springframework.context.annotation.Conditional", "org.springframework.context.annotation.Scope",
                "org.springframework.context.annotation.Lazy", "org.springframework.context.annotation.Import",
                "org.springframework.context.annotation.ImportResource", "org.springframework.context.annotation.ComponentScan");
        List<Object> constraints = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Object> current = declaration;
        while (current != null) {
            if (seen.size() >= 128 || !seen.add(Json.string(current, "id"))) { gaps.add(path); break; }
            for (Object raw : ModelAnnotations.declared(current)) {
                Map<String, Object> annotation = Json.map(raw);
                String name = Json.string(annotation, "type");
                if (!supported.contains(name)) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                for (Object expression : Json.list(Json.map(current.get("attributes"))
                        .getOrDefault("source_annotation_expressions", List.of()))) {
                    Map<String, Object> written = Json.map(expression);
                    if (name.equals(written.get("type"))) {
                        values.putAll(Json.map(written.get("attributes")));
                        if (!Boolean.TRUE.equals(written.get("complete"))) gaps.add(path);
                    }
                }
                constraints.add(Json.object("annotation", name, "attributes", values, "status", "unevaluated"));
            }
            current = declarations.get(Json.string(current, "parent"));
        }
        if (!constraints.isEmpty()) attributes.put("configuration_constraints", constraints);
    }

    private void spring(Map<String, Object> model) throws Exception {
        boolean springEnabled = Boolean.TRUE.equals(context.get("spring"));
        ModelAnnotations annotations = this.annotations == null ? new ModelAnnotations(model) : this.annotations;
        Set<String> recognized =
                Set.of(
                        "org.springframework.stereotype.Component",
                        "org.springframework.stereotype.Service",
                        "org.springframework.stereotype.Repository",
                        "org.springframework.stereotype.Controller",
                        "org.springframework.web.bind.annotation.RestController",
                        "org.springframework.context.annotation.Configuration");
        for (Object raw : Json.list(model.get("observations"))) {
            if (!springEnabled) break;
            Map<String, Object> n = Json.map(raw);
            String path = Json.string(n, "source");
            if (!sources.containsKey(path) || !n.get("kind").equals("annotation")) continue;
            String annotation = Json.string(n, "target");
            Map<String, Object> declaration = declarations.get(Json.string(n, "parent"));
            if (declaration == null) continue;
            String world = Json.string(sources.get(path), "world");
            String signature = Json.string(declaration, "signature");
            Optional<ModelAnnotations.Match> stereotype =
                    annotations
                            .first(signature, recognized)
                            .filter(match -> ModelAnnotations.matchesWritten(declaration, match.direct(), annotation));
            Optional<ModelAnnotations.Match> factory =
                    annotations
                            .first(signature, Set.of("org.springframework.context.annotation.Bean"))
                            .filter(match -> ModelAnnotations.matchesWritten(declaration, match.direct(), annotation));
            if (stage.equals("java") && stereotype.isPresent()) {
                if (Set.of("CLASS", "INTERFACE").contains(Json.map(declaration.get("attributes")).get("element_kind")))
                    annotatedBean(
                            stereotype.get(),
                            defaultName(signature),
                            signature,
                            declaration,
                            path,
                            world,
                            annotations);
            } else if (stage.equals("java") && factory.isPresent()) {
                String type =
                        (String)
                                Json.map(declaration.get("attributes"))
                                        .getOrDefault("return_type", "");
                annotatedBean(
                        factory.get(),
                        Json.string(declaration, "name"),
                        type,
                        declaration,
                        path,
                        world,
                        annotations);
            } else if (stage.equals("java")
                    && stereotype.isEmpty()
                    && factory.isEmpty()
                    && !Set.of(
                                    "org.springframework.beans.factory.annotation.Autowired",
                                    "javax.inject.Inject",
                                    "javax.annotation.Resource",
                                    "jakarta.inject.Inject",
                                    "jakarta.annotation.Resource",
                                    "org.springframework.context.annotation.Primary",
                                    "org.springframework.beans.factory.annotation.Qualifier")
                            .contains(annotation)
                    && annotation.startsWith("org.springframework.")) gaps.add(path);
        }
        if (stage.equals("java")) {
            gapCoverage();
            return;
        }

        gapCoverage();
    }

    private void gapCoverage() {
        for (String path : gaps)
            coverage(
                    "spring-model-v1",
                    path,
                    Json.string(sources.get(path), "world"),
                    false,
                    "Framework selection requires unsupported or runtime evidence");
    }
}
