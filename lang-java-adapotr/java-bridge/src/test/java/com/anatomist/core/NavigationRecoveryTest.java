package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Original recovery scenarios exercised against real selected compiler declarations. */
class NavigationRecoveryTest {
    @Test
    void implicitPublicInterfaceMethodIsAccessibleAcrossPackages(@TempDir Path directory)
            throws Exception {
        Files.createDirectories(directory.resolve("dep"));
        Files.writeString(
                directory.resolve("dep/Repo.java"),
                "package dep; public interface Repo { void save(Object value); }");
        var fixture =
                fixture(
                        directory,
                        """
                package use;
                import java.util.function.Consumer;
                class Use {
                  dep.Repo repo;
                  Consumer<String> callback() { return repo::save; }
                }
                """);
        assertTargets(fixture.facts(), "dep.Repo#save(java.lang.Object)");
    }

    @Test
    void ordinaryInterfaceReceiverRetainsStreamAndMethodReferenceTargets(@TempDir Path directory)
            throws Exception {
        Files.createDirectories(directory.resolve("dep"));
        Files.writeString(
                directory.resolve("dep/Dao.java"),
                "package dep; public interface Dao { java.util.List<String> values(String key); }");
        var fixture =
                fixture(
                        directory,
                        """
                package use;
                class Use {
                  dep.Dao dao;
                  Object run(String key) {
                    return dao.values(key).stream().filter(String::isEmpty).collect(java.util.stream.Collectors.toList());
                  }
                }
                """);
        assertTargets(
                fixture.facts(),
                "dep.Dao#values(java.lang.String)",
                "java.util.Collection#stream()",
                "java.util.stream.Stream#filter(java.util.function.Predicate)",
                "java.lang.String#isEmpty()");
    }

    @Test
    void deferredCallbackOutputDoesNotBlockUniqueBuilderMembers(@TempDir Path directory)
            throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*;
                class Builder {
                  Builder values(List<String> values) { return this; }
                  Builder name(String name) { return this; }
                  Object build() { return null; }
                }
                class Use {
                  Object run(String input) {
                    return Optional.ofNullable(input).map(item -> new Builder()
                        .values(Optional.ofNullable(item).map(value -> {
                          List<String> values = Collections.singletonList(value);
                          return values;
                        }).orElse(null)).name(item).build());
                  }
                }
                """);
        assertTargets(
                fixture.facts(),
                "Builder#values(java.util.List)",
                "Builder#name(java.lang.String)",
                "Builder#build()");
    }

    @Test
    void receiverChainKeepsChangingGenericReturnTypes(@TempDir Path directory) throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                class Box<T> { T get() { return null; } }
                class Service { Box<String> box() { return null; }
                  String run() { return box().get().trim().substring(0).toUpperCase(); }
                }
                """);
        assertTargets(fixture.facts(), "java.lang.String#toUpperCase()");
    }

    @Test
    void variadicParameterRemainsAnArrayWhenForwarded(@TempDir Path directory) throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*;
                class Use {
                  static <T> List<T> copy(T... values) { return null; }
                  static <T> List<T> copy(Iterable<T> values) { return null; }
                  static <T> Object forward(List<T>... lists) { return copy(lists); }
                  static Object ordinary(List<String> values) { return copy(values); }
                }
                """);
        assertTargets(
                fixture.facts(), "Use#copy(java.lang.Object...)", "Use#copy(java.lang.Iterable)");
    }

    @Test
    void sourceOnlyStaticCallsPreferFixedArity(@TempDir Path directory) throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*;
                class Model { Boolean flag() { return null; } int count() { return 0; } }
                enum Flag { ON }
                class Api {
                  static String render(Object value) { return null; }
                  static String render(Object value, Object... options) { return null; }
                  static <T> T parse(String value, Class<T> type) { return null; }
                  static <T> T parse(String value, Class<T> type, Object... options) { return null; }
                  static void copy(Object source, Object target) {}
                  static void copy(Object source, Object target, String... ignored) {}
                  static String choose(String value) { return null; }
                  static String choose(Integer value) { return null; }
                }
                class Use {
                  Object run(List<Model> input) {
                    return input.stream().map(item -> Optional.ofNullable(item).map(value -> {
                      Object target = new Object();
                      Api.copy(value, target);
                      String rendered = String.valueOf(value.flag());
                      String count = String.valueOf(value.count());
                      Flag flag = Flag.valueOf(Api.render(value.flag()));
                      String unknown = Api.choose(null);
                      return Api.parse(Api.render(value.flag()), String.class);
                    }).orElse("")).toList();
                  }
                }
                """);
        assertTargets(
                fixture.facts(),
                "Api#render(java.lang.Object)",
                "Api#copy(java.lang.Object,java.lang.Object)",
                "Api#parse(java.lang.String,java.lang.Class)",
                "java.lang.String#valueOf(java.lang.Object)",
                "java.lang.String#valueOf(int)",
                "Flag#valueOf(java.lang.String)");
        assertFalse(fixture.completeSelection("choose"));
    }

    @Test
    void sourceOnlyTieBreakersKeepPreviouslyUniqueExpandedVarargs(@TempDir Path directory)
            throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*;
                class Ref<T> {}
                class Log { static void error(String message, Object... args) {} }
                class Json { static <T> T parse(String value, Ref<T> type, Object... features) { return null; } }
                class Lists { static <T> List<T> create(T... values) { return null; } }
                class Use {
                  void run(List<String> input) {
                    input.forEach(value -> {
                      Log.error("{} {}", value, new RuntimeException());
                      Ref<String> ref = new Ref<>();
                      String parsed = Json.parse(value, ref);
                      List<String> values = Lists.create(value);
                    });
                  }
                }
                """);
        assertTargets(
                fixture.facts(),
                "Log#error(java.lang.String,java.lang.Object...)",
                "Json#parse(java.lang.String,Ref,java.lang.Object...)",
                "Lists#create(java.lang.Object...)");
    }

    @Test
    void invocationPhasesStayWithinLongBuilderBudget(@TempDir Path directory) throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*;
                class Json {
                  static <T> T parse(String value, Class<T> type) { return null; }
                  static <T> T parse(String value, Class<T> type, Object... features) { return null; }
                }
                class Builder {
                  static Builder builder() { return new Builder(); }
                  Builder value(String value) { return this; }
                  Object build() { return null; }
                }
                class Use { Object run(List<String> input) {
                  return input.stream().map(item -> Builder.builder().value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).value(Json.parse(item, String.class)).build()).toList();
                } }
                """);
        assertTargets(
                fixture.facts(),
                "Builder#build()",
                "Builder#value(java.lang.String)",
                "Json#parse(java.lang.String,java.lang.Class)");
    }

    @Test
    void singleReturnAndMethodReferencePreserveDownstreamReceivers(@TempDir Path directory)
            throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.stream.*;
                class Info { String code() { return null; } }
                class Content { List<Info> infos() { return null; } }
                class Requirement { List<Content> contents() { return null; } }
                class Use {
                  Object run(List<Requirement> input, List<Object> visited) {
                    return input.stream().flatMap(item -> {
                      visited.add(item);
                      return Optional.ofNullable(item.contents()).orElse(Collections.emptyList()).stream();
                    }).flatMap(item -> {
                      visited.add(item);
                      return item.infos().stream();
                    }).map(Info::code).filter(code -> code.equals("x")).collect(Collectors.toList());
                  }
                }
                """);
        assertTargets(
                fixture.facts(),
                "Requirement#contents()",
                "Content#infos()",
                "Info#code()",
                "java.lang.String#equals(java.lang.Object)");
    }

    @Test
    void unknownElementDoesNotEraseKnownContainerWhenSelectingOverloads(@TempDir Path directory)
            throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.stream.*;
                class Use {
                  static String join(Collection<?> values, String separator) { return null; }
                  static String join(Iterator<?> values, String separator) { return null; }
                  static String join(Object[] values, String separator) { return null; }
                  Object run(List<String> input) {
                    return join(input.stream().map(v -> Optional.ofNullable(v).map(String::trim).orElse("")).collect(Collectors.toList()), ",");
                  }
                }
                """);
        assertTargets(fixture.facts(), "Use#join(java.util.Collection,java.lang.String)");
    }

    @Test
    void knownGetterInputDoesNotBecomeTheCallbackOutputType(@TempDir Path directory)
            throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.function.*;
                class Tree {}
                class Param { List<String> getValues() { return null; } }
                class A {
                  static <F,T> List<T> convert(List<F> values, Function<F,T> mapper) { return null; }
                  static <T> List<T> convert(List<?> values, Class<T> target) { return null; }
                  static Tree parse(String value, Class<Tree> target) { return null; }
                  static Tree parse(String value, Class<Tree> target, Object... options) { return null; }
                  static Tree parse(Tree value, Class<Tree> target) { return null; }
                  Object run(Param input) {
                    return Optional.ofNullable(input).map(param -> convert(param.getValues(), value -> parse(value, Tree.class)));
                  }
                }
                """);
        assertTargets(fixture.facts(), "A#parse(java.lang.String,java.lang.Class)");
    }

    @Test
    void inputAndOutputVariablesRemainDistinctThroughWildcardSam(@TempDir Path directory)
            throws Exception {
        var fixture =
                fixture(
                        directory,
                        """
                import java.util.*; import java.util.function.*;
                class A {
                  static <F,T> List<T> convert(List<F> values, Function<? super F,? extends T> mapper) { return null; }
                  Object run(List<String> input) { return convert(input, value -> value.length()); }
                }
                """);
        assertTargets(fixture.facts(), "java.lang.String#length()");
    }

    @Test
    void anonymousSubclassCanCallProtectedConstructorAcrossPackages(@TempDir Path directory)
            throws Exception {
        Files.createDirectories(directory.resolve("dep"));
        Files.writeString(
                directory.resolve("dep/Base.java"),
                """
            package dep; public class Base { protected Base() {} protected Base(String... values) {}
              Base(int value) {} private Base(boolean value) {} }
            """);
        var value =
                fixture(
                        directory,
                        """
            package use; class Use extends dep.Base {
              Object[] values = {new dep.Base() {}, new dep.Base(new String[]{"a"}) {}, new dep.Base("a") {},
                new dep.Base(), new dep.Base(1) {}, new dep.Base(true) {}};
            }
            """);
        var creations = value.nodes("NEW_CLASS");
        assertEquals(6, creations.size());
        for (int i = 0; i < 3; i++)
            assertTrue(value.binding(creations.get(i)).declarationResolved());
        for (int i = 3; i < 6; i++) {
            var binding = value.binding(creations.get(i));
            assertTrue(
                    binding == null
                            || !binding.declarationResolved()
                            || !binding.evidenceComplete());
        }
        assertTrue(value.hasCompilerError());
    }

    @Test
    void chainsResolveWithoutRewalkingEveryPrefix(@TempDir Path directory) throws Exception {
        for (int length : new int[] {4, 8, 16, 32}) {
            var value =
                    fixture(
                            directory,
                            "class A { A next() { return this; } A run() { return next()"
                                    + ".next()".repeat(length - 1)
                                    + "; } }");
            assertEquals(length, value.nodes("METHOD_INVOCATION").size());
            assertTrue(
                    value.nodes("METHOD_INVOCATION").stream()
                            .allMatch(node -> value.binding(node).declarationResolved()));
            assertFalse(value.hasCompilerError());
        }
    }

    @Test
    void longChainUsesCompilerFactsWithinBoundedWorkerScope(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        "class A { A next() { return this; } A run() { return next()"
                                + ".next()".repeat(64)
                                + "; } }");
        assertEquals(65, value.nodes("METHOD_INVOCATION").size());
        assertTrue(
                value.nodes("METHOD_INVOCATION").stream()
                        .allMatch(node -> value.binding(node).declarationResolved()));
        assertFalse(value.hasCompilerError());
    }

    @Test
    void knownInputsCannotOverrideExplicitGenericConstraints(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
            import java.util.*; import java.util.function.*;
            class A { static <F,T> List<T> convert(List<F> values, Function<F,T> mapper) { return null; }
              Object run(List<String> values) { return A.<Integer,Integer>convert(values, value -> value); } }
            """);
        assertTrue(value.hasCompilerError());
        assertFalse(value.completeSelection("convert"));
    }

    @Test
    void largeOrMultipleReturnBodiesKeepActualCompilerOutput(@TempDir Path directory)
            throws Exception {
        for (String body :
                List.of(
                        "if (x.isEmpty()) return new Result(); return new Result();",
                        "x.trim();".repeat(40) + "return new Result();")) {
            var value =
                    fixture(
                            directory,
                            "class Result {} class Use { Object run(java.util.List<String> input) {"
                                    + "return input.stream().map(x -> {"
                                    + body
                                    + "}).map(value -> value); } }");
            assertFalse(value.hasCompilerError());
            assertTrue(value.completeSelection("map"));
            assertTargets(
                    value.facts(), "java.util.stream.Stream#map(java.util.function.Function)");
        }
    }

    @Test
    void failedArgumentDoesNotEraseUniqueParentDeclaration(@TempDir Path directory)
            throws Exception {
        StringBuilder declarations = new StringBuilder();
        for (int i = 0; i < 17; i++) declarations.append("class P").append(i).append(" {} ");
        declarations.append("class Noise { ");
        for (int i = 0; i < 17; i++)
            declarations.append("Object choose(P").append(i).append(" p) { return null; } ");
        declarations.append("} ");
        var value =
                fixture(
                        directory,
                        declarations
                                + """
            class Use { String consume(Object value) { return null; }
              Object run(Noise input, Object value) {
                return java.util.Optional.ofNullable(input).map(noise -> consume(noise.choose(value))).orElseGet(() -> "");
            } }
            """);
        assertTrue(value.hasCompilerError());
        assertFalse(value.completeSelection("choose"));
        assertTrue(
                value.nodes("METHOD_INVOCATION").stream()
                        .map(value::binding)
                        .filter(Objects::nonNull)
                        .anyMatch(
                                binding ->
                                        binding.name().equals("consume")
                                                && binding.declarationResolved()));
    }

    @Test
    void lowerBoundedListDoesNotInventAReadableStringElement(@TempDir Path directory)
            throws Exception {
        var value =
                fixture(
                        directory,
                        """
            import java.util.*; import java.util.function.*;
            class A { static <F,T> List<T> convert(List<F> values, Function<F,T> mapper) { return null; }
              Object run(List<? super String> input) { return convert(input, value -> value.length()); } }
            """);
        assertTrue(value.hasCompilerError());
        assertFalse(value.completeSelection("length"));
        assertFalse(
                value.facts().edges.stream()
                        .anyMatch(
                                edge ->
                                        "java.lang.String#length()"
                                                .equals(edge.externalTargetFqn)));
    }

    private record Fixture(JavaAnalysisContext context, JavaUnit unit, ExtractionResult facts) {
        List<JavaNode> nodes(String kind) {
            try (var model = new JavaModel(context)) {
                return model.syntax(unit.path()).nodes().stream()
                        .filter(node -> node.kind().equals(kind))
                        .toList();
            }
        }

        JavaBinding binding(JavaNode node) {
            return unit.bindings().get(node.id());
        }

        boolean completeSelection(String name) {
            return nodes("METHOD_INVOCATION").stream()
                    .map(this::binding)
                    .filter(Objects::nonNull)
                    .anyMatch(
                            binding ->
                                    binding.name().equals(name)
                                            && binding.declarationResolved()
                                            && binding.evidenceComplete()
                                            && Set.of("METHOD", "CONSTRUCTOR")
                                                    .contains(binding.kind()));
        }

        boolean hasCompilerError() {
            return context.diagnostics().stream().anyMatch(value -> value.kind().equals("ERROR"));
        }
    }

    private static Fixture fixture(Path directory, String source) throws Exception {
        var context = JavaFactsTestSupport.analyze(directory, "Use.java", source);
        var unit =
                context.units().stream()
                        .filter(value -> value.path().equals(directory.resolve("Use.java")))
                        .findFirst()
                        .orElseThrow();
        var facts = new ExtractionResult();
        var backend = JavaFactsTestSupport.backend(directory);
        try (var binaries = backend.openBinaryRepository(context)) {
            new JavaModelExtractor(directory, binaries)
                    .extract(context, value -> facts.addFacts(value.facts()));
        }
        return new Fixture(context, unit, facts);
    }

    private static void assertTargets(ExtractionResult facts, String... expected) {
        Set<String> targets =
                facts.edges.stream()
                        .filter(edge -> edge.relation.equals("CALLS"))
                        .map(edge -> edge.targetId == null ? edge.externalTargetFqn : edge.targetId)
                        .collect(Collectors.toSet());
        assertTrue(
                targets.containsAll(List.of(expected)),
                "expected " + List.of(expected) + "; actual " + targets);
    }
}
