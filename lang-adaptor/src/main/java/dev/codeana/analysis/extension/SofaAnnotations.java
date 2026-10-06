package dev.codeana.analysis.extension;

import dev.codeana.analysis.ModelAnnotations;
import dev.codeana.common.Json;
import java.util.*;

/** API-shape-checked SOFA static registrations; mismatched APIs remain explicitly unverified. */
public final class SofaAnnotations implements JavaUnitAnalyzer {
    private static final String API = "com.alipay.sofa.runtime.api.annotation.";
    public String id() { return "sofa-annotations"; }
    public String version() { return "3"; }
    public String producer() { return "sofa-annotations-v1"; }
    public Set<String> requires() { return Set.of("sofa"); }
    public boolean enabled(Map<String, Object> c) { return Boolean.TRUE.equals(c.get("sofa")); }
    public boolean supportsBodyOnlyReuse() { return true; }
    public Map<String, Integer> facets() { return Map.of("java.sofa", 1); }
    public void analyzeUnit(ExtensionContext context, dev.codeana.analysis.model.JavaSemanticModel.Unit unit, FactSink sink) {
        FrameworkFacts facts = new FrameworkFacts(this, context, sink, unit.source());
        ModelAnnotations annotations = context.annotations();
        for (Map<String, Object> declaration : facts.published()) {
            Map<String, Object> attributes = Json.map(declaration.get("attributes"));
            boolean provider = Set.of("CLASS", "INTERFACE").contains(attributes.get("element_kind"));
            if (!provider && !"FIELD".equals(attributes.get("element_kind"))) continue;
            var annotation = annotations.first(Json.string(declaration, "signature"),
                    Set.of(API + (provider ? "SofaService" : "SofaReference"))).orElse(null);
            if (annotation == null) continue;
            String contract = ModelAnnotations.className(annotation.values().get("interfaceType"));
            if (contract == null || contract.equals("void")) {
                List<Object> interfaces = Json.list(attributes.getOrDefault("interface_type_models", List.of()));
                contract = provider ? interfaces.size() == 1 ? SpringHandlers.typeName(interfaces.getFirst()) : null
                        : SpringHandlers.typeName(attributes.get("type_model"));
            }
            Object unique = annotation.values().get("uniqueId");
            var members = annotations.members(annotation.direct());
            boolean supported = annotation.complete() && members.isPresent()
                    && members.get().equals(Set.of("interfaceType", "uniqueId"))
                    && contract != null && !contract.equals("void") && unique instanceof String name
                    && !SpringHandlers.dynamic(name) && Set.of("interfaceType", "uniqueId").containsAll(annotation.values().keySet());
            Map<String, Object> a = new TreeMap<>(annotation.evidence());
            a.put("kind", provider ? "service" : "reference"); a.put("role", provider ? "provider" : "requirement");
            a.put("mechanism", "sofa.annotation." + (provider ? "provider" : "requirement"));
            a.put("contract", contract == null ? "" : contract); a.put("unique_id", unique instanceof String ? unique : "");
            a.put("attributes", annotation.values()); a.put("api_members", members.orElse(Set.of()).stream().sorted().toList());
            a.put("written_attributes", FrameworkFacts.writtenAttributes(declaration, annotation.direct()));
            a.put("contract_supported", supported); a.put("transport", supported ? "jvm" : "unknown");
            a.put("contract_baseline", supported ? "internal-runtime-4.19.1-api" : "unverified-api");
            facts.registration(declaration, "java.sofa", provider ? "provider" : "requirement", Json.string(declaration, "signature"), a);
            if (!supported) facts.gap(declaration, "SOFA_UNVERIFIED_ANNOTATION_API");
        }
        facts.annotationLimits(annotations);
    }
}
