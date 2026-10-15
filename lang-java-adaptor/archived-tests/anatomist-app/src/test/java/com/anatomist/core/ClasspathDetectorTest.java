package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

class ClasspathDetectorTest {

    @TempDir Path cacheRoot;

    @BeforeEach
    void isolateClasspathCache() {
        System.setProperty("anatomist.classpath.cache.dir", cacheRoot.toString());
    }

    @AfterEach
    void restoreClasspathCache() {
        System.clearProperty("anatomist.classpath.cache.dir");
    }

    @Test
    void detect_returnsEmptyAndWarnsWhenMvnUnavailable(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        ClasspathDetector det =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) throws IOException {
                        throw new IOException("mvn not found on PATH");
                    }
                };

        ClasspathDetectionResult result = det.detectResult(tmp);
        assertTrue(result.entries().isEmpty(), "expected empty classpath; got " + result.entries());
        assertEquals(ClasspathDetectionResult.Status.UNAVAILABLE, result.status());
        assertEquals("CLASSPATH_UNAVAILABLE", result.diagnostics().get(0).code());
        assertEquals("warning", result.diagnostics().get(0).severity());
        assertTrue(result.diagnostics().get(0).sample().contains("mvn"));
    }

    @Test
    void detect_returnsEmptyForNonMavenProject(@TempDir Path tmp) {
        ClasspathDetector det =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) {
                        throw new AssertionError(
                                "runMvn must not be invoked for non-Maven project");
                    }
                };
        assertTrue(det.detect(tmp).isEmpty());
    }

    @Test
    void detectSourcePaths_returnsSrcMainJavaForMavenProject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path src = Files.createDirectories(tmp.resolve("src/main/java"));

        List<Path> paths = new ClasspathDetector().detectSourcePaths(tmp);
        assertEquals(List.of(src), paths);
    }

    @Test
    void detectSourcePaths_collectsAllModuleRootsForMultiModuleReactor(@TempDir Path tmp)
            throws Exception {
        // Root reactor pom with no src/main/java of its own.
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path a = Files.createDirectories(tmp.resolve("app/api/src/main/java"));
        Path b = Files.createDirectories(tmp.resolve("app/core/service/src/main/java"));
        // Noise that must be excluded.
        Files.createDirectories(tmp.resolve("app/api/target/classes/src/main/java"));
        Files.createDirectories(tmp.resolve("app/api/src/test/java"));

        List<Path> paths = new ClasspathDetector().detectSourcePaths(tmp);
        assertEquals(List.of(a, b), paths);
    }

    @Test
    void detectSourcePaths_includesGeneratedSourcesRoots(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(tmp.resolve("app/core/src/main/java"));
        Path generated =
                Files.createDirectories(
                        tmp.resolve("app/core/target/generated-sources/annotations"));
        Path pkg = Files.createDirectories(generated.resolve("com/example"));
        Files.writeString(
                pkg.resolve("GeneratedType.java"), "package com.example; class GeneratedType {}");

        List<Path> paths = new ClasspathDetector().detectSourcePaths(tmp);
        assertTrue(paths.contains(main), "expected main source root; got " + paths);
        assertTrue(paths.contains(generated), "expected generated source root; got " + paths);
    }

    @Test
    void detectBuildOutputClasspath_collectsTargetClasses(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("app/core/target/classes"));
        Path testClasses = Files.createDirectories(tmp.resolve("app/core/target/test-classes"));

        List<Path> paths = new ClasspathDetector().detectBuildOutputClasspath(tmp);
        assertTrue(paths.contains(classes), "expected target/classes; got " + paths);
        assertTrue(paths.contains(testClasses), "expected target/test-classes; got " + paths);
    }

    @Test
    void detectSourcePaths_includesTestRootsWhenRequested(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(tmp.resolve("app/core/src/main/java"));
        Path test = Files.createDirectories(tmp.resolve("app/core/src/test/java"));
        // A test-only module (no src/main/java) — must still be picked up.
        Path testOnly = Files.createDirectories(tmp.resolve("app/test/src/test/java"));

        List<Path> withTests = new ClasspathDetector().detectSourcePaths(tmp, true);
        assertTrue(withTests.contains(main), "expected main root; got " + withTests);
        assertTrue(withTests.contains(test), "expected test root; got " + withTests);
        assertTrue(
                withTests.contains(testOnly), "expected test-only module root; got " + withTests);

        List<Path> withoutTests = new ClasspathDetector().detectSourcePaths(tmp, false);
        assertEquals(List.of(main), withoutTests);
    }

    @Test
    void detectSourcePaths_singleModuleIncludesTestRoot(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path main = Files.createDirectories(tmp.resolve("src/main/java"));
        Path test = Files.createDirectories(tmp.resolve("src/test/java"));

        assertEquals(List.of(main), new ClasspathDetector().detectSourcePaths(tmp, false));
        assertEquals(List.of(main, test), new ClasspathDetector().detectSourcePaths(tmp, true));
    }

    @Test
    void detect_unionsGoodModulesWhenOneModuleFails(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path good = Files.createDirectories(tmp.resolve("app/core"));
        Files.createDirectories(tmp.resolve("app/test"));

        ClasspathDetector det =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) throws IOException {
                        // Reactor with -fae: the good module writes its file; the bad one
                        // (unresolvable system dep) writes nothing, and mvn exits non-zero.
                        String rel = extractOutputFile(args).toString();
                        Files.writeString(
                                good.resolve(rel),
                                "/lib/shared.jar" + File.pathSeparator + "/lib/core.jar");
                        return 1; // overall reactor failure
                    }
                };

        ClasspathDetectionResult result = det.detectResult(tmp);
        List<String> cp = result.entries();
        // Despite exit 1, the good module's classpath survives.
        assertEquals(List.of("/lib/shared.jar", "/lib/core.jar"), cp);
        assertEquals(ClasspathDetectionResult.Status.PARTIAL, result.status());
        assertEquals(1, result.mavenExitCode());
        assertEquals("CLASSPATH_PARTIAL", result.diagnostics().get(0).code());
        assertEquals("warning", result.diagnostics().get(0).severity());
    }

    @Test
    void detect_parsesClasspathFromMockedMvnOutput(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        String mockOutput = "/lib/a.jar" + File.pathSeparator + "/lib/b.jar";

        ClasspathDetector det =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) throws IOException {
                        // Mimic Maven writing the relative outputFile into the module basedir.
                        Path out = workingDir.resolve(extractOutputFile(args));
                        Files.writeString(out, mockOutput);
                        return 0;
                    }
                };

        List<String> cp = det.detect(tmp);
        assertEquals(List.of("/lib/a.jar", "/lib/b.jar"), cp);
    }

    @Test
    void detect_unionsAndDedupesAcrossReactorModules(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path modA = Files.createDirectories(tmp.resolve("app/core"));
        Path modB = Files.createDirectories(tmp.resolve("app/web"));

        ClasspathDetector det =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) throws IOException {
                        // Simulate the reactor: each module writes its own relative file.
                        String rel = extractOutputFile(args).toString();
                        Files.writeString(
                                tmp.resolve(rel),
                                "/lib/shared.jar" + File.pathSeparator + "/lib/a.jar");
                        Files.writeString(
                                modA.resolve(rel),
                                "/lib/shared.jar" + File.pathSeparator + "/lib/core.jar");
                        Files.writeString(
                                modB.resolve(rel),
                                "/lib/shared.jar" + File.pathSeparator + "/lib/web.jar");
                        return 0;
                    }
                };

        List<String> cp = det.detect(tmp);
        // shared.jar appears once; union covers every module; no leftover files.
        assertEquals(List.of("/lib/shared.jar", "/lib/a.jar", "/lib/core.jar", "/lib/web.jar"), cp);
        assertEquals(
                0,
                java.nio.file.Files.walk(tmp)
                        .filter(p -> p.getFileName().toString().equals("anatomist-classpath.txt"))
                        .count(),
                "generated classpath files must be cleaned up");
    }

    @Test
    void selectMavenJavaHome_prefersExplicitOverride(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>8</maven.compiler.source>"
                        + "</properties></project>");
        Path explicit = fakeJdk(tmp.resolve("explicit"), false);

        ClasspathDetector detector =
                detectorWithEnvironment(
                        Map.of(ClasspathDetector.MAVEN_JAVA_HOME_ENV, explicit.toString()), tmp);

        assertEquals(explicit.toAbsolutePath().normalize(), detector.selectMavenJavaHome(tmp));
    }

    @Test
    void selectMavenJavaHome_usesJava8HomeForLegacyProject(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>1.8</maven.compiler.source>"
                        + "</properties></project>");
        Path current = fakeJdk(tmp.resolve("current"), false);
        Path java8 = fakeJdk(tmp.resolve("java8"), true);

        ClasspathDetector detector =
                new ClasspathDetector() {
                    @Override
                    protected String environment(String name) {
                        return "JAVA8_HOME".equals(name) ? java8.toString() : null;
                    }

                    @Override
                    protected String systemProperty(String name) {
                        return switch (name) {
                            case "java.home" -> current.toString();
                            case "user.home" -> tmp.resolve("no-sdkman").toString();
                            default -> null;
                        };
                    }

                    @Override
                    protected String discoverMacJava8Home() {
                        return null;
                    }
                };

        assertEquals(java8.toAbsolutePath().normalize(), detector.selectMavenJavaHome(tmp));
    }

    @Test
    void selectMavenJavaHome_usesJava8WhenAnyModuleReferencesToolsJar(@TempDir Path tmp)
            throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>17</maven.compiler.source>"
                        + "</properties><dependencies><dependency>"
                        + "<artifactId>jdk.tools</artifactId>"
                        + "<systemPath>${java.home}/../lib/tools.jar</systemPath>"
                        + "</dependency></dependencies></project>");
        Path java8 = fakeJdk(tmp.resolve("java8"), true);

        ClasspathDetector detector =
                new ClasspathDetector() {
                    @Override
                    protected String environment(String name) {
                        return "JAVA8_HOME".equals(name) ? java8.toString() : null;
                    }

                    @Override
                    protected String systemProperty(String name) {
                        return switch (name) {
                            case "java.home" -> tmp.resolve("jdk25").toString();
                            case "user.home" -> tmp.resolve("no-sdkman").toString();
                            default -> null;
                        };
                    }

                    @Override
                    protected String discoverMacJava8Home() {
                        return null;
                    }
                };

        assertEquals(java8.toAbsolutePath().normalize(), detector.selectMavenJavaHome(tmp));
    }

    @Test
    void detect_drainsLargeMavenOutputAndReportsTail(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path script = tmp.resolve("fake-mvn.sh");
        Files.writeString(
                script,
                "#!/bin/sh\ni=0\nwhile [ $i -lt 7000 ]; do echo 0123456789; i=$((i+1)); done\necho FINAL-MAVEN-ERROR\nexit 3\n");
        assertTrue(script.toFile().setExecutable(true));

        ClasspathDetector detector =
                new ClasspathDetector() {
                    @Override
                    protected String mavenExecutable() {
                        return script.toString();
                    }
                };

        ClasspathDetectionResult result = detector.detectResult(tmp);
        assertTrue(result.entries().isEmpty());
        assertEquals(ClasspathDetectionResult.Status.UNAVAILABLE, result.status());
        assertEquals("CLASSPATH_UNAVAILABLE", result.diagnostics().get(0).code());
        assertTrue(result.errorSample().contains("FINAL-MAVEN-ERROR"), result.errorSample());
    }

    @Test
    void detect_retriesInheritedToolsJarFailureWithJava8(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>17</maven.compiler.source>"
                        + "</properties></project>");
        Path java8 = fakeJdk(tmp.resolve("java8"), true);
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();

        ClasspathDetector detector =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) throws IOException {
                        if (attempts.getAndIncrement() == 0) {
                            setLastMavenOutput(
                                    "Could not find jdk.tools:jdk.tools at ../lib/tools.jar");
                            return 1;
                        }
                        Files.writeString(
                                workingDir.resolve(extractOutputFile(args)), "/lib/resolved.jar");
                        return 0;
                    }

                    @Override
                    protected String environment(String name) {
                        return "JAVA8_HOME".equals(name) ? java8.toString() : null;
                    }

                    @Override
                    protected String systemProperty(String name) {
                        return switch (name) {
                            case "java.home" -> tmp.resolve("jdk25").toString();
                            case "user.home" -> tmp.resolve("no-sdkman").toString();
                            default -> null;
                        };
                    }

                    @Override
                    protected String discoverMacJava8Home() {
                        return null;
                    }
                };

        assertEquals(List.of("/lib/resolved.jar"), detector.detect(tmp));
        assertEquals(2, attempts.get());
    }

    @Test
    void detect_reusesPomFingerprintCache(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Path jar = Files.write(tmp.resolve("dep.jar"), new byte[] {1});
        Path mainOutput = Files.createDirectories(tmp.resolve("core/target/classes"));
        Path testOutput = Files.createDirectories(tmp.resolve("core/target/test-classes"));
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        ClasspathDetector first =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) throws IOException {
                        attempts.incrementAndGet();
                        Files.writeString(
                                workingDir.resolve(extractOutputFile(args)), jar.toString());
                        return 0;
                    }
                };
        assertEquals(
                List.of(jar.toString(), mainOutput.toString(), testOutput.toString()),
                first.detect(tmp));

        ClasspathDetector second =
                new ClasspathDetector() {
                    @Override
                    protected int runMvn(Path workingDir, List<String> args) {
                        throw new AssertionError("cache hit must not invoke Maven");
                    }
                };
        ClasspathDetectionResult cached = second.detectResult(tmp);
        assertEquals(
                List.of(jar.toString(), mainOutput.toString(), testOutput.toString()),
                cached.entries());
        assertEquals(ClasspathDetectionResult.Status.CACHE_HIT, cached.status());
        assertEquals(0, cached.mavenClasspathFiles());
        assertEquals(2, cached.buildOutputEntries());
        assertEquals(1, attempts.get());
    }

    @Test
    void classpathInputFingerprintChangesWhenPomChanges(@TempDir Path tmp) throws Exception {
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><version>1</version></project>");
        ClasspathDetector detector = new ClasspathDetector();
        String before = detector.classpathInputFingerprint(tmp);

        long previousModified = Files.getLastModifiedTime(pom).toMillis();
        Files.writeString(pom, "<project><version>2</version></project>");
        // Coarse filesystem clocks can otherwise give both writes identical size and mtime.
        Files.setLastModifiedTime(
                pom, java.nio.file.attribute.FileTime.fromMillis(previousModified + 2000));

        assertNotEquals(before, detector.classpathInputFingerprint(tmp));
    }

    @Test
    void classpathInputFingerprintFollowsDeclaredModulesButIgnoresTargetPoms(@TempDir Path tmp)
            throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><modules><module>api</module></modules></project>");
        Path module = Files.createDirectories(tmp.resolve("api"));
        Files.writeString(module.resolve("pom.xml"), "<project><version>1</version></project>");
        Path target = Files.createDirectories(tmp.resolve("target/fake"));
        Files.writeString(target.resolve("pom.xml"), "<project><version>1</version></project>");
        ClasspathDetector detector = new ClasspathDetector();
        String before = detector.classpathInputFingerprint(tmp);

        long modified = Files.getLastModifiedTime(module.resolve("pom.xml")).toMillis() + 2000;
        Files.writeString(target.resolve("pom.xml"), "<project><version>2</version></project>");
        Files.setLastModifiedTime(
                target.resolve("pom.xml"), java.nio.file.attribute.FileTime.fromMillis(modified));
        assertEquals(before, detector.classpathInputFingerprint(tmp));

        Files.writeString(module.resolve("pom.xml"), "<project><version>2</version></project>");
        Files.setLastModifiedTime(
                module.resolve("pom.xml"), java.nio.file.attribute.FileTime.fromMillis(modified));
        assertNotEquals(before, detector.classpathInputFingerprint(tmp));
    }

    private static ClasspathDetector detectorWithEnvironment(
            Map<String, String> env, Path userHome) {
        return new ClasspathDetector() {
            @Override
            protected String environment(String name) {
                return env.get(name);
            }

            @Override
            protected String systemProperty(String name) {
                return "user.home".equals(name) ? userHome.toString() : null;
            }

            @Override
            protected String discoverMacJava8Home() {
                return null;
            }
        };
    }

    private static Path fakeJdk(Path home, boolean toolsJar) throws IOException {
        Path java = Files.createDirectories(home.resolve("bin")).resolve("java");
        Files.writeString(java, "");
        assertTrue(java.toFile().setExecutable(true));
        if (toolsJar) {
            Files.createDirectories(home.resolve("lib"));
            Files.writeString(home.resolve("lib/tools.jar"), "");
        }
        return home;
    }

    private static Path extractOutputFile(List<String> args) {
        for (String a : args) {
            if (a.startsWith("-Dmdep.outputFile=")) {
                return Path.of(a.substring("-Dmdep.outputFile=".length()));
            }
        }
        throw new AssertionError("missing -Dmdep.outputFile arg");
    }

    @Test
    void detectJavaVersion_readsMavenCompilerSource(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>17</maven.compiler.source>"
                        + "</properties></project>");
        assertEquals(OptionalInt.of(17), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_fallsBackToJavaVersionProperty(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><properties>"
                        + "<java.version>11</java.version>"
                        + "</properties></project>");
        assertEquals(OptionalInt.of(11), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_multiModuleReturnsMax(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><modules><module>a</module><module>b</module></modules></project>");
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        Files.writeString(
                a.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>11</maven.compiler.source>"
                        + "</properties></project>");
        Files.writeString(
                b.resolve("pom.xml"),
                "<project><properties>"
                        + "<maven.compiler.source>17</maven.compiler.source>"
                        + "</properties></project>");
        assertEquals(OptionalInt.of(17), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_noPomReturnsEmpty(@TempDir Path tmp) {
        assertEquals(OptionalInt.empty(), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_readsReleaseAndPluginPropertyReference(@TempDir Path tmp)
            throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                """
                <project>
                  <properties>
                    <java.level>17</java.level>
                    <maven.compiler.release>${java.level}</maven.compiler.release>
                  </properties>
                  <build><plugins><plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration><release>${maven.compiler.release}</release></configuration>
                  </plugin></plugins></build>
                </project>
                """);

        JavaVersionDetection result = new ClasspathDetector().detectJavaVersionDetailed(tmp);

        assertEquals(17, result.version());
        assertEquals(JavaVersionDetection.Source.MAVEN, result.source());
        assertTrue(result.evidenceExpression().contains("release"));
    }

    @Test
    void detectJavaVersion_inheritsLocalParentProperties(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("pom.xml"),
                """
                <project>
                  <properties><java.level>11</java.level></properties>
                  <modules><module>child</module></modules>
                </project>
                """);
        Path child = Files.createDirectories(tmp.resolve("child"));
        Files.writeString(
                child.resolve("pom.xml"),
                """
                <project>
                  <parent><relativePath>../pom.xml</relativePath></parent>
                  <properties><maven.compiler.release>${java.level}</maven.compiler.release></properties>
                </project>
                """);

        assertEquals(OptionalInt.of(11), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_readsGradleGroovyToolchain(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("build.gradle"),
                """
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(17)
                    }
                }
                """);

        JavaVersionDetection result = new ClasspathDetector().detectJavaVersionDetailed(tmp);

        assertEquals(17, result.version());
        assertEquals(JavaVersionDetection.Source.GRADLE, result.source());
    }

    @Test
    void detectJavaVersion_readsGradleKotlinProperty(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("gradle.properties"), "javaTarget=11\n");
        Files.writeString(
                tmp.resolve("build.gradle.kts"),
                """
                java {
                    toolchain {
                        languageVersion.set(JavaLanguageVersion.of(property("javaTarget")))
                    }
                }
                """);

        assertEquals(OptionalInt.of(11), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    void detectJavaVersion_readsAssignmentAfterWhitespaceOnlyLines(@TempDir Path tmp)
            throws Exception {
        Files.writeString(
                tmp.resolve("build.gradle"), "\n".repeat(2_000) + "\t  options.release = 17\n");

        assertEquals(OptionalInt.of(17), new ClasspathDetector().detectJavaVersion(tmp));
    }

    @Test
    @Tag("regex-performance")
    void detectJavaVersion_largeWhitespaceGradleFileIsBounded(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("build.gradle"),
                "\n".repeat(100_000) + "sourceCompatibility = JavaVersion.VERSION_17\n");

        assertTimeout(
                Duration.ofSeconds(3),
                () ->
                        assertEquals(
                                OptionalInt.of(17),
                                new ClasspathDetector().detectJavaVersion(tmp)));
    }

    @Test
    void detectJavaVersion_reportsDynamicGradleExpression(@TempDir Path tmp) throws Exception {
        Files.writeString(
                tmp.resolve("build.gradle"),
                """
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(computeVersion())
                    }
                }
                """);

        JavaVersionDetection result = new ClasspathDetector().detectJavaVersionDetailed(tmp);

        assertFalse(result.found());
        assertTrue(
                result.diagnostics().stream()
                        .anyMatch(
                                diagnostic -> "JAVA_VERSION_AMBIGUOUS".equals(diagnostic.code())));
    }
}
