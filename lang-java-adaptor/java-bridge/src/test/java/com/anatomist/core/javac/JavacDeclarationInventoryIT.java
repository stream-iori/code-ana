package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class JavacDeclarationInventoryIT {
    @Test
    void exportsImplicitMembersSignaturesConstantsAndAllAnnotationRetentions(
            @TempDir Path directory) throws Exception {
        String source =
                """
                package sample;
                import java.lang.annotation.*;
                import java.util.List;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE_USE, ElementType.TYPE, ElementType.PARAMETER})
                @interface Mark {
                    Class<?> type() default String[].class;
                    int[] numbers() default {2, 3};
                    Nested nested() default @Nested(flag=true);
                    Nested[] children() default {@Nested(flag=false)};
                    RetentionPolicy mode() default RetentionPolicy.CLASS;
                }
                @interface Nested { boolean flag(); }
                @Retention(RetentionPolicy.SOURCE)
                @Target(ElementType.FIELD)
                @interface SourceOnly {}
                @Mark(numbers={5, 7})
                class Box<T extends Number & Comparable<T>> {
                    @SourceOnly static final int LIMIT = 6 * 7;
                    List<@Mark T> items;
                    <X extends CharSequence> X pick(@Mark X value) throws java.io.IOException { return value; }
                }
                class Outer<T> { class Inner<U> {} }
                class Use { Outer<String>.Inner<Integer> value; }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(directory.resolve("java" + release));
            JavaUnit unit = analyze(project, home, release, source);
            JavaDeclaration box = type(unit, "sample.Box");
            assertTrue(box.complete(), box.incompleteReasons().toString());
            assertEquals("sample.Box", box.binaryName());
            assertEquals(
                    "<T:Ljava/lang/Number;:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;",
                    box.signature());
            JavaDeclaration constructor = member(box, "<init>");
            assertEquals(JavaDeclaration.Origin.COMPILER_SYNTHETIC, constructor.origin());
            assertFalse(constructor.span().present());
            assertEquals("()V", constructor.symbol().descriptor());
            JavaDeclaration limit = member(box, "LIMIT");
            assertEquals(new JavaBinaryType.Scalar(42), limit.constant());
            assertEquals(
                    JavaAnnotation.Retention.SOURCE, limit.annotations().getFirst().retention());
            assertTrue(limit.span().present());
            assertTrue(
                    source.substring((int) limit.span().start(), (int) limit.span().end())
                            .contains("6 * 7"));
            assertEquals(
                    JavaAnnotation.Retention.RUNTIME, box.annotations().getFirst().retention());
            assertEquals(
                    new JavaBinaryType.ArrayValue(
                            List.of(new JavaBinaryType.Scalar(5), new JavaBinaryType.Scalar(7))),
                    box.annotations().getFirst().values().get("numbers"));
            JavaDeclaration items = member(box, "items");
            assertEquals("Ljava/util/List<TT;>;", items.signature());
            JavaType item =
                    unit.types()
                            .get(
                                    unit.types()
                                            .get(items.symbol().declaredType())
                                            .arguments()
                                            .getFirst());
            assertTrue(
                    item.annotations().stream()
                            .anyMatch(a -> a.descriptor().equals("Lsample/Mark;")));
            JavaDeclaration pick = member(box, "pick");
            assertEquals(
                    "<X::Ljava/lang/CharSequence;>(TX;)TX;^Ljava/io/IOException;",
                    pick.signature());
            assertEquals(
                    "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;",
                    pick.symbol().descriptor());
            assertEquals("value", pick.members().getFirst().symbol().name());
            assertEquals(
                    "Lsample/Mark;",
                    pick.members().getFirst().annotations().getFirst().descriptor());
            JavaDeclaration mark = type(unit, "sample.Mark");
            assertEquals(
                    new JavaBinaryType.ClassLiteral("[Ljava/lang/String;"),
                    member(mark, "type").annotationDefault());
            assertEquals(
                    new JavaBinaryType.ArrayValue(
                            List.of(new JavaBinaryType.Scalar(2), new JavaBinaryType.Scalar(3))),
                    member(mark, "numbers").annotationDefault());
            assertEquals(
                    new JavaBinaryType.EnumValue("Ljava/lang/annotation/RetentionPolicy;", "CLASS"),
                    member(mark, "mode").annotationDefault());
            assertEquals(
                    new JavaBinaryType.Scalar(true),
                    ((JavaBinaryType.NestedAnnotation) member(mark, "nested").annotationDefault())
                            .annotation()
                            .values()
                            .get("flag"));
            assertEquals(
                    1,
                    ((JavaBinaryType.ArrayValue) member(mark, "children").annotationDefault())
                            .values()
                            .size());
            assertEquals(
                    "Lsample/Outer<Ljava/lang/String;>.Inner<Ljava/lang/Integer;>;",
                    member(type(unit, "sample.Use"), "value").signature());
            tested++;
        }
        assertTrue(tested > 0);
    }

    @Test
    void preservesDirectTypeVariableAnnotationsAndConstantExpressions(@TempDir Path directory)
            throws Exception {
        String source =
                """
                package sample;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
                @interface Use {
                    int[] value();
                    boolean comparison();
                    float rounded();
                    String text();
                }
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE) @Repeatable(Tags.class)
                @interface Tag { int value(); }
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE_USE)
                @interface Tags { Tag[] value(); }
                @Target(ElementType.TYPE_PARAMETER) @interface Parameter {}
                class Box<@Parameter T extends @Use(value=1, comparison=true, rounded=1f, text="bound") Number & Comparable<T>> {
                    @Use(value=(byte)(2 + 3), comparison=9007199254740993L > 9007199254740992L,
                         rounded=16777217L - 16777216f, text="x" + (1 << 2)) T annotated;
                    T plain;
                    @Tag(1) @Tag(2) T repeated;
                    @Use(value={1, ~0, (int)(true ? 2 : 3L)}, comparison=("x" == "x"), rounded=1f, text="return")
                    T identity(@Use(value=7, comparison=true, rounded=1f, text="argument") T value) { return value; }
                    <@Parameter X extends CharSequence> X generic(X value) { return value; }
                }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            JavaUnit unit =
                    analyze(
                            Files.createDirectory(directory.resolve("java" + release)),
                            home,
                            release,
                            source);
            JavaDeclaration box = type(unit, "sample.Box");
            assertTrue(box.complete(), box.incompleteReasons().toString());
            JavaAnnotation use =
                    unit.types()
                            .get(member(box, "annotated").symbol().declaredType())
                            .annotations()
                            .getFirst();
            assertEquals(
                    new JavaBinaryType.ArrayValue(List.of(new JavaBinaryType.Scalar(5))),
                    use.values().get("value"));
            assertEquals(new JavaBinaryType.Scalar(true), use.values().get("comparison"));
            assertEquals(new JavaBinaryType.Scalar(0f), use.values().get("rounded"));
            assertEquals(new JavaBinaryType.Scalar("x4"), use.values().get("text"));
            assertTrue(
                    unit.types()
                            .get(member(box, "plain").symbol().declaredType())
                            .annotations()
                            .isEmpty());
            JavaAnnotation repeated =
                    unit.types()
                            .get(member(box, "repeated").symbol().declaredType())
                            .annotations()
                            .getFirst();
            assertEquals("Lsample/Tags;", repeated.descriptor());
            assertEquals(
                    List.of(new JavaBinaryType.Scalar(1), new JavaBinaryType.Scalar(2)),
                    ((JavaBinaryType.ArrayValue) repeated.values().get("value"))
                            .values().stream()
                                    .map(
                                            v ->
                                                    ((JavaBinaryType.NestedAnnotation) v)
                                                            .annotation()
                                                            .values()
                                                            .get("value"))
                                    .toList());
            JavaType identity = unit.types().get(member(box, "identity").symbol().declaredType());
            assertEquals(
                    new JavaBinaryType.Scalar("return"),
                    unit.types()
                            .get(identity.returns())
                            .annotations()
                            .getFirst()
                            .values()
                            .get("text"));
            assertEquals(
                    new JavaBinaryType.Scalar("argument"),
                    unit.types()
                            .get(identity.parameters().getFirst())
                            .annotations()
                            .getFirst()
                            .values()
                            .get("text"));
            assertEquals(
                    "Lsample/Parameter;", member(box, "T").annotations().getFirst().descriptor());
            JavaType bound = unit.types().get(member(box, "T").symbol().declaredType());
            JavaType intersection = unit.types().get(bound.upperBounds().getFirst());
            assertEquals(
                    new JavaBinaryType.Scalar("bound"),
                    unit.types()
                            .get(intersection.upperBounds().getFirst())
                            .annotations()
                            .getFirst()
                            .values()
                            .get("text"));
            assertEquals(
                    "Lsample/Parameter;",
                    member(member(box, "generic"), "X").annotations().getFirst().descriptor());
            tested++;
        }
        assertTrue(tested > 0);
    }

    @Test
    void exportsRecordsAndSealedHierarchyWithoutInventingSourceMembers(@TempDir Path directory)
            throws Exception {
        String source =
                """
                package sample;
                record Pair<T>(T value, int count) {}
                sealed interface Root permits Leaf {}
                final class Leaf implements Root {}
                """;
        int tested = 0;
        for (int release : List.of(17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            JavaUnit unit =
                    analyze(
                            Files.createDirectory(directory.resolve("java" + release)),
                            home,
                            release,
                            source);
            JavaDeclaration pair = type(unit, "sample.Pair");
            assertEquals("RECORD", pair.symbol().kind());
            assertTrue(pair.complete(), pair.incompleteReasons().toString());
            assertEquals(
                    2,
                    pair.members().stream()
                            .filter(m -> m.symbol().kind().equals("RECORD_COMPONENT"))
                            .count());
            JavaDeclaration component =
                    pair.members().stream()
                            .filter(
                                    m ->
                                            m.symbol().kind().equals("RECORD_COMPONENT")
                                                    && m.symbol().name().equals("value"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(JavaDeclaration.Origin.SOURCE, component.origin());
            assertTrue(
                    source.substring((int) component.span().start(), (int) component.span().end())
                            .contains("T value"));
            JavaDeclaration field =
                    pair.members().stream()
                            .filter(
                                    m ->
                                            m.symbol().kind().equals("FIELD")
                                                    && m.symbol().name().equals("value"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(JavaDeclaration.Origin.COMPILER_SYNTHETIC, field.origin());
            assertFalse(field.span().present());
            assertTrue(
                    pair.members().stream()
                            .anyMatch(
                                    m ->
                                            m.symbol().name().equals("value")
                                                    && m.symbol().kind().equals("METHOD")
                                                    && m.origin()
                                                            == JavaDeclaration.Origin
                                                                    .COMPILER_SYNTHETIC));
            assertEquals("(Ljava/lang/Object;I)V", member(pair, "<init>").symbol().descriptor());
            JavaDeclaration root = type(unit, "sample.Root");
            assertEquals(
                    List.of("sample.Leaf"),
                    root.permitted().stream().map(i -> unit.types().get(i).name()).toList());
            assertEquals(
                    List.of("sample.Root"),
                    type(unit, "sample.Leaf").interfaces().stream()
                            .map(i -> unit.types().get(i).name())
                            .toList());
            tested++;
        }
        assertTrue(tested > 0);
    }

    @Test
    void bodyErrorsDoNotInvalidateDeclarationsButHeaderAndConstantErrorsDo(@TempDir Path directory)
            throws Exception {
        JavaUnit unit =
                analyze(
                        directory,
                        Path.of(System.getProperty("java.home")),
                        25,
                        """
                package sample;
                class Healthy { int value; void broken() { absent(); } }
                class Invalid extends Missing { static final int LIMIT = missing; }
                """);
        assertTrue(type(unit, "sample.Healthy").complete());
        assertFalse(type(unit, "sample.Invalid").complete());
        assertFalse(member(type(unit, "sample.Invalid"), "LIMIT").complete());
        assertTrue(
                unit.bindings().values().stream()
                        .anyMatch(b -> b.name().equals("Healthy") && !b.evidenceComplete()));
        assertEquals(
                JavaType.Kind.ERROR,
                unit.types().get(type(unit, "sample.Invalid").superclass()).kind());
    }

    @Test
    void missingAnnotationClassLiteralsRetainValidSiblingDeclarations(@TempDir Path directory)
            throws Exception {
        JavaUnit unit =
                analyze(
                        directory,
                        Path.of(System.getProperty("java.home")),
                        25,
                        """
                @interface ClassValue {
                    Class<?> value() default Missing.class;
                    Class<?>[] list() default {Missing.class};
                    String text() default "<error>";
                }
                @ClassValue(Missing.class) class Broken {}
                class Good { int value; }
                """);
        assertTrue(type(unit, "Good").complete());
        assertEquals(
                new JavaBinaryType.Scalar("<error>"),
                member(type(unit, "ClassValue"), "text").annotationDefault());
        assertInstanceOf(
                JavaBinaryType.UnavailableValue.class,
                ((JavaBinaryType.ArrayValue)
                                member(type(unit, "ClassValue"), "list").annotationDefault())
                        .values()
                        .getFirst());
        assertFalse(type(unit, "Broken").complete());
        assertFalse(type(unit, "ClassValue").complete());
        assertInstanceOf(
                JavaBinaryType.UnavailableValue.class,
                member(type(unit, "ClassValue"), "value").annotationDefault());
        assertInstanceOf(
                JavaBinaryType.UnavailableValue.class,
                type(unit, "Broken").annotations().getFirst().values().get("value"));
    }

    @Test
    void implementationDiagnosticsDoNotInvalidateIndependentAbiHeaders(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path home = java.util.Objects.requireNonNull(JavacBackendIT.home(release));
            Path project = Files.createDirectory(directory.resolve("headers" + release));
            Path file =
                    Files.writeString(
                            project.resolve("Declarations.java"),
                            """
                    interface Weight { int getWeight(); }
                    class Missing implements Weight { int weight; }
                    class Wrong { @Override public int count() { return 1; } }
                    """);
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var contexts = new ArrayList<JavaAnalysisContext>();
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
            var context = contexts.getFirst();
            assertTrue(
                    context.diagnostics().stream()
                            .anyMatch(
                                    diagnostic ->
                                            diagnostic
                                                    .code()
                                                    .contains("does.not.override.abstract")));
            assertTrue(
                    context.diagnostics().stream()
                            .anyMatch(
                                    diagnostic ->
                                            diagnostic
                                                    .code()
                                                    .contains(
                                                            "method.does.not.override.superclass")));
            var unit = context.units().getFirst();
            assertTrue(
                    type(unit, "Missing").complete(),
                    type(unit, "Missing").incompleteReasons().toString());
            assertTrue(member(type(unit, "Wrong"), "count").complete());
            assertTrue(member(type(unit, "Wrong"), "count").overrides().targets().isEmpty());
            assertInstanceOf(
                    com.anatomist.core.asmsolver.JavaSignatureTypes.Complete.class,
                    com.anatomist.core.asmsolver.JavaSignatureTypes.project(
                            context.units(), release));
        }
    }

    private static JavaUnit analyze(Path project, Path home, int release, String source)
            throws Exception {
        Path file = project.resolve("Declarations.java");
        Files.writeString(file, source);
        ProjectConfig config = new ProjectConfig();
        config.setJavaHome(home.toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), release, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
        assertEquals("", contexts.getFirst().analysisFailure());
        return contexts.getFirst().units().getFirst();
    }

    private static JavaDeclaration type(JavaUnit unit, String name) {
        return unit.declarationInventory().stream()
                .filter(d -> d.canonicalName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static JavaDeclaration member(JavaDeclaration owner, String name) {
        return owner.members().stream()
                .filter(d -> d.symbol().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
