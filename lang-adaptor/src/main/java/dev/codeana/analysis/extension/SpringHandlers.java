package dev.codeana.analysis.extension;

import dev.codeana.analysis.ModelAnnotations;
import dev.codeana.common.Json;
import java.util.*;

/** Source lifecycle/event registrations and bounded, nearest-member factory lifecycle lookup. */
public final class SpringHandlers implements JavaModelAnalyzer {
    private static final Map<String, String> ROLES = Map.of(
            "javax.annotation.PostConstruct", "init", "jakarta.annotation.PostConstruct", "init",
            "javax.annotation.PreDestroy", "destroy", "jakarta.annotation.PreDestroy", "destroy",
            "org.springframework.context.event.EventListener", "event",
            "org.springframework.transaction.event.TransactionalEventListener", "event");
    public String id() { return "spring-handlers"; }
    public String version() { return "2"; }
    public String producer() { return "spring-handlers-v1"; }
    public Set<String> requires() { return Set.of("spring"); }
    public boolean enabled(Map<String, Object> c) { return Boolean.TRUE.equals(c.get("spring")); }
    public boolean supportsBodyOnlyReuse() { return true; }
    public Map<String, Integer> facets() { return Map.of("java.spring.handler", 1); }
    public void analyze(ExtensionContext context, FactSink sink) {
        FrameworkFacts facts = new FrameworkFacts(this, context, sink);
        ModelAnnotations annotations = context.annotations();
        for (Map<String, Object> method : facts.published()) {
            Map<String, Object> methodAttributes = Json.map(method.get("attributes"));
            if (!"METHOD".equals(methodAttributes.get("element_kind"))) continue;
            String signature = Json.string(method, "signature");
            var role = annotations.first(signature, ROLES.keySet()).orElse(null);
            if (role != null) {
                String mechanism = ROLES.get(role.root());
                Map<String, Object> a = new TreeMap<>(role.evidence());
                a.put("role", mechanism); a.put("mechanism", "spring.annotation." + mechanism);
                a.put("attributes", role.values()); a.put("condition_status", "unknown");
                a.put("parameter_types", methodAttributes.getOrDefault("parameter_type_models", List.of()));
                a.put("source_parameter_types", methodAttributes.getOrDefault("source_parameter_types", List.of()));
                a.put("written_attributes", FrameworkFacts.writtenAttributes(method, role.direct()));
                if (role.root().endsWith("TransactionalEventListener")) {
                    a.put("transaction_phase", role.values().get("phase")); a.put("delivery_status", "runtime-dependent");
                }
                String registration = facts.registration(method, "java.spring.handler", "annotation:" + mechanism, signature, a);
                facts.handles(method, registration, method);
                if (!role.complete()) facts.gap(method, "HANDLER_UNSUPPORTED_ANNOTATION_VALUE");
            }
            var bean = annotations.first(signature, Set.of("org.springframework.context.annotation.Bean")).orElse(null);
            if (bean == null) continue;
            String product = typeName(methodAttributes.get("return_type_model"));
            for (String lifecycle : List.of("init", "destroy")) {
                Object raw = bean.values().get(lifecycle + "Method");
                if (!(raw instanceof String name) || name.isBlank() || name.equals("(inferred)")) continue;
                Map<String, Object> a = new TreeMap<>(bean.evidence());
                a.put("role", lifecycle); a.put("mechanism", "spring.bean." + lifecycle);
                a.put("product_type", product == null ? "" : product); a.put("configured_method", name);
                List<Map<String, Object>> targets = bean.complete() && !dynamic(name) ? lifecycle(facts, product, name) : List.of();
                a.put("resolution_status", targets.size() == 1 ? "static-member" : "unresolved");
                String registration = facts.registration(method, "java.spring.handler", "bean:" + lifecycle, name, a);
                if (targets.size() == 1) facts.handles(method, registration, targets.getFirst());
                else facts.gap(method, "BEAN_LIFECYCLE_MEMBER_UNRESOLVED");
            }
        }
        facts.annotationLimits(annotations);
    }
    static boolean dynamic(String value) { return value.contains("${") || value.contains("#{"); }
    static String typeName(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<String, Object> type = Json.map(raw);
        if (!Boolean.TRUE.equals(type.get("complete")) || !"DECLARED".equals(type.get("kind"))) return null;
        Object name = type.get("element");
        return name instanceof String ? (String) name : null;
    }
    private record Owner(String name, int depth) {}
    private static List<Map<String, Object>> lifecycle(FrameworkFacts facts, String product, String member) {
        if (product == null) return List.of();
        ArrayDeque<Owner> pending = new ArrayDeque<>(); pending.add(new Owner(product, 0));
        Set<String> visited = new HashSet<>(); List<Map<String, Object>> found = new ArrayList<>();
        int nearest = Integer.MAX_VALUE, work = 0;
        while (!pending.isEmpty()) {
            if (++work > 100000 || visited.size() >= 256) return List.of();
            Owner current = pending.removeFirst();
            if (current.depth() > nearest || !visited.add(current.name())) continue;
            Map<String, Object> owner = facts.declarations.get(current.name());
            if (owner == null) return List.of();
            for (Map<String, Object> method : facts.declarations.values()) {
                if (++work > 100000) return List.of();
                Map<String, Object> a = Json.map(method.get("attributes"));
                List<Object> modifiers = Json.list(a.getOrDefault("modifiers", List.of()));
                if (!"METHOD".equals(a.get("element_kind")) || !member.equals(method.get("name"))
                        || !Json.string(method, "signature").startsWith(current.name() + "#")
                        || !Json.list(a.getOrDefault("parameter_type_models", List.of())).isEmpty()
                        || modifiers.contains("static") || current.depth() > 0 && modifiers.contains("private")) continue;
                found.add(method); nearest = current.depth();
            }
            if (current.depth() < nearest) {
                Map<String, Object> a = Json.map(owner.get("attributes"));
                List<Object> parents = new ArrayList<>(Json.list(a.getOrDefault("interface_type_models", List.of())));
                if (a.get("superclass_type_model") != null) parents.add(a.get("superclass_type_model"));
                for (Object raw : parents) {
                    String name = typeName(raw); if (name != null && !name.equals("java.lang.Object")) pending.add(new Owner(name, current.depth() + 1));
                }
            }
        }
        return found;
    }
}
