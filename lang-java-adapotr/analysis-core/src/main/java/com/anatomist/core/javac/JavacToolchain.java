package com.anatomist.core.javac;

import com.anatomist.core.JavaVersionException;
import com.anatomist.core.nativeimage.JdkTypeCatalogBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Validated, content-addressed target compiler; never resolves an environment fallback. */
public record JavacToolchain(Path home, int release, String fingerprint) {
    public static JavacToolchain resolve(JavacOptions config, int release, Path override) {
        if (release != 8 && release != 17 && release != 25)
            throw invalid("JAVAC_VERSION_UNSUPPORTED", "supported versions are 8, 17, 25");
        if (!config.toolchainExecution())
            throw invalid(
                    "JAVAC_NATIVE_UNSUPPORTED",
                    "embedded javac has not passed production acceptance; select javac_execution=toolchain");
        if (!config.vmClasspath())
            throw invalid("JAVAC_PLATFORM_REQUIRED", "javac requires index.vm_classpath=true");
        Path path = override != null ? override : config.javaHome();
        if (path == null)
            throw invalid("JAVAC_JDK_HOME_REQUIRED", "configure index.java_home or use --jdk-home");
        if (!path.isAbsolute())
            throw invalid("JDK_HOME_INVALID", "java home must be absolute: " + path);
        try {
            Path home = path.toRealPath();
            int actual = JdkTypeCatalogBuilder.releaseOf(home);
            if (actual != release)
                throw invalid(
                        "JDK_HOME_RELEASE_MISMATCH",
                        "expected Java " + release + " but " + home + " is Java " + actual);
            for (String executable : List.of("bin/java", "bin/javac"))
                if (!Files.isRegularFile(home.resolve(executable))
                        || !Files.isExecutable(home.resolve(executable)))
                    throw invalid(
                            "JDK_HOME_INVALID", "missing executable " + executable + " in " + home);
            List<Path> inputs =
                    new ArrayList<>(
                            List.of(
                                    home.resolve("release"),
                                    home.resolve("bin/java"),
                                    home.resolve("bin/javac")));
            if (release == 8) {
                require(home.resolve("lib/tools.jar"));
                require(home.resolve("jre/lib/rt.jar"));
                try (var paths = Files.walk(home.resolve("jre/lib"))) {
                    inputs.addAll(
                            paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList());
                }
                inputs.add(home.resolve("lib/tools.jar"));
            } else {
                require(home.resolve("lib/modules"));
                require(home.resolve("lib/ct.sym"));
                require(home.resolve("jmods/jdk.compiler.jmod"));
                inputs.addAll(List.of(home.resolve("lib/modules"), home.resolve("lib/ct.sym")));
                try (var paths = Files.list(home.resolve("jmods"))) {
                    inputs.addAll(
                            paths.filter(p -> p.toString().endsWith(".jmod")).sorted().toList());
                }
            }
            return new JavacToolchain(home, release, digest(home, inputs));
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid("JDK_HOME_INVALID", path + " (" + failure.getMessage() + ")");
        }
    }

    private static void require(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing " + path);
    }

    private static String digest(Path home, List<Path> inputs) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (Path input : inputs) {
                digest.update(
                        home.relativize(input)
                                .toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try (var stream = Files.newInputStream(input)) {
                    for (int count; (count = stream.read(buffer)) >= 0; )
                        digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JavaVersionException invalid(String code, String detail) {
        return new JavaVersionException(2, code + ": " + detail);
    }
}
