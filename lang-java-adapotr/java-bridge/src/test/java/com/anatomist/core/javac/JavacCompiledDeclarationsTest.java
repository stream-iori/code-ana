package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.ContentHash;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

class JavacCompiledDeclarationsTest {
    @Test
    void mergesVerifiedStructureWithoutLosingSourceEvidence(@TempDir Path directory)
            throws Exception {
        var field =
                declaration(
                        "FIELD", "Sample", "value", "Ljava/lang/String;", "", List.of(), List.of());
        var sourceAnnotation =
                new JavaAnnotation("LSourceOnly;", JavaAnnotation.Retention.SOURCE, Map.of());
        var type =
                declaration(
                        "CLASS",
                        "",
                        "Sample",
                        "LSample;",
                        "Sample",
                        List.of(sourceAnnotation),
                        List.of(field));
        var unit =
                new JavaUnit(
                        directory.resolve("Sample.java"),
                        new JavaNode(0, "COMPILATION_UNIT", 0, 20, Map.of()),
                        JavaProcessingState.LOMBOK,
                        Map.of(),
                        new JavaTypes(List.of()),
                        false,
                        List.of(type),
                        "source-hash");
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path file = classes.resolve("Sample.class");
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(
                org.objectweb.asm.Opcodes.V1_8,
                org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "Sample",
                null,
                "java/lang/Object",
                null);
        writer.visitAnnotation("LBinary;", true).visitEnd();
        writer.visitField(
                        org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL,
                        "value",
                        "Ljava/lang/String;",
                        null,
                        "compiled")
                .visitEnd();
        writer.visitEnd();
        Files.write(file, writer.toByteArray());
        Path manifest = directory.resolve("manifest.txt");
        Files.writeString(manifest, ContentHash.sha256(file) + " classes/Sample.class\n");
        var merged = JavacCompiledDeclarations.merge(directory, List.of(unit)).getFirst();
        var actual = merged.declarationInventory().getFirst();
        assertEquals(type.span(), actual.span());
        assertEquals(JavaDeclaration.Origin.SOURCE, actual.origin());
        assertEquals(sourceAnnotation, actual.annotations().getFirst());
        assertEquals(2, actual.annotations().size());
        assertNotNull(actual.members().getFirst().constant());
        assertEquals(unit.tree(), merged.tree());
        assertEquals(unit.sourceHash(), merged.sourceHash());
        Files.writeString(file, "corrupt");
        assertThrows(
                IOException.class, () -> JavacCompiledDeclarations.merge(directory, List.of(unit)));
        Files.writeString(manifest, ContentHash.sha256(file) + " classes/Sample.class\n");
        assertThrows(
                IOException.class, () -> JavacCompiledDeclarations.merge(directory, List.of(unit)));
        Files.writeString(manifest, "");
        assertThrows(
                IOException.class, () -> JavacCompiledDeclarations.merge(directory, List.of(unit)));
    }

    private static JavaDeclaration declaration(
            String kind,
            String owner,
            String name,
            String descriptor,
            String binary,
            List<JavaAnnotation> annotations,
            List<JavaDeclaration> members) {
        return new JavaDeclaration(
                new JavaSymbol(kind, owner, name, descriptor, -1, List.of(), false),
                name,
                binary,
                "",
                new SourceSpan(0, 20),
                JavaDeclaration.Origin.SOURCE,
                -1,
                List.of(),
                List.of(),
                annotations,
                null,
                null,
                members,
                List.of());
    }

    @Test
    void verifiesManifestAndRejectsUntrustedOutputs(@TempDir Path directory) throws Exception {
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path file = Files.writeString(classes.resolve("Noise.class"), "not a class");
        Path manifest = directory.resolve("manifest.txt");
        Files.writeString(manifest, "invalid");
        assertThrows(
                IOException.class, () -> JavacCompiledDeclarations.merge(directory, List.of()));
        Files.writeString(manifest, "0".repeat(64) + " classes/Missing.class\n");
        assertThrows(
                IOException.class, () -> JavacCompiledDeclarations.merge(directory, List.of()));
        Files.writeString(manifest, ContentHash.sha256(file) + " classes/Noise.class\n");
        assertEquals(List.of(), JavacCompiledDeclarations.merge(directory, List.of()));
        Files.writeString(
                manifest,
                ContentHash.sha256(file)
                        + " classes/Noise.class\n"
                        + ContentHash.sha256(file)
                        + " classes/Noise.class\n");
        assertThrows(
                IOException.class, () -> JavacCompiledDeclarations.merge(directory, List.of()));
    }
}
