package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaBinaryType;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class AsmTypeSolverTest {
    @Test
    void unknownNameReturnsEmpty() {
        try (var repository = repository(Map.of(), 4096)) {
            assertTrue(repository.find("does.not.Exist").isEmpty());
        }
    }

    @Test
    void sourceNestedNameResolvesBinaryNestedClass() {
        try (var repository =
                repository(
                        Map.of("com.x.Outer$Inner$Deep", miniClass("com.x.Outer$Inner$Deep")),
                        4096)) {
            assertEquals(
                    "com.x.Outer$Inner$Deep",
                    repository.find("com.x.Outer.Inner.Deep").orElseThrow().name());
        }
    }

    @Test
    void knownNameReturnsProvenClassName() {
        try (var repository = repository(Map.of("com.x.Foo", miniClass("com.x.Foo")), 4096)) {
            assertEquals("com.x.Foo", repository.find("com.x.Foo").orElseThrow().name());
        }
    }

    @Test
    void cachesDetachedDeclarations() {
        try (var repository = repository(Map.of("com.x.Foo", miniClass("com.x.Foo")), 4096)) {
            assertSame(
                    repository.find("com.x.Foo").orElseThrow(),
                    repository.find("com.x.Foo").orElseThrow());
        }
    }

    @Test
    void closeDropsCacheAndClosesSourceExactlyOnce() {
        var closes = new AtomicInteger();
        ClassFileSource source =
                new ClassFileSource() {
                    public Optional<byte[]> find(String name) {
                        return Optional.of(miniClass(name));
                    }

                    public Set<String> knownClasses() {
                        return Set.of("com.x.Foo");
                    }

                    public void close() {
                        closes.incrementAndGet();
                    }
                };
        var repository = new JavaBinaryRepository(source, 4096);
        assertTrue(repository.find("com.x.Foo").isPresent());
        repository.close();
        repository.close();
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> repository.find("com.x.Foo"));
    }

    @Test
    void declarationCacheIsBoundedAndEvictedEntriesReload() {
        try (var repository =
                repository(
                        Map.of("com.x.A", miniClass("com.x.A"), "com.x.B", miniClass("com.x.B")),
                        4096)) {
            var first = repository.find("com.x.A").orElseThrow();
            assertTrue(repository.find("com.x.B").isPresent());
            var reloaded = repository.find("com.x.A").orElseThrow();
            assertNotSame(first, reloaded);
            assertEquals(first, reloaded);
        }
    }

    @Test
    void detachedDeclarationsNeverRetainRawClassBytes() {
        try (var repository = repository(Map.of("com.x.Foo", miniClass("com.x.Foo")), 4096)) {
            var type = repository.find("com.x.Foo").orElseThrow();
            assertInstanceOf(JavaBinaryType.class, type);
            assertTrue(type.fields().isEmpty());
            assertTrue(type.methods().isEmpty());
            assertEquals("com.x.Foo", type.name());
            assertTrue(
                    java.util.Arrays.stream(JavaBinaryType.class.getRecordComponents())
                            .noneMatch(component -> component.getType() == byte[].class));
        }
    }

    @Test
    void rejectsNegativeBudgetAndZeroDisablesCaching() {
        assertThrows(IllegalArgumentException.class, () -> repository(Map.of(), -1));
        try (var repository = repository(Map.of("com.x.Foo", miniClass("com.x.Foo")), 0)) {
            assertNotSame(
                    repository.find("com.x.Foo").orElseThrow(),
                    repository.find("com.x.Foo").orElseThrow());
        }
    }

    @Test
    void exactBinaryNamesTakePrecedenceOverNestedAliases() {
        try (var repository =
                repository(
                        Map.of(
                                "com.x.Outer.Inner",
                                miniClass("com.x.Outer.Inner"),
                                "com.x.Outer$Inner",
                                miniClass("com.x.Outer$Inner")),
                        8192)) {
            assertEquals(
                    "com.x.Outer.Inner", repository.find("com.x.Outer.Inner").orElseThrow().name());
            assertEquals(
                    "com.x.Outer$Inner", repository.find("com.x.Outer$Inner").orElseThrow().name());
        }
    }

    @Test
    void repositoryDoesNotFallBackToHostClasses() {
        try (var repository = repository(Map.of(), 4096)) {
            assertTrue(repository.find("java.lang.String").isEmpty());
            assertTrue(repository.find(null).isEmpty());
            assertTrue(repository.find(" ").isEmpty());
        }
    }

    private static JavaBinaryRepository repository(Map<String, byte[]> classes, long budget) {
        return new JavaBinaryRepository(new InMemoryClassFileSource(classes), budget);
    }

    private static byte[] miniClass(String fqn) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC,
                fqn.replace('.', '/'),
                null,
                "java/lang/Object",
                null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
