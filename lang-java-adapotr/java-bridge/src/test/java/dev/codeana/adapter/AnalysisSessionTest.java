package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeana.common.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalysisSessionTest {
    @TempDir Path temporary;

    private Map<String, Object> request(String source) throws Exception {
        Path input = temporary.resolve("input.java");
        Files.writeString(input, source);
        Path scratch = Files.createDirectory(temporary.resolve("scratch"));
        Path home =
                Path.of(System.getProperty("user.home"), ".sdkman/candidates/java/8.0.492-zulu");
        assertTrue(Files.isExecutable(home.resolve("bin/javac")), "Java 8 fixture SDK required");
        Map<String, Object> unit =
                Json.object(
                        "path",
                        "src/A.java",
                        "hash",
                        Json.sha(input),
                        "module",
                        ".",
                        "world",
                        "main",
                        "scope",
                        "MAIN",
                        "input",
                        input.toString(),
                        "publish",
                        true);
        Map<String, Object> context =
                Json.object(
                        "context",
                        "main",
                        "module",
                        ".",
                        "world",
                        "main",
                        "scope",
                        "MAIN",
                        "digest",
                        Json.sha(input),
                        "lombok",
                        "off",
                        "sources",
                        List.of(unit),
                        "classpath",
                        List.of(),
                        "resources",
                        List.of(),
                        "dependency_modules",
                        List.of());
        return Json.object(
                "version",
                8,
                "scratch",
                scratch.toString(),
                "java_home",
                home.toString(),
                "contexts",
                List.of(context),
                "full",
                true,
                "compiler_heap_bytes",
                256L << 20,
                "result_bytes",
                64L << 20,
                "fact_limit",
                100000);
    }

    private List<Map<String, Object>> run(Map<String, Object> request) throws Exception {
        try (var session = new AnalysisSession(request)) {
            Map<String, Object> result = session.analyze();
            List<Map<String, Object>> facts = new ArrayList<>();
            for (Object row : (Iterable<?>) result.get("facts")) facts.add(Json.map(row));
            return facts;
        }
    }

    @Test
    void xmlOnlyConsumersCompileAuxiliaryDeclarationsWithoutPublishingJava() throws Exception {
        var captured = request("package p; class Api { public Api(){} public void start(){} }");
        var context = Json.map(Json.list(captured.get("contexts")).getFirst());
        Json.map(Json.list(context.get("sources")).getFirst()).put("publish", false);
        context.put("spring", true);
        context.put("spring_xml", true);
        Path xml = temporary.resolve("beans.xml");
        Files.writeString(xml, "<beans><bean id=\"api\" class=\"p.Api\" init-method=\"start\"/></beans>");
        context.put("resources", List.of(Json.object("input", xml.toString(), "unit",
                Json.object("path", "beans.xml", "hash", Json.sha(xml), "module", ".",
                        "world", "resources", "scope", "RESOURCE"))));
        var facts = run(captured);
        assertFalse(facts.stream().anyMatch(f -> "declaration".equals(f.get("kind"))));
        var byId = new HashMap<String, Map<String, Object>>();
        facts.forEach(f -> byId.put(Json.string(f, "id"), f));
        var signatures = facts.stream().filter(f -> "config_member".equals(f.get("relation")))
                .map(f -> Json.string(byId.get(Json.string(f, "target")), "signature"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("p.Api#<init>()", "p.Api#start()"), signatures);
    }

    @Test
    void capturedModuleXmlChildrenUsePublishedConfigurationParents() throws Exception {
        var captured = request("class Holder { public void setNested(Holder value){} }");
        var context = Json.map(Json.list(captured.get("contexts")).getFirst());
        context.put("module", "app");
        Json.map(Json.list(context.get("sources")).getFirst()).put("module", "app");
        context.put("spring", true); context.put("spring_xml", true);
        Path xml = temporary.resolve("beans.xml");
        Files.writeString(xml, "<beans><bean id=\"holder\" class=\"Holder\"><property name=\"nested\"><bean class=\"Holder\"/></property></bean></beans>");
        context.put("resources", List.of(Json.object("input", xml.toString(), "unit",
                Json.object("path", "beans.xml", "hash", Json.sha(xml), "module", "app",
                        "world", "resources", "scope", "RESOURCE"))));
        var facts = run(captured);
        var holder = facts.stream().filter(f -> "java.bean".equals(f.get("relation"))
                && "holder".equals(f.get("name"))).findFirst().orElseThrow();
        var property = facts.stream().filter(f -> "XML_PROPERTY".equals(Json.map(f.get("attributes")).get("element_kind")))
                .findFirst().orElseThrow();
        assertEquals(holder.get("id"), property.get("parent"));
        var nested = facts.stream().filter(f -> "java.spring.xml".equals(f.get("relation"))
                && "BEAN".equals(Json.map(f.get("attributes")).get("element_kind")))
                .findFirst().orElseThrow();
        assertEquals(property.get("id"), nested.get("parent"));
        assertTrue(facts.stream().anyMatch(f -> "configures".equals(f.get("relation"))
                && holder.get("id").equals(f.get("parent")) && property.get("id").equals(f.get("target"))));
    }

    @Test
    void xmlReferenceCandidatesDoNotIncludeTheSameInventoryTwice() throws Exception {
        var captured = request("class Holder { public void setNested(Holder value){} }");
        var context = Json.map(Json.list(captured.get("contexts")).getFirst());
        context.put("module", "app");
        Json.map(Json.list(context.get("sources")).getFirst()).put("module", "app");
        context.put("spring", true); context.put("spring_xml", true);
        Path xml = temporary.resolve("beans.xml");
        Files.writeString(xml, "<beans><bean id=\"holder\" class=\"Holder\"><property name=\"nested\" ref=\"target\"/></bean></beans>");
        Path definitions = temporary.resolve("definitions.xml");
        Files.writeString(definitions, "<beans><bean id=\"target\" class=\"Holder\"/></beans>");
        context.put("resources", List.of(Json.object("input", xml.toString(), "unit",
                Json.object("path", "beans.xml", "hash", Json.sha(xml), "module", "",
                        "world", "resources", "scope", "MAIN")),
                Json.object("input", definitions.toString(), "unit",
                    Json.object("path", "definitions.xml", "hash", Json.sha(definitions), "module", "",
                        "world", "resources", "scope", "MAIN"))));
        var facts = run(captured);
        var sites = facts.stream().filter(f -> "java.spring.xml".equals(f.get("relation"))
                && "bean_ref".equals(Json.map(f.get("attributes")).get("kind"))).toList();
        assertEquals(1, sites.size());
        var site = sites.getFirst();
        assertEquals(1L, ((Number) Json.map(site.get("attributes")).get("candidate_count")).longValue());
        var links = facts.stream().filter(f -> "config".equals(f.get("relation"))
                && site.get("id").equals(f.get("parent"))).toList();
        assertEquals(1, links.size());
        var target = facts.stream().filter(f -> links.getFirst().get("target").equals(f.get("id")))
                .findFirst().orElseThrow();
        assertEquals("java.bean", target.get("relation"));
        assertEquals("target", target.get("name"));
    }

    @Test
    void repeatedXmlIdsCountCandidatesAtEachOccurrence() throws Exception {
        var captured = request("class Holder { public void setNested(Holder value){} }");
        var context = Json.map(Json.list(captured.get("contexts")).getFirst());
        context.put("spring", true); context.put("spring_xml", true);
        Path xml = temporary.resolve("repeated.xml");
        Files.writeString(xml, "<beans>\n<bean id=\"target\" class=\"Holder\"/>\n"
                + "<bean id=\"holder\" class=\"Holder\"><property name=\"nested\" ref=\"target\"/></bean>\n"
                + "<bean id=\"holder\" class=\"Holder\"><property name=\"nested\" ref=\"target\"/></bean>\n</beans>");
        context.put("resources", List.of(Json.object("input", xml.toString(), "unit",
                Json.object("path", "repeated.xml", "hash", Json.sha(xml), "module", ".",
                        "world", "resources", "scope", "RESOURCE"))));
        var facts = run(captured);
        var sites = facts.stream().filter(f -> "java.spring.xml".equals(f.get("relation"))
                && "bean_ref".equals(Json.map(f.get("attributes")).get("kind"))).toList();
        assertEquals(2, sites.size());
        assertEquals(2, sites.stream().map(f -> f.get("id")).distinct().count());
        for (var site : sites) {
            assertEquals(1L, ((Number) Json.map(site.get("attributes")).get("candidate_count")).longValue());
            assertEquals(1, facts.stream().filter(f -> "config".equals(f.get("relation"))
                    && site.get("id").equals(f.get("parent"))).count());
        }
    }

    @Test
    void projectXmlUsesMergedDeclarationsAcrossJavaCompileContexts() throws Exception {
        var captured = request("package p; class A { public A(){} }");
        var first = Json.map(Json.list(captured.get("contexts")).getFirst());
        Path java = temporary.resolve("Other.java");
        Files.writeString(java, "class Other {}");
        Path xml = temporary.resolve("beans.xml");
        Files.writeString(xml, "<beans xmlns=\"http://www.springframework.org/schema/beans\"><bean id=\"from-a\" class=\"p.A\"/></beans>");
        var other = new LinkedHashMap<>(first);
        other.put("context", "other-main"); other.put("world", "other-main"); other.put("module", "other");
        other.put("digest", Json.sha(java)); other.put("spring_xml", true);
        other.put("sources", List.of(Json.object("path", "other/Other.java", "hash", Json.sha(java),
                "module", "other", "world", "other-main", "scope", "MAIN", "input", java.toString(), "publish", true)));
        other.put("resources", List.of(Json.object("input", xml.toString(), "unit",
                Json.object("path", "other/beans.xml", "hash", Json.sha(xml), "module", "other", "world", "other-resources", "scope", "RESOURCE"))));
        captured.put("contexts", List.of(first, other));
        var facts = run(captured);
        var constructor = facts.stream().filter(f -> "declaration".equals(f.get("kind"))
                && "p.A#<init>()".equals(f.get("signature"))).findFirst().orElseThrow();
        var bindings = facts.stream().filter(f -> "config_member".equals(f.get("relation"))
                && "other/beans.xml".equals(f.get("source"))).toList();
        assertEquals(1, bindings.size());
        assertEquals(constructor.get("id"), bindings.getFirst().get("target"));
    }

    @Test
    void nestedCallbacksKeepTheExtractedContainsParent() throws Exception {
        var facts = run(request("class A { java.util.function.Supplier<Runnable> run(){ return () -> () -> System.out.println(\"x\"); } }"));
        var callbacks = facts.stream().filter(f -> "declaration".equals(f.get("kind"))
                && "LAMBDA".equals(Json.map(f.get("attributes")).get("kind")))
                .sorted(Comparator.comparingLong(f -> Json.number(Json.map(f.get("span")), "start"))).toList();
        assertEquals(2, callbacks.size());
        assertEquals(callbacks.getFirst().get("id"), callbacks.getLast().get("parent"));
        var calls = facts.stream().filter(f -> "call_site".equals(f.get("kind"))).toList();
        assertEquals(1, calls.size());
        var println = calls.getFirst();
        assertEquals(callbacks.getLast().get("id"), println.get("parent"));
    }

    @Test
    void anonymousAndLocalDeclarationsKeepSourceGraphIdentities() throws Exception {
        var facts = run(request("class A {\n Object run() { class Local { int n; }\n return new Object() { int value; int get(){return value;} };\n }\n}"));
        var signatures = facts.stream().filter(f -> "declaration".equals(f.get("kind")))
                .map(f -> Json.string(f, "signature")).toList();
        assertTrue(signatures.contains("A#run()$anon@L3C9#get()"), signatures.toString());
        assertTrue(signatures.contains("A#run()$anon@L3C9#value"), signatures.toString());
        assertTrue(signatures.contains("A#run()$anon@L3C9#<init>()"), signatures.toString());
        assertTrue(signatures.stream().anyMatch(s -> s.startsWith("A#run()$local@L2C") && s.endsWith(":Local#n")), signatures.toString());
        assertFalse(signatures.stream().anyMatch(s -> s.startsWith("A$1")), signatures.toString());
    }

    @Test
    void sourceAnnotationScalarsKeepOriginalKindsAndNonFiniteBits() throws Exception {
        var facts = run(request("@Tag(nan=0.0f/0.0f, character='\\ud800') class A {} "
                + "@interface Tag {float nan(); char character();}"));
        var declaration = facts.stream().filter(f -> "declaration".equals(f.get("kind"))
                && "A".equals(f.get("signature"))).findFirst().orElseThrow();
        var annotations = Json.list(Json.map(declaration.get("attributes")).get("declaration_annotations"));
        var annotation = annotations.stream().map(Json::map)
                .filter(a -> "LTag;".equals(a.get("descriptor"))).findFirst().orElseThrow();
        var models = Json.map(annotation.get("value_models"));
        assertEquals("FLOAT", Json.map(models.get("nan")).get("kind"));
        assertTrue(Float.isNaN(Float.intBitsToFloat((int) Json.number(Json.map(models.get("nan")), "bits"))));
        assertEquals(0xd800, Json.number(Json.map(models.get("character")), "value"));
    }

    @Test
    void modeledCallsKeepTheOriginalSyntaxMemberNameWhenBindingIsUnavailable() throws Exception {
        var captured = request("@lombok.Getter class A {String value; String run(){return getValue();}}");
        var context = Json.map(((List<?>)captured.get("contexts")).getFirst());
        var sources = new ArrayList<>(Json.list(context.get("sources")));
        Path input = temporary.resolve("Getter.java");
        Files.writeString(input, "package lombok; public @interface Getter {}");
        var unit = new LinkedHashMap<>(Json.map(sources.getFirst()));
        unit.put("path", "src/lombok/Getter.java");unit.put("hash", Json.sha(input));unit.put("input", input.toString());
        sources.add(unit);context.put("sources", sources);context.put("lombok", "model");
        var facts = run(captured);
        var call = facts.stream().filter(f -> "call_site".equals(f.get("kind"))
                && "getValue".equals(f.get("name"))).findFirst().orElseThrow();
        assertEquals("getValue", Json.map(call.get("attributes")).get("lookup_name"));
        assertEquals("unresolved", call.get("status"));
    }

    @Test
    void constantsRetainNonFiniteBitsNegativeZeroAndUtf16CodeUnits() throws Exception {
        var facts = run(request("class A {static final float NAN=0.0f/0.0f; static final double INFINITY=1.0/0.0;"
                + "static final float ZERO=-0.0f; static final char SURROGATE='\\ud800';}"));
        var constants = new HashMap<String, Map<String, Object>>();
        for (var fact : facts) if ("declaration".equals(fact.get("kind")))
            constants.put(Objects.toString(fact.get("signature")), Json.map(fact.get("attributes")));
        var nan = Json.map(constants.get("A#NAN").get("constant_model"));
        assertEquals("FLOAT", nan.get("kind"));
        assertTrue(Float.isNaN(Float.intBitsToFloat(((Number) nan.get("bits")).intValue())));
        var infinity = Json.map(constants.get("A#INFINITY").get("constant_model"));
        assertEquals("DOUBLE", infinity.get("kind"));
        assertEquals(Double.doubleToRawLongBits(Double.POSITIVE_INFINITY), ((Number) infinity.get("bits")).longValue());
        var zero = Json.map(constants.get("A#ZERO").get("constant_model"));
        assertEquals(Float.floatToRawIntBits(-0.0f), ((Number) zero.get("bits")).intValue());
        var character = Json.map(constants.get("A#SURROGATE").get("constant_model"));
        assertEquals("CHARACTER", character.get("kind"));
        assertEquals(0xd800, ((Number) character.get("value")).intValue());
    }

    @Test
    void modeledOverrideRelationsRetainTheirOriginalInferenceOrigin() throws Exception {
        var captured = request("interface Contract<T> {T getValue();} @lombok.Getter class A implements Contract<String> {String value;}");
        var context = Json.map(((List<?>)captured.get("contexts")).getFirst());
        var sources = new ArrayList<>(Json.list(context.get("sources")));
        Path input = temporary.resolve("Getter.java");
        Files.writeString(input, "package lombok; public @interface Getter {}");
        var unit = new LinkedHashMap<>(Json.map(sources.getFirst()));
        unit.put("path", "src/lombok/Getter.java"); unit.put("hash", Json.sha(input)); unit.put("input", input.toString());
        sources.add(unit); context.put("sources", sources); context.put("lombok", "model");
        var facts = run(captured);
        var override = facts.stream().filter(f -> "overrides".equals(f.get("relation"))).findFirst().orElseThrow();
        assertEquals(true, Json.map(override.get("attributes")).get("modeledInput"));
        assertEquals("modeled_override", override.get("origin"));
        assertEquals("possible", override.get("status"));
        assertEquals(false, override.get("evidence_complete"));
        assertEquals(false, override.get("declaration_resolved"));
    }

    @Test
    void observedBinaryMembersRetainExactClassfileDeclarations() throws Exception {
        Path library = temporary.resolve("Api.java"), classes = Files.createDirectory(temporary.resolve("classes"));
        Files.writeString(library, "package ext; public class Api {"
                + "@Deprecated public static <T extends Number> T value(T input) throws java.io.IOException {return input;} "
                + "public static long unused(long input){return input;} }");
        Path java8 = Path.of(System.getProperty("user.home"), ".sdkman/candidates/java/8.0.492-zulu");
        var compiler = new ProcessBuilder(java8.resolve("bin/javac").toString(), "-proc:none", "-parameters", "-d",
                classes.toString(), library.toString()).redirectErrorStream(true).start();
        String output = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, compiler.waitFor(), output);
        var captured = request("class A {Object run() throws Exception {return ext.Api.value(1);}}");
        Json.map(((List<?>)captured.get("contexts")).getFirst()).put("classpath", List.of(classes.toString()));
        var facts = run(captured);
        var member = facts.stream().filter(f -> "external_symbol".equals(f.get("kind"))
                && "ext.Api#value(java.lang.Number)".equals(f.get("signature"))).findFirst().orElseThrow();
        var attributes = Json.map(member.get("attributes"));
        var binary = Json.map(attributes.get("binary_member"));
        assertEquals("(Ljava/lang/Number;)Ljava/lang/Number;", binary.get("descriptor"));
        assertEquals("<T:Ljava/lang/Number;>(TT;)TT;", binary.get("signature"));
        assertEquals(List.of("java.io.IOException"), binary.get("exceptions"));
        assertEquals("input", Json.map(((List<?>)binary.get("parameters")).getFirst()).get("name"));
        assertEquals("Ljava/lang/Deprecated;", Json.map(((List<?>)binary.get("annotations")).getFirst()).get("descriptor"));
        assertFalse(facts.stream().anyMatch(f -> "external_symbol".equals(f.get("kind"))
                && Objects.toString(f.get("signature"), "").contains("#unused")), "catalogue does not publish unobserved members");
    }

    @Test
    void reflectionLateBindingUsesTheCopiedGraphPostProcessorForNestedClasses() throws Exception {
        var facts = run(request("class A {static class Inner {public void target(){}} void run() throws Exception {"
                + "java.lang.reflect.Method method=A.Inner.class.getMethod(\"target\"); method.invoke(new Inner());}}"));
        var declaration = facts.stream().filter(f -> "declaration".equals(f.get("kind"))
                && "A$Inner#target()".equals(f.get("signature"))).findFirst().orElseThrow();
        var reflected = facts.stream().filter(f -> "call_target".equals(f.get("kind"))
                && "METHOD_INVOKE".equals(Json.map(f.get("attributes")).get("operation")))
                .findFirst().orElseThrow();
        assertEquals(declaration.get("id"), reflected.get("target"));
        assertEquals("possible", reflected.get("status"));
        assertEquals(false, reflected.get("evidence_complete"));
        assertEquals(false, reflected.get("declaration_resolved"));
        var provenance = facts.stream().filter(f -> "provenance".equals(f.get("kind"))).findFirst().orElseThrow();
        assertEquals(com.anatomist.core.GraphSemantics.VERSION,
                Json.number(Json.map(provenance.get("attributes")), "graph_semantics_version"));
    }

    @Test
    void reflectionTargetsKeepTheirUpstreamMechanismAndInference() throws Exception {
        var facts = run(request("class A {public void target(){} void run() throws Exception {"
                + "java.lang.reflect.Method method=A.class.getMethod(\"target\"); method.invoke(this);}}"));
        var reflected = facts.stream().filter(f -> "call_target".equals(f.get("kind"))
                && "METHOD_INVOKE".equals(Json.map(f.get("attributes")).get("operation")))
                .findFirst().orElseThrow();
        assertEquals("reflection_model", reflected.get("origin"));
        assertEquals("possible", reflected.get("status"));
        assertEquals(false, reflected.get("declaration_resolved"));
        assertEquals(false, reflected.get("evidence_complete"));
        assertEquals("REFLECTION", Json.map(reflected.get("attributes")).get("call_kind"));
        var site = facts.stream().filter(f -> reflected.get("parent").equals(f.get("id"))).findFirst().orElseThrow();
        assertEquals("call_site", site.get("kind"));
        assertEquals("resolved", site.get("status"));
        assertEquals(true, site.get("declaration_resolved"));
        assertEquals(true, site.get("evidence_complete"));
        assertEquals("method.invoke", site.get("name"));
        assertEquals("INSTANCE", Json.map(site.get("attributes")).get("call_kind"));
        assertTrue(facts.stream().anyMatch(f -> "call_target".equals(f.get("kind"))
                && site.get("id").equals(f.get("parent")) && "javac".equals(f.get("origin"))),
                "original Method.invoke binding remains on the same source site");
    }

    @Test
    void publicationModuleLabelsDoNotRemoveTheirPhysicalCompilerContext() throws Exception {
        var captured = request("class A {void run(){}}");
        var context = Json.map(((List<?>)captured.get("contexts")).getFirst());
        Json.map(((List<?>)context.get("sources")).getFirst()).put("module", "api");
        var declaration = run(captured).stream().filter(f -> "declaration".equals(f.get("kind"))
                && "A".equals(f.get("signature"))).findFirst().orElseThrow();
        assertEquals("api", Json.map(declaration.get("attributes")).get("module"));
        assertEquals("main", Json.map(declaration.get("owner")).get("world"));
    }

    @Test
    void extractedCallSitesExposeTheirOriginalSelectedMemberName() throws Exception {
        var facts = run(request("class A {static int build(){return 1;} int field=build(); void run(){build();}}"));
        var sites = facts.stream().filter(f -> "call_site".equals(f.get("kind"))).toList();
        assertEquals(2, sites.size());
        for (var site : sites)
            assertEquals("build", Json.map(site.get("attributes")).get("lookup_name"));
    }

    @Test
    void compilerErrorsRetainCompilerSeverityAlongsideUpstreamRetentionSeverity() throws Exception {
        var facts = run(request("class A {int healthy(){return 1;} int broken(){return missing();}}"));
        var diagnostic = facts.stream().filter(f -> "diagnostic".equals(f.get("kind"))
                && Json.map(f.get("attributes")).get("compiler_diagnostic") != null).findFirst().orElseThrow();
        var attributes = Json.map(diagnostic.get("attributes"));
        assertEquals("ERROR", attributes.get("severity"));
        assertEquals("warning", attributes.get("upstream_severity"));
        assertEquals("ERROR", Json.map(attributes.get("compiler_diagnostic")).get("kind"));
    }

    @Test
    void declarationAnnotationsUseTheSharedDetachedValueEncoding() throws Exception {
        var facts = run(request("@interface Tag {String text(); Class<?> type(); int[] numbers();} "
                + "@Tag(text=\"😀中文\",type=String.class,numbers={1,2}) class A {}"));
        var declaration = facts.stream().filter(f -> "declaration".equals(f.get("kind"))
                && "A".equals(f.get("signature"))).findFirst().orElseThrow();
        var annotations = (List<?>) Json.map(declaration.get("attributes")).get("declaration_annotations");
        assertEquals(1, annotations.size());
        var annotation = Json.map(annotations.getFirst());
        assertEquals("LTag;", annotation.get("descriptor"));
        var values = Json.map(annotation.get("values"));
        assertEquals("😀中文", values.get("text"));
        assertEquals(List.of(1L, 2L), values.get("numbers"));
        assertEquals("Ljava/lang/String;", Json.map(values.get("type")).get("descriptor"));
    }

    @Test
    void classNamedMethodDoesNotReplaceConstructorOrItsCall() throws Exception {
        var facts =
                run(
                        request(
                                "class A { public void A() throws Exception {} "
                                        + "void caller() throws Exception { new A(); A(); } }"));
        var constructor =
                facts.stream()
                        .filter(
                                f ->
                                        "declaration".equals(f.get("kind"))
                                                && "A#<init>()".equals(f.get("signature")))
                        .findFirst()
                        .orElseThrow();
        var method =
                facts.stream()
                        .filter(
                                f ->
                                        "declaration".equals(f.get("kind"))
                                                && "A#A()".equals(f.get("signature")))
                        .findFirst()
                        .orElseThrow();
        assertNotEquals(constructor.get("id"), method.get("id"));
        assertEquals("CONSTRUCTOR", Json.map(constructor.get("attributes")).get("kind"));
        assertEquals("METHOD", Json.map(method.get("attributes")).get("kind"));
        for (var declaration : List.of(constructor, method))
            assertTrue(
                    facts.stream()
                            .anyMatch(
                                    f ->
                                            "call_target".equals(f.get("kind"))
                                                    && declaration.get("id").equals(f.get("target"))
                                                    && "resolved".equals(f.get("status"))));
    }

    @Test
    void selectedMethodAndUnicodeOffsetsSurviveProjection() throws Exception {
        String text =
                "class A { String s=\"😀中文\"; void caller(){ callee(1); } void callee(int x){} void callee(String x){} }";
        var facts = run(request(text));
        Map<String, Object> method =
                facts.stream()
                        .filter(f -> "A#callee(int)".equals(f.get("signature")))
                        .findFirst()
                        .orElseThrow();
        Map<String, Object> call =
                facts.stream()
                        .filter(
                                f ->
                                        "call_target".equals(f.get("kind"))
                                                && method.get("id").equals(f.get("target")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("resolved", call.get("status"));
        assertEquals(true, call.get("declaration_resolved"));
        Map<String, Object> span = Json.map(call.get("span"));
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "callee(1)",
                new String(
                        Arrays.copyOfRange(
                                bytes,
                                ((Number) span.get("start")).intValue(),
                                ((Number) span.get("end")).intValue()),
                        StandardCharsets.UTF_8));
        Set<Object> ids = new HashSet<>();
        for (var f : facts) assertTrue(ids.add(f.get("id")), "duplicate fact");
        for (var f : facts)
            for (String key : List.of("parent", "target"))
                if (!"".equals(f.get(key))) assertTrue(ids.contains(f.get(key)), "dangling " + f);
    }

    @Test
    void uncapturedNeighborIsInvisibleAndProcessorIsNotRun() throws Exception {
        var req = request("class A { Missing missing; void f(){ Missing.run(); } }");
        Files.writeString(
                temporary.resolve("Missing.java"), "class Missing { static void run(){} }");
        var facts = run(req);
        assertTrue(
                facts.stream()
                        .noneMatch(
                                f ->
                                        "Missing".equals(f.get("signature"))
                                                && "declaration".equals(f.get("kind"))));
        assertTrue(facts.stream().anyMatch(f -> "diagnostic".equals(f.get("kind"))));
        try (var files = Files.walk(temporary)) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".class")));
        }
    }

    @Test
    void xmlOnlyContextPublishesOriginalFrameworkEvidence() throws Exception {
        var req = request("class A { public void start(){} }");
        var context = Json.map(Json.list(req.get("contexts")).getFirst());
        Path input = temporary.resolve("beans.xml");
        Files.writeString(
                input, "<beans><bean id=\"a\" class=\"A\" init-method=\"start\"/></beans>");
        context.put("spring", true);
        context.put("spring_xml", true);
        context.put(
                "resources",
                List.of(
                        Json.object(
                                "input",
                                input.toString(),
                                "unit",
                                Json.object(
                                        "path",
                                        "resources/beans.xml",
                                        "hash",
                                        Json.sha(input),
                                        "module",
                                        ".",
                                        "world",
                                        "resources",
                                        "scope",
                                        "RESOURCE"))));
        var facts = run(req);
        assertTrue(
                facts.stream().anyMatch(f -> "java.bean".equals(f.get("relation"))),
                facts.toString());
        assertTrue(
                facts.stream()
                        .filter(f -> "resources/beans.xml".equals(f.get("source")))
                        .allMatch(f -> "resource".equals(Json.map(f.get("owner")).get("kind"))));
    }

    @Test
    void springJavaDisableKeepsExplicitXmlAndSofaButSuppressesJavaComponents() throws Exception {
        var req = request("@org.springframework.stereotype.Component(\"a\") class A {}");
        var context = Json.map(Json.list(req.get("contexts")).getFirst());
        Path annotation = temporary.resolve("Component.java");
        Files.writeString(annotation, "package org.springframework.stereotype;"
                + "public @interface Component {String value() default \"\";}");
        var sources = new ArrayList<>(Json.list(context.get("sources")));
        var unit = new LinkedHashMap<>(Json.map(sources.getFirst()));
        unit.put("path", "src/org/springframework/stereotype/Component.java");
        unit.put("input", annotation.toString());
        unit.put("hash", Json.sha(annotation));
        sources.add(unit);
        context.put("sources", sources);
        context.put("spring", false);
        context.put("spring_xml", true);
        context.put("sofa", true);
        Path xml = temporary.resolve("beans.xml");
        Files.writeString(xml, "<beans xmlns:sofa=\"http://schema.alipay.com/sofa/schema/service\">"
                + "<bean id=\"a\" class=\"A\"/><sofa:reference id=\"remote\" interface=\"A\"/></beans>");
        context.put("resources", List.of(Json.object("input", xml.toString(), "unit",
                Json.object("path", "resources/beans.xml", "hash", Json.sha(xml),
                        "module", ".", "world", "resources", "scope", "RESOURCE"))));
        var facts = run(req);
        assertTrue(facts.stream().anyMatch(f -> "java.bean".equals(f.get("relation"))
                && "resources/beans.xml".equals(f.get("source"))));
        assertTrue(facts.stream().anyMatch(f -> "java.sofa".equals(f.get("relation"))));
        assertFalse(facts.stream().anyMatch(f -> "java.bean".equals(f.get("relation"))
                && "src/A.java".equals(f.get("source"))),
                "Spring Java is disabled independently of XML and SOFA");
    }

    @Test
    void qualifiedXmlEdgesPreserveTheNestedConfigurationParentChain() throws Exception {
        var req = request("class A {}");
        var context = Json.map(Json.list(req.get("contexts")).getFirst());
        Path input = temporary.resolve("beans.xml");
        Files.writeString(input, "<beans><bean id=\"a\" class=\"A\"><property name=\"items\">"
                + "<list><ref bean=\"a\"/><value>😀</value></list></property></bean></beans>");
        context.put("spring", true);
        context.put("spring_xml", true);
        context.put("resources", List.of(Json.object("input", input.toString(), "unit",
                Json.object("path", "resources/beans.xml", "hash", Json.sha(input),
                        "module", ".", "world", "resources", "scope", "RESOURCE"))));
        var facts = run(req);
        Map<String, Map<String, Object>> byId = new HashMap<>();
        facts.forEach(f -> byId.put((String) f.get("id"), f));
        var wire=facts.stream().filter(f->"wires_possible".equals(f.get("relation")))
                .findFirst().orElseThrow();
        assertEquals("possible",wire.get("status"));
        assertEquals(false,wire.get("evidence_complete"));
        assertEquals(false,wire.get("declaration_resolved"));
        var wireAttrs=Json.map(wire.get("attributes"));
        assertEquals("xml_configuration",wireAttrs.get("via"));
        assertEquals("EXTRACTED",wireAttrs.get("confidence"));
        assertEquals("WIRES",wireAttrs.get("upstream_relation"));
        assertEquals("spring-xml",wireAttrs.get("upstream_producer"));
        assertEquals(wireAttrs.get("upstream_position"),wireAttrs.get("position"));
        var bean = facts.stream().filter(f -> "java.bean".equals(f.get("relation")))
                .findFirst().orElseThrow();
        var link = facts.stream().filter(f -> "config".equals(f.get("relation")))
                .findFirst().orElseThrow();
        assertEquals(bean.get("id"), link.get("target"));
        var current = byId.get(link.get("parent"));
        List<String> kinds = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        while (!bean.get("id").equals(current.get("id"))) {
            assertTrue(seen.add(current.get("id")), "configuration cycle");
            kinds.add((String) Json.map(current.get("attributes")).get("kind"));
            current = byId.get(current.get("parent"));
            assertNotNull(current, "configuration parent is not published");
        }
        assertEquals(List.of("bean_ref", "list", "property"), kinds);
    }

    @Test
    void originalTypeNavigationOccurrencesArePublicReferences() throws Exception {
        var facts=run(request("class A { A value; A read(A input) {A local=input; return local;} }"));
        var navigation=facts.stream().filter(f->"type_navigation".equals(f.get("relation"))).toList();
        assertFalse(navigation.isEmpty());
        assertTrue(navigation.stream().allMatch(f->"reference".equals(f.get("kind"))));
    }

    @Test
    void eventRegistrationKeepsItsRecordedCallableParent() throws Exception {
        var project=new CapturedProject(request("class A {void event(String value){}}"));
        var result=new com.anatomist.model.ExtractionResult();
        var method=new com.anatomist.model.Node();
        method.id="A#event(java.lang.String)"; method.kind="METHOD"; method.sourceFile="src/A.java";
        result.nodes.add(method);
        var registration=new com.anatomist.model.Node();
        registration.id="framework-registration:event";registration.kind="FRAMEWORK_REGISTRATION";
        registration.domain="configuration";registration.sourceFile="src/A.java";
        registration.metadata="{\"role\":\"event\",\"mechanism\":\"spring.annotation.event\"}";
        result.nodes.add(registration);
        var edge=new com.anatomist.model.Edge();edge.sourceId=registration.id;edge.targetId=method.id;
        edge.relation="HANDLES";edge.confidence="CONFIGURED";edge.sourceFile="src/A.java";
        result.edges.add(edge);
        try(var archive=new FactArchive(temporary.resolve("event.caf"),1<<20,100)) {
            var projection=new FactProjection(project,archive);projection.context(project.contexts.getFirst());projection.extraction(result);
            var handler=archive.stream().map(Json::map).filter(f->"java.spring.handler".equals(f.get("relation"))).findFirst().orElseThrow();
            var callable=archive.stream().map(Json::map).filter(f->"declaration".equals(f.get("kind"))).findFirst().orElseThrow();
            assertEquals(callable.get("id"),handler.get("parent"));
        }
    }

    @Test
    void diagnosticClasspathIdentitySurvivesCapturedArchiveRelocation() throws Exception {
        var first=request("class A {Object run(ext.Api api){return api.missing();}}");
        Path archive=temporary.resolve("first/dependency.jar");
        Files.createDirectories(archive.getParent());
        Path source=temporary.resolve("Api.java"),classes=Files.createDirectory(temporary.resolve("binary-classes"));
        Files.writeString(source,"package ext;public class Api {}");
        var compiler=new ProcessBuilder(Path.of(Json.string(first,"java_home"),"bin/javac").toString(),
                "-proc:none","-d",classes.toString(),source.toString()).redirectErrorStream(true).start();
        String compileLog=new String(compiler.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,compiler.waitFor(),compileLog);
        try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(archive))) {
            var entry=new java.util.jar.JarEntry("ext/Api.class");entry.setTime(0);
            out.putNextEntry(entry);out.write(Files.readAllBytes(classes.resolve("ext/Api.class")));out.closeEntry();
        }
        var context=Json.map(Json.list(first.get("contexts")).getFirst());
        context.put("classpath",List.of(archive.toString()));
        var before=run(first).stream().filter(f->"diagnostic".equals(f.get("kind")))
                .filter(f->f.toString().contains("NAVIGATION_TARGET_UNRESOLVED")).toList();
        assertFalse(before.isEmpty(),"actual unresolved javac navigation must be observed");
        Path relocated=temporary.resolve("second/dependency.jar");
        Files.createDirectories(relocated.getParent());Files.copy(archive,relocated);
        var second=new LinkedHashMap<>(first);
        second.put("scratch",Files.createDirectory(temporary.resolve("second-scratch")).toString());
        context.put("classpath",List.of(relocated.toString()));
        var after=run(second).stream().filter(f->"diagnostic".equals(f.get("kind")))
                .filter(f->f.toString().contains("NAVIGATION_TARGET_UNRESOLVED")).toList();
        assertEquals(before,after,"captured dependency bytes and order are unchanged");
        try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(relocated))) {
            out.putNextEntry(new java.util.jar.JarEntry("proof.txt"));out.write(1);out.closeEntry();
        }
        var third=new LinkedHashMap<>(second);
        third.put("scratch",Files.createDirectory(temporary.resolve("changed-scratch")).toString());
        var changed=run(third).stream().filter(f->"diagnostic".equals(f.get("kind")))
                .filter(f->f.toString().contains("NAVIGATION_TARGET_UNRESOLVED")).toList();
        assertFalse(changed.isEmpty());assertNotEquals(before,changed,"changed dependency bytes invalidate provenance");
    }

    @Test
    void changedConsumerReusesTheExactPublishedProducerCatalogue() throws Exception {
        catalogueDelta(false);
    }

    @Test
    void generatedNoCodeClasspathKeepsDiagnosticIdentityAcrossTaskCaches() throws Exception {
        catalogueDelta(true);
    }

    private void catalogueDelta(boolean unresolved) throws Exception {
        var request=request("package p; public class Api {public String get(){return \"x\";}}");
        var library=Json.map(Json.list(request.get("contexts")).getFirst());
        var libraryUnit=Json.map(Json.list(library.get("sources")).getFirst());
        libraryUnit.put("path","lib/p/Api.java");libraryUnit.put("module","lib");libraryUnit.put("world","lib-main");
        library.put("module","lib");library.put("context","lib@MAIN");library.put("world","lib-main");
        Path use=temporary.resolve("Use.java");
        String member=unresolved ? "missing" : "get";
        Files.writeString(use,"package p; class Use {String read(Api api){return api."+member+"();}}");
        var consumerUnit=Json.object("path","app/p/Use.java","module","app","world","app-main","scope","MAIN",
                "input",use.toString(),"hash",Json.sha(use),"publish",true);
        var consumer=new LinkedHashMap<>(library);
        consumer.put("module","app");consumer.put("context","app@MAIN");consumer.put("world","app-main");consumer.put("digest","before");
        consumer.put("dependency_modules",List.of("lib"));
        var auxiliary=new LinkedHashMap<>(libraryUnit);auxiliary.put("publish",false);
        consumer.put("sources",List.of(auxiliary,consumerUnit));
        request.put("contexts",List.of(consumer,library));
        request.put("cache_root",Files.createDirectory(temporary.resolve("shared-cache")).toString());
        var initial=run(request);
        Map<String,Object> baseline;
        try(var archive=new FactArchive(temporary.resolve("baseline.tmp"),64<<20,100000)) {
            initial.forEach(archive::add);baseline=archive.export(temporary.resolve("baseline.caf"));
        }
        Files.writeString(use,"package p; class Use {String read(Api api){return api."+member+"()+\"changed\";}}");
        consumerUnit.put("hash",Json.sha(use));consumer.put("digest","after");
        var delta=new LinkedHashMap<>(request);
        delta.put("full",false);delta.put("baseline_archive",baseline);
        delta.put("scratch",Files.createDirectory(temporary.resolve("delta-scratch")).toString());
        if(unresolved)delta.put("cache_root",Files.createDirectory(temporary.resolve("delta-cache")).toString());
        List<Map<String,Object>> deltaFacts=new ArrayList<>();
        try(var session=new AnalysisSession(delta)) {
            var result=session.analyze();
            var replaced=Json.list(result.get("replacements")).stream().map(Json::write).collect(java.util.stream.Collectors.toSet());
            for(var fact:initial)if(!replaced.contains(Json.write(fact.get("owner"))))deltaFacts.add(fact);
            for(var fact:(Iterable<?>)result.get("facts"))deltaFacts.add(Json.map(fact));
        }
        var full=new LinkedHashMap<>(request);
        full.put("scratch",Files.createDirectory(temporary.resolve("full-scratch")).toString());
        if(unresolved)full.put("cache_root",Files.createDirectory(temporary.resolve("full-cache")).toString());
        Comparator<Map<String,Object>> order=Comparator.comparing(f->Json.string(f,"id"));
        var fullFacts=run(full).stream().sorted(order).toList();
        deltaFacts.sort(order);
        assertEquals(fullFacts.size(),deltaFacts.size());
        for(int i=0;i<fullFacts.size();i++)assertEquals(fullFacts.get(i),deltaFacts.get(i),"fact "+i);
    }

    @Test
    void originalMetadataObjectOrderDoesNotChangeFactIdentity() throws Exception {
        var project=new CapturedProject(request("class A {}"));
        var node=new com.anatomist.model.Node();
        node.id="A";node.symbolId="A";node.kind="CLASS";node.sourceFile="src/A.java";
        var edge=com.anatomist.model.Edge.reference("A","A",false);edge.sourceFile="src/A.java";
        var result=new com.anatomist.model.ExtractionResult();result.nodes.add(node);result.edges.add(edge);
        List<String> variants=List.of("{\"z\":3,\"nested\":{\"b\":2,\"a\":1}}",
                "{\"nested\":{\"a\":1,\"b\":2},\"z\":3}");
        List<Object> first=null;
        for(int index=0;index<variants.size();index++) {
            node.metadata=variants.get(index);edge.metadata=variants.get(index);
            try(var archive=new FactArchive(temporary.resolve("order-"+index+".caf"),1<<20,100)) {
                var projection=new FactProjection(project,archive);
                projection.context(project.contexts.getFirst());projection.extraction(result);
                var facts=archive.stream().toList();
                if(first==null)first=facts;else assertEquals(first,facts);
            }
        }
    }

    @Test
    void nodeAndEdgeProjectionRetainsProviderColumnsOutsideMetadata() throws Exception {
        var project = new CapturedProject(request("class A {}"));
        var result = new com.anatomist.model.ExtractionResult();
        var node = new com.anatomist.model.Node();
        node.id="A"; node.symbolId="A"; node.kind="CLASS"; node.sourceFile="src/A.java";
        node.domain="code"; node.language="java"; node.providerId="java-core";
        node.entityKind="type"; node.languageKind="CLASS"; node.namespace="p";
        node.qualifiedName="p.A"; node.pkg="p"; node.module="."; node.scope="MAIN";
        node.sourceLocation="L1:1"; node.sourceOrdinal=7;
        node.metadata="{\"namespace\":\"metadata-namespace\"}";
        result.nodes.add(node);
        var edge=com.anatomist.model.Edge.reference("A","A",false);
        edge.sourceFile="src/A.java"; edge.sourceLocation="L1:7"; edge.sourceOrdinal=9;
        edge.language="java"; edge.providerId="java-core"; edge.semantic="reference";
        edge.mechanism="java.type"; edge.syntaxTarget="A";
        edge.metadata="{\"mechanism\":\"metadata-mechanism\"}";
        result.edges.add(edge);
        try (var archive=new FactArchive(temporary.resolve("columns.caf"),1<<20,100)) {
            var projection=new FactProjection(project,archive);
            projection.context(project.contexts.getFirst()); projection.extraction(result);
            var declaration=archive.stream().map(Json::map).filter(f->"declaration".equals(f.get("kind"))).findFirst().orElseThrow();
            var attrs=Json.map(declaration.get("attributes"));
            assertEquals(node.providerId,attrs.get("provider_id"));
            assertEquals(node.domain,attrs.get("domain"));
            assertEquals(node.language,attrs.get("language"));
            assertEquals("metadata-namespace",attrs.get("namespace"));
            assertEquals(node.namespace,attrs.get("upstream_namespace"));
            assertEquals(node.metadata,attrs.get("upstream_metadata"));
            assertEquals(node.qualifiedName,attrs.get("upstream_qualified_name"));
            assertEquals(node.entityKind,attrs.get("upstream_entity_kind"));
            assertEquals(node.languageKind,attrs.get("language_kind"));
            assertEquals(node.sourceLocation,attrs.get("source_location"));
            assertEquals(node.sourceOrdinal,((Number)attrs.get("source_ordinal")).intValue());
            var relation=archive.stream().map(Json::map).filter(f->"type_reference".equals(f.get("relation"))).findFirst().orElseThrow();
            attrs=Json.map(relation.get("attributes"));
            assertEquals(edge.providerId,attrs.get("provider_id"));
            assertEquals(edge.semantic,attrs.get("semantic"));
            assertEquals("metadata-mechanism",attrs.get("mechanism"));
            assertEquals(edge.mechanism,attrs.get("upstream_mechanism"));
            assertEquals(edge.metadata,attrs.get("upstream_metadata"));
            assertEquals(edge.sourceLocation,attrs.get("source_location"));
            assertEquals(edge.sourceOrdinal,((Number)attrs.get("source_ordinal")).intValue());
            assertEquals(edge.syntaxTarget,attrs.get("syntax_target"));
            assertEquals(false,attrs.get("upstream_is_external"));
        }
    }

    @Test
    void annotationProjectionRetainsTheOriginalProviderAndSourceEvidence() throws Exception {
        var project = new CapturedProject(request("class A {}"));
        var result = new com.anatomist.model.ExtractionResult();
        var meta = new com.anatomist.model.AnnotationMetaRelation();
        meta.annotationFqn = "p.Note";
        meta.metaAnnotationFqn = "java.lang.annotation.Target";
        meta.rawName = "Target";
        meta.language = "java";
        meta.providerId = "java-core";
        meta.mechanism = "annotation.meta";
        meta.resolutionStatus = "exact";
        meta.sourceFile = "src/A.java";
        meta.sourceLocation = "L1:1";
        meta.producerId = "java-core";
        result.annotationMetaRelations.add(meta);
        try (var archive = new FactArchive(temporary.resolve("projection.caf"), 1 << 20, 100)) {
            var projection = new FactProjection(project, archive);
            projection.context(project.contexts.getFirst());
            projection.extraction(result);
            var fact = Json.map(archive.getFirst());
            var attrs = Json.map(fact.get("attributes"));
            assertEquals(meta.rawName, attrs.get("raw_name"));
            assertEquals(meta.language, attrs.get("language"));
            assertEquals(meta.providerId, attrs.get("provider_id"));
            assertEquals(meta.mechanism, attrs.get("mechanism"));
            assertEquals(meta.sourceLocation, attrs.get("source_location"));
            assertEquals(meta.producerId, attrs.get("upstream_producer"));
        }
    }

    @Test
    void unresolvedCallsKeepControlContextWithoutInventingTargets() throws Exception {
        var project = new CapturedProject(request("class A { void run() {} }"));
        var result = new com.anatomist.model.ExtractionResult();
        result.unresolvedCallSites.add(new com.anatomist.model.UnresolvedCallSite(
                "A#run()", "src/A.java", 1, 11, 1, 20, 0, "missing()",
                null, "VIRTUAL", "foreach@L1>if-then@L1", "{}", "java-core"));
        try (var archive = new FactArchive(temporary.resolve("unresolved.caf"), 1 << 20, 100)) {
            var projection = new FactProjection(project, archive);
            projection.context(project.contexts.getFirst());
            projection.extraction(result);
            var facts = archive.stream().map(Json::map).toList();
            assertTrue(facts.stream().noneMatch(f -> "call_target".equals(f.get("kind"))));
            var sites = facts.stream().filter(f -> "call_site".equals(f.get("kind"))).toList();
            assertEquals(1, sites.size());
            var site = sites.getFirst();
            assertEquals("call_site", site.get("kind"));
            assertEquals("unresolved", site.get("status"));
            assertEquals(false, site.get("declaration_resolved"));
            assertEquals(false, site.get("evidence_complete"));
            assertEquals("foreach@L1>if-then@L1",
                    Json.map(site.get("attributes")).get("control_context"));
        }
    }

    @Test
    void repeatedTypeRelationshipsKeepTheirOriginalMultiplicity() throws Exception {
        var project = new CapturedProject(request("class A {}"));
        var result = new com.anatomist.model.ExtractionResult();
        var node = new com.anatomist.model.Node();
        node.id = "A";
        node.kind = "CLASS";
        node.sourceFile = "src/A.java";
        result.nodes.add(node);
        for (int i = 0; i < 3; i++) {
            var edge = com.anatomist.model.Edge.reference("A", "A", false);
            edge.sourceFile = "src/A.java";
            edge.context = "generic_arg";
            result.edges.add(edge);
        }
        try (var archive = new FactArchive(temporary.resolve("projection.caf"), 1 << 20, 100)) {
            var projection = new FactProjection(project, archive);
            projection.context(project.contexts.getFirst());
            projection.extraction(result);
            var relations = archive.stream().map(Json::map)
                    .filter(f -> "type_reference".equals(f.get("relation"))).toList();
            assertEquals(3, relations.size());
            assertEquals(3, relations.stream().map(f -> f.get("id")).distinct().count());
        }
    }

    @Test
    void generatedSignatureTypeReferencesRetainTheModeledDeclarationStatus() throws Exception {
        var req = request("@lombok.Getter class A { B value; } class B {}");
        var context = Json.map(Json.list(req.get("contexts")).getFirst());
        context.put("lombok", "model");
        Path annotation = temporary.resolve("Getter.java");
        Files.writeString(annotation, "package lombok; public @interface Getter {}");
        var sources = new ArrayList<>(Json.list(context.get("sources")));
        sources.add(Json.object("path", "src/lombok/Getter.java", "hash", Json.sha(annotation),
                "module", ".", "world", "main", "scope", "MAIN", "input", annotation.toString(),
                "publish", true));
        context.put("sources", sources);
        var facts = run(req);
        var getter = facts.stream().filter(f -> "A#getValue()".equals(f.get("signature")))
                .findFirst().orElseThrow();
        var references = facts.stream().filter(f -> "type_reference".equals(f.get("relation"))
                && getter.get("id").equals(f.get("parent"))).toList();
        assertFalse(references.isEmpty());
        for (var reference : references) {
            assertEquals("possible", reference.get("status"));
            assertEquals("lombok_model", reference.get("origin"));
            assertEquals(false, reference.get("evidence_complete"));
            assertEquals("EXTRACTED", Json.map(reference.get("attributes")).get("confidence"));
        }
    }

    @Test
    void corruptCaptureIsRejectedBeforeCompilation() throws Exception {
        var req = request("class A {}");
        Files.writeString(temporary.resolve("input.java"), "class Changed {}");
        assertTrue(
                assertThrows(Exception.class, () -> run(req))
                        .getMessage()
                        .contains("CAPTURE_HASH_MISMATCH"));
    }

    @Test
    void emptyTestScopeKeepsMainTypesForXmlWithoutAnotherCompilerTask() throws Exception {
        var req = request("class A {public void start(){}}");
        var main = Json.map(Json.list(req.get("contexts")).getFirst());
        var test = new LinkedHashMap<String, Object>(main);
        test.put("context", ".@TEST");
        test.put("scope", "TEST");
        test.put("world", "test");
        test.put("digest", "test-xml");
        test.put("spring", true);
        test.put("spring_xml", true);
        var auxiliary = new LinkedHashMap<>(Json.map(Json.list(main.get("sources")).getFirst()));
        auxiliary.put("publish", false);
        test.put("sources", List.of(auxiliary));
        Path xml = temporary.resolve("test-beans.xml");
        Files.writeString(xml, "<beans><bean id=\"a\" class=\"A\" init-method=\"start\"/></beans>");
        test.put(
                "resources",
                List.of(
                        Json.object(
                                "input",
                                xml.toString(),
                                "unit",
                                Json.object(
                                        "path",
                                        "test-resources/beans.xml",
                                        "hash",
                                        Json.sha(xml),
                                        "module",
                                        ".",
                                        "world",
                                        "test",
                                        "scope",
                                        "RESOURCE"))));
        req.put("contexts", List.of(main, test));
        List<String> analyzed = new ArrayList<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        try (var session =
                new AnalysisSession(
                        req,
                        new AnalysisObserver() {
                            public void model(com.anatomist.core.java.JavaAnalysisContext c) {
                                analyzed.add(c.scope().name());
                            }
                        })) {
            for (Object row : (Iterable<?>) session.analyze().get("facts"))
                facts.add(Json.map(row));
        }
        assertEquals(List.of("MAIN"), analyzed);
        var method =
                facts.stream()
                        .filter(f -> "A#start()".equals(f.get("signature")))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                facts.stream()
                        .anyMatch(
                                f ->
                                        "config_member".equals(f.get("relation"))
                                                && method.get("id").equals(f.get("target"))));
        assertTrue(
                facts.stream()
                        .anyMatch(
                                f ->
                                        "java.bean".equals(f.get("relation"))
                                                && "test-resources/beans.xml"
                                                        .equals(f.get("source"))));
    }
}
