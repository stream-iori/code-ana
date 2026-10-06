package dev.codeana.analysis;

import dev.codeana.analysis.extension.*;
import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Test-only adversarial assembly. Never included in a product native image. */
public final class ExtensionProbe {
    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        Map<String, Object> first = Json.map(Json.list(request.get("contexts")).getFirst());
        String version = (String) first.getOrDefault("extension_version", "1");
        String assembly = (String) first.getOrDefault("assembly", "normal");
        Fixture a = new Fixture("fixture", version, Set.of());
        a.selectorMode = (String)first.getOrDefault("test_mode", "normal");
        Fixture b = new Fixture("dependent", "1", Set.of("fixture"));
        ExtensionRegistry registry = switch (assembly) {
            case "duplicate" -> new ExtensionRegistry(List.of(a, a));
            case "missing" -> new ExtensionRegistry(List.of(b));
            case "cycle" -> new ExtensionRegistry(List.of(new Fixture("fixture", "1", Set.of("dependent")), b));
            case "reserved-producer" -> new ExtensionRegistry(List.of(new Fixture("fixture", version, Set.of(), "javac-java8-v1")));
            case "producer-collision" -> new ExtensionRegistry(List.of(a, new Fixture("other", version, Set.of(), a.producer())));
            case "reverse" -> new ExtensionRegistry(List.of(b, a));
            case "ordered" -> new ExtensionRegistry(List.of(a, b));
            case "declarations" -> new ExtensionRegistry(List.of(new DeclarationFixture()));
            case "units" -> new ExtensionRegistry(List.of(new UnitFixture()));
            default -> new ExtensionRegistry(List.of(a));
        };
        try (CompilerSession session = new CompilerSession(request)) {
            System.out.println(Json.write(JavaAnalysis.run(request, session, registry)));
        }
    }
    private static class DeclarationFixture implements JavaDeclarationModelExtension, JavaDeclarationAnalyzer {
        public String id() { return "declarations"; }
        public String producer() { return "declarations-fixture"; }
        public boolean enabled(Map<String, Object> c) { return !Boolean.FALSE.equals(c.get("extension_enabled")); }
        public Map<String, Object> configuration(Map<String, Object> c) { return Json.object("mode", c.getOrDefault("test_mode", "normal")); }
        public Map<String, Integer> facets() { return Map.of("java.lombok", 1); }
        public boolean acceptsDeclaration(Map<String, Object> d) {
            return "CLASS".equals(Json.map(d.get("attributes")).get("element_kind"));
        }
        private String mode(ExtensionContext c) { return (String) c.configuration().getOrDefault("test_mode", "normal"); }
        public void augmentDeclaration(ExtensionContext c, Map<String, Object> d, ModelOverlay overlay) {
            String signature = Json.string(d, "signature");
            overlay.add(Json.object("kind", "declaration", "source", d.get("source"), "start", -1, "end", -1,
                    "parent", signature, "signature", signature+"#model()", "name", "model", "generated", true,
                    "attributes", Json.object("element_kind", "METHOD", "generated_from", signature, "body_available", false)));
            overlay.describe(signature, Map.of("seen", true));
            if (!signature.equals("Bad")) return;
            switch (mode(c)) {
                case "declaration-fail" -> throw new IllegalStateException("bad declaration");
                case "declaration-reject" -> overlay.describe("Missing", Map.of("forged", true));
                case "source-removal" -> overlay.removeCompilerConstructor("Bad#<init>(int)");
                case "strict" -> throw new ExtensionPoint.GateFailure("DECLARATION_STRICT");
                case "cancel" -> throw new java.util.concurrent.CancellationException("DECLARATION_CANCELLED");
            }
        }
        public void analyzeDeclaration(ExtensionContext c, Map<String, Object> d, FactSink sink) {
            String signature = Json.string(d, "signature");
            Map<String, Object> declaration = c.facts().stream().map(Json::map)
                    .filter(f -> "declaration".equals(f.get("kind")) && signature.equals(f.get("signature")))
                    .findFirst().orElseThrow();
            Map<String, Object> owner = new TreeMap<>(Json.map(declaration.get("owner"))); owner.put("producer", producer());
            sink.add(Json.object("id", Json.key("declaration-fixture", Json.string(owner, "world"), signature), "kind", "facet",
                    "owner", owner, "source", declaration.get("source"), "span", null, "name", signature,
                    "signature", "", "parent", declaration.get("id"), "target",
                    signature.equals("Bad") && mode(c).equals("declaration-reject") ? "missing-target" : "",
                    "relation", "java.lombok", "status", "modeled", "origin", producer(), "evidence_complete", false,
                    "declaration_resolved", false, "attributes", Json.object("schema_version", 1, "stage", "declaration")));
            if (signature.equals("Bad") && mode(c).equals("declaration-fail")) throw new IllegalStateException("bad declaration");
        }
    }
    private static final class UnitFixture extends DeclarationFixture implements JavaUnitAnalyzer {
        @Override public String id(){return "units";}
        @Override public String producer(){return "units-fixture";}
        @Override public void analyze(ExtensionContext context,FactSink sink){JavaUnitAnalyzer.super.analyze(context,sink);}
        public void analyzeUnit(ExtensionContext context, dev.codeana.analysis.model.JavaSemanticModel.Unit unit, FactSink sink) {
            if(context.javaModel().type("Good") instanceof DetachedJavaModel.MissingType)
                throw new IllegalStateException("missing cross-file type");
            for(Object raw:unit.rows("declarations")) {
                var declaration=Json.map(raw);
                if(!acceptsDeclaration(declaration))continue;
                analyzeDeclaration(context,declaration,sink);
                if("Bad".equals(declaration.get("signature"))) {
                    String mode=(String)context.configuration().getOrDefault("test_mode","normal");
                    if(mode.equals("unit-fail"))throw new IllegalStateException("bad unit");
                    if(mode.equals("unit-foreign")) {
                        var good=context.facts().stream().map(Json::map)
                                .filter(f->"declaration".equals(f.get("kind")) && "Good".equals(f.get("signature")))
                                .findFirst().orElseThrow();
                        var owner=new LinkedHashMap<>(Json.map(good.get("owner")));owner.put("producer",producer());
                        sink.add(Json.object("id","foreign-file-facet","kind","facet","source",good.get("source"),
                                "owner",owner,"span",null,"name","foreign","signature","","parent",good.get("id"),
                                "target","","relation","java.lombok","status","modeled","origin",producer(),
                                "evidence_complete",false,"declaration_resolved",false,"attributes",Json.object("schema_version",1)));
                    }
                }
            }
        }
    }
    private static final class Fixture implements JavaModelExtension, JavaModelAnalyzer,
            JavaCallSiteEvidenceProvider, ProjectResourceProvider, ProjectResourceAnalyzer {
        private final String id, version, producer; private final Set<String> requires;
        private String selectorMode = "normal";
        Fixture(String id, String version, Set<String> requires) { this(id, version, requires, id + "-producer"); }
        Fixture(String id, String version, Set<String> requires, String producer) {
            this.id = id; this.version = version; this.requires = requires; this.producer = producer;
        }
        public String id() { return id; }
        public String version() { return version; }
        public String producer() { return producer; }
        public Set<String> requires() { return requires; }
        public boolean enabled(Map<String, Object> c) {
            return !Boolean.FALSE.equals(c.get("extension_enabled"))
                    && !(id.equals("fixture") && Boolean.TRUE.equals(c.get("disable_base")));
        }
        public Map<String, Object> configuration(Map<String, Object> c) { return Json.object("mode", c.getOrDefault("test_mode", "normal")); }
        public Map<String, Integer> facets() { return Map.of("java.lombok", 1); }
        public Set<String> kinds() { return Set.of("fixture-resource"); }
        public Set<String> resourceKinds() { return kinds(); }
        public ResourceSelector selector() {
            if (selectorMode.equals("selector-factory-fail")) throw new IllegalStateException("selector factory failed");
            return ResourceSelector.kind("fixture-resource").and(resource -> {
                if (selectorMode.equals("selector-fail")) throw new IllegalStateException("selector predicate failed");
                if (selectorMode.equals("selector-cancel")) throw new java.util.concurrent.CancellationException("SELECTOR_CANCELLED");
                return !selectorMode.equals("select") || resource.path().endsWith(".cfg");
            });
        }
        private String mode(ExtensionContext c) { return (String) c.configuration().getOrDefault("test_mode", "normal"); }
        private Map<String, Object> type(ExtensionContext c) {
            return Json.list(c.model().get("declarations")).stream().map(Json::map)
                    .filter(d -> "CLASS".equals(Json.map(d.get("attributes")).get("element_kind")))
                    .filter(d -> Json.list(c.configuration().get("sources")).stream().map(Json::map)
                            .anyMatch(s -> Boolean.TRUE.equals(s.get("publish")) && Objects.equals(s.get("path"), d.get("source"))))
                    .findFirst().orElseThrow();
        }
        public void augment(ExtensionContext c, ModelOverlay overlay) {
            if (mode(c).equals("immutable")) { Json.map(type(c).get("attributes")).put("compiler_corruption", true); return; }
            Map<String, Object> original = type(c); String parent = Json.string(original, "signature");
            Map<String, Object> d = Json.object("kind", "declaration", "source", original.get("source"), "start", -1, "end", -1,
                    "signature", parent + '#' + id + "()", "parent", parent, "name", id, "generated", true,
                    "attributes", Json.object("element_kind", "METHOD", "generated_from", parent, "body_available", false,
                            "declared_type", "()void", "return_type", "void", "modifiers", List.of("public")));
            overlay.add(d);
            if (mode(c).equals("overlay-fail")) throw new IllegalStateException("after staging header");
            if (mode(c).equals("overlay-owner")) { d.put("source", "unsealed/Use.java"); overlay.add(d); }
        }
        private Map<String, Object> fact(ExtensionContext c, String stage) {
            Map<String, Object> declaration = c.facts().stream().map(Json::map)
                    .filter(f -> "declaration".equals(f.get("kind"))
                            && "CLASS".equals(Json.map(f.get("attributes")).get("element_kind"))
                            && Objects.equals(c.configuration().get("world"), Json.map(f.get("owner")).get("world")))
                    .findFirst().orElseThrow();
            String source = Json.string(declaration, "source"), world = Json.string(Json.map(declaration.get("owner")), "world");
            return Json.object("id", Json.key("fixture-extension", world, id, stage), "kind", "facet",
                    "owner", Json.object("world", world, "producer", producer(), "kind", "source", "id", source),
                    "source", source, "span", null, "name", stage, "signature", "", "parent", declaration.get("id"), "target", "",
                    "relation", "java.lombok", "status", "modeled", "origin", producer(), "evidence_complete", false,
                    "declaration_resolved", false, "attributes", Json.object("schema_version", 1, "stage", stage));
        }
        public void analyze(ExtensionContext c, FactSink sink) {
            Map<String, Object> f = fact(c, "java");
            switch (mode(c)) {
                case "owner" -> Json.map(f.get("owner")).put("producer", "javac-java8-v1");
                case "target" -> f.put("kind", "call_target");
                case "schema" -> Json.map(f.get("attributes")).put("schema_version", 999);
                case "link" -> f.put("target", "missing-target");
            }
            if (mode(c).startsWith("external")) {
                Map<String,Object> external = Json.object("id", Json.key("fixture-binary", id, Json.string(c.configuration(),"world")),
                        "kind","external_symbol","owner",Json.object("world",c.configuration().get("world"),"producer",producer(),
                            "kind","context","id",c.configuration().get("context")),"source","","span",null,"name","Binary",
                        "signature","ext.Binary","parent","","target","","relation","","status","possible","origin",producer(),
                        "declaration_resolved",false,"evidence_complete",false,
                        "attributes",Json.object("source_available",false,"binding_origin","binary_metadata"));
                switch (mode(c)) {
                    case "external-confirmed" -> external.put("declaration_resolved",true);
                    case "external-evidence" -> external.put("evidence_complete",true);
                    case "external-origin" -> external.put("origin","javac");
                    case "external-source" -> Json.map(external.get("attributes")).put("source_available",true);
                    case "external-span" -> external.put("span",Json.object("start",0,"end",1));
                    case "external-binding" -> Json.map(external.get("attributes")).put("binding_origin","javac");
                }
                sink.add(external);
            }
            sink.add(f);
            if (mode(c).equals("java-fail")) throw new IllegalArgumentException("after staging fact");
            if (mode(c).equals("strict")) throw new ExtensionPoint.GateFailure("FIXTURE_STRICT");
            if (mode(c).equals("cancel")) throw new java.util.concurrent.CancellationException("FIXTURE_CANCELLED");
        }
        public Optional<Evidence> observe(ExtensionContext c, Map<String, Object> call) {
            if (mode(c).equals("call-fail")) throw new IllegalStateException("call failed");
            return Optional.of(new Evidence("java.lombok", 1, Map.of("observation", "source-use")));
        }
        public List<ProjectResource> discover(ExtensionContext c) {
            List<ProjectResource> result = Json.list(c.configuration().get("resources")).stream().map(Json::map)
                    .map(r -> new ProjectResource("fixture-resource", r)).toList();
            if (mode(c).equals("resource-fail")) throw new IllegalStateException("resource failed");
            if (mode(c).equals("resource-unsealed"))
                return List.of(new ProjectResource("fixture-resource", Json.object("unit", Json.object("path", "other.xml", "world", "bad", "hash", "bad"), "input", "/tmp/unsealed")));
            return result;
        }
        public void analyzeResources(ExtensionContext c, List<ProjectResource> resources, FactSink sink) {
            Map<String, Object> f = fact(c, "project");
            Map<String, Object> a = Json.map(f.get("attributes"));
            a.put("resource_count", resources.size());
            a.put("saw_java_facts", c.facts().stream().map(Json::map).anyMatch(v -> "java".equals(v.get("name"))));
            a.put("saw_call_evidence", c.facts().stream().map(Json::map).anyMatch(v -> Json.map(v.get("attributes")).containsKey("observation")));
            a.put("selected_paths", resources.stream().map(ProjectResource::path).toList());
            a.put("inventory_paths", c.resources().stream().map(ProjectResource::path).toList());
            a.put("visible_types", c.facts().stream().map(Json::map)
                    .filter(v -> "declaration".equals(v.get("kind"))
                            && "CLASS".equals(Json.map(v.get("attributes")).get("element_kind")))
                    .map(v -> Json.string(v, "signature")).sorted().toList());
            a.put("visible_java_stages", c.facts().stream().map(Json::map)
                    .filter(v -> "java".equals(v.get("name")) && "fixture-producer".equals(Json.map(v.get("owner")).get("producer")))
                    .count());
            sink.add(f);
            if (mode(c).equals("project-fail")) throw new IllegalStateException("after staging project fact");
        }
    }
}
