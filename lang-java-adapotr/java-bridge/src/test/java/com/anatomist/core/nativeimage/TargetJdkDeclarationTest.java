package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.asmsolver.JdkClassFileSource;
import com.anatomist.core.java.JavaBinaryType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class TargetJdkDeclarationTest {
    @Test
    void namedLookupRetainsOverloadOrderAndImmutableOwnership() {
        var value = type("java.lang.String");
        var methods =
                value.methods().stream()
                        .filter(method -> method.name().equals("substring"))
                        .toList();
        assertEquals(
                List.of("(I)Ljava/lang/String;", "(II)Ljava/lang/String;"),
                methods.stream().map(JavaBinaryType.Method::descriptor).toList());
        assertThrows(UnsupportedOperationException.class, () -> value.methods().clear());
    }

    @Test
    void identityUsesPhysicalClassName() {
        assertEquals("java.lang.String", type("java.lang.String").name());
    }

    @Test
    void classFlagsArePreserved() {
        assertEquals(0, type("java.lang.String").access() & Opcodes.ACC_INTERFACE);
    }

    @Test
    void interfaceFlagsArePreserved() {
        assertNotEquals(0, type("java.lang.CharSequence").access() & Opcodes.ACC_INTERFACE);
    }

    @Test
    void superclassComesFromPhysicalHeader() {
        assertEquals("java.lang.Object", type("java.lang.String").superclass());
    }

    @Test
    void interfacesArePresent() {
        assertTrue(
                type("java.lang.String")
                        .interfaces()
                        .containsAll(List.of("java.lang.CharSequence", "java.io.Serializable")));
    }

    @Test
    void completeAncestorHeadersRemainAvailableWithinRepository() {
        try (var repository = repository()) {
            var pending = new ArrayDeque<String>();
            pending.add("java.lang.String");
            Set<String> visited = new HashSet<>();
            while (!pending.isEmpty()) {
                var name = pending.removeFirst();
                if (!visited.add(name)) continue;
                assertTrue(visited.size() < 100);
                var value = repository.find(name).orElseThrow();
                if (value.superclass() != null) pending.add(value.superclass());
                pending.addAll(value.interfaces());
            }
            assertTrue(
                    visited.containsAll(
                            Set.of(
                                    "java.lang.Object",
                                    "java.lang.CharSequence",
                                    "java.io.Serializable")));
        }
    }

    @Test
    void declaredMethodsKeepOverloadedDescriptors() {
        var value = type("java.lang.String");
        assertTrue(
                value.methods().stream()
                        .anyMatch(
                                method ->
                                        method.name().equals("length")
                                                && method.descriptor().equals("()I")));
        assertTrue(
                value.methods().stream()
                        .anyMatch(
                                method ->
                                        method.name().equals("charAt")
                                                && method.descriptor().equals("(I)C")));
        assertEquals(
                2,
                value.methods().stream()
                        .filter(method -> method.name().equals("substring"))
                        .count());
    }

    @Test
    void fieldTypeComesFromGenericDescriptor() {
        var value = type("java.lang.String");
        var field =
                value.fields().stream()
                        .filter(member -> member.name().equals("CASE_INSENSITIVE_ORDER"))
                        .findFirst()
                        .orElseThrow();
        assertNotEquals(0, field.access() & Opcodes.ACC_STATIC);
        assertEquals(
                "java.util.Comparator",
                org.objectweb.asm.Type.getType(field.descriptor()).getClassName());
        assertEquals("Ljava/util/Comparator<Ljava/lang/String;>;", field.signature());
    }

    @Test
    void compilerAcceptsSelfAssignment(@TempDir Path root) throws Exception {
        assertFalse(hasError(root, "class A { String run(String value) { return value; } }"));
    }

    @Test
    void compilerAcceptsImplementationAsInterface(@TempDir Path root) throws Exception {
        assertFalse(hasError(root, "class A { CharSequence run(String value) { return value; } }"));
    }

    @Test
    void compilerRejectsReverseInterfaceAssignment(@TempDir Path root) throws Exception {
        assertTrue(hasError(root, "class A { String run(CharSequence value) { return value; } }"));
    }

    private static boolean hasError(Path root, String source) throws Exception {
        return JavaFactsTestSupport.analyze(root, "A.java", source).diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.kind().equals("ERROR"));
    }

    private static JavaBinaryType type(String name) {
        try (var repository = repository()) {
            return repository.find(name).orElseThrow();
        }
    }

    private static JavaBinaryRepository repository() {
        return new JavaBinaryRepository(
                new JdkClassFileSource(Path.of(System.getProperty("java.home")), 25),
                4L * 1024 * 1024);
    }
}
