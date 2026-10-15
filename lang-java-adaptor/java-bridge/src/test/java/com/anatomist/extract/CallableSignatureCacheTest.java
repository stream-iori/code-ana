package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class CallableSignatureCacheTest {
    @Test
    void declarationAndParameterFactsAreSharedWithoutChangingVarargsIds(@TempDir Path directory)
            throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "A.java",
                        "class A { <T extends Number> T f(T value, String... rest) { return value; } }");
        try (var model = new JavaModel(context)) {
            var source = context.units().getFirst().path();
            var method =
                    model.declarations(source).stream()
                            .filter(ref -> model.declaration(ref).symbol().name().equals("f"))
                            .findFirst()
                            .orElseThrow();
            var identities = new JavaIdentities(model);
            assertEquals(
                    "A#f(java.lang.Number,java.lang.String...)", identities.declaration(method));
            assertSame(identities.declaration(method), identities.declaration(method));
            var types = model.unit(source).types();
            var executable = types.get(model.declaration(method).symbol().declaredType());
            assertSame(executable, types.get(model.declaration(method).symbol().declaredType()));
            assertEquals(
                    "java.lang.String[]",
                    JavaTypeNames.erased(types, executable.parameters().get(1)));
            assertEquals("java.lang.Number", JavaTypeNames.erased(types, executable.returns()));
        }
    }

    @Test
    void cacheIsReleasedAfterFailureAndDoesNotLeakAcrossUnits(@TempDir Path directory)
            throws Exception {
        var first =
                JavaFactsTestSupport.analyze(
                        directory, "A.java", "class A { void f(int value) {} }");
        var failed = new JavaModel(first);
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try (failed) {
                        assertEquals(
                                "A#f(int)", identity(failed, first.units().getFirst().path(), "f"));
                        throw new IllegalArgumentException("abort extraction");
                    }
                });
        assertThrows(
                IllegalStateException.class,
                () -> failed.declarations(first.units().getFirst().path()));
        var renamed =
                JavaFactsTestSupport.analyze(
                        directory, "A.java", "class A { void renamed(int value) {} }");
        try (var model = new JavaModel(renamed)) {
            assertEquals(
                    "A#renamed(int)",
                    identity(model, renamed.units().getFirst().path(), "renamed"));
        }
        var other =
                JavaFactsTestSupport.analyze(
                        directory, "A.java", "class A { void f(String value) {} }");
        try (var model = new JavaModel(other)) {
            assertEquals(
                    "A#f(java.lang.String)", identity(model, other.units().getFirst().path(), "f"));
        }
    }

    private static String identity(JavaModel model, Path path, String name) {
        var ref =
                model.declarations(path).stream()
                        .filter(value -> model.declaration(value).symbol().name().equals(name))
                        .findFirst()
                        .orElseThrow();
        return new JavaIdentities(model).declaration(ref);
    }
}
