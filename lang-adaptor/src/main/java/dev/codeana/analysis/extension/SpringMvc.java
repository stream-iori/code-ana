package dev.codeana.analysis.extension;

import dev.codeana.analysis.ModelAnnotations;
import dev.codeana.common.Json;
import java.util.*;

/** Bounded static MVC registration; no container routing or conditions are evaluated. */
public final class SpringMvc implements JavaModelAnalyzer {
    private static final String API = "org.springframework.web.bind.annotation.";
    private static final Set<String> MAPPINGS = Set.of(API + "RequestMapping", API + "GetMapping", API + "PostMapping",
            API + "PutMapping", API + "DeleteMapping", API + "PatchMapping");
    public String id() { return "spring-mvc"; }
    public String version() { return "2"; }
    public String producer() { return "spring-mvc-v1"; }
    public Set<String> requires() { return Set.of("spring"); }
    public boolean enabled(Map<String, Object> c) { return Boolean.TRUE.equals(c.get("spring")); }
    public boolean supportsBodyOnlyReuse() { return true; }
    public Map<String, Integer> facets() { return Map.of("java.spring.mvc", 1); }
    public void analyze(ExtensionContext context, FactSink sink) {
        FrameworkFacts facts = new FrameworkFacts(this, context, sink);
        ModelAnnotations annotations = context.annotations(); int emitted = 0;
        for (Map<String, Object> method : facts.published()) {
            if (!"METHOD".equals(Json.map(method.get("attributes")).get("element_kind"))) continue;
            Map<String, Object> owner = facts.declarationsById.get(method.get("parent"));
            if (owner == null || annotations.first(Json.string(owner, "signature"),
                    Set.of("org.springframework.stereotype.Controller", API + "RestController")).isEmpty()) continue;
            var mapping = annotations.first(Json.string(method, "signature"), MAPPINGS).orElse(null);
            if (mapping == null) continue;
            var base = annotations.first(Json.string(owner, "signature"), Set.of(API + "RequestMapping")).orElse(null);
            List<String> prefixes = base == null ? List.of("") : paths(base), suffixes = paths(mapping);
            List<String> http = http(mapping);
            if (!mapping.complete() || base != null && !base.complete() || prefixes.isEmpty() || suffixes.isEmpty() || http.isEmpty()) {
                facts.gap(method, "MVC_UNSUPPORTED_ANNOTATION_VALUE"); continue;
            }
            Set<String> routes = new HashSet<>();
            for (String prefix : prefixes) for (String suffix : suffixes) for (String verb : http) {
                String path = join(prefix, suffix);
                if (!routes.add(verb + " " + path)) continue;
                if (++emitted > 100000) throw new IllegalArgumentException("MVC_ROUTE_EXPANSION_BUDGET");
                Map<String, Object> a = new TreeMap<>(mapping.evidence());
                a.put("path", path); a.put("http_method", verb); a.put("handler", method.get("id"));
                a.put("condition_status", "unknown");
                for (String key : List.of("consumes", "produces", "params", "headers"))
                    a.put(key, ModelAnnotations.strings(mapping.values().get(key)));
                a.put("parameters", parameters(method, annotations));
                String registration = facts.registration(method, "java.spring.mvc", verb + " " + path, verb + " " + path, a);
                facts.handles(method, registration, method);
            }
        }
        facts.annotationLimits(annotations);
    }
    private static List<String> paths(ModelAnnotations.Match match) {
        List<String> values = ModelAnnotations.strings(match.values().get("value"));
        if (values.isEmpty()) values = ModelAnnotations.strings(match.values().get("path"));
        if (values.stream().anyMatch(v -> v.contains("${") || v.contains("#{"))) return List.of();
        return values.isEmpty() ? List.of("") : values.stream().distinct().toList();
    }
    private static List<String> http(ModelAnnotations.Match match) {
        String simple = match.root().substring(API.length());
        if (!simple.equals("RequestMapping")) return List.of(simple.substring(0, simple.length() - "Mapping".length()).toUpperCase(Locale.ROOT));
        List<Object> raw = ModelAnnotations.values(match.values().get("method"));
        if (raw.isEmpty()) return List.of("ANY");
        Set<String> methods = new TreeSet<>();
        for (Object item : raw) {
            if (!(item instanceof Map) || !"enum".equals(Json.map(item).get("kind"))
                    || !(API + "RequestMethod").equals(Json.map(item).get("type"))) return List.of();
            methods.add(Json.string(Json.map(item), "name"));
        }
        return List.copyOf(methods);
    }
    private static List<Object> parameters(Map<String, Object> method, ModelAnnotations annotations) {
        Map<String, Object> a = Json.map(method.get("attributes"));
        List<Object> types = Json.list(a.getOrDefault("parameter_type_models", List.of()));
        List<Object> names = Json.list(a.getOrDefault("parameter_names", List.of()));
        List<Object> sourceTypes = Json.list(a.getOrDefault("source_parameter_types", List.of()));
        List<Object> declared = Json.list(a.getOrDefault("parameter_annotations", List.of()));
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            Object resolved = Json.map(types.get(i)).get("display");
            Object written = i < sourceTypes.size() ? sourceTypes.get(i) : null;
            Map<String, Object> row = Json.object("index", i, "name", i < names.size() ? names.get(i) : "",
                    "type", written instanceof String ? written : resolved, "resolved_type", resolved);
            if (i < declared.size()) annotations.firstOn(Json.list(declared.get(i)), Set.of(API + "PathVariable", API + "RequestParam", API + "RequestBody"))
                    .ifPresent(match -> { row.put("binding", match.root()); row.put("binding_evidence", match.evidence()); row.put("attributes", match.values()); });
            result.add(row);
        }
        return result;
    }
    private static String normalize(String value) {
        String result = value.strip();
        if (result.isEmpty()) return "";
        if (!result.startsWith("/")) result = "/" + result;
        while (result.length() > 1 && result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
    private static String join(String prefix, String suffix) {
        String left = normalize(prefix), right = normalize(suffix);
        return left.isEmpty() || left.equals("/") ? right.isEmpty() ? "/" : right
                : right.isEmpty() || right.equals("/") ? left : left + right;
    }
}
