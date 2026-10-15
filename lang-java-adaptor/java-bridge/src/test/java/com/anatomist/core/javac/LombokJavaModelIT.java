package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.asmsolver.*;
import com.anatomist.core.java.*;
import com.anatomist.core.process.ProcessRunner;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.JavaModelExtensions;
import com.anatomist.framework.lombok.LombokJavaModelExtension;
import com.anatomist.json.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

class LombokJavaModelIT {
    @Test
    void modeledCallbackTargetsRestoreReferencesWithoutInventingCompilerBindings(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("callbacks-" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                class Item { void consume() {} }
                @lombok.Getter class Config { private java.util.List<Item> items; }
                class Use {
                  void run(Config config) { config.getItems().forEach(item -> item.consume()); }
                  void bad(Config config) { config.getItems().forEach((String item) -> {}); }
                }
                """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            List.of(LOMBOK),
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                backend(project, release, List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                        .analyzeFiles(
                                List.of(source), context -> extractor.extract(context, facts::add));
            }
            var result = facts.getFirst().facts();
            var callback =
                    result.nodes.stream()
                            .filter(
                                    node ->
                                            node.kind.equals("LAMBDA")
                                                    && node.id.startsWith("Use#run("))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    callback.metadata.contains(
                            "\"functional_type_resolution\":\"source_fallback\""),
                    "JDK " + release + ": " + callback.metadata);
            assertTrue(callback.metadata.contains("\"bindingResolved\":false"));
            var refs =
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("REFERENCES")
                                                    && edge.sourceId.equals(callback.id))
                            .toList();
            assertEquals(
                    Set.of("Item", "java.util.function.Consumer"),
                    refs.stream()
                            .map(
                                    edge ->
                                            edge.targetId == null
                                                    ? edge.externalTargetFqn
                                                    : edge.targetId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(
                    refs.stream()
                            .allMatch(
                                    edge ->
                                            edge.confidence.equals("INFERRED")
                                                    && edge.resolution.equals("source_fallback")
                                                    && edge.metadata.contains(
                                                            "\"bindingResolved\":false")
                                                    && edge.beginLine != null));
            assertFalse(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("REFERENCES")
                                                    && edge.sourceId.startsWith("Use#bad(")
                                                    && "java.util.function.Consumer"
                                                            .equals(edge.externalTargetFqn)));
        }
    }

    @Test
    void enumCaseLabelsRecoverOnlyFromConfirmedModeledSelectorTypes(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("enum-labels-" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    enum State { ON, OFF }
                    @lombok.Getter class Input { private State state; }
                    @lombok.Getter class MissingInput { private Missing state; }
                    class Use {
                        void run(Input input) {
                            switch (input.getState()) { case ON: break; case OFF: break; }
                        }
                        void missing(MissingInput input) {
                            switch (input.getState()) { case ON: break; }
                        }
                        void wrong(Input input) {
                            switch (input.getState()) { case ABSENT: break; }
                        }
                        void unmodeled(Missing input) {
                            switch (input.getState()) { case ON: break; }
                        }
                    }
                    """);
            Path lombok =
                    Path.of(
                            System.getProperty("user.home"),
                            ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
            var backend = backend(project, release, List.of(), List.of(lombok));
            var facts = new com.anatomist.model.ExtractionResult();
            var debug = new ArrayList<String>();
            backend.analyzeFiles(
                    List.of(source),
                    context -> {
                        debug.add("failure=" + context.analysisFailure());
                        for (var unit : context.units())
                            debug.add(unit.extensions().diagnostics().toString());
                        try (var binaries = backend.openBinaryRepository(context)) {
                            new JavaModelExtractor(project, binaries)
                                    .extract(context, unit -> facts.addFacts(unit.facts()));
                        }
                    });
            var labels =
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("READS")
                                                    && edge.targetId != null
                                                    && edge.targetId.startsWith("State#"))
                            .toList();
            assertEquals(2, labels.size(), () -> debug.toString());
            assertEquals(
                    Set.of("State#ON", "State#OFF"),
                    labels.stream()
                            .map(edge -> edge.targetId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(
                    labels.stream()
                            .allMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#run(Input)")
                                                    && edge.confidence.equals("INFERRED")
                                                    && edge.resolution.equals("source_fallback")
                                                    && edge.metadata.contains(
                                                            "\"bindingResolved\":false")));
        }
    }

    @Test
    void sourceOverridesModeledParentMethodsWithConservativeAccessAndReturnChecks(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("source-overrides-" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    @lombok.Getter class Parent<T> { T value; int count; }
                    class Child extends Parent<String> {
                        @Override public String getValue() { return "value"; }
                        @Override public int getCount() { return 1; }
                    }
                    class BadReturn extends Parent<String> { public int getValue() { return 1; } }
                    class ReducedVisibility extends Parent<String> { String getValue() { return "value"; } }
                    class StaticChild extends Parent<String> { public static String getValue() { return "value"; } }
                    @lombok.Getter(lombok.AccessLevel.PRIVATE) class PrivateParent { String value; }
                    class PrivateChild extends PrivateParent { public String getValue() { return "value"; } }
                    """);
            var lombok =
                    Path.of(
                            System.getProperty("user.home"),
                            ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
            assertTrue(Files.isRegularFile(lombok));
            var backend = backend(project, release, List.of(), List.of(lombok));
            var facts = new com.anatomist.model.ExtractionResult();
            var diagnostics = new ArrayList<com.anatomist.core.IndexDiagnostic>();
            backend.analyzeFiles(
                    List.of(source),
                    context -> {
                        try (var binaries = backend.openBinaryRepository(context)) {
                            new JavaModelExtractor(project, binaries)
                                    .extract(
                                            context,
                                            unit -> {
                                                facts.addFacts(unit.facts());
                                                diagnostics.addAll(unit.diagnostics());
                                            });
                        }
                    });
            var overrides =
                    facts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("OVERRIDES")
                                                    && edge.targetId != null
                                                    && edge.targetId.startsWith("Parent#"))
                            .toList();
            assertEquals(
                    Set.of("Child#getValue()", "Child#getCount()"),
                    overrides.stream()
                            .map(edge -> edge.sourceId)
                            .collect(java.util.stream.Collectors.toSet()),
                    () -> diagnostics.toString());
            assertEquals(2, overrides.size());
            assertTrue(
                    overrides.stream()
                            .allMatch(
                                    edge ->
                                            edge.confidence.equals("INFERRED")
                                                    && edge.resolution.equals("source_fallback")
                                                    && edge.metadata.contains(
                                                            "\"bindingResolved\":false")));
            assertFalse(
                    facts.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("OVERRIDES")
                                                    && "PrivateChild#getValue()"
                                                            .equals(edge.sourceId)));
        }
    }

    @Test
    void modeledGetterTypesSelectSiblingOverloadsAndPreserveInputProvenance(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("overloads" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    class Parent {}
                    class Parser extends Parent {}
                    class Assembler extends Parent {}
                    enum Protocol { HTTP }
                    @lombok.Getter class Config {
                        private Parser parser;
                        private String text;
                        private java.util.Map<String,Parser> parsers;
                        private java.util.List<Parser> list;
                        private int number;
                        private Integer boxed;
                        private char[] characters;
                        private java.util.List<Config> configs;
                        private java.util.List<? extends Config> wildcardConfigs;
                        private Config nested;
                        private java.util.Map.Entry<String,String> entry;
                        private java.util.Base64.Decoder decoder;
                        private java.text.Normalizer.Form form;
                        private Protocol protocol;
                        private java.util.HashMap<String,String> data;
                        static { getParser(); }
                    }
                    class Child extends Config {}
                    class Explicit extends Config { public String getText() { return "explicit"; } }
                    class Factory {
                        void format(Assembler value) {}
                        void format(Parser value) {}
                        void number(long value) {}
                        void number(Integer value) {}
                        void reference(Object value) {}
                        void reference(String value) {}
                        void broad(Object value) {}
                        void broad(long value) {}
                        void widened(long value) {}
                        void packed(Object value) {}
                        void packed(String... values) {}
                        void expanded(Object... values) {}
                        void expanded(String... values) {}
                        void zero(Object... values) {}
                        void zero(String... values) {}
                    }
                    class Use {
                        void run(Child config, Factory factory) { factory.format(config.getParser()); }
                        int length(Config config) { return config.getText().length(); }
                        int independent(Config config) { String value = config.getText(); return value.length(); }
                        void mapped(Config config, Factory factory) { factory.format(config.getParsers().get("key")); }
                        Object optional(Config config) { return java.util.Optional.ofNullable(config.getParser()); }
                        void optionalChain(Config config, Factory factory) { factory.format(java.util.Optional.ofNullable(config.getParser()).get()); }
                        Object stream(Config config) { return config.getList().stream(); }
                        String converted(Config config) { return String.valueOf(config.getParser()); }
                        String invalid(Config config) { return Integer.toString(config.getParser()); }
                        void numeric(Config config, Factory factory) { factory.number(config.getNumber()); }
                        void boxed(Config config, Factory factory) { factory.number(config.getBoxed()); }
                        void reference(Config config, Factory factory) { factory.reference(config.getText()); }
                        void broad(Config config, Factory factory) { factory.broad(config.getBoxed()); }
                        void widened(Config config, Factory factory) { factory.widened(config.getBoxed()); }
                        void packed(Config config, Factory factory) { factory.packed(config.getText()); }
                        void expanded(Config config, Factory factory) { factory.expanded(config.getText(), config.getText()); }
                        void zero(Config config, Factory factory) { factory.zero(); }
                        String characters(Config config) { return String.valueOf(config.getCharacters()); }
                        Object binaryVarargs(Config config) { return java.util.Arrays.asList(config.getText(), config.getText()); }
                        void genericVarargs(Config config, Factory factory) { factory.reference(java.util.Arrays.asList(config.getText(), config.getText()).get(0)); }
                        Object boxedOptional(Config config) { return java.util.Optional.ofNullable(config.getNumber()); }
                        Object arrayStream(Config config) { return java.util.Arrays.stream(config.getCharacters()); }
                        Object filtered(Config config) { return config.getConfigs().stream().filter(value -> value.getText().isEmpty()).count(); }
                        Object mappedReference(Config config) { return config.getConfigs().stream().map(Config::getText).count(); }
                        Object mappedLambda(Config config) { return config.getConfigs().stream().map(value -> value.getText()).count(); }
                        void optionalReference(Config config, Factory factory) { factory.reference(java.util.Optional.ofNullable(config.getNested()).map(Config::getText).get()); }
                        void independentLambda(java.util.List<String> values) { values.forEach(value -> value.length()); }
                        Object appended(Config config) { return new StringBuilder().append(config.getText()).append(config.getNumber()); }
                        Object constructed(Config config) { return new StringBuilder(config.getText()); }
                        Object nestedEntry(Config config) { return config.getEntry().getKey(); }
                        Object nestedDecoder(Config config) { return config.getDecoder().decode(config.getText()); }
                        Object nestedParameter(Config config) { return java.text.Normalizer.normalize(config.getText(), config.getForm()); }
                        String enumName(Config config) { return config.getProtocol().name(); }
                        void merged(Config config, java.util.Map<String,String> values) { config.getData().putAll(values); }
                        Object wildcardReference(Config config) { return config.getWildcardConfigs().stream().map(Config::getText).count(); }
                        Object wildcardLambda(Config config) { return config.getWildcardConfigs().stream().map(value -> value.getText()).count(); }
                        Object blockLambda(Config config) { return config.getConfigs().stream().map(value -> { if (value == null) return "null"; return value.getText(); }).count(); }
                        String directReference(Config config) { return java.util.Optional.ofNullable(config).map(Config::getText).get(); }
                        String explicitReference(Explicit config) { return java.util.Optional.ofNullable(config).map(Explicit::getText).get(); }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    new JavaBinaryRepository(
                            new JdkClassFileSource(
                                    Objects.requireNonNull(JavacBackendIT.home(release)), release),
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                                .analyzeFiles(
                                        List.of(source),
                                        analysis -> extractor.extract(analysis, facts::add))
                                .complete());
            }
            var result = facts.getFirst().facts();
            for (String method : List.of("wildcardReference", "wildcardLambda", "blockLambda")) {
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals("Use#" + method + "(Config)")
                                                        && "java.util.stream.Stream#map(java.util.function.Function)"
                                                                .equals(edge.externalTargetFqn)),
                        "JDK " + release + ": " + method + ": " + result.unresolvedCallSites);
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.startsWith(
                                                                "Use#" + method + "(Config)$")
                                                        && "Config#getText()"
                                                                .equals(edge.targetId)),
                        "JDK " + release + ": " + method);
            }
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#run(Child,Factory)")
                                                    && "Factory#format(Parser)"
                                                            .equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")
                                                    && edge.metadata.contains(
                                                            "\"modeledInput\":true")),
                    "JDK " + release + ": " + result.unresolvedCallSites);
            assertFalse(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && "Factory#format(Assembler)"
                                                            .equals(edge.targetId)));
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#length(Config)")
                                                    && "java.lang.String#length()"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("INFERRED")
                                                    && edge.metadata.contains(
                                                            "\"modeledInput\":true")));
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#independent(Config)")
                                                    && "java.lang.String#length()"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("EXTRACTED")));
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#mapped(Config,Factory)")
                                                    && "Factory#format(Parser)"
                                                            .equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")));
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#mapped(Config,Factory)")
                                                    && "java.util.Map#get(java.lang.Object)"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("INFERRED")));
            for (var expected :
                    Map.of(
                                    "Use#optional(Config)",
                                            "java.util.Optional#ofNullable(java.lang.Object)",
                                    "Use#converted(Config)",
                                            "java.lang.String#valueOf(java.lang.Object)",
                                    "Use#boxedOptional(Config)",
                                            "java.util.Optional#ofNullable(java.lang.Object)",
                                    "Use#binaryVarargs(Config)",
                                            "java.util.Arrays#asList(java.lang.Object[])")
                            .entrySet())
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(expected.getKey())
                                                        && expected.getValue()
                                                                .equals(edge.externalTargetFqn)
                                                        && edge.confidence.equals("INFERRED")),
                        "JDK " + release + " " + expected + ": " + result.unresolvedCallSites);
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals(
                                                            "Use#optionalChain(Config,Factory)")
                                                    && "Factory#format(Parser)"
                                                            .equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")),
                    result.unresolvedCallSites.toString());
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#stream(Config)")
                                                    && "java.util.Collection#stream()"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("INFERRED")),
                    result.unresolvedCallSites.toString());
            assertFalse(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#invalid(Config)")
                                                    && edge.relation.equals("CALLS")
                                                    && edge.externalTargetFqn != null));
            assertTrue(
                    result.unresolvedCallSites.stream()
                            .anyMatch(site -> site.callerId().equals("Use#invalid(Config)")));
            for (var expected :
                    Map.of(
                                    "Use#numeric(Config,Factory)", "Factory#number(long)",
                                    "Use#boxed(Config,Factory)",
                                            "Factory#number(java.lang.Integer)",
                                    "Use#reference(Config,Factory)",
                                            "Factory#reference(java.lang.String)",
                                    "Use#broad(Config,Factory)", "Factory#broad(java.lang.Object)",
                                    "Use#genericVarargs(Config,Factory)",
                                            "Factory#reference(java.lang.String)")
                            .entrySet())
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(expected.getKey())
                                                        && expected.getValue().equals(edge.targetId)
                                                        && edge.confidence.equals("INFERRED")),
                        "JDK " + release + " " + expected + ": " + result.unresolvedCallSites);
            for (var expected :
                    Map.of(
                                    "Use#widened(Config,Factory)", "Factory#widened(long)",
                                    "Use#packed(Config,Factory)",
                                            "Factory#packed(java.lang.Object)",
                                    "Use#expanded(Config,Factory)",
                                            "Factory#expanded(java.lang.String...)")
                            .entrySet())
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(expected.getKey())
                                                        && expected.getValue().equals(edge.targetId)
                                                        && edge.confidence.equals("INFERRED")),
                        "JDK " + release + " " + expected + ": " + result.unresolvedCallSites);
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#characters(Config)")
                                                    && "java.lang.String#valueOf(char[])"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("INFERRED")),
                    result.unresolvedCallSites.toString());
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.startsWith("Use#filtered(Config)$lambda@")
                                                    && "Config#getText()".equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")),
                    result.unresolvedCallSites.toString());
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.startsWith(
                                                            "Use#independentLambda(java.util.List)$lambda@")
                                                    && "java.lang.String#length()"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("EXTRACTED")),
                    result.unresolvedCallSites.toString());
            for (String method : List.of("filtered", "mappedReference", "mappedLambda"))
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals("Use#" + method + "(Config)")
                                                        && "java.util.stream.Stream#count()"
                                                                .equals(edge.externalTargetFqn)
                                                        && edge.confidence.equals("INFERRED")),
                        "JDK " + release + " " + method + ": " + result.unresolvedCallSites);
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals(
                                                            "Use#optionalReference(Config,Factory)")
                                                    && "Factory#reference(java.lang.String)"
                                                            .equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")),
                    result.unresolvedCallSites.toString());
            for (String target :
                    List.of(
                            "java.lang.StringBuilder#append(java.lang.String)",
                            "java.lang.StringBuilder#append(int)"))
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals("Use#appended(Config)")
                                                        && target.equals(edge.externalTargetFqn)
                                                        && edge.confidence.equals("INFERRED")),
                        result.unresolvedCallSites.toString());
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("Use#constructed(Config)")
                                                    && "java.lang.StringBuilder#StringBuilder(java.lang.String)"
                                                            .equals(edge.externalTargetFqn)
                                                    && edge.confidence.equals("INFERRED")),
                    result.unresolvedCallSites.toString());
            for (var expected :
                    Map.of(
                                    "Use#nestedEntry(Config)", "java.util.Map$Entry#getKey()",
                                    "Use#nestedDecoder(Config)",
                                            "java.util.Base64$Decoder#decode(java.lang.String)",
                                    "Use#nestedParameter(Config)",
                                            "java.text.Normalizer#normalize(java.lang.CharSequence,java.text.Normalizer$Form)")
                            .entrySet())
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(expected.getKey())
                                                        && expected.getValue()
                                                                .equals(edge.externalTargetFqn)
                                                        && edge.confidence.equals("INFERRED")),
                        result.unresolvedCallSites.toString());
            assertTrue(
                    result.unresolvedCallSites.stream()
                            .anyMatch(site -> site.callerId().startsWith("Config#<clinit>")));
            for (var expected :
                    Map.of(
                                    "Use#enumName(Config)", "java.lang.Enum#name()",
                                    "Use#merged(Config,java.util.Map)",
                                            "java.util.HashMap#putAll(java.util.Map)",
                                    "Use#directReference(Config)", "java.util.Optional#get()")
                            .entrySet()) {
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(expected.getKey())
                                                        && expected.getValue()
                                                                .equals(edge.externalTargetFqn)
                                                        && edge.confidence.equals("INFERRED")),
                        "JDK " + release + " " + expected + ": " + result.unresolvedCallSites);
            }
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.startsWith(
                                                            "Use#explicitReference(Explicit)$methodref@")
                                                    && "Explicit#getText()".equals(edge.targetId)
                                                    && edge.confidence.equals("EXTRACTED")),
                    "explicit source override JDK " + release);
            assertFalse(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.startsWith(
                                                            "Config#<clinit>")));
        }
    }

    @Test
    void functionalDescriptorsRecoverModeledReferencesWithOriginalCoordinates(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("references" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Getter @lombok.Setter @lombok.AllArgsConstructor
                    class Config { private String text; }
                    interface Getter extends java.util.function.Function<Config,String> {}
                    interface NotFunctional extends java.util.function.Supplier<String> {
                        default String get() { return "default"; }
                    }
                    interface Reactivated extends NotFunctional { String get(); }
                    class Use {
                        static void run(Config config) {
                            java.util.function.Function<Config,String> unbound = Config::getText;
                            Getter inherited = Config::getText;
                            Reactivated reactivated = config::getText;
                            java.util.function.Supplier<String> bound = config::getText;
                            java.util.function.Consumer<String> write = config::setText;
                            java.util.function.BiConsumer<Config,String> unboundWrite = Config::setText;
                            java.util.function.Function<String,Config> create = Config::new;
                            java.util.function.ToIntFunction<Config> invalid = Config::getText;
                            NotFunctional notFunctional = config::getText;
                        }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    new JavaBinaryRepository(
                            new JdkClassFileSource(
                                    Objects.requireNonNull(JavacBackendIT.home(release)), release),
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                                .analyzeFiles(
                                        List.of(source),
                                        analysis -> extractor.extract(analysis, facts::add))
                                .complete());
            }
            var result = facts.getFirst().facts();
            var references =
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.contains("methodref@"))
                            .toList();
            assertEquals(
                    7, references.size(), "JDK " + release + ": " + result.unresolvedCallSites);
            assertEquals(
                    4,
                    references.stream()
                            .filter(edge -> "p.Config#getText()".equals(edge.targetId))
                            .count());
            assertEquals(
                    2,
                    references.stream()
                            .filter(
                                    edge ->
                                            "p.Config#setText(java.lang.String)"
                                                    .equals(edge.targetId))
                            .count());
            assertEquals(
                    1,
                    references.stream()
                            .filter(
                                    edge ->
                                            "p.Config#Config(java.lang.String)"
                                                    .equals(edge.targetId))
                            .count());
            assertTrue(
                    references.stream()
                            .allMatch(
                                    edge ->
                                            edge.confidence.equals("INFERRED")
                                                    && edge.beginLine != null
                                                    && edge.beginLine > 0
                                                    && edge.beginColumn != null
                                                    && edge.beginColumn > 0));
            assertEquals(
                    2,
                    result.unresolvedCallSites.stream()
                            .filter(site -> site.callerId().contains("methodref@"))
                            .count());
        }
    }

    @Test
    void modeledAccessChecksUseInheritanceReceiverAndCaller(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("access" + release));
            Path parent =
                    Files.writeString(
                            project.resolve("Parent.java"),
                            """
                    package a;
                    public class Parent {
                        @lombok.Getter(lombok.AccessLevel.PROTECTED) private String text;
                        @lombok.Getter(lombok.AccessLevel.PRIVATE) private String secret;
                    }
                    """);
            Path child =
                    Files.writeString(
                            project.resolve("Child.java"),
                            """
                    package b;
                    public class Child extends a.Parent {
                        String permitted(Child value) { return value.getText(); }
                        String forbidden(a.Parent value) { return value.getText(); }
                        String privateParent(Child value) { return value.getSecret(); }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(
                                    project,
                                    release,
                                    List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                            .analyzeFiles(
                                    List.of(parent, child),
                                    analysis -> extractor.extract(analysis, facts::add))
                            .complete());
            var result =
                    facts.stream()
                            .filter(f -> f.source().equals(child))
                            .findFirst()
                            .orElseThrow()
                            .facts();
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("b.Child#permitted(b.Child)")
                                                    && "a.Parent#getText()".equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")));
            assertFalse(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && (edge.sourceId.equals(
                                                                    "b.Child#forbidden(a.Parent)")
                                                            || edge.sourceId.equals(
                                                                    "b.Child#privateParent(b.Child)"))));
            assertEquals(2, result.unresolvedCallSites.size());
        }
    }

    @Test
    void unverifiedValueConstantsKeepModuleOnSourceFallback(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("constants" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Values.java"),
                            """
                    @lombok.Value class Computed { int value = 1 + 2; }
                    @lombok.Value class Invalid { byte value = 1000; }
                    """);
            var backend =
                    backend(
                            project,
                            release,
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)));
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
            for (var owner : contexts.getFirst().units().getFirst().declarationInventory()) {
                assertFalse(owner.complete(), owner.canonicalName());
                assertTrue(
                        owner.members().stream()
                                .filter(member -> member.symbol().kind().equals("FIELD"))
                                .allMatch(
                                        member -> member.constant() == null && !member.complete()));
            }
            assertInstanceOf(
                    JavaSignatureTypes.Incomplete.class,
                    JavaSignatureTypes.project(contexts.getFirst().units(), release));
        }
    }

    @Test
    void builderSignaturesMatchActualProcessorOutputAcrossJdks(@TempDir Path directory)
            throws Exception {
        Path processor =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        assertTrue(Files.isRegularFile(processor));
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("abi" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PARAMETER})
                    @interface Constraint {}
                    @lombok.Builder(toBuilder=true, access=lombok.AccessLevel.PROTECTED, setterPrefix="with")
                    class Person { @Constraint String name; int age; }
                    class Constructed {
                        private final String name;
                        @lombok.Builder(builderClassName="Maker", builderMethodName="start", buildMethodName="finish")
                        Constructed(@Constraint String name) { this.name = name; }
                    }
                    @lombok.Value @lombok.experimental.NonFinal
                    class Immutable {
                        String name;
                        int fixed = 7;
                        public long exposed;
                        @lombok.experimental.NonFinal @lombok.experimental.PackagePrivate int mutable;
                        static int shared;
                    }
                    @lombok.Builder(toBuilder=true) @lombok.Getter
                    class Defaults {
                        @lombok.Builder.Default final int number = 7;
                        @lombok.Builder.Default String text = "hello";
                        boolean ready;
                    }
                    @lombok.ToString @lombok.EqualsAndHashCode class Standalone { int value; }
                    @lombok.Builder(toBuilder=true) class Generic<T extends Number & Comparable<T>> { T value; }
                    @lombok.Builder(toBuilder=true) class GenericDefaults<T> { @lombok.Builder.Default T value = null; }
                    class MethodFactory {
                        @lombok.Builder static String make(String name) { return name; }
                    }
                    class GenericFactory {
                        @lombok.Builder(builderClassName="Maker")
                        static <T extends Number> java.util.List<T> make(T value) { return java.util.Collections.singletonList(value); }
                    }
                    class InstanceFactory {
                        @lombok.Builder String make(String name) { return name; }
                    }
                    class GenericInstanceFactory {
                        @lombok.Builder(builderClassName="Maker")
                        <T extends Number> java.util.List<T> make(T value) { return java.util.Collections.singletonList(value); }
                    }
                    @lombok.Builder(toBuilder=true) class Custom {
                        String name; int age;
                        public static class CustomBuilder {
                            private String name;
                            public CustomBuilder name(String name) { this.name = name; return this; }
                            public String marker() { return "custom"; }
                        }
                    }
                    @lombok.Builder(toBuilder=true) class GenericCustom<T extends Number> {
                        T value; String name;
                        public static class GenericCustomBuilder<T extends Number> {
                            private T value;
                            public GenericCustomBuilder<T> value(T value) { this.value = value; return this; }
                            public String marker() { return "custom"; }
                        }
                    }
                    class GenericOwnerFactory<T extends Number> {
                        @lombok.Builder(builderClassName="Maker") T make(T value) { return value; }
                    }
                    @lombok.experimental.SuperBuilder abstract class SuperRoot<T extends Number & Comparable<T>> { T value; }
                    @lombok.experimental.SuperBuilder class SuperChild<T extends Number & Comparable<T>> extends SuperRoot<T> { String name; }
                    @lombok.experimental.SuperBuilder class SuperFixed extends SuperRoot<Integer> { int number; }
                    @lombok.experimental.SuperBuilder class SuperPlain { String name; }
                    @lombok.experimental.SuperBuilder class SuperCollision<C,B> { C first; B second; }
                    @lombok.experimental.SuperBuilder class SuperDefault<T> { @lombok.Builder.Default T value = null; @lombok.Builder.Default final int count = 2; }
                    /** @deprecated type */
                    class DocDeprecated {
                        /** @deprecated field */
                        @lombok.Getter @lombok.Setter private String value;
                        @lombok.Getter @lombok.Setter @DeprecatedVersion private String modern;
                        /** @deprecated method */
                        void plain() {}
                    }
                    """
                                    .replace(
                                            "@DeprecatedVersion",
                                            release == 8
                                                    ? "@Deprecated"
                                                    : "@Deprecated(since=\"2\",forRemoval=true)"));
            var backend =
                    backend(
                            project,
                            release,
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                            processor);
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                var reference =
                        assertInstanceOf(JavaModel.FoundType.class, model.type("p.DocDeprecated"))
                                .declaration();
                var declaration = model.declaration(reference);
                assertTrue(declaration.deprecated());
                assertTrue(declaration.annotations().isEmpty());
                for (String name : List.of("value", "plain")) {
                    var original =
                            model.members(reference).stream()
                                    .map(model::declaration)
                                    .filter(member -> member.symbol().name().equals(name))
                                    .findFirst()
                                    .orElseThrow();
                    assertTrue(original.deprecated());
                    assertFalse(
                            original.annotations().stream()
                                    .anyMatch(
                                            annotation ->
                                                    annotation
                                                            .descriptor()
                                                            .equals("Ljava/lang/Deprecated;")));
                }
                var facts = new com.anatomist.model.ExtractionResult();
                new com.anatomist.extract.JavaDeclarationFacts(model, project, ".", "MAIN")
                        .extract(source, facts);
                assertEquals(
                        true,
                        ((Map<?, ?>)
                                        Json.parseTree(
                                                facts.nodes.stream()
                                                        .filter(
                                                                node ->
                                                                        node.id.equals(
                                                                                "p.DocDeprecated"))
                                                        .findFirst()
                                                        .orElseThrow()
                                                        .metadata))
                                .get("deprecated"));
            }
            var projected = JavaSignatureTypes.project(contexts.getFirst().units(), release);
            Path output = Files.createDirectory(directory.resolve("compiled-abi" + release));
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(JavacBackendIT.home(release))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-cp",
                                            processor.toString(),
                                            "-processorpath",
                                            processor.toString(),
                                            "-d",
                                            output.toString(),
                                            source.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(
                    0,
                    compilation.exitCode(),
                    new String(compilation.requireValue(), StandardCharsets.UTF_8));
            var projection =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            projected,
                            projected
                                    + " actual Defaults="
                                    + methodHeaders(
                                            JavaBinaryReader.read(
                                                    Files.readAllBytes(
                                                            output.resolve("p/Defaults.class"))))
                                    + " builder="
                                    + JavaBinaryReader.read(
                                            Files.readAllBytes(
                                                    output.resolve(
                                                            "p/Defaults$DefaultsBuilder.class"))));
            assertEquals(45, projection.types().size());
            for (var modeled : projection.types()) {
                var actual =
                        JavaBinaryReader.read(
                                Files.readAllBytes(
                                        output.resolve(
                                                modeled.name().replace('.', '/') + ".class")));
                assertEquals(
                        methodHeaders(actual),
                        methodHeaders(modeled),
                        modeled.name() + " JDK " + release);
                assertEquals(
                        actual.signature(),
                        modeled.signature(),
                        modeled.name() + " signature JDK " + release);
                assertEquals(
                        actual.access(),
                        modeled.access(),
                        modeled.name() + " modifiers JDK " + release);
                assertEquals(
                        actual.annotations(),
                        modeled.annotations(),
                        modeled.name() + " type annotations");
                assertEquals(
                        actual.methods().stream()
                                .filter(
                                        method ->
                                                (method.access()
                                                                        & org.objectweb.asm.Opcodes
                                                                                .ACC_BRIDGE)
                                                                == 0
                                                        && (method.access()
                                                                        & org.objectweb.asm.Opcodes
                                                                                .ACC_SYNTHETIC)
                                                                == 0)
                                .map(
                                        method ->
                                                method.name()
                                                        + method.descriptor()
                                                        + method.annotations())
                                .sorted()
                                .toList(),
                        modeled.methods().stream()
                                .map(
                                        method ->
                                                method.name()
                                                        + method.descriptor()
                                                        + method.annotations())
                                .sorted()
                                .toList(),
                        modeled.name() + " method annotations JDK " + release);
                assertEquals(
                        actual.fields().stream()
                                // Outer-instance capture is compiler body machinery, not a source
                                // declaration.
                                .filter(
                                        field ->
                                                !(field.name().equals("this$0")
                                                        && (field.access()
                                                                        & org.objectweb.asm.Opcodes
                                                                                .ACC_SYNTHETIC)
                                                                != 0))
                                .map(
                                        field ->
                                                field.name()
                                                        + field.descriptor()
                                                        + field.signature()
                                                        + field.access()
                                                        + field.constant()
                                                        + field.annotations())
                                .sorted()
                                .toList(),
                        modeled.fields().stream()
                                .map(
                                        field ->
                                                field.name()
                                                        + field.descriptor()
                                                        + field.signature()
                                                        + field.access()
                                                        + field.constant()
                                                        + field.annotations())
                                .sorted()
                                .toList(),
                        modeled.name() + " fields JDK " + release);
            }
            assertFalse(Files.exists(project.resolve("target")));
        }
    }

    @Test
    void constructorLocalTypeParametersRemainUnsupportedLikeTheRealProcessor(
            @TempDir Path directory) throws Exception {
        Path processor =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("constructor" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Target.java"),
                            """
                    class Target {
                        @lombok.Builder <T extends Number> Target(T value) {}
                    }
                    """);
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(
                    backend(
                                    project,
                                    release,
                                    List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                    processor)
                            .analyzeFiles(List.of(source), contexts::add)
                            .complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                assertInstanceOf(JavaModel.MissingType.class, model.type("Target.TargetBuilder"));
            }
            assertInstanceOf(
                    JavaSignatureTypes.Incomplete.class,
                    JavaSignatureTypes.project(contexts.getFirst().units(), release));
            Path output = Files.createDirectory(directory.resolve("oracle" + release));
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(JavacBackendIT.home(release))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-cp",
                                            processor.toString(),
                                            "-processorpath",
                                            processor.toString(),
                                            "-d",
                                            output.toString(),
                                            source.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertNotEquals(0, compilation.exitCode());
            assertTrue(
                    compilation
                            .diagnostics()
                            .stderrTail()
                            .contains(
                                    "not supported on constructors with constructor type parameters"),
                    "JDK " + release + ": " + compilation.diagnostics().stderrTail());
            assertFalse(Files.exists(project.resolve("target")));
        }
    }

    @Test
    void modeledBuildersRecoverChainsAndRespectConstructorTargets(@TempDir Path directory)
            throws Exception {
        Path processor =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("builder" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Builder(toBuilder=true, setterPrefix="with")
                    class Person {
                        String name; int age;
                        Person duplicate() { return Person.builder().withName(this.name).withAge(this.age).build(); }
                    }
                    class Constructed {
                        String name;
                        @lombok.Builder(builderClassName="Maker", builderMethodName="start", buildMethodName="finish")
                        Constructed(String name) { this.name = name; }
                    }
                    @lombok.Builder class Generic<T> { T value; }
                    class Instance {
                        @lombok.Builder String make(String name) { return name; }
                    }
                    @lombok.Builder class Custom {
                        String name; int age;
                        public static class CustomBuilder {
                            private String name;
                            public CustomBuilder name(String name) { this.name = name; return this; }
                            public String marker() { return "custom"; }
                        }
                    }
                    @lombok.Builder class GenericCustom<T extends Number> {
                        T value; String name;
                        public static class GenericCustomBuilder<T extends Number> {
                            private T value;
                            public GenericCustomBuilder<T> value(T value) { this.value = value; return this; }
                        }
                    }
                    class GenericOwnerFactory<T extends Number> {
                        @lombok.Builder(builderClassName="Maker") T make(T value) { return value; }
                    }
                    class Use {
                        Person create() { return Person.builder().withName("a").withAge(1).build(); }
                        Person copy(Person person) { return person.toBuilder().build(); }
                        Constructed construct() { return Constructed.start().name("a").finish(); }
                        Person invalid() { return new Person(); }
                        Generic<String> generic() { return Generic.<String>builder().value("a").build(); }
                        String instance(Instance instance) { return instance.builder().name("a").build(); }
                        Custom custom() { return Custom.builder().name("a").age(1).build(); }
                        GenericCustom<Integer> genericCustom() { return GenericCustom.<Integer>builder().value(1).name("a").build(); }
                        Integer genericOwner(GenericOwnerFactory<Integer> factory) { return factory.builder().value(1).build(); }
                        SuperChild<Integer> inherited() { return SuperChild.<Integer>builder().value(1).name("a").build(); }
                        Integer inheritedRead() { return SuperChild.<Integer>builder().value(1).name("a").build().getValue(); }
                        SuperFixed fixedSuper() { return SuperFixed.builder().value(1).number(2).build(); }
                        SuperPlain plainSuper() { return SuperPlain.builder().name("a").build(); }
                        Integer defaultSuper() { return SuperDefaults.<Integer>builder().value(1).count(3).build().getValue(); }
                    }
                    @lombok.Getter @lombok.experimental.SuperBuilder abstract class SuperRoot<T extends Number & Comparable<T>> { T value; }
                    @lombok.Getter @lombok.experimental.SuperBuilder class SuperChild<T extends Number & Comparable<T>> extends SuperRoot<T> { String name; }
                    @lombok.experimental.SuperBuilder class SuperFixed extends SuperRoot<Integer> { int number; }
                    @lombok.experimental.SuperBuilder class SuperPlain { String name; }
                    @lombok.Getter @lombok.experimental.SuperBuilder class SuperDefaults<T> { @lombok.Builder.Default T value = null; @lombok.Builder.Default final int count = 2; }
                    """);
            var backend =
                    backend(
                            project,
                            release,
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                            processor);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            List.of(processor),
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend.analyzeFiles(
                                        List.of(file),
                                        analysis -> extractor.extract(analysis, facts::add))
                                .complete());
            }
            var result = facts.getFirst().facts();
            var ownBuilderCalls =
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals("p.Person#duplicate()"))
                            .toList();
            assertEquals(4, ownBuilderCalls.size(), "own builder calls JDK " + release);
            assertTrue(
                    ownBuilderCalls.stream()
                            .anyMatch(edge -> "p.Person#builder()".equals(edge.targetId)));
            assertTrue(
                    ownBuilderCalls.stream()
                            .anyMatch(
                                    edge ->
                                            "p.Person.PersonBuilder#build()"
                                                    .equals(edge.targetId)));
            for (var chain :
                    Map.of(
                                    "p.Use#instance(p.Instance)",
                                    3,
                                    "p.Use#custom()",
                                    4,
                                    "p.Use#genericCustom()",
                                    4,
                                    "p.Use#genericOwner(p.GenericOwnerFactory)",
                                    3,
                                    "p.Use#inherited()",
                                    4,
                                    "p.Use#inheritedRead()",
                                    5,
                                    "p.Use#fixedSuper()",
                                    4,
                                    "p.Use#plainSuper()",
                                    3)
                            .entrySet()) {
                assertEquals(
                        chain.getValue().longValue(),
                        result.edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.equals(chain.getKey()))
                                .count(),
                        chain.getKey() + " JDK " + release + ": " + result.unresolvedCallSites);
            }
            var actualFacts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            List.of(processor),
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                        List.of(processor),
                                        true)
                                .analyzeFiles(
                                        List.of(file),
                                        analysis -> extractor.extract(analysis, actualFacts::add))
                                .complete());
            }
            for (String method :
                    List.of(
                            "inherited",
                            "inheritedRead",
                            "fixedSuper",
                            "plainSuper",
                            "defaultSuper")) {
                String caller = "p.Use#" + method + "()";
                var modeled =
                        result.edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.equals(caller))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .sorted()
                                .toList();
                var compiled =
                        actualFacts.getFirst().facts().edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.equals(caller))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .sorted()
                                .toList();
                assertEquals(
                        compiled, modeled, "SuperBuilder targets JDK " + release + ": " + caller);
            }
            var customSetter =
                    result.nodes.stream()
                            .filter(
                                    node ->
                                            node.id.equals(
                                                    "p.Custom.CustomBuilder#name(java.lang.String)"))
                            .toList();
            assertEquals(1, customSetter.size());
            assertNotEquals("lombok-ast", customSetter.getFirst().producerId);
            assertEquals(
                    1,
                    result.nodes.stream()
                            .filter(node -> node.id.equals("p.Custom.CustomBuilder"))
                            .count());
            assertTrue(
                    result.nodes.stream()
                            .anyMatch(
                                    node ->
                                            node.id.equals("p.Person.PersonBuilder")
                                                    && node.producerId.equals("lombok-ast")));
            assertTrue(
                    result.nodes.stream()
                            .anyMatch(
                                    node ->
                                            node.id.equals(
                                                    "p.Person#Person(java.lang.String,int)")));
            assertFalse(
                    result.nodes.stream().anyMatch(node -> node.id.equals("p.Person#Person()")));
            assertTrue(
                    result.nodes.stream()
                            .anyMatch(node -> node.id.equals("p.Generic.GenericBuilder")));
            assertFalse(
                    result.nodes.stream().anyMatch(node -> node.id.equals("p.Generic#Generic()")));
            var calls =
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && (edge.sourceId.equals("p.Use#create()")
                                                            || edge.sourceId.equals(
                                                                    "p.Use#copy(p.Person)")
                                                            || edge.sourceId.equals(
                                                                    "p.Use#construct()")
                                                            || edge.sourceId.equals(
                                                                    "p.Use#generic()")))
                            .toList();
            assertEquals(
                    12,
                    calls.size(),
                    "JDK "
                            + release
                            + ": "
                            + result.unresolvedCallSites
                            + " calls="
                            + calls.stream().map(edge -> edge.targetId).toList()
                            + " nodes="
                            + result.nodes.stream()
                                    .filter(node -> node.id.startsWith("p.Person"))
                                    .map(node -> node.id)
                                    .toList());
            assertTrue(calls.stream().allMatch(edge -> edge.confidence.equals("INFERRED")));
            assertEquals(
                    1, result.unresolvedCallSites.size(), result.unresolvedCallSites.toString());
            assertEquals("new Person", result.unresolvedCallSites.getFirst().syntaxTarget());
            assertFalse(
                    facts.getFirst().diagnostics().stream()
                            .anyMatch(
                                    diagnostic ->
                                            diagnostic.code().equals("LOMBOK_FEATURE_UNSUPPORTED")
                                                    && diagnostic.sample().contains("generic")));
            assertFalse(Files.exists(project.resolve("target")));
        }
    }

    @Test
    void processorDeclarationsKeepCompileProvenanceWithoutModeledMembers(@TempDir Path directory)
            throws Exception {
        Path processor =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        assertTrue(Files.isRegularFile(processor));
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("compiled" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Data @lombok.Builder class Person { private String name; }
                    @lombok.Getter class OnlyGetter { private int value; }
                    class Use { String read() { return Person.builder().name("name").build().getName(); } }
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
            var defaults = config.javacOptions();
            var options =
                    new JavacOptions(
                            defaults.javaHome(),
                            true,
                            true,
                            true,
                            false,
                            directory.resolve("compiled-cache" + release));
            var backend =
                    new JavacBackend(
                            project,
                            JavacToolchain.resolve(options, release, null),
                            List.of(processor),
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                            options);
            backend.setDeclarationOverlay(
                    new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, false))));
            var extractor = new JavaModelExtractor(project, null);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            assertTrue(
                    backend.analyzeFiles(
                                    List.of(file),
                                    analysis -> {
                                        assertTrue(
                                                analysis.units().stream()
                                                        .allMatch(
                                                                unit ->
                                                                        unit
                                                                                .hasProcessorEvidence()));
                                        assertTrue(
                                                analysis.units().stream()
                                                        .flatMap(
                                                                unit ->
                                                                        unit
                                                                                .declarationInventory()
                                                                                .stream())
                                                        .noneMatch(
                                                                declaration ->
                                                                        declaration.origin()
                                                                                == JavaDeclaration
                                                                                        .Origin
                                                                                        .MODEL));
                                        extractor.extract(analysis, facts::add);
                                    })
                            .complete());
            var result = facts.getFirst().facts();
            assertTrue(result.unresolvedCallSites.isEmpty(), result.unresolvedCallSites.toString());
            assertTrue(
                    result.nodes.stream()
                            .anyMatch(
                                    node ->
                                            node.id.equals("p.Person#getName()")
                                                    && node.producerId.equals("lombok-ast")
                                                    && node.metadata.contains(
                                                            "\"generatorMode\":\"compile\"")));
            assertTrue(
                    result.nodes.stream()
                            .anyMatch(
                                    node ->
                                            node.id.equals("p.Person.PersonBuilder")
                                                    && node.producerId.equals("lombok-ast")));
            assertTrue(
                    result.nodes.stream()
                            .anyMatch(
                                    node ->
                                            node.id.equals("p.OnlyGetter#OnlyGetter()")
                                                    && node.producerId.equals("java-core")));
            assertEquals(
                    4,
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals("p.Use#read()")
                                                    && edge.confidence.equals("EXTRACTED")
                                                    && edge.metadata != null
                                                    && edge.metadata.contains(
                                                            "\"generatorMode\":\"compile\""))
                            .count());
            assertTrue(
                    facts.getFirst().diagnostics().stream()
                            .noneMatch(
                                    diagnostic ->
                                            diagnostic.code().equals("LOMBOK_COMPILE_FAILED")),
                    facts.getFirst().diagnostics().toString());
            assertFalse(Files.exists(project.resolve("target")));
        }
    }

    @Test
    void nestedConfigurationChangesDeclarationsAndMutationAbortsTheAnalysis(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Files.writeString(
                    project.resolve("lombok.config"),
                    "config.stopBubbling = true\nlombok.getter.noIsPrefix = true\n");
            Path nested = Files.createDirectory(project.resolve("nested"));
            Path nestedConfig =
                    Files.writeString(
                            nested.resolve("lombok.config"), "lombok.getter.noIsPrefix = false\n");
            Path rootSource =
                    Files.writeString(
                            project.resolve("Root.java"),
                            "@lombok.Getter class Root { boolean active; }");
            Path childSource =
                    Files.writeString(
                            nested.resolve("Child.java"),
                            "@lombok.Getter class Child { boolean active; }");
            var backend =
                    backend(
                            project,
                            release,
                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)));
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(
                    backend.analyzeFiles(List.of(rootSource, childSource), contexts::add)
                            .complete());
            var declarations =
                    contexts.getFirst().units().stream()
                            .flatMap(unit -> unit.declarationInventory().stream())
                            .toList();
            assertTrue(
                    declarations.stream()
                            .filter(type -> type.canonicalName().equals("Root"))
                            .flatMap(type -> type.members().stream())
                            .anyMatch(member -> member.symbol().name().equals("getActive")));
            assertTrue(
                    declarations.stream()
                            .filter(type -> type.canonicalName().equals("Child"))
                            .flatMap(type -> type.members().stream())
                            .anyMatch(member -> member.symbol().name().equals("isActive")));
            backend.setSourceSignatures(false);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            backend.analyzeFiles(
                                    List.of(childSource),
                                    context -> {
                                        try {
                                            Files.writeString(
                                                    nestedConfig,
                                                    "lombok.getter.noIsPrefix = true\n");
                                        } catch (java.io.IOException failure) {
                                            throw new java.io.UncheckedIOException(failure);
                                        }
                                    }));
            assertThrows(
                    IllegalStateException.class,
                    () -> backend.analyzeFiles(List.of(childSource), context -> fail()));
            assertTrue(
                    backend(
                                    project,
                                    release,
                                    List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                            .analyzeFiles(List.of(childSource), context -> {})
                            .complete());
        }
    }

    private static final Path LOMBOK =
            Path.of(
                    System.getProperty("user.home"),
                    ".m2/repository/org/projectlombok/lombok/1.18.6/lombok-1.18.6.jar");

    @Test
    void genericAccessorAndConstructorSignaturesMatchRealLombokAndReachDependencies(
            @TempDir Path directory) throws Exception {
        assertTrue(Files.isRegularFile(LOMBOK), "required baseline Lombok jar");
        String source =
                """
                package dep;
                @lombok.Getter @lombok.Setter @lombok.AllArgsConstructor
                public class Box<T extends Number> {
                    private T value;
                    private boolean isReady;
                    private final int fixed = 3;
                    private final String label;
                    @lombok.Getter private static Number global;
                    @lombok.Builder public static class Custom<T extends Number> {
                        T value;
                        public static class CustomBuilder<T extends Number> {
                            private T value;
                            public CustomBuilder<T> value(T value) { this.value = value; return this; }
                        }
                    }
                    public static class Factory<T extends Number> {
                        @lombok.Builder(builderClassName="Maker") T make(T value) { return value; }
                    }
                }
                """;
        JavaBinaryType modeled = null;
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Files.writeString(
                    project.resolve("lombok.config"),
                    "config.stopBubbling = true\nlombok.addGeneratedAnnotation = true\nlombok.addLombokGeneratedAnnotation = true");
            Path library = Files.createDirectories(project.resolve("lib/src/main/java"));
            Path usage = Files.createDirectories(project.resolve("app/src/main/java"));
            Path box = Files.writeString(library.resolve("Box.java"), source);
            Path use =
                    Files.writeString(
                            usage.resolve("Use.java"),
                            """
                    class Use {
                        Integer run(dep.Box<Integer> box) {
                            box.setValue(2); box.setReady(true); dep.Box.getGlobal();
                            box.isReady(); box.getFixed(); return box.getValue();
                        }
                        dep.Box<Integer> create() { return new dep.Box<Integer>(1, true, "label"); }
                        Integer factory(dep.Box.Factory<Integer> f) { return f.builder().value(1).build(); }
                        dep.Box.Custom<Integer> custom() { return dep.Box.Custom.<Integer>builder().value(1).build(); }
                    }
                    """);
            pom(project, "lib", "", release);
            pom(project, "app", "lib", release);
            var backend =
                    backend(
                            project,
                            release,
                            List.of(
                                    new SourceRoot(library, "lib", SourceScope.MAIN),
                                    new SourceRoot(usage, "app", SourceScope.MAIN)));
            var units = new ArrayList<JavaAnalysisContext>();
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            assertTrue(
                    backend.analyzeFiles(
                                    List.of(box, use),
                                    context -> {
                                        units.add(context);
                                        try (var binaries = backend.openBinaryRepository(context)) {
                                            new JavaModelExtractor(project, binaries)
                                                    .extract(context, facts::add);
                                        }
                                    })
                            .complete());
            assertEquals(
                    1,
                    backend.signatureStats().archivesUsed(),
                    "JDK "
                            + release
                            + ": "
                            + backend.signatureStats()
                            + " diagnostics="
                            + units.stream()
                                    .flatMap(context -> context.diagnostics().stream())
                                    .toList()
                            + " policies="
                            + units.stream()
                                    .flatMap(context -> context.units().stream())
                                    .map(unit -> unit.extensions().declarationMetadata())
                                    .toList());
            assertEquals(2, backend.signatureStats().sourceAppearances());
            var libraryUnit =
                    units.stream()
                            .flatMap(context -> context.units().stream())
                            .filter(unit -> unit.path().equals(box) && !unit.bindings().isEmpty())
                            .findFirst()
                            .orElseThrow();
            var projection =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            JavaSignatureTypes.project(List.of(libraryUnit), release));
            var type = projection.types().getFirst();
            assertTrue(
                    type.methods().stream()
                            .anyMatch(
                                    method ->
                                            method.name().equals("getValue")
                                                    && "()TT;".equals(method.signature())
                                                    && method.descriptor()
                                                            .equals("()Ljava/lang/Number;")));
            if (release == 8) modeled = type;
            var usageFacts =
                    facts.stream()
                            .filter(unit -> unit.source().equals(use))
                            .findFirst()
                            .orElseThrow()
                            .facts();
            assertTrue(
                    usageFacts.unresolvedCallSites.isEmpty(),
                    usageFacts.unresolvedCallSites.toString());
            assertEquals(
                    7,
                    usageFacts.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.targetId != null
                                                    && edge.targetId.startsWith("dep.Box#")
                                                    && edge.confidence.equals("INFERRED"))
                            .count());
            assertTrue(
                    facts.stream()
                            .filter(unit -> unit.source().equals(box))
                            .findFirst()
                            .orElseThrow()
                            .facts()
                            .nodes
                            .stream()
                            .filter(node -> node.producerId.equals("lombok-ast"))
                            .allMatch(
                                    node ->
                                            node.metadata.contains("\"bodyAvailable\":false")
                                                    && node.metadata.contains(
                                                            "\"bindingResolved\":false")));
            assertFalse(Files.exists(project.resolve("lib/target")));
            assertFalse(Files.exists(project.resolve("app/target")));
            var originalBackend =
                    backend(
                            project,
                            release,
                            List.of(
                                    new SourceRoot(library, "lib", SourceScope.MAIN),
                                    new SourceRoot(usage, "app", SourceScope.MAIN)));
            originalBackend.setSourceSignatures(false);
            var originalFacts = new ArrayList<JavaModelExtractor.UnitFacts>();
            originalBackend.analyzeFiles(
                    List.of(box, use),
                    context -> {
                        try (var binaries = originalBackend.openBinaryRepository(context)) {
                            new JavaModelExtractor(project, binaries)
                                    .extract(context, originalFacts::add);
                        }
                    });
            var sourceCalls =
                    originalFacts.stream()
                            .filter(unit -> unit.source().equals(use))
                            .findFirst()
                            .orElseThrow()
                            .facts();
            assertEquals(
                    callRows(usageFacts), callRows(sourceCalls), "source/archive JDK " + release);
        }
        Path actual = Files.createDirectories(directory.resolve("actual"));
        Path original = Files.writeString(directory.resolve("Box.java"), source);
        var result =
                ProcessRunner.run(
                        new ProcessRunner.Spec(
                                List.of(
                                        Objects.requireNonNull(JavacBackendIT.home(8))
                                                .resolve("bin/javac")
                                                .toString(),
                                        "-cp",
                                        LOMBOK.toString(),
                                        "-processorpath",
                                        LOMBOK.toString(),
                                        "-d",
                                        actual.toString(),
                                        original.toString()),
                                directory,
                                Duration.ofSeconds(30)),
                        null,
                        ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
        assertEquals(
                0, result.exitCode(), new String(result.requireValue(), StandardCharsets.UTF_8));
        var compiled = JavaBinaryReader.read(Files.readAllBytes(actual.resolve("dep/Box.class")));
        assertEquals(methodHeaders(compiled), methodHeaders(Objects.requireNonNull(modeled)));
    }

    @Test
    void dataExplicitConstructorsLoggerAndPartialPoliciesRemainVisibleWithoutBodies(
            @TempDir Path project) throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Models.java"),
                        """
                @lombok.Data class Note {
                    private int count;
                    Note(String ignored) {}
                }
                @lombok.Value class Immutable { String name; }
                @lombok.Getter class Disabled {
                    @lombok.Getter(lombok.AccessLevel.NONE) int hidden;
                    @lombok.Getter(lazy=true) private final Object expensive = new Object();
                    @lombok.experimental.Accessors(fluent=true) int special;
                }
                @lombok.extern.java.Log class Logged {}
                @lombok.Builder class Built { int value; }
                @interface Getter {}
                @Getter class Plain { int value; }
                """);
        var backend = backend(project, 25, List.of(new SourceRoot(project, ".", SourceScope.MAIN)));
        var contexts = new ArrayList<JavaAnalysisContext>();
        var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
        assertTrue(
                backend.analyzeFiles(
                                List.of(source),
                                context -> {
                                    contexts.add(context);
                                    new JavaModelExtractor(project, null)
                                            .extract(context, facts::add);
                                })
                        .complete());
        var result = facts.getFirst().facts();
        var ids =
                result.nodes.stream()
                        .map(node -> node.id)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(
                ids.containsAll(
                        Set.of(
                                "Note#getCount()",
                                "Note#setCount(int)",
                                "Note#canEqual(java.lang.Object)",
                                "Note#equals(java.lang.Object)",
                                "Note#hashCode()",
                                "Note#toString()",
                                "Immutable#Immutable(java.lang.String)",
                                "Immutable#getName()",
                                "Logged#log")),
                ids.toString());
        assertFalse(ids.contains("Note#Note()"));
        assertFalse(ids.contains("Immutable#Immutable()"));
        assertFalse(ids.contains("Immutable#canEqual(java.lang.Object)"));
        assertFalse(ids.contains("Disabled#getHidden()"));
        assertFalse(ids.contains("Disabled#getExpensive()"));
        assertFalse(ids.contains("Disabled#getSpecial()"));
        assertFalse(ids.contains("Plain#getValue()"));
        assertTrue(
                facts.getFirst().diagnostics().stream()
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.code().equals("LOMBOK_FEATURE_UNSUPPORTED")
                                                && diagnostic.severity().equals("error")));
        var built =
                result.nodes.stream()
                        .filter(node -> node.id.equals("Built"))
                        .findFirst()
                        .orElseThrow();
        var policy = ((Map<?, ?>) Json.parseTree(built.metadata)).get("lombok");
        assertEquals(List.of("builder"), ((Map<?, ?>) policy).get("modeled_capabilities"));
        assertEquals("complete", ((Map<?, ?>) policy).get("coverage"));
        assertInstanceOf(
                JavaSignatureTypes.Incomplete.class,
                JavaSignatureTypes.project(contexts.getFirst().units(), 25));
    }

    private static List<String> methodHeaders(JavaBinaryType type) {
        return type.methods().stream()
                .filter(method -> !method.name().equals("<clinit>"))
                // Bridges and Java 8 private-access helpers are body machinery. NoCode retains
                // source declarations, including the private constructors/providers they call.
                .filter(
                        method ->
                                (method.access()
                                                & (org.objectweb.asm.Opcodes.ACC_SYNTHETIC
                                                        | org.objectweb.asm.Opcodes.ACC_BRIDGE))
                                        == 0)
                .map(
                        method ->
                                method.name()
                                        + method.descriptor()
                                        + "|"
                                        + method.signature()
                                        + "|"
                                        + method.access())
                .sorted()
                .toList();
    }

    private static List<String> callRows(com.anatomist.model.ExtractionResult facts) {
        return facts.edges.stream()
                .filter(edge -> edge.relation.equals("CALLS"))
                .map(
                        edge ->
                                edge.sourceId
                                        + "|"
                                        + edge.targetId
                                        + "|"
                                        + edge.externalTargetFqn
                                        + "|"
                                        + edge.callKind
                                        + "|"
                                        + edge.confidence
                                        + "|"
                                        + edge.resolution
                                        + "|"
                                        + edge.metadata)
                .sorted()
                .toList();
    }

    @Test
    void modeledSourceCallsPreserveChainsAndRejectAmbiguousOrIllegalUses(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Data class Node {
                        private int value;
                        private Node next;
                        static int illegal() { return getValue(); }
                    }
                    class Secret { @lombok.Getter(lombok.AccessLevel.PRIVATE) private int hidden; }
                    @lombok.AllArgsConstructor class Choice {
                        private String value;
                        Choice(Integer value) {}
                    }
                    @lombok.AllArgsConstructor class VariableChoice {
                        private String value;
                        VariableChoice(Integer... values) {}
                    }
                    class Run {
                        void run(Node node, Secret secret) {
                            node.getNext().getValue();
                            node.setValue(1);
                            node.setValue(1L);
                            node.toString();
                            new Node();
                            secret.getHidden();
                            new Choice(null);
                            new VariableChoice(null);
                        }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var extractor = new JavaModelExtractor(project, null);
            assertTrue(
                    backend(
                                    project,
                                    release,
                                    List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                            .analyzeFiles(
                                    List.of(file),
                                    context -> extractor.extract(context, facts::add))
                            .complete());
            var result = facts.getFirst().facts();
            assertEquals(
                    Set.of(
                            "p.Node#getNext()",
                            "p.Node#getValue()",
                            "p.Node#setValue(int)",
                            "p.Node#toString()",
                            "p.Node#Node()"),
                    result.edges.stream()
                            .filter(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals(
                                                            "p.Run#run(p.Node,p.Secret)"))
                            .map(edge -> edge.targetId)
                            .collect(java.util.stream.Collectors.toSet()),
                    "JDK " + release);
            assertEquals(
                    5,
                    result.unresolvedCallSites.size(),
                    "JDK " + release + ": " + result.unresolvedCallSites);
            assertTrue(
                    result.unresolvedCallSites.stream()
                            .anyMatch(
                                    site ->
                                            site.syntaxTarget().equals("new Choice")
                                                    && site.metadata()
                                                            .contains(
                                                                    "ambiguous-or-incomplete-overload")));
            assertTrue(
                    result.edges.stream()
                            .noneMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals("p.Node#illegal()")));
        }
    }

    @Test
    void sourceAndBinaryOverloadsCompeteAndSourceOverridesRetainTheirOwner(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("mixed" + release));
            Path helpers = Files.createDirectory(directory.resolve("mixed-helpers" + release));
            Path classes = Files.createDirectory(directory.resolve("mixed-classes" + release));
            Path parent =
                    Files.writeString(
                            helpers.resolve("Parent.java"),
                            """
                    package lib;
                    public class Parent<T extends Number> {
                        public static class Bounded<T extends Number> { public Bounded(T value) {} }
                        public <T extends CharSequence> T same(T value) { return value; }
                        public static <T extends CharSequence> T pair(T first, T second) { return first; }
                        public static <E> java.util.ArrayList<E> list(E... values) { return new java.util.ArrayList<E>(java.util.Arrays.asList(values)); }
                        public static <E> java.util.ArrayList<E> list(Iterable<E> values) { return new java.util.ArrayList<E>(); }
                        public static <E> java.util.ArrayList<E> list(java.util.Iterator<E> values) { return new java.util.ArrayList<E>(); }
                        public static boolean equals(String first, String second) { return first.equals(second); }
                        public static boolean equals(Object first, Object second) { return first.equals(second); }
                        public static String clean(String value) { return value; }
                        public static java.util.stream.Stream<Object> raw() { return java.util.stream.Stream.empty(); }
                        public static class Json extends java.util.HashMap<String,Object> {
                            public Object put(String key, Object value) { return super.put(key, value); }
                        }
                        public String pick(String value) { return value; }
                        public String getText() { return "parent"; }
                        public final String getFinalText() { return "final"; }
                    }
                    """);
            var compiled =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(JavacBackendIT.home(release))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-proc:none",
                                            "-d",
                                            classes.toString(),
                                            parent.toString()),
                                    helpers,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(0, compiled.exitCode(), compiled.diagnostics().stderrTail());
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    import static p.SourceFactory.stat;
                    import static p.SourceFactory.cross;
                    import static p.MoreFactory.cross;
                    import static p.SingleImported.chosen;
                    import static p.WildcardImported.*;
                    @lombok.Getter class Child extends lib.Parent<Integer> {
                        String text;
                        Object pick(Object value) { return value; }
                    }
                    class SourceParent extends lib.Parent<Integer> {
                        public void setValue(String value) {}
                    }
                    @lombok.Getter @lombok.Setter class GrandChild extends SourceParent { Object value; }
                    @lombok.Getter class CovariantParent { Object item; }
                    class CovariantChild extends CovariantParent {
                        public String getItem() { return "child"; }
                    }
                    class SourceFactory<T extends Number> {
                        <T extends CharSequence> T same(T value) { return value; }
                        static <R> R map(Config value, java.util.function.Function<Config,R> fn) {
                            return fn.apply(value);
                        }
                        static <R> R first(R... values) { return values[0]; }
                        static String stat(String value) { return value; }
                        static <R> R fixed(lib.Parent<Integer> first, R value) { return value; }
                        static <R extends Number> java.util.List<R> numeric(R value) { return java.util.Collections.singletonList(value); }
                        static <R> R loose(int value) { return null; }
                        static <R> Object opaque(R value) { return value; }
                        static <K,V> java.util.Map<K,V> supplied(java.util.function.Supplier<java.util.Map<K,V>> factory, String marker) { return factory.get(); }
                        static <R> R compare(String label, java.util.function.Supplier<R> fresh, java.util.function.Supplier<R> old, java.util.function.BiPredicate<R,R> matcher) { return fresh.get(); }
                        static void log(String... message) {}
                        static void run(Object marker, Runnable task) {}
                        static <R> R run(Object marker, java.util.concurrent.Callable<R> task) { return null; }
                        static <R> R suppliedBlock(String marker, java.util.function.Supplier<R> task) { return task.get(); }
                        static String typed(java.util.List<String> values, java.util.Map<String,String> headers) { return values.get(0); }
                        static String expectClass(Class<? extends String> value) { return "text"; }
                        static Object cross(Object value) { return value; }
                    }
                    class MoreFactory { static String cross(String value) { return value; } }
                    class SingleImported { static String chosen(Object value) { return "single"; } }
                    class WildcardImported { static String chosen(String value) { return "wildcard"; } }
                    @lombok.Getter @lombok.AllArgsConstructor class ConstructNumber<T extends Number> { T value; }
                    @lombok.Getter @lombok.AllArgsConstructor class ConstructText { String value; }
                    interface ComponentKind { String name(); }
                    interface ComponentProperties<T extends ComponentKind> { T getComponent(); }
                    interface RawFactory<T extends Number, P extends java.util.List<T>> {
                        void accept(P values);
                    }
                    class RawParent {
                        public final void acceptParent(lib.Parent value) {}
                    }
                    @lombok.Getter @lombok.Setter class Config {
                        Object value; int number; Child child; String text; GrandChild grand; CovariantChild covariant;
                        SourceFactory<Integer> factory; lib.Parent.Json json;
                        java.util.List<String> texts; java.lang.reflect.Method method;
                        java.util.List<java.util.List<String>> groups;
                        Class<? extends Number> numberClass;
                        Long timeout; Boolean enabled;
                        lib.Parent rawParent; RawFactory rawFactory;
                        java.util.Map<String,String> headers;
                    }
                    class Use extends RawParent {
                        void rawReceiver(Config config, java.util.List<Integer> values) { config.getRawFactory().accept(values); }
                        void rawInherited(Config config) { acceptParent(config.getRawParent()); }
                        String rawCast(Config config) { return SourceFactory.typed((java.util.List) config.getTexts(), (java.util.Map) config.getHeaders()); }
                        String singleImport(Config config) { return chosen(config.getText()); }
                        void lexicalInherited(Config config) {
                            Runnable task = new Runnable() { public void run() { acceptParent(config.getRawParent()); } };
                        }
                        Object inherited(Config config) { return config.getChild().pick(config.getText()); }
                        Object implicitNull(Config config) { return config.getChild().pick(null); }
                        String own(Child child) { return child.getText(); }
                        void narrower(Config config) { config.getGrand().setValue(config.getText()); }
                        String covariant(Config config) { return config.getCovariant().getItem(); }
                        String binaryGeneric(Config config) { return config.getChild().same(config.getText()).trim(); }
                        String sourceGeneric(Config config) { return config.getFactory().same(config.getText()).trim(); }
                        Object widenedReturn(Config config) { return config.getFactory().same(config.getText()); }
                        String sourceExplicit(Config config) { return config.getFactory().<String>same(config.getText()).trim(); }
                        String binaryExplicit(Config config) { return config.getChild().<String>same(config.getText()).trim(); }
                        String sourceReference(Config config) { return SourceFactory.map(config, Config::getText).trim(); }
                        String sourceBlock(Config config) { return SourceFactory.map(config, v -> { return v.getText(); }).trim(); }
                        String sourceVarargs(Config config) { return SourceFactory.first(config.getText(), config.getText()).trim(); }
                        String staticImported(Config config) { return stat(config.getText()).trim(); }
                        Object bridged(Config config) { return config.getJson().put(config.getText(), config.getText()); }
                        Object multipleImports(Config config) { return cross(config.getValue()); }
                        String fixed(Config config) { return SourceFactory.fixed(config.getChild(), config.getText()).trim(); }
                        String polyInvocation(Config config) { return lib.Parent.pair(config.getText(), SourceFactory.loose(1)).trim(); }
                        String polyAssignment(Config config) { String value = SourceFactory.loose(config.getNumber()); return value.trim(); }
                        String cast(Config config) { return (String) SourceFactory.opaque(config.getText()); }
                        String castGeneric(Config config) { return (String) SourceFactory.first(config.getValue()); }
                        void collectionOverloads(Config config) { config.setTexts(lib.Parent.list(config.getText())); }
                        Class<?> declaringClass(Config config) { return config.getMethod().getDeclaringClass(); }
                        Class<?> wildcardWidened(Config config) { return SourceFactory.fixed(config.getChild(), config.getNumberClass()); }
                        java.util.List<Config> collect(java.util.List<Config> values) {
                            return values.stream().filter(value -> value.getText() != null).collect(java.util.stream.Collectors.toList());
                        }
                        void remove(java.util.List<Config> values) { values.removeIf(value -> value.getNumber() < 1); }
                        void block(java.util.Map<String,Config> values) { values.forEach((key,value) -> { value.getText().trim(); }); }
                        void grouping(java.util.List<Config> values) {
                            values.stream().collect(java.util.stream.Collectors.groupingBy(Config::getText))
                                .forEach((key,items) -> items.get(0).getNumber());
                        }
                        java.util.List<Config> flatten(java.util.List<java.util.List<Config>> values) {
                            return values.stream().flatMap(java.util.Collection::stream).filter(value -> value.getText() != null).collect(java.util.stream.Collectors.toList());
                        }
                        void flattenSetter(Config config) {
                            config.setTexts(config.getGroups().stream().flatMap(java.util.Collection::stream).collect(java.util.stream.Collectors.toList()));
                        }
                        void concurrentMap(java.util.List<Config> values) {
                            Runnable task = () -> {
                                java.util.Map<String,Config> mapped = values.stream().collect(java.util.stream.Collectors.toConcurrentMap(Config::getText, c -> c, (a,b) -> a));
                            };
                        }
                        void boundedOptional(Config config, java.util.List<Object> values) {
                            values.forEach(item -> {
                                java.util.Optional.ofNullable(item)
                                    .filter(v -> v instanceof ComponentProperties)
                                    .map(v -> ((ComponentProperties<?>) v).getComponent())
                                    .ifPresent(v -> config.setText(v.getClass().getSimpleName() + "." + v.name()));
                            });
                        }
                        String wrappedValueOf(Config config) {
                            return String.valueOf(config.getTimeout()) + String.valueOf(config.getEnabled());
                        }
                        String conditionalAppend(Config config, boolean choose, String text) {
                            return new StringBuilder().append(config.getText()).append(choose ? text : "").toString();
                        }
                        void compoundPredicate(Config config) {
                            java.util.Optional.ofNullable(config.getChild())
                                .filter(v -> (v.same(config.getText()) != null && !config.getEnabled()))
                                .filter(v -> !v.same(config.getText()).isEmpty())
                                .ifPresent(v -> config.setText(v.same(config.getText())));
                        }
                        void expressionRunnable(Config config, java.util.List<Config> values) {
                            SourceFactory.run(config.getText(), () -> values.forEach(v -> { v.setText(config.getText()); }));
                        }
                        ConstructText constructorLambda(Config config) {
                            return SourceFactory.suppliedBlock(config.getText(), () -> new ConstructText(config.getText()));
                        }
                        void runnable(Config config) { SourceFactory.run(config.getText(), () -> { config.setText("changed"); }); }
                        Config tryFinally(Config config) {
                            return SourceFactory.suppliedBlock(config.getText(), () -> {
                                try { config.setText("changed"); return config; }
                                finally { config.getNumber(); }
                            });
                        }
                        Config tryCatch(Config config) {
                            return SourceFactory.suppliedBlock(config.getText(), () -> {
                                try { config.setText("changed"); }
                                catch (RuntimeException failed) { return null; }
                                return config;
                            });
                        }
                        void stringConcat(Config config) { SourceFactory.log("id:" + lib.Parent.clean(config.getText()) + ",number:" + config.getNumber()); }
                        String suppliers(Config config) { return SourceFactory.compare("compare", () -> config.getText(), () -> "old", lib.Parent::equals); }
                        java.util.List<Config> rawMap(Config config) {
                            java.util.List<Config> values = lib.Parent.raw().map(value -> {
                                Config item = new Config(); item.setText(config.getText()); return item;
                            }).collect(java.util.stream.Collectors.toList());
                            return values;
                        }
                        java.util.Map<String,Object> mapValues(java.util.List<Config> values) {
                            return values.stream().collect(java.util.stream.Collectors.toMap(Config::getText, c -> c.getTexts()));
                        }
                        Object objectDiamond(Config config) {
                            return java.util.Optional.ofNullable(config.getValue()).orElse(new java.util.HashMap<>());
                        }
                        java.util.Map<String,Config> constructorReference(Config config) { return SourceFactory.supplied(java.util.HashMap::new, config.getText()); }
                        void diamond(Config config) { config.setTexts(new java.util.ArrayList<>()); }
                        int numberConstruction(Config config) { ConstructNumber<Integer> value = new ConstructNumber<>(config.getNumber()); return value.getValue().intValue(); }
                        int widenedTarget(Config config) {
                            java.util.List<Number> values = SourceFactory.numeric(config.getNumber());
                            return values.get(0).intValue();
                        }
                        java.util.Comparator<java.util.Map.Entry<String,Config>> contextual(Config config) {
                            java.util.Comparator<java.util.Map.Entry<String,Config>> order =
                                java.util.Comparator.comparing(entry -> entry.getValue().getText());
                            return order;
                        }
                        java.util.Comparator<java.util.Map.Entry<String,Config>> contextualReturn(Config config) {
                            return java.util.Comparator.comparing(entry -> entry.getValue().getText());
                        }
                    }
                    """);
            var classpath = List.of(LOMBOK, classes);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            classpath,
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                        classpath)
                                .analyzeFiles(
                                        List.of(source),
                                        analysis -> extractor.extract(analysis, facts::add))
                                .complete());
            }
            var result = facts.getFirst().facts();
            for (String method : List.of("inherited", "implicitNull")) {
                assertTrue(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(
                                                                "p.Use#" + method + "(p.Config)")
                                                        && "lib.Parent#pick(java.lang.String)"
                                                                .equals(edge.externalTargetFqn)),
                        "JDK " + release + ": " + method + ": " + result.unresolvedCallSites);
                assertFalse(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.sourceId.equals(
                                                                "p.Use#" + method + "(p.Config)")
                                                        && "p.Child#pick(java.lang.Object)"
                                                                .equals(edge.targetId)));
            }
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("p.Use#own(p.Child)")
                                                    && "p.Child#getText()".equals(edge.targetId)
                                                    && edge.confidence.equals("INFERRED")));
            Path processor =
                    release == 8
                            ? LOMBOK
                            : Path.of(
                                    System.getProperty("user.home"),
                                    ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
            var actualFacts = new ArrayList<JavaModelExtractor.UnitFacts>();
            var actualClasspath = List.of(processor, classes);
            try (var binaries =
                    JavaBinarySources.open(
                            actualClasspath,
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                        actualClasspath,
                                        true)
                                .analyzeFiles(
                                        List.of(source),
                                        analysis -> {
                                            assertTrue(
                                                    analysis.units()
                                                            .getFirst()
                                                            .hasProcessorEvidence());
                                            extractor.extract(analysis, actualFacts::add);
                                        })
                                .complete());
            }
            for (String method :
                    List.of(
                            "inherited",
                            "implicitNull",
                            "own",
                            "narrower",
                            "covariant",
                            "binaryGeneric",
                            "sourceGeneric",
                            "widenedReturn",
                            "sourceExplicit",
                            "binaryExplicit",
                            "sourceReference",
                            "sourceBlock",
                            "sourceVarargs",
                            "staticImported",
                            "bridged",
                            "multipleImports",
                            "contextual",
                            "contextualReturn",
                            "fixed",
                            "widenedTarget",
                            "cast",
                            "castGeneric",
                            "collectionOverloads",
                            "declaringClass",
                            "wildcardWidened",
                            "collect",
                            "remove",
                            "block",
                            "grouping",
                            "flatten",
                            "flattenSetter",
                            "concurrentMap",
                            "boundedOptional",
                            "wrappedValueOf",
                            "conditionalAppend",
                            "compoundPredicate",
                            "expressionRunnable",
                            "rawReceiver",
                            "rawInherited",
                            "rawCast",
                            "singleImport",
                            "lexicalInherited",
                            "constructorLambda",
                            "runnable",
                            "tryFinally",
                            "tryCatch",
                            "stringConcat",
                            "suppliers",
                            "rawMap",
                            "mapValues",
                            "objectDiamond",
                            "constructorReference",
                            "diamond",
                            "numberConstruction",
                            "polyInvocation",
                            "polyAssignment")) {
                var modeledTargets =
                        result.edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Use#" + method + "("))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .sorted()
                                .toList();
                var actualTargets =
                        actualFacts.getFirst().facts().edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Use#" + method + "("))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .sorted()
                                .toList();
                assertEquals(
                        actualTargets,
                        modeledTargets,
                        "JDK "
                                + release
                                + ": "
                                + method
                                + ": "
                                + result.unresolvedCallSites.stream()
                                        .filter(
                                                site ->
                                                        site.callerId()
                                                                .startsWith(
                                                                        "p.Use#" + method + "("))
                                        .toList());
                var modeledKinds =
                        result.edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Use#" + method + "("))
                                .map(
                                        edge ->
                                                (edge.targetId == null
                                                                ? edge.externalTargetFqn
                                                                : edge.targetId)
                                                        + ":"
                                                        + edge.callKind)
                                .sorted()
                                .toList();
                var actualKinds =
                        actualFacts.getFirst().facts().edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Use#" + method + "("))
                                .map(
                                        edge ->
                                                (edge.targetId == null
                                                                ? edge.externalTargetFqn
                                                                : edge.targetId)
                                                        + ":"
                                                        + edge.callKind)
                                .sorted()
                                .toList();
                assertEquals(actualKinds, modeledKinds, "dispatch JDK " + release + ": " + method);
            }
            Path invalid =
                    Files.writeString(
                            project.resolve("Invalid.java"),
                            """
                    package p;
                    import static p.Imported.fail;
                    import static p.WildcardFailure.*;
                    class Imported { static void fail(Number value) {} }
                    class WildcardFailure { static String fail(String value) { return value; } }
                    @lombok.Getter class FinalChild extends lib.Parent { String finalText; }
                    class SourceFinalParent { public final String getFinalText() { return "parent"; } }
                    @lombok.Getter class SourceFinalChild extends SourceFinalParent { String finalText; }
                    @lombok.Getter(lombok.AccessLevel.PRIVATE) class HiddenChild extends lib.Parent {
                        String text;
                        String own() { return getText(); }
                    }
                    @lombok.Getter class BoundsConfig { String text; int number; Class<? extends Number> numberClass; }
                    class BoundsFactory<T extends Number> {
                        static <K,V> java.util.Map<K,V> supplied(java.util.function.Supplier<java.util.Map<K,V>> factory, String marker) { return factory.get(); }
                        static <T> void both(java.util.List<T> first, java.util.List<T> second, String marker) {}
                        static <T> void slots(java.util.Map<T,T> values, String marker) {}
                        <T extends Number> T only(T value) { return value; }
                        <T extends CharSequence> T text(T value) { return value; }
                        static <T extends Number> T loose(int value) { return null; }
                        static String expectClass(Class<? extends String> value) { return "text"; }
                    }
                    @lombok.AllArgsConstructor class NumericBox<T extends Number> { T value; }
                    class InvalidRawParent { public void outer(Object value) {} }
                    class Invalid extends InvalidRawParent {
                        static void lexicalStatic(BoundsConfig c) {
                            Runnable task = new Runnable() { public void run() { outer(c.getText()); } };
                        }
                        void lexicalShadow(BoundsConfig c) {
                            Runnable task = new Runnable() {
                                public void outer(Number value) {}
                                public void run() { outer(c.getText()); }
                            };
                        }
                        Object sourceClassBound(BoundsConfig c) { return new NumericBox<String>(c.getText()); }
                        Object binaryClassBound(BoundsConfig c) { return new lib.Parent.Bounded<String>(c.getText()); }
                        java.util.Map<String,BoundsConfig> wrongConstructor(BoundsConfig c) { return BoundsFactory.supplied(java.util.HashMap<Integer,String>::new, c.getText()); }
                        void separateCaptures(BoundsConfig c, java.util.List<?> first, java.util.List<?> second) { BoundsFactory.both(first, second, c.getText()); }
                        void separateSlots(BoundsConfig c, java.util.Map<?,?> values) { BoundsFactory.slots(values, c.getText()); }
                        void illegalImport(BoundsConfig config) { fail(config.getText()); }
                        Object sourceBound(BoundsConfig c, BoundsFactory<Integer> f) { return f.only(c.getText()); }
                        Object binaryBound(BoundsConfig c, lib.Parent<Integer> f) { return f.same(c.getNumber()); }
                        Object sourceExplicitBound(BoundsConfig c, BoundsFactory<Integer> f) { return f.<Number>text(c.getText()); }
                        Object binaryExplicitBound(BoundsConfig c, lib.Parent<Integer> f) { return f.<Number>same(c.getText()); }
                        Object polyBound(BoundsConfig c) { return lib.Parent.pair(c.getText(), BoundsFactory.loose(1)); }
                        String polyAssignmentBound(BoundsConfig c) { String value = BoundsFactory.loose(c.getNumber()); return value; }
                        String wildcardBound(BoundsConfig c) { return BoundsFactory.expectClass(c.getNumberClass()); }
                        void numericComparisonBound(java.util.List<BoundsConfig> values) { values.removeIf(value -> value.getText() < 1); }
                        void logicalNegationBound(java.util.List<BoundsConfig> values) { values.removeIf(value -> !value.getText()); }
                        void logicalConjunctionBound(java.util.List<BoundsConfig> values) { values.removeIf(value -> true && value.getText()); }
                        void conditionalBooleanBound(java.util.List<BoundsConfig> values) { values.removeIf(value -> value.getText() ? true : false); }
                        void blockConditionalBooleanBound(java.util.List<BoundsConfig> values) { values.removeIf(value -> { return value.getText() ? true : false; }); }
                        java.util.Map<String,Object> fixedCollector(java.util.List<BoundsConfig> values, java.util.stream.Collector<BoundsConfig,?,java.util.Map<String,java.util.List<String>>> collector) {
                            return values.stream().filter(value -> value.getText() != null).collect(collector);
                        }
                        java.util.List<Number> collectorBound(java.util.List<BoundsConfig> values) {
                            return values.stream().map(value -> value.getText()).collect(java.util.stream.Collectors.toList());
                        }
                        String finalGetter(FinalChild value) { return value.getFinalText(); }
                        String sourceFinalGetter(SourceFinalChild value) { return value.getFinalText(); }
                        String hiddenGetter(HiddenChild value) { return value.getText(); }
                    }
                    """);
            var invalidFacts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            classpath,
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                        classpath)
                                .analyzeFiles(
                                        List.of(invalid),
                                        analysis -> extractor.extract(analysis, invalidFacts::add))
                                .complete());
            }
            for (String owner :
                    List.of(
                            "p.HiddenChild#own()",
                            "p.Invalid#finalGetter(p.FinalChild)",
                            "p.Invalid#sourceFinalGetter(p.SourceFinalChild)",
                            "p.Invalid#hiddenGetter(p.HiddenChild)"))
                assertFalse(
                        invalidFacts.getFirst().facts().edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.equals(owner)),
                        "JDK " + release + ": " + owner);
            for (String method :
                    List.of(
                            "sourceBound",
                            "binaryBound",
                            "sourceExplicitBound",
                            "binaryExplicitBound",
                            "polyBound",
                            "polyAssignmentBound",
                            "wildcardBound",
                            "separateCaptures",
                            "separateSlots",
                            "wrongConstructor",
                            "sourceClassBound",
                            "binaryClassBound")) {
                var calls =
                        invalidFacts.getFirst().facts().edges.stream()
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Invalid#" + method + "("))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .toList();
                assertTrue(
                        calls.stream()
                                .noneMatch(
                                        target ->
                                                target.contains("#only(")
                                                        || target.contains("#same(")
                                                        || target.contains("#text(")
                                                        || target.contains("#pair(")
                                                        || target.contains("#expectClass(")
                                                        || target.contains("#both(")
                                                        || target.contains("#slots(")
                                                        || target.contains("#supplied(")
                                                        || target.contains("NumericBox#")
                                                        || target.contains("Bounded#")),
                        "JDK " + release + ": " + method + ": " + calls);
            }
            assertFalse(
                    invalidFacts.getFirst().facts().edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && "p.Imported#fail(java.lang.Number)"
                                                            .equals(edge.targetId)));
            assertFalse(
                    invalidFacts.getFirst().facts().edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && "p.WildcardFailure#fail(java.lang.String)"
                                                            .equals(edge.targetId)),
                    "JDK " + release + ": explicit import must shadow on-demand overloads");
            assertFalse(
                    invalidFacts.getFirst().facts().edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.startsWith(
                                                            "p.Invalid#numericComparisonBound(")
                                                    && "java.util.Collection#removeIf(java.util.function.Predicate)"
                                                            .equals(edge.externalTargetFqn)));
            for (String method :
                    List.of(
                            "logicalNegationBound",
                            "logicalConjunctionBound",
                            "conditionalBooleanBound",
                            "blockConditionalBooleanBound"))
                assertFalse(
                        invalidFacts.getFirst().facts().edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Invalid#" + method + "(")
                                                        && "java.util.Collection#removeIf(java.util.function.Predicate)"
                                                                .equals(edge.externalTargetFqn)),
                        "JDK " + release + ": " + method);
            for (String method : List.of("lexicalStatic", "lexicalShadow"))
                assertFalse(
                        invalidFacts.getFirst().facts().edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(
                                                                "p.Invalid#" + method + "(")
                                                        && "p.InvalidRawParent#outer(java.lang.Object)"
                                                                .equals(edge.targetId)),
                        "JDK " + release + ": " + method);
            assertFalse(
                    invalidFacts.getFirst().facts().edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && (edge.sourceId.startsWith(
                                                                    "p.Invalid#collectorBound(")
                                                            || edge.sourceId.startsWith(
                                                                    "p.Invalid#fixedCollector("))
                                                    && "java.util.stream.Stream#collect(java.util.stream.Collector)"
                                                            .equals(edge.externalTargetFqn)));
            var invalidCompilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(JavacBackendIT.home(release))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-cp",
                                            processor + java.io.File.pathSeparator + classes,
                                            "-processorpath",
                                            processor.toString(),
                                            "-d",
                                            helpers.toString(),
                                            invalid.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertNotEquals(0, invalidCompilation.exitCode());
            assertTrue(
                    invalidCompilation
                            .diagnostics()
                            .stderrTail()
                            .contains("fail(config.getText())"));
            assertTrue(
                    invalidCompilation.diagnostics().stderrTail().contains("BoundsFactory.loose"));
            assertTrue(invalidCompilation.diagnostics().stderrTail().contains("getFinalText"));
            assertTrue(invalidCompilation.diagnostics().stderrTail().contains("getText"));
            assertTrue(invalidCompilation.diagnostics().stderrTail().contains("!value.getText()"));
            assertTrue(
                    invalidCompilation
                            .diagnostics()
                            .stderrTail()
                            .contains("true && value.getText()"));
            assertTrue(
                    invalidCompilation
                            .diagnostics()
                            .stderrTail()
                            .contains("value.getText() ? true : false"));
            assertTrue(
                    invalidCompilation.diagnostics().stderrTail().contains("outer(c.getText())"));
        }
    }

    @Test
    void binaryHierarchyReentersCurrentSourceAndHonorsLostPackageInheritance(
            @TempDir Path directory) throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("reentry" + release));
            Path helpers = Files.createDirectory(directory.resolve("reentry-helpers" + release));
            Path classes = Files.createDirectory(directory.resolve("reentry-classes" + release));
            Path processor =
                    release == 8
                            ? LOMBOK
                            : Path.of(
                                    System.getProperty("user.home"),
                                    ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
            Path base =
                    Files.writeString(
                            project.resolve("Base.java"),
                            """
                    package p;
                    @lombok.Getter public class Base<T> {
                        T text;
                        @lombok.Getter(lombok.AccessLevel.PACKAGE) String packageText;
                        @lombok.Getter(lombok.AccessLevel.PRIVATE) String secret;
                    }
                    """);
            Path child =
                    Files.writeString(
                            helpers.resolve("Child.java"),
                            """
                    package lib;
                    public class Child extends p.Base<String> {}
                    """);
            Path override =
                    Files.writeString(
                            helpers.resolve("OverrideChild.java"),
                            """
                    package lib;
                    public class OverrideChild extends p.Base<String> {
                        @Override public String getText() { return "child"; }
                    }
                    """);
            var command =
                    List.of(
                            Objects.requireNonNull(JavacBackendIT.home(release))
                                    .resolve("bin/javac")
                                    .toString(),
                            "-cp",
                            processor.toString(),
                            "-processorpath",
                            processor.toString(),
                            "-d",
                            classes.toString(),
                            base.toString(),
                            child.toString(),
                            override.toString());
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(command, helpers, Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(0, compilation.exitCode(), compilation.diagnostics().stderrTail());
            // Only the binary children remain. The current source parent is authoritative.
            Files.delete(classes.resolve("p/Base.class"));
            Path use =
                    Files.writeString(
                            project.resolve("Use.java"),
                            """
                    package p;
                    class Use {
                        String inherited(lib.Child value) { return value.getText().trim(); }
                        String overridden(lib.OverrideChild value) { return value.getText().trim(); }
                    }
                    """);
            var modeled = new ArrayList<JavaModelExtractor.UnitFacts>();
            var actual = new ArrayList<JavaModelExtractor.UnitFacts>();
            for (boolean compile : List.of(false, true)) {
                var classpath = List.of(compile ? processor : LOMBOK, classes);
                try (var binaries =
                        JavaBinarySources.open(
                                classpath,
                                Objects.requireNonNull(JavacBackendIT.home(release)),
                                release,
                                8L * 1024 * 1024)) {
                    var extractor = new JavaModelExtractor(project, binaries);
                    assertTrue(
                            backend(
                                            project,
                                            release,
                                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                            classpath,
                                            compile)
                                    .analyzeFiles(
                                            List.of(base, use),
                                            analysis -> {
                                                if (compile)
                                                    assertTrue(
                                                            analysis.units().stream()
                                                                    .filter(
                                                                            unit ->
                                                                                    unit.path()
                                                                                            .equals(
                                                                                                    use))
                                                                    .findFirst()
                                                                    .orElseThrow()
                                                                    .hasProcessorEvidence());
                                                extractor.extract(
                                                        analysis,
                                                        compile ? actual::add : modeled::add);
                                            })
                                    .complete());
                }
            }
            for (String method : List.of("inherited", "overridden")) {
                String caller = "p.Use#" + method + "(";
                var expected =
                        actual.stream()
                                .flatMap(f -> f.facts().edges.stream())
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(caller))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .sorted()
                                .toList();
                var observed =
                        modeled.stream()
                                .flatMap(f -> f.facts().edges.stream())
                                .filter(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.startsWith(caller))
                                .map(
                                        edge ->
                                                edge.targetId == null
                                                        ? edge.externalTargetFqn
                                                        : edge.targetId)
                                .sorted()
                                .toList();
                assertFalse(expected.isEmpty());
                assertEquals(expected, observed, "JDK " + release + ": " + method);
            }
            Path invalid =
                    Files.writeString(
                            project.resolve("Invalid.java"),
                            """
                    package p;
                    class Leaf extends lib.Child {}
                    class Invalid {
                        String lostPackage(Leaf value) { return value.getPackageText(); }
                        String privateMember(lib.Child value) { return value.getSecret(); }
                    }
                    """);
            var invalidFacts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            List.of(LOMBOK, classes),
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                        List.of(LOMBOK, classes))
                                .analyzeFiles(
                                        List.of(base, invalid),
                                        analysis -> extractor.extract(analysis, invalidFacts::add))
                                .complete());
            }
            assertFalse(
                    invalidFacts.stream()
                            .flatMap(f -> f.facts().edges.stream())
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.startsWith("p.Invalid#")),
                    "JDK " + release + ": invalid inheritance");
            var invalidCommand = new ArrayList<>(command.subList(0, command.size() - 3));
            invalidCommand.set(2, processor + java.io.File.pathSeparator + classes);
            invalidCommand.addAll(List.of(base.toString(), invalid.toString()));
            var rejected =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(invalidCommand, project, Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertNotEquals(0, rejected.exitCode());
            assertTrue(rejected.diagnostics().stderrTail().contains("getPackageText"));
            assertTrue(rejected.diagnostics().stderrTail().contains("getSecret"));
        }
    }

    @Test
    void explicitConstructorCallsRecoverModeledThisAndProtectedSuperTargets(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("constructors" + release));
            Path parent =
                    Files.writeString(
                            project.resolve("ProtectedParent.java"),
                            """
                    package p;
                    @lombok.AllArgsConstructor(access=lombok.AccessLevel.PROTECTED)
                    public class ProtectedParent { private String name; }
                    """);
            Path models =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.AllArgsConstructor @lombok.NoArgsConstructor
                    class Parent { String left; String right; }
                    class Child extends Parent { Child(String left, String right) { super(left, right); } }
                    @lombok.AllArgsConstructor class Plain {
                        String text; int count;
                        Plain(String text) { this(text, 1); }
                    }
                    """);
            Path child =
                    Files.writeString(
                            project.resolve("ForeignChild.java"),
                            """
                    package q;
                    class ForeignChild extends p.ProtectedParent {
                        ForeignChild(String name) { super(name); }
                        Object forbidden(String name) { return new p.ProtectedParent(name); }
                    }
                    """);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            List.of(LOMBOK),
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)))
                                .analyzeFiles(
                                        List.of(parent, models, child),
                                        analysis -> extractor.extract(analysis, facts::add))
                                .complete());
            }
            Map<String, String> expected =
                    Map.of(
                            "p.Child#Child(java.lang.String,java.lang.String)",
                            "p.Parent#Parent(java.lang.String,java.lang.String)",
                            "p.Plain#Plain(java.lang.String)",
                            "p.Plain#Plain(java.lang.String,int)",
                            "q.ForeignChild#ForeignChild(java.lang.String)",
                            "p.ProtectedParent#ProtectedParent(java.lang.String)");
            for (var entry : expected.entrySet()) {
                var edges =
                        facts.stream()
                                .flatMap(unit -> unit.facts().edges.stream())
                                .filter(
                                        edge ->
                                                edge.sourceId.equals(entry.getKey())
                                                        && entry.getValue().equals(edge.targetId))
                                .toList();
                assertEquals(1, edges.size(), "JDK " + release + ": " + entry);
                assertEquals("INFERRED", edges.getFirst().confidence);
            }
            assertFalse(
                    facts.stream()
                            .flatMap(unit -> unit.facts().edges.stream())
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals(
                                                            "q.ForeignChild#forbidden(java.lang.String)")));
        }
    }

    @Test
    void generatedAnnotationDefaultsAndOverridesMatchTheSelectedProcessor(@TempDir Path directory)
            throws Exception {
        Path modern =
                Path.of(
                        System.getProperty("user.home"),
                        ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
        record Case(int release, Path processor, String configuration, boolean generated) {}
        List<Case> cases =
                List.of(
                        new Case(8, LOMBOK, "", false),
                        new Case(8, LOMBOK, "lombok.addLombokGeneratedAnnotation = true", true),
                        new Case(
                                8,
                                LOMBOK,
                                "lombok.addGeneratedAnnotation = true\nlombok.addLombokGeneratedAnnotation = true",
                                true),
                        new Case(
                                17,
                                modern,
                                "lombok.addLombokGeneratedAnnotation = false\nlombok.addSuppressWarnings = false",
                                false),
                        new Case(25, modern, "", true));
        int ordinal = 0;
        for (Case example : cases) {
            Path project = Files.createDirectory(directory.resolve("policy" + ordinal++));
            Files.writeString(
                    project.resolve("lombok.config"),
                    "config.stopBubbling = true\n" + example.configuration());
            Path source =
                    Files.writeString(
                            project.resolve("Target.java"),
                            "@lombok.Getter class Target { String value; }");
            List<JavaAnalysisContext> contexts = new ArrayList<>();
            assertTrue(
                    backend(
                                    project,
                                    example.release(),
                                    List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                    example.processor())
                            .analyzeFiles(List.of(source), contexts::add)
                            .complete());
            if (example.configuration().contains("lombok.addGeneratedAnnotation = true")) {
                try (var model = new JavaModel(contexts.getFirst())) {
                    var owner =
                            assertInstanceOf(JavaModel.FoundType.class, model.type("Target"))
                                    .declaration();
                    var getter =
                            model.members(owner).stream()
                                    .map(model::declaration)
                                    .filter(member -> member.symbol().name().equals("getValue"))
                                    .findFirst()
                                    .orElseThrow();
                    assertTrue(
                            getter.annotations().stream()
                                    .anyMatch(
                                            annotation ->
                                                    annotation
                                                                    .descriptor()
                                                                    .equals(
                                                                            "Ljavax/annotation/Generated;")
                                                            && annotation.retention()
                                                                    == JavaAnnotation.Retention
                                                                            .SOURCE));
                    List<JavaAnalysisContext> actualContexts = new ArrayList<>();
                    assertTrue(
                            backend(
                                            project,
                                            example.release(),
                                            List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                            List.of(example.processor()),
                                            true)
                                    .analyzeFiles(List.of(source), actualContexts::add)
                                    .complete());
                    assertTrue(actualContexts.getFirst().units().getFirst().hasProcessorEvidence());
                    try (var actualModel = new JavaModel(actualContexts.getFirst())) {
                        var actualOwner =
                                assertInstanceOf(
                                                JavaModel.FoundType.class,
                                                actualModel.type("Target"))
                                        .declaration();
                        var actualGetter =
                                actualModel.members(actualOwner).stream()
                                        .map(actualModel::declaration)
                                        .filter(member -> member.symbol().name().equals("getValue"))
                                        .findFirst()
                                        .orElseThrow();
                        assertEquals(
                                actualGetter.annotations().stream()
                                        .sorted(
                                                java.util.Comparator.comparing(
                                                        JavaAnnotation::descriptor))
                                        .toList(),
                                getter.annotations().stream()
                                        .sorted(
                                                java.util.Comparator.comparing(
                                                        JavaAnnotation::descriptor))
                                        .toList());
                    }
                }
            }
            var projection =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            JavaSignatureTypes.project(
                                    contexts.getFirst().units(), example.release()));
            var modeled =
                    projection.types().stream()
                            .filter(type -> type.name().equals("Target"))
                            .findFirst()
                            .orElseThrow();
            Path output = Files.createDirectory(directory.resolve("policy-output" + ordinal));
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(
                                                            JavacBackendIT.home(example.release()))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-cp",
                                            example.processor().toString(),
                                            "-processorpath",
                                            example.processor().toString(),
                                            "-d",
                                            output.toString(),
                                            source.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(0, compilation.exitCode(), compilation.diagnostics().stderrTail());
            var actual = JavaBinaryReader.read(Files.readAllBytes(output.resolve("Target.class")));
            var getter =
                    modeled.methods().stream()
                            .filter(method -> method.name().equals("getValue"))
                            .findFirst()
                            .orElseThrow();
            var compiled =
                    actual.methods().stream()
                            .filter(method -> method.name().equals("getValue"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(compiled.annotations(), getter.annotations());
            assertEquals(
                    example.generated(),
                    getter.annotations().stream()
                            .anyMatch(
                                    annotation ->
                                            annotation.descriptor().equals("Llombok/Generated;")));
            assertTrue(modeled.fields().getFirst().annotations().isEmpty());
        }
        Path noManifest = directory.resolve("unversioned.jar");
        try (var source = new java.util.jar.JarFile(LOMBOK.toFile());
                var output = new java.util.jar.JarOutputStream(Files.newOutputStream(noManifest))) {
            for (var entries = source.entries(); entries.hasMoreElements(); ) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                output.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) {
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
        Path project = Files.createDirectory(directory.resolve("unversioned"));
        Files.writeString(project.resolve("lombok.config"), "config.stopBubbling = true");
        Path source =
                Files.writeString(
                        project.resolve("Target.java"),
                        "@lombok.Getter class Target { String value; }");
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        backend(project, 8, List.of(new SourceRoot(project, ".", SourceScope.MAIN)), noManifest)
                .analyzeFiles(List.of(source), contexts::add);
        assertInstanceOf(
                JavaSignatureTypes.Incomplete.class,
                JavaSignatureTypes.project(contexts.getFirst().units(), 8));
        assertTrue(
                contexts.getFirst().units().getFirst().extensions().diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.sample().contains("manifest version")));
    }

    @Test
    void rejectedPolyOverloadsDoNotShareProvisionalLambdaResults(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("poly" + release));
            Path helpers = Files.createDirectory(directory.resolve("helpers" + release));
            Path classes = Files.createDirectory(directory.resolve("classes" + release));
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put(
                    "NumberInput",
                    "public class NumberInput { public Integer getValue() { return 1; } }");
            sources.put(
                    "TextInput",
                    "public class TextInput { public String getValue() { return \"text\"; } }");
            sources.put(
                    "TextFunction",
                    "public interface TextFunction<T> { T apply(NumberInput value); }");
            sources.put(
                    "NumberFunction",
                    "public interface NumberFunction<T> { T apply(TextInput value); }");
            sources.put(
                    "Choose",
                    "public class Choose { public static <T extends CharSequence> void pick(TextFunction<T> value) {} public static <T extends Number> void pick(NumberFunction<T> value) {} }");
            List<String> command =
                    new ArrayList<>(
                            List.of(
                                    Objects.requireNonNull(JavacBackendIT.home(release))
                                            .resolve("bin/javac")
                                            .toString(),
                                    "-proc:none",
                                    "-d",
                                    classes.toString()));
            for (var entry : sources.entrySet())
                command.add(
                        Files.writeString(
                                        helpers.resolve(entry.getKey() + ".java"),
                                        "package p; " + entry.getValue())
                                .toString());
            var compilation =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(command, helpers, Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertEquals(0, compilation.exitCode(), compilation.diagnostics().stderrTail());
            Path source =
                    Files.writeString(
                            project.resolve("Models.java"),
                            """
                    package p;
                    @lombok.Getter class NumberInput { Integer value; }
                    @lombok.Getter class TextInput { String value; }
                    @lombok.Getter class Config { Choose chooser; java.util.List<TextInput> values; }
                    class Use {
                        void bad(Config config) { config.getChooser().pick(value -> value.getValue()); }
                        Object invalidExpression(Config config) { return config.getValues().stream().filter(value -> value.getValue()); }
                        Object invalidBlock(Config config) { return config.getValues().stream().filter(value -> { return value.getValue(); }); }
                        Object incompleteBlock(Config config) { return config.getValues().stream().filter(value -> { if (value != null) return true; }); }
                        Object validBlock(Config config) { return config.getValues().stream().filter(value -> { if (value == null) return false; return value.getValue().isEmpty(); }); }
                    }
                    """);
            var classpath = List.of(LOMBOK, classes);
            var facts = new ArrayList<JavaModelExtractor.UnitFacts>();
            try (var binaries =
                    JavaBinarySources.open(
                            classpath,
                            Objects.requireNonNull(JavacBackendIT.home(release)),
                            release,
                            8L * 1024 * 1024)) {
                var extractor = new JavaModelExtractor(project, binaries);
                assertTrue(
                        backend(
                                        project,
                                        release,
                                        List.of(new SourceRoot(project, ".", SourceScope.MAIN)),
                                        classpath)
                                .analyzeFiles(
                                        List.of(source),
                                        analysis -> extractor.extract(analysis, facts::add))
                                .complete());
            }
            var result = facts.getFirst().facts();
            assertFalse(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.relation.equals("CALLS")
                                                    && edge.sourceId.equals("p.Use#bad(p.Config)")
                                                    && edge.externalTargetFqn != null
                                                    && edge.externalTargetFqn.startsWith(
                                                            "p.Choose#pick(")),
                    "JDK " + release + ": " + result.edges);
            for (String method : List.of("invalidExpression", "invalidBlock", "incompleteBlock"))
                assertFalse(
                        result.edges.stream()
                                .anyMatch(
                                        edge ->
                                                edge.relation.equals("CALLS")
                                                        && edge.sourceId.equals(
                                                                "p.Use#" + method + "(p.Config)")
                                                        && "java.util.stream.Stream#filter(java.util.function.Predicate)"
                                                                .equals(edge.externalTargetFqn)),
                        "JDK " + release + ": " + method + ": " + result.edges);
            assertTrue(
                    result.edges.stream()
                            .anyMatch(
                                    edge ->
                                            edge.sourceId.equals("p.Use#validBlock(p.Config)")
                                                    && "java.util.stream.Stream#filter(java.util.function.Predicate)"
                                                            .equals(edge.externalTargetFqn)),
                    "JDK " + release + ": validBlock: " + result.unresolvedCallSites);
            Path processor =
                    release == 8
                            ? LOMBOK
                            : Path.of(
                                    System.getProperty("user.home"),
                                    ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");
            Path oracle =
                    Files.writeString(
                            project.resolve("Oracle.java"),
                            """
                    package p;
                    @lombok.Getter class NumberInput { Integer value; }
                    @lombok.Getter class TextInput { String value; }
                    @lombok.Getter class Config { Choose chooser; }
                    class Oracle { void bad(Config config) { config.getChooser().pick(value -> value.getValue()); } }
                    """);
            var rejected =
                    ProcessRunner.run(
                            new ProcessRunner.Spec(
                                    List.of(
                                            Objects.requireNonNull(JavacBackendIT.home(release))
                                                    .resolve("bin/javac")
                                                    .toString(),
                                            "-cp",
                                            processor + java.io.File.pathSeparator + classes,
                                            "-processorpath",
                                            processor.toString(),
                                            "-d",
                                            helpers.toString(),
                                            oracle.toString()),
                                    project,
                                    Duration.ofSeconds(30)),
                            null,
                            ProcessRunner.capture(ProcessRunner.SMALL_OUTPUT));
            assertNotEquals(0, rejected.exitCode());
            assertTrue(
                    rejected.diagnostics().stderrTail().contains("pick"),
                    rejected.diagnostics().stderrTail());
        }
    }

    private static JavacBackend backend(Path project, int release, List<SourceRoot> roots)
            throws Exception {
        return backend(project, release, roots, LOMBOK);
    }

    private static JavacBackend backend(
            Path project, int release, List<SourceRoot> roots, Path lombok) throws Exception {
        return backend(project, release, roots, List.of(lombok));
    }

    private static JavacBackend backend(
            Path project, int release, List<SourceRoot> roots, List<Path> classpath)
            throws Exception {
        return backend(project, release, roots, classpath, false);
    }

    private static JavacBackend backend(
            Path project,
            int release,
            List<SourceRoot> roots,
            List<Path> classpath,
            boolean compile)
            throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        var defaults = config.javacOptions();
        var options =
                new JavacOptions(
                        defaults.javaHome(),
                        true,
                        true,
                        compile,
                        true,
                        project.resolve(compile ? "compile-cache" : "cache"));
        var backend =
                new JavacBackend(
                        project,
                        JavacToolchain.resolve(options, release, null),
                        classpath,
                        roots,
                        options);
        backend.setDeclarationOverlay(
                new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, true))));
        return backend;
    }

    private static void pom(Path project, String module, String dependency, int release)
            throws Exception {
        Files.writeString(
                project.resolve(module).resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>"
                        + "<artifactId>"
                        + module
                        + "</artifactId><version>1</version><properties><maven.compiler.release>"
                        + release
                        + "</maven.compiler.release></properties>"
                        + (dependency.isEmpty()
                                ? ""
                                : "<dependencies><dependency><groupId>test</groupId>"
                                        + "<artifactId>"
                                        + dependency
                                        + "</artifactId><version>1</version></dependency></dependencies>")
                        + "</project>");
    }
}
