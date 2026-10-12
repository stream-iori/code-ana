package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.asmsolver.JavaSignatureTypes;
import com.anatomist.core.java.*;
import com.anatomist.framework.JavaModelExtensions;
import com.anatomist.framework.lombok.LombokJavaModelExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class LombokUtilityClassIT {
    private static final Path LOMBOK =
            Path.of(
                    System.getProperty("user.home"),
                    ".m2/repository/org/projectlombok/lombok/1.18.38/lombok-1.18.38.jar");

    @Test
    void explicitStaticUtilitySignaturesMatchActualProcessors(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("jdk" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Utility.java"),
                            """
                    package p;
                    @lombok.experimental.UtilityClass
                    public class Utility {
                        public static final String NAME = "utility";
                        private static java.util.Set<String> values;
                        public static <T> T identity(T input) { return input; }
                    }
                    """);
            var modeled = analyze(project, source, release, false);
            var actual = analyze(project, source, release, true);
            assertTrue(
                    actual.diagnostics().stream()
                            .noneMatch(diagnostic -> diagnostic.kind().equals("ERROR")),
                    actual.diagnostics().toString());
            assertTrue(actual.units().getFirst().hasProcessorEvidence());
            var expected =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            JavaSignatureTypes.project(actual.units(), release));
            var projected =
                    assertInstanceOf(
                            JavaSignatureTypes.Complete.class,
                            JavaSignatureTypes.project(modeled.units(), release));
            assertEquals(expected.types(), projected.types(), "JDK " + release);
            var unit = modeled.units().getFirst();
            assertEquals(
                    "complete",
                    ((Map<?, ?>)
                                    unit.extensions()
                                            .declarationMetadata()
                                            .get("p.Utility")
                                            .get("lombok"))
                            .get("coverage"));
            var constructor =
                    unit.declarationInventory().getFirst().members().stream()
                            .filter(member -> member.symbol().kind().equals("CONSTRUCTOR"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(JavaDeclaration.Origin.MODEL, constructor.origin());
            assertEquals(List.of("private"), constructor.symbol().modifiers());
            assertFalse(unit.extensions().generated().get("p.Utility#Utility()").bodyAvailable());
            assertTrue(Files.notExists(project.resolve("target")));
        }
    }

    @Test
    void instanceMembersExplicitConstructorsAndOtherGeneratorsRemainPartial(@TempDir Path directory)
            throws Exception {
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(directory.resolve("partial" + release));
            Path source =
                    Files.writeString(
                            project.resolve("Unsupported.java"),
                            """
                    @lombok.experimental.UtilityClass class Instance { int value; }
                    @lombok.experimental.UtilityClass class Explicit { Explicit() {} }
                    @lombok.experimental.UtilityClass @lombok.Getter class Combined { static int value; }
                    @lombok.experimental.UtilityClass class Generic<T> { static int value; }
                    """);
            var analysis = analyze(project, source, release, false);
            for (var declaration : analysis.units().getFirst().declarationInventory()) {
                assertEquals(
                        "partial",
                        ((Map<?, ?>)
                                        analysis.units()
                                                .getFirst()
                                                .extensions()
                                                .declarationMetadata()
                                                .get(declaration.canonicalName())
                                                .get("lombok"))
                                .get("coverage"));
                assertTrue(
                        declaration
                                .incompleteReasons()
                                .contains("lombok:incomplete-utility-class"));
                assertTrue(
                        declaration.members().stream()
                                .noneMatch(
                                        member -> member.origin() == JavaDeclaration.Origin.MODEL));
            }
            assertInstanceOf(
                    JavaSignatureTypes.Incomplete.class,
                    JavaSignatureTypes.project(analysis.units(), release));
        }
    }

    private static JavaAnalysisContext analyze(
            Path project, Path source, int release, boolean compile) throws Exception {
        assertTrue(Files.isRegularFile(LOMBOK));
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        var defaults = config.javacOptions();
        var options =
                new JavacOptions(
                        defaults.javaHome(),
                        true,
                        true,
                        compile,
                        false,
                        project.resolve(compile ? "compiler-cache" : "model-cache"));
        var backend =
                new JavacBackend(
                        JavacToolchain.resolve(options, release, null),
                        List.of(LOMBOK),
                        List.of(project),
                        options);
        backend.setDeclarationOverlay(
                new JavaModelExtensions(List.of(new LombokJavaModelExtension(project, false))));
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
        return contexts.getFirst();
    }
}
