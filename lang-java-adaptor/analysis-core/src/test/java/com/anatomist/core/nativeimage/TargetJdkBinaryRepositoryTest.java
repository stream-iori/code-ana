package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.asmsolver.JavaBinaryTypes;
import com.anatomist.core.asmsolver.JdkClassFileSource;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;

class TargetJdkBinaryRepositoryTest {
    @Test
    void unknownTypeIsAbsent() {
        try (var repository = repository()) {
            assertTrue(repository.find("missing.Unknown").isEmpty());
        }
    }

    @Test
    void selectedPlatformTypeIsPresent() {
        try (var repository = repository()) {
            assertEquals(
                    "java.lang.Object", repository.find("java.lang.Object").orElseThrow().name());
        }
    }

    @Test
    void immutableHeadersAreCachedWithinOwnedRepository() {
        try (var repository = repository()) {
            assertSame(
                    repository.find("java.lang.Object").orElseThrow(),
                    repository.find("java.lang.Object").orElseThrow());
        }
    }

    @Test
    void sourceNestedNameUsesSamePhysicalHeader() {
        try (var repository = repository()) {
            var source = repository.find("java.util.Map.Entry").orElseThrow();
            assertSame(source, repository.find("java.util.Map$Entry").orElseThrow());
            assertEquals("java.util.Map$Entry", source.name());
        }
    }

    @Test
    void selectedReleaseCannotUseHostHeaders() {
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new JdkClassFileSource(
                                                Path.of(System.getProperty("java.home")), 8))
                        .getMessage()
                        .contains("JDK_HOME_RELEASE_MISMATCH"));
    }

    @Test
    void varargsAndEnumFlagsComeFromPhysicalClassFiles() {
        try (var repository = repository()) {
            var arrays = repository.find("java.util.Arrays").orElseThrow();
            var method =
                    arrays.methods().stream()
                            .filter(value -> value.name().equals("asList"))
                            .findFirst()
                            .orElseThrow();
            assertNotEquals(0, method.access() & Opcodes.ACC_VARARGS);
            var shape = JavaBinaryTypes.method(arrays, method);
            assertEquals(1, shape.types().get(shape.executable()).parameters().size());
            var time = repository.find("java.util.concurrent.TimeUnit").orElseThrow();
            assertNotEquals(0, time.access() & Opcodes.ACC_ENUM);
            assertTrue(
                    time.fields().stream()
                            .anyMatch(
                                    value ->
                                            value.name().equals("SECONDS")
                                                    && (value.access() & Opcodes.ACC_ENUM) != 0));
        }
    }

    private static JavaBinaryRepository repository() {
        return new JavaBinaryRepository(
                new JdkClassFileSource(Path.of(System.getProperty("java.home")), 25),
                4L * 1024 * 1024);
    }
}
