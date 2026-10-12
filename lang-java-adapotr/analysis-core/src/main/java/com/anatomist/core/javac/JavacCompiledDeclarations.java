package com.anatomist.core.javac;

import com.anatomist.core.ContentHash;
import com.anatomist.core.asmsolver.JavaBinaryReader;
import com.anatomist.core.java.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Reconciles compiler declarations with verified output, preserving source ownership and evidence.
 */
final class JavacCompiledDeclarations {
    private static final long MAX_CLASS_BYTES = 32L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

    record Artifact(Path path, String sha256) {}

    private JavacCompiledDeclarations() {}

    static List<JavaUnit> merge(Path compilation, List<JavaUnit> units) throws IOException {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        long bytes = 0;
        for (String line : Files.readAllLines(compilation.resolve("manifest.txt"))) {
            if (line.length() < 66 || line.charAt(64) != ' ')
                throw new IOException("invalid compilation manifest");
            Path relative = Path.of(line.substring(65));
            if (!relative.startsWith("classes") || !relative.toString().endsWith(".class"))
                continue;
            Path file = compilation.resolve(relative).normalize();
            if (!file.startsWith(compilation)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("invalid compilation output: " + relative);
            long size = Files.size(file);
            bytes += size;
            if (size > MAX_CLASS_BYTES || bytes > MAX_TOTAL_BYTES)
                throw new IOException("compiled declaration input budget exceeded");
            String name =
                    relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
            name = name.substring(0, name.length() - 6).replace('/', '.');
            if (artifacts.put(name, new Artifact(file, line.substring(0, 64))) != null)
                throw new IOException("duplicate compiled type: " + name);
        }
        List<JavaUnit> result = new ArrayList<>();
        for (JavaUnit unit : units) {
            List<JavaDeclaration> declarations = new ArrayList<>();
            for (var declaration : unit.declarationInventory())
                declarations.add(mergeType(declaration, artifacts));
            result.add(
                    new JavaUnit(
                            unit.path(),
                            unit.tree(),
                            unit.processing(),
                            unit.bindings(),
                            unit.types(),
                            unit.declarations(),
                            declarations,
                            unit.sourceHash(),
                            unit.extensions()));
        }
        return result;
    }

    private static JavaDeclaration mergeType(
            JavaDeclaration declaration, Map<String, Artifact> artifacts) throws IOException {
        var artifact = artifacts.get(declaration.binaryName());
        if (artifact == null) {
            if (Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                    .contains(declaration.symbol().kind()))
                throw new IOException("missing compiled declaration: " + declaration.binaryName());
            return declaration;
        }
        if (!ContentHash.sha256(artifact.path()).equals(artifact.sha256()))
            throw new IOException("compiled declaration changed: " + artifact.path());
        JavaBinaryType binary;
        try {
            binary = JavaBinaryReader.read(Files.readAllBytes(artifact.path()));
        } catch (IllegalArgumentException corrupt) {
            throw new IOException("invalid compiled declaration: " + artifact.path(), corrupt);
        }
        if (!binary.name().equals(declaration.binaryName()))
            throw new IOException("compiled type identity mismatch: " + artifact.path());
        Map<String, JavaBinaryType.Field> fields = new HashMap<>();
        binary.fields().forEach(field -> fields.put(field.name() + field.descriptor(), field));
        Map<String, JavaBinaryType.Method> methods = new HashMap<>();
        binary.methods()
                .forEach(method -> methods.put(method.name() + method.descriptor(), method));
        List<JavaDeclaration> members = new ArrayList<>();
        for (var member : declaration.members()) {
            String key = member.symbol().name() + member.symbol().descriptor();
            var field = fields.get(key);
            var method = methods.get(key);
            if (field != null)
                members.add(
                        copy(
                                member,
                                field.signature(),
                                field.annotations(),
                                field.constant(),
                                member.annotationDefault(),
                                member.members()));
            else if (method != null)
                members.add(
                        copy(
                                member,
                                method.signature(),
                                method.annotations(),
                                member.constant(),
                                method.annotationDefault(),
                                member.members()));
            else members.add(mergeType(member, artifacts));
        }
        return copy(
                declaration,
                binary.signature(),
                binary.annotations(),
                declaration.constant(),
                declaration.annotationDefault(),
                members);
    }

    private static JavaDeclaration copy(
            JavaDeclaration original,
            String signature,
            List<JavaBinaryType.Annotation> binaryAnnotations,
            JavaBinaryType.Value constant,
            JavaBinaryType.Value defaultValue,
            List<JavaDeclaration> members) {
        Map<String, JavaAnnotation> annotations = new LinkedHashMap<>();
        original.annotations()
                .forEach(annotation -> annotations.put(annotation.descriptor(), annotation));
        binaryAnnotations.forEach(
                annotation ->
                        annotations.putIfAbsent(
                                annotation.descriptor(),
                                new JavaAnnotation(
                                        annotation.descriptor(),
                                        annotation.visible()
                                                ? JavaAnnotation.Retention.RUNTIME
                                                : JavaAnnotation.Retention.CLASS,
                                        annotation.values())));
        return new JavaDeclaration(
                original.symbol(),
                original.canonicalName(),
                original.binaryName(),
                signature == null ? original.signature() : signature,
                original.span(),
                original.origin(),
                original.superclass(),
                original.interfaces(),
                original.permitted(),
                List.copyOf(annotations.values()),
                constant,
                defaultValue,
                members,
                original.incompleteReasons(),
                original.overrides(),
                original.deprecated());
    }
}
