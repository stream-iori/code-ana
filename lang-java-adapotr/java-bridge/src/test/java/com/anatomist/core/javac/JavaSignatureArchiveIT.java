package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.asmsolver.*;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import javax.tools.ToolProvider;

class JavaSignatureArchiveIT {
    @Test
    void sourceAbiProjectionNeedsNoProjectBuildAndRetainsHiddenConstructorsAndBounds(
            @TempDir Path directory) throws Exception {
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project =
                    Files.createDirectories(directory.resolve("jdk" + release).resolve("library"));
            Path annotation =
                    Files.writeString(
                            project.resolve("Mark.java"),
                            "package dep; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE_USE,ElementType.TYPE_PARAMETER,ElementType.PARAMETER,ElementType.METHOD,ElementType.FIELD}) public @interface Mark { int value() default 4; Class<?> type() default String[].class; }");
            Path library =
                    Files.writeString(
                            project.resolve("Library.java"),
                            """
                    package dep;
                    public class Library<@Mark T extends @Mark Number & @Mark Comparable<T>> {
                        public static final int LIMIT = 42;
                        public java.util.List<@Mark T[]> items;
                        public @Mark T echo(@Mark T value) { return value; }
                        public <U extends @Mark Runnable> U action(U value) { return value; }
                        public class Inner { public Inner(T value) {} public T get() { return null; } }
                        public static class Nested {}
                    }
                    """);
            Path mode =
                    Files.writeString(
                            project.resolve("Mode.java"),
                            "package dep; public enum Mode { FIRST, SECOND; }");
            var config = new ProjectConfig();
            config.setJavaHome(home.toString());
            var backend =
                    new JavacBackend(
                            JavacToolchain.resolve(config.javacOptions(), release, null),
                            List.of(),
                            List.of(project),
                            config.javacOptions());
            List<JavaAnalysisContext> contexts = new ArrayList<>();
            assertTrue(
                    backend.analyzeFiles(List.of(library, annotation, mode), contexts::add)
                            .complete());
            var projection = JavaSignatureTypes.project(contexts.getFirst().units(), release);
            var complete =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class, projection, projection.toString());
            assertTrue(Files.notExists(project.resolve("target")));
            Path archive = project.getParent().resolve("source-signature.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(archive))) {
                for (JavaBinaryType type : complete.types()) {
                    byte[] bytes = JavaBinaryWriter.signature(type);
                    output.putNextEntry(new JarEntry(type.name().replace('.', '/') + ".class"));
                    output.write(bytes);
                    output.closeEntry();
                }
            }
            var inner =
                    complete.types().stream()
                            .filter(type -> type.name().equals("dep.Library$Inner"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "(Ldep/Library;Ljava/lang/Number;)V",
                    inner.methods().stream()
                            .filter(method -> method.name().equals("<init>"))
                            .findFirst()
                            .orElseThrow()
                            .descriptor());
            var outer =
                    complete.types().stream()
                            .filter(type -> type.name().equals("dep.Library"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    outer.typeAnnotations().stream()
                            .anyMatch(
                                    value ->
                                            new org.objectweb.asm.TypeReference(value.target())
                                                                    .getSort()
                                                            == org.objectweb.asm.TypeReference
                                                                    .CLASS_TYPE_PARAMETER_BOUND
                                                    && new org.objectweb.asm.TypeReference(
                                                                            value.target())
                                                                    .getTypeParameterBoundIndex()
                                                            == 1),
                    "JDK " + release + ": " + outer.typeAnnotations());
            Path client = Files.createDirectory(project.getParent().resolve("client"));
            Path use =
                    Files.writeString(
                            client.resolve("Use.java"),
                            """
                    class Use {
                        dep.Library<Integer>.Inner inner(dep.Library<Integer> library) { return library.new Inner(1); }
                        Integer value(dep.Library<Integer> library) { return library.echo(1); }
                        dep.Mode first() { return dep.Mode.valueOf("FIRST"); }
                        @dep.Mark int marker;
                    }
                    """);
            JavaUnit unit = analyze(client, use, archive, home, release);
            assertTrue(
                    targets(unit).stream()
                            .anyMatch(
                                    value ->
                                            value.contains(
                                                    "dep.Library#echo(Ljava/lang/Number;)Ljava/lang/Number;")),
                    targets(unit).toString());
            var enumType =
                    complete.types().stream()
                            .filter(type -> type.name().equals("dep.Mode"))
                            .findFirst()
                            .orElseThrow();
            assertTrue(
                    enumType.methods().stream()
                            .anyMatch(
                                    method ->
                                            method.name().equals("<init>")
                                                    && method.descriptor()
                                                            .equals("(Ljava/lang/String;I)V")));
            tested++;
        }
        assertTrue(tested > 0);
    }

    @Test
    void incompleteHeadersPreventArchiveGenerationButBodyErrorsDoNot(@TempDir Path project)
            throws Exception {
        Path source =
                Files.writeString(
                        project.resolve("Example.java"),
                        "class Good { int run() { return missing(); } } class Bad { Missing header; }");
        var config = new ProjectConfig();
        config.setJavaHome(System.getProperty("java.home"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), 25, null),
                        List.of(),
                        List.of(project),
                        config.javacOptions());
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        backend.analyzeFiles(List.of(source), contexts::add);
        assertInstanceOf(
                JavaSignatureTypes.Incomplete.class,
                JavaSignatureTypes.project(contexts.getFirst().units(), 25));
        Files.writeString(source, "class Good { int run() { return missing(); } }");
        contexts.clear();
        backend.analyzeFiles(List.of(source), contexts::add);
        assertInstanceOf(
                JavaSignatureTypes.Complete.class,
                JavaSignatureTypes.project(contexts.getFirst().units(), 25));
    }

    @Test
    void javacReadsIndexOnlySignaturesLikeRealBinariesOnAllToolchains(@TempDir Path directory)
            throws Exception {
        Path source =
                Files.writeString(
                        directory.resolve("Library.java"),
                        """
                package dep;
                public class Library<T extends Number & Comparable<T>> {
                    public static final int LIMIT = 42;
                    public static int choose(long value) { return 1; }
                    public static int choose(Integer value) { return 2; }
                    public T echo(T value) { return value; }
                    public class Inner { public Inner(T value) {} public T get() { return null; } }
                    public static class Nested { public static String text() { return "x"; } }
                }
                """);
        Path annotation =
                Files.writeString(
                        directory.resolve("Mark.java"),
                        "package dep; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Mark { int value() default Library.LIMIT; Class<?> type() default String.class; }");
        Path classes = Files.createDirectory(directory.resolve("classes"));
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "8",
                                "-proc:none",
                                "-parameters",
                                "-d",
                                classes.toString(),
                                source.toString(),
                                annotation.toString()));
        Path original = directory.resolve("original.jar"),
                signature = directory.resolve("signature.jar");
        Map<String, byte[]> signatures = new LinkedHashMap<>();
        try (var real = new JarOutputStream(Files.newOutputStream(original));
                var index = new JarOutputStream(Files.newOutputStream(signature));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                byte[] bytes = Files.readAllBytes(file);
                byte[] declaration = JavaBinaryWriter.signature(JavaBinaryReader.read(bytes));
                signatures.put(name, declaration);
                real.putNextEntry(new JarEntry(name));
                real.write(bytes);
                real.closeEntry();
                index.putNextEntry(new JarEntry(name));
                index.write(declaration);
                index.closeEntry();
            }
        }
        // It is compiler input, not a project output that can execute.
        assertThrows(
                ClassFormatError.class,
                () -> new SignatureLoader().load(signatures.get("dep/Library.class")));
        String client =
                """
                class Use {
                    @dep.Mark(type=String[].class) int marker;
                    java.util.function.LongUnaryOperator select = dep.Library::choose;
                    dep.Library<Integer>.Inner create(Integer value) {
                        dep.Library<Integer> library = new dep.Library<Integer>();
                        return library.new Inner(value);
                    }
                    Integer read(dep.Library<Integer> library) { return library.echo(1); }
                    int constant() { return dep.Library.LIMIT; }
                    String nested() { return dep.Library.Nested.text(); }
                }
                """;
        int tested = 0;
        for (int release : List.of(8, 17, 25)) {
            Path home = JavacBackendIT.home(release);
            if (home == null) continue;
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path file = Files.writeString(project.resolve("Use.java"), client);
            JavaUnit real = analyze(project, file, original, home, release);
            JavaUnit indexed = analyze(project, file, signature, home, release);
            assertEquals(
                    JavaContract.fingerprint(real),
                    JavaContract.fingerprint(indexed),
                    "JDK " + release);
            assertEquals(targets(real), targets(indexed), "JDK " + release);
            assertTrue(
                    targets(indexed).stream()
                            .anyMatch(value -> value.contains("dep.Library#choose(J)I")),
                    targets(indexed).toString());
            tested++;
        }
        assertTrue(tested > 0);
    }

    private static JavaUnit analyze(Path project, Path file, Path archive, Path home, int release) {
        var config = new ProjectConfig();
        config.setJavaHome(home.toString());
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(config.javacOptions(), release, null),
                        List.of(archive),
                        List.of(project),
                        config.javacOptions());
        List<JavaAnalysisContext> contexts = new ArrayList<>();
        assertTrue(backend.analyzeFiles(List.of(file), contexts::add).complete());
        assertTrue(
                contexts.getFirst().diagnostics().stream()
                        .noneMatch(value -> value.kind().equals("ERROR")),
                contexts.getFirst().diagnostics().toString());
        return contexts.getFirst().units().getFirst();
    }

    private static List<String> targets(JavaUnit unit) {
        return unit.bindings().values().stream()
                .filter(
                        binding ->
                                Set.of("METHOD", "CONSTRUCTOR", "FIELD").contains(binding.kind()))
                .map(
                        binding ->
                                binding.id()
                                        + " "
                                        + binding.kind()
                                        + " "
                                        + binding.owner()
                                        + "#"
                                        + binding.name()
                                        + binding.descriptor()
                                        + " "
                                        + binding.evidenceComplete())
                .sorted()
                .toList();
    }

    private static final class SignatureLoader extends ClassLoader {
        private Class<?> load(byte[] bytes) {
            return defineClass("dep.Library", bytes, 0, bytes.length);
        }
    }
}
