package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.*;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Platform API resolution uses the requested SDK, including when the host is Java 25. */
class TargetJdkResolutionIT {
    @Test
    void sourceEnumInheritsPlatformNameMethod(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("State.java"), "public enum State { ACTIVE }");
        assertCalls(
                JavaFactsTestSupport.extract(
                        root,
                        "A.java",
                        "class A { String read() { return State.ACTIVE.name(); } }",
                        8),
                "java.lang.Enum#name()");
    }

    @Test
    void genericFactoryPreservesMethodTypeBinding(@TempDir Path root) throws Exception {
        var file =
                Files.writeString(
                        root.resolve("A.java"),
                        "class A { Object value = java.util.Optional.of(\"x\"); }");
        var contexts = new ArrayList<JavaAnalysisContext>();
        JavaFactsTestSupport.backend(root, 8).analyzeFiles(List.of(file), contexts::add);
        var context = contexts.getFirst();
        try (var model = new JavaModel(context)) {
            var unit = model.unit(file);
            var call =
                    model.syntax(file).nodes().stream()
                            .filter(node -> node.kind().equals("METHOD_INVOCATION"))
                            .findFirst()
                            .orElseThrow();
            var type = unit.types().get(unit.bindings().get(call.id()).expressionType());
            assertEquals("java.util.Optional", type.name());
            assertEquals(
                    "java.lang.String",
                    JavaTypeNames.erased(unit.types(), type.arguments().getFirst()));
        }
    }

    @Test
    void resolvesImplicitObjectMethodsInsideJdkCallArguments(@TempDir Path root) throws Exception {
        assertCalls(
                JavaFactsTestSupport.extract(
                        root,
                        "A.java",
                        """
                class A { String text() { StringBuilder sb = new StringBuilder();
                  sb.append(getClass().getSimpleName()); sb.append("Hash = ").append(hashCode());
                  return sb.toString(); } }
                """,
                        8),
                "java.lang.StringBuilder#append(java.lang.String)",
                "java.lang.StringBuilder#append(int)",
                "java.lang.Object#getClass()",
                "java.lang.Object#hashCode()");
    }

    @Test
    void resolvesPrimitiveArrayCopyAcrossSupportedSdks(@TempDir Path root) throws Exception {
        for (int release : List.of(8, 17, 25))
            assertCalls(
                    JavaFactsTestSupport.extract(
                            root,
                            "A.java",
                            """
                    class A { byte[] slice(byte[] input) { return java.util.Arrays.copyOfRange(input, 0, input.length); } }
                    """,
                            release),
                    "java.util.Arrays#copyOfRange(byte[],int,int)");
    }

    @Test
    void resolvesOptionalAndCryptoApisIncludingFieldReads(@TempDir Path root) throws Exception {
        var graph =
                JavaFactsTestSupport.extract(
                        root,
                        "A.java",
                        """
                import java.util.Optional; import javax.crypto.*; import javax.crypto.spec.*;
                class A { void run() throws Exception {
                  String value = Optional.of("x").orElse("y");
                  Cipher cipher = Cipher.getInstance("AES");
                  KeyGenerator generator = KeyGenerator.getInstance("AES");
                  SecretKeySpec key = new SecretKeySpec(new byte[16], "AES");
                  IvParameterSpec iv = new IvParameterSpec(new byte[16]);
                  cipher.init(Cipher.ENCRYPT_MODE, key, iv);
                } }
                """,
                        8);
        assertCalls(
                graph,
                "java.util.Optional#orElse(java.lang.Object)",
                "javax.crypto.Cipher#getInstance(java.lang.String)",
                "javax.crypto.KeyGenerator#getInstance(java.lang.String)",
                "javax.crypto.Cipher#init(int,java.security.Key,java.security.spec.AlgorithmParameterSpec)",
                "javax.crypto.spec.SecretKeySpec#SecretKeySpec(byte[],java.lang.String)",
                "javax.crypto.spec.IvParameterSpec#IvParameterSpec(byte[])");
        assertTrue(
                graph.edges.stream()
                        .anyMatch(
                                edge ->
                                        edge.relation.equals("READS")
                                                && "javax.crypto.Cipher#ENCRYPT_MODE"
                                                        .equals(edge.externalTargetFqn)));
        assertTrue(graph.unresolvedCallSites.isEmpty());
    }

    @Test
    void resolvesOptionalCollectionChainAcrossSupportedSdks(@TempDir Path root) throws Exception {
        for (int release : List.of(8, 17, 25))
            assertCalls(
                    JavaFactsTestSupport.extract(
                            root,
                            "A.java",
                            """
                    import java.util.*;
                    class A { long count(List<String> values) {
                      return Optional.ofNullable(values).orElse(Collections.emptyList()).stream().count();
                    } }
                    """,
                            release),
                    "java.util.Optional#orElse(java.lang.Object)",
                    "java.util.Collection#stream()",
                    "java.util.stream.Stream#count()");
    }

    @Test
    void resolvesOptionalCollectionChainInsideFlatMapAcrossSupportedSdks(@TempDir Path root)
            throws Exception {
        for (int release : List.of(8, 17, 25))
            assertCalls(
                    JavaFactsTestSupport.extract(
                            root,
                            "A.java",
                            """
                    import java.util.*; import java.util.function.*; import java.util.stream.*;
                    class Config { List<String> getCoAssessors() { return null; } }
                    class A { List<String> collect(List<Config> configs) {
                      Function<List<Config>, List<String>> processor = data -> data.stream()
                        .filter(config -> config != null)
                        .flatMap(config -> Optional.ofNullable(config.getCoAssessors()).orElse(Collections.emptyList()).stream())
                        .collect(Collectors.toList()); return processor.apply(configs);
                    } }
                    """,
                            release),
                    "java.util.stream.Stream#filter(java.util.function.Predicate)",
                    "java.util.stream.Stream#flatMap(java.util.function.Function)",
                    "java.util.Optional#orElse(java.lang.Object)",
                    "java.util.Collection#stream()",
                    "java.util.stream.Stream#collect(java.util.stream.Collector)");
    }

    @Test
    void java8CannotResolveNewerStreamApiFromHost(@TempDir Path root) throws Exception {
        var graph =
                JavaFactsTestSupport.extract(
                        root,
                        "A.java",
                        """
                class A { java.util.List<String> copy(java.util.List<String> values) { return values.stream().toList(); } }
                """,
                        8);
        assertFalse(
                graph.edges.stream()
                        .anyMatch(
                                edge ->
                                        "java.util.stream.Stream#toList()"
                                                .equals(edge.externalTargetFqn)));
        assertFalse(graph.unresolvedCallSites.isEmpty());
    }

    private static void assertCalls(ExtractionResult graph, String... expected) {
        Set<String> actual =
                graph.edges.stream()
                        .filter(edge -> edge.relation.equals("CALLS"))
                        .map(edge -> edge.targetId == null ? edge.externalTargetFqn : edge.targetId)
                        .collect(Collectors.toSet());
        assertTrue(
                actual.containsAll(List.of(expected)),
                "expected " + List.of(expected) + "; actual " + actual);
    }
}
