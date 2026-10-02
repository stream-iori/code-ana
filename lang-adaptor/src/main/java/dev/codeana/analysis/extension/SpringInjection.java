package dev.codeana.analysis.extension;

import dev.codeana.analysis.ModelAnnotations;
import dev.codeana.common.Json;

import java.util.*;

/** Static injection sites, independently of any container's runtime bean selection. */
public final class SpringInjection implements ProjectResourceAnalyzer {
    public boolean projectRequiresFullModel() { return false; }
    private static final Set<String> INJECTION =
            Set.of(
                    "org.springframework.beans.factory.annotation.Autowired",
                    "javax.inject.Inject",
                    "jakarta.inject.Inject",
                    "javax.annotation.Resource",
                    "jakarta.annotation.Resource");
    private static final Set<String> QUALIFIER =
            Set.of(
                    "org.springframework.beans.factory.annotation.Qualifier",
                    "javax.inject.Named",
                    "jakarta.inject.Named");
    private static final Set<String> COMPONENT =
            Set.of(
                    "org.springframework.stereotype.Component",
                    "org.springframework.stereotype.Service",
                    "org.springframework.stereotype.Repository",
                    "org.springframework.stereotype.Controller",
                    "org.springframework.web.bind.annotation.RestController");

    public String id() {
        return "spring-injection";
    }

    public String producer() {
        return "spring-injection-v1";
    }

    public Set<String> requires() {
        return Set.of("spring");
    }

    public Set<String> resourceKinds() {
        return Set.of();
    }

    public Map<String, Integer> facets() {
        return Map.of("java.spring.injection", 1);
    }

    public boolean enabled(Map<String, Object> c) {
        return Boolean.TRUE.equals(c.get("spring"));
    }

    public boolean supportsBodyOnlyReuse() {
        return true;
    }

    public void analyzeResources(
            ExtensionContext context, List<ProjectResource> resources, FactSink sink) {
        FrameworkFacts facts = new FrameworkFacts(this, context, sink);
        ModelAnnotations annotations = context.annotations();
        Map<String, List<String>> parameters = new HashMap<>();
        Map<String, Integer> constructors = new HashMap<>();
        for (Map<String, Object> declaration : facts.published()) {
            String kind = Json.string(Json.map(declaration.get("attributes")), "element_kind");
            String parent = Json.string(declaration, "parent");
            if (kind.equals("PARAMETER"))
                parameters
                        .computeIfAbsent(parent, ignored -> new ArrayList<>())
                        .add(Json.string(declaration, "id"));
            if (kind.equals("CONSTRUCTOR")) constructors.merge(parent, 1, Integer::sum);
        }
        for (Map<String, Object> declaration : facts.published()) {
            Map<String, Object> a = Json.map(declaration.get("attributes"));
            String signature = Json.string(declaration, "signature"),
                    kind = Json.string(a, "element_kind");
            ModelAnnotations.Match injection = annotations.first(signature, INJECTION).orElse(null);
            if (kind.equals("FIELD")) {
                if (injection != null)
                    emit(facts, annotations, declaration, declaration, injection, false, "field");
                continue;
            }
            if (!Set.of("METHOD", "CONSTRUCTOR").contains(kind)) continue;
            Map<String, Object> owner = facts.declarationsById.get(declaration.get("parent"));
            boolean implicit =
                    kind.equals("CONSTRUCTOR")
                            && injection == null
                            && owner != null
                            && constructors.getOrDefault(Json.string(declaration, "parent"), 0) == 1
                            && annotations
                                    .first(Json.string(owner, "signature"), COMPONENT)
                                    .isPresent();
            boolean bean =
                    kind.equals("METHOD")
                            && annotations
                                    .first(
                                            signature,
                                            Set.of("org.springframework.context.annotation.Bean"))
                                    .isPresent();
            if (injection == null && !implicit && !bean) continue;
            List<String> sites = parameters.getOrDefault(Json.string(declaration, "id"), List.of());
            boolean resource = injection != null && injection.root().endsWith(".Resource");
            if (resource
                    && (!kind.equals("METHOD")
                            || sites.size() != 1
                            || !Json.string(declaration, "name").startsWith("set"))) {
                facts.gap(declaration, "RESOURCE_SETTER_SHAPE_UNSUPPORTED");
                continue;
            }
            for (String parameterId : sites) {
                Map<String, Object> parameter = facts.declarationsById.get(parameterId);
                if (annotations
                        .first(
                                Json.string(parameter, "signature"),
                                Set.of("org.springframework.beans.factory.annotation.Value"))
                        .isPresent()) continue;
                emit(
                        facts,
                        annotations,
                        parameter,
                        declaration,
                        injection,
                        implicit,
                        resource
                                ? "setter"
                                : bean ? "factory_parameter" : kind.toLowerCase(Locale.ROOT));
            }
        }
        facts.annotationLimits(annotations);
    }

    private void emit(
            FrameworkFacts facts,
            ModelAnnotations annotations,
            Map<String, Object> site,
            Map<String, Object> injectionSite,
            ModelAnnotations.Match injection,
            boolean implicit,
            String role) {
        Object rawType = Json.map(site.get("attributes")).get("type_model");
        if (!(rawType instanceof Map)) {
            facts.gap(site, "INJECTION_TYPE_UNAVAILABLE");
            return;
        }
        Map<String, Object> type = Json.map(rawType);
        String raw = SpringHandlers.typeName(type);
        if (raw == null && "ARRAY".equals(type.get("kind")))
            raw = SpringHandlers.typeName(type.get("component"));
        if (raw == null) {
            facts.gap(site, "INJECTION_TYPE_UNAVAILABLE");
            return;
        }
        Map<String, Object> a =
                Json.object(
                        "role",
                        role,
                        "injected_type",
                        rawType,
                        "injection_point",
                        site.get("name"),
                        "injection_point_name_available",
                        role.equals("field"),
                        "implicit_single_constructor",
                        implicit,
                        "runtime_selection",
                        "unknown");
        if (injection != null) {
            a.put("annotation", injection.root());
            a.put("annotation_evidence", injection.evidence());
            if (injection.values().containsKey("required"))
                a.put("required", injection.values().get("required"));
        }
        ModelAnnotations.Match qualifier =
                annotations
                        .first(Json.string(site, "signature"), QUALIFIER)
                        .orElseGet(
                                () ->
                                        annotations
                                                .first(
                                                        Json.string(injectionSite, "signature"),
                                                        QUALIFIER)
                                                .orElse(null));
        Object name =
                qualifier == null
                        ? null
                        : qualifier.values().getOrDefault("value", qualifier.values().get("name"));
        if (injection != null && injection.root().endsWith(".Resource")) {
            Object resourceName = injection.values().get("name");
            a.put("resource_attributes", injection.values());
            if (resourceName instanceof String text && !text.isBlank()) {
                a.put("resource_name", text);
                if (name == null) name = text;
            } else a.put("resource_default_name", site.get("name"));
            if (injection.values().entrySet().stream()
                            .anyMatch(
                                    e ->
                                            Set.of("lookup", "mappedName").contains(e.getKey())
                                                    && e.getValue() instanceof String value
                                                    && !value.isEmpty())
                    || injection.values().get("type") instanceof Map specified
                            && !"java.lang.Object".equals(ModelAnnotations.className(specified)))
                a.put("matching_unsupported", true);
        }
        if (name instanceof String text && !text.isBlank()) {
            a.put("qualifier", text);
            if (SpringHandlers.dynamic(text) || qualifier != null && !qualifier.complete())
                a.put("matching_unsupported", true);
        }
        shape(type, a);
        String registration = facts.registration(site, "java.spring.injection", role, raw, a);
        Map<String, Object> target = facts.declarations.get(raw);
        if (target != null) {
            Map<String, Object> edge = facts.fact(site, "relation", "injects_possible", role);
            edge.put("parent", registration);
            edge.put("target", target.get("id"));
            edge.put(
                    "attributes",
                    Json.object("runtime_confirmed", false, "evidence", "static_injected_type"));
            facts.add(edge);
        }
        if (Boolean.TRUE.equals(a.get("matching_unsupported"))
                || injection != null && !injection.complete())
            facts.gap(site, "INJECTION_MATCHING_UNSUPPORTED");
    }

    private static void shape(Map<String, Object> type, Map<String, Object> a) {
        String shape = "single";
        Object element = null;
        if ("ARRAY".equals(type.get("kind"))) {
            shape = "array";
            element = type.get("component");
        } else {
            String raw = (String) type.get("element");
            List<Object> args = Json.list(type.getOrDefault("arguments", List.of()));
            if (Set.of("java.util.List", "java.util.Set", "java.util.Collection", "java.util.Map")
                    .contains(raw)) {
                shape = "collection";
                int count = "java.util.Map".equals(raw) ? 2 : 1;
                if (args.size() == count
                        && (count == 1
                                || "java.lang.String"
                                        .equals(SpringHandlers.typeName(args.getFirst())))) {
                    element = args.getLast();
                    a.put("collection_type", raw);
                } else a.put("matching_unsupported", true);
            } else if (!args.isEmpty()) {
                shape = "parameterized";
                a.put("matching_unsupported", true);
            }
        }
        a.put("shape", shape);
        if (element != null) {
            String name = SpringHandlers.typeName(element);
            if (name == null
                    || !Json.list(Json.map(element).getOrDefault("arguments", List.of())).isEmpty())
                a.put("matching_unsupported", true);
            else {
                a.put("element_type", name);
                a.put("cardinality", "many");
            }
        }
    }
}
