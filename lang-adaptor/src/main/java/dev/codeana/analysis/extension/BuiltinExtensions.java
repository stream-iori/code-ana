package dev.codeana.analysis.extension;

import dev.codeana.analysis.LombokModel;
import dev.codeana.analysis.SofaXmlAnalysis;
import dev.codeana.analysis.SpringXmlAnalysis;
import dev.codeana.common.Json;

import java.util.*;

/** Explicit native-image-safe assembly. Target jars never participate in extension loading. */
public final class BuiltinExtensions {
    public static final ExtensionRegistry REGISTRY =
            new ExtensionRegistry(
                    List.of(
                            new Lombok(),
                            new Spring(),
                            new SpringMvc(),
                            new SpringHandlers(),
                            new SpringInjection(),
                            new Sofa(),
                            new SofaAnnotations(),
                            new Xml()));

    private BuiltinExtensions() {}

    private static final class Lombok
            implements JavaUnitModelExtension, JavaUnitAnalyzer, JavaCallSiteEvidenceProvider {
        public String id() {
            return "lombok";
        }

        public String version() {
            return "14";
        }

        public String producer() {
            return "lombok-model-v1";
        }

        public boolean enabled(Map<String, Object> c) {
            return "model".equals(c.get("lombok"));
        }

        public Map<String, Object> configuration(Map<String, Object> c) {
            return Json.object(
                    "mode", c.get("lombok"), "strict", Boolean.TRUE.equals(c.get("lombok_strict")));
        }

        public Map<String, Integer> facets() {
            return Map.of("java.lombok", 1);
        }

        public void augmentUnits(ExtensionContext context, Map<String,Object> candidate) throws Exception {
            LombokAstUnits.augment(context,candidate);
        }

        public void analyzeUnit(ExtensionContext context, dev.codeana.analysis.model.JavaSemanticModel.Unit unit, FactSink facts) throws Exception {
            facts.addAll(context.analyzeFramework(unit, "lombok"));
        }

        public boolean acceptsCallSite(ExtensionContext context, Map<String, Object> header) {
            return annotatedReceiver(context, (String) header.getOrDefault("receiver_type", ""));
        }

        private boolean annotatedReceiver(ExtensionContext context, String receiver) {
            return !receiver.isEmpty() && context.declaration(receiver).stream()
                    .flatMap(d -> dev.codeana.analysis.ModelAnnotations.declared(d).stream())
                    .map(Json::map).anyMatch(a -> Json.string(a, "type").startsWith("lombok."));
        }

        public Optional<Evidence> observe(ExtensionContext context, Map<String, Object> call) {
            String receiver =
                    (String) Json.map(call.get("attributes")).getOrDefault("receiver_type", "");
            if (receiver.isEmpty()) return Optional.empty();
            boolean annotated = annotatedReceiver(context, receiver);
            return annotated
                    ? Optional.of(
                            new Evidence(
                                    "java.lombok",
                                    1,
                                    Json.object(
                                            "receiver_type",
                                            receiver,
                                            "call_evidence",
                                            "annotated_receiver",
                                            "method_bodies",
                                            "not_modeled",
                                            "hypothesis_only",
                                            true)))
                    : Optional.empty();
        }
    }

    private static final class Spring implements JavaUnitAnalyzer, ProjectResourceAnalyzer {
        public boolean requiresCapturedResources() { return true; }
        public boolean projectRequiresFullModel() { return false; }
        public String id() {
            return "spring";
        }

        public String version() {
            return "8";
        }

        public String producer() {
            return "spring-model-v1";
        }

        public boolean enabled(Map<String, Object> c) {
            return Boolean.TRUE.equals(c.get("spring"));
        }

        public boolean supportsBodyOnlyReuse() {
            return true;
        }

        public Map<String, Object> configuration(Map<String, Object> c) {
            return Json.object("enabled", true, "xml", Boolean.TRUE.equals(c.get("spring_xml")));
        }

        public Map<String, Integer> facets() {
            return Map.of("java.bean", 1, "java.spring.xml", 1);
        }

        public Set<String> resourceKinds() {
            return Set.of("xml");
        }

        private Map<String, Object> configuration(
                ExtensionContext context, List<ProjectResource> resources) {
            Map<String, Object> c = new LinkedHashMap<>(context.configuration());
            c.put("sofa", false);
            c.put("resources", resources.stream().map(ProjectResource::captured).toList());
            return c;
        }

        public void analyzeUnit(ExtensionContext context, dev.codeana.analysis.model.JavaSemanticModel.Unit unit, FactSink facts) throws Exception {
            facts.addAll(context.analyzeFramework(unit, "java"));
        }

        public void analyzeResources(
                ExtensionContext context, List<ProjectResource> resources, FactSink facts)
                throws Exception {
            if (Boolean.TRUE.equals(context.configuration().get("spring_xml")))
                facts.addAll(
                        SpringXmlAnalysis.extract(
                                configuration(context, resources), context.facts()));
        }
    }

    private static final class Sofa implements ProjectResourceAnalyzer {
        public boolean requiresCapturedResources() { return true; }
        public boolean projectRequiresFullModel() { return false; }
        public String id() {
            return "sofa";
        }

        public String version() {
            return "2";
        }

        public String producer() {
            return "sofa-model-v1";
        }

        public boolean enabled(Map<String, Object> c) {
            return Boolean.TRUE.equals(c.get("sofa"));
        }

        public Map<String, Object> configuration(Map<String, Object> c) {
            return Map.of("enabled", true);
        }

        public Map<String, Integer> facets() {
            return Map.of("java.sofa", 1);
        }

        public Set<String> resourceKinds() {
            return Set.of("xml");
        }

        public void analyzeResources(
                ExtensionContext context, List<ProjectResource> resources, FactSink facts)
                throws Exception {
            Map<String, Object> c = new LinkedHashMap<>(context.configuration());
            c.put("spring", false);
            c.put("resources", resources.stream().map(ProjectResource::captured).toList());
            facts.addAll(SofaXmlAnalysis.extract(c, context.facts()));
        }
    }

    private static final class Xml implements ProjectResourceProvider {
        public boolean requiresCapturedResources() { return true; }
        public boolean projectRequiresFullModel() { return false; }
        public String id() {
            return "captured-xml";
        }

        public boolean enabled(Map<String, Object> c) {
            return Boolean.TRUE.equals(c.get("sofa"))
                    || Boolean.TRUE.equals(c.get("spring"))
                            && Boolean.TRUE.equals(c.get("spring_xml"));
        }

        public Map<String, Object> configuration(Map<String, Object> c) {
            return Map.of("kind", "xml");
        }

        public Set<String> kinds() {
            return Set.of("xml");
        }

        public List<ProjectResource> discover(ExtensionContext context) {
            return Json.list(context.configuration().get("resources")).stream()
                    .map(Json::map)
                    .filter(
                            r ->
                                    Json.string(Json.map(r.get("unit")), "path")
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith(".xml"))
                    .map(r -> new ProjectResource("xml", r))
                    .toList();
        }
    }
}
