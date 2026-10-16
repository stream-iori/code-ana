package com.anatomist.framework.lombok;

import static com.anatomist.framework.lombok.LombokJavaModelExtension.*;
import static com.anatomist.framework.lombok.LombokJavaModelExtension.Overlay.*;

import com.anatomist.core.java.*;

import java.util.*;

/** Task-local LombokGeneratedMembers; shared state remains owned by LombokJavaModelExtension. */
final class LombokGeneratedMembers {
    private final LombokJavaModelExtension.Overlay context;

    LombokGeneratedMembers(LombokJavaModelExtension.Overlay context) {
        this.context = context;
    }

    void method(
            JavaDeclaration owner,
            List<JavaDeclaration> members,
            String name,
            List<JavaDeclaration> parameters,
            int returns,
            String signature,
            List<String> modifiers,
            SourceSpan span,
            String from) {
        context.lombokGeneratedMembers.method(
                owner,
                members,
                name,
                parameters,
                returns,
                signature,
                modifiers,
                span,
                from,
                List.of());
    }

    void method(
            JavaDeclaration owner,
            List<JavaDeclaration> members,
            String name,
            List<JavaDeclaration> parameters,
            int returns,
            String signature,
            List<String> modifiers,
            SourceSpan span,
            String from,
            List<JavaAnnotation> methodAnnotations) {
        var parameterTypes = parameters.stream().map(context::generatedType).toList();
        int executable = context.types.size();
        context.types.add(
                new JavaType(
                        JavaType.Kind.EXECUTABLE,
                        "",
                        signature,
                        -1,
                        List.of(),
                        List.of(),
                        -1,
                        -1,
                        parameterTypes,
                        returns,
                        List.of()));
        String descriptor =
                "("
                        + String.join("", parameterTypes.stream().map(context::descriptor).toList())
                        + ")"
                        + context.lombokGeneratedMembers.descriptor(returns);
        String kind = name.equals("<init>") ? "CONSTRUCTOR" : "METHOD";
        var method =
                new JavaDeclaration(
                        new JavaSymbol(
                                kind,
                                owner.canonicalName(),
                                name,
                                descriptor,
                                executable,
                                modifiers,
                                false),
                        "",
                        "",
                        signature,
                        span,
                        JavaDeclaration.Origin.MODEL,
                        -1,
                        List.of(),
                        List.of(),
                        methodAnnotations,
                        null,
                        null,
                        parameters.stream()
                                .map(
                                        parameter ->
                                                context.lombokGeneratedMembers.parameter(
                                                        owner,
                                                        parameter.symbol().name(),
                                                        context.lombokGeneratedMembers
                                                                .generatedType(parameter),
                                                        context.lombokGeneratedMembers
                                                                .copiedParameterAnnotations(
                                                                        parameter)))
                                .toList(),
                        List.of());
        members.add(method);
        String id =
                owner.canonicalName()
                        + "#"
                        + (kind.equals("CONSTRUCTOR") ? owner.symbol().name() : name)
                        + "("
                        + String.join(
                                ",",
                                parameterTypes.stream()
                                        .map(
                                                type ->
                                                        JavaTypeNames.erased(
                                                                new JavaTypes(context.types), type))
                                        .toList())
                        + ")";
        context.generated.put(id, origin(from));
    }

    JavaDeclaration parameter(JavaDeclaration owner, String name, int type) {
        return context.lombokGeneratedMembers.parameter(owner, name, type, List.of());
    }

    JavaDeclaration parameter(
            JavaDeclaration owner, String name, int type, List<JavaAnnotation> annotations) {
        return new JavaDeclaration(
                new JavaSymbol(
                        "PARAMETER",
                        owner.canonicalName(),
                        name,
                        context.lombokGeneratedMembers.descriptor(type),
                        type,
                        List.of(),
                        false),
                "",
                "",
                context.lombokGeneratedMembers.descriptor(type),
                SourceSpan.ABSENT,
                JavaDeclaration.Origin.MODEL,
                -1,
                List.of(),
                List.of(),
                annotations,
                null,
                null,
                List.of(),
                List.of());
    }

    boolean needsAnnotationCopies(JavaDeclaration declaration) {
        if (declaration.annotations().stream().anyMatch(context::annotationCopyNeedsModel))
            return true;
        if (!context.lombokGeneratedMembers.hasBaseAnnotations(
                declaration.symbol().declaredType(), 0)) return false;
        var reference = context.references.get(declaration);
        if (reference == null)
            return declaration.annotations().stream().anyMatch(context::annotationCopyNeedsModel);
        if (context.compiledAnnotations == null) context.compiledAnnotations(declaration);
        return context.compiledAnnotations.annotations(reference).stream()
                .anyMatch(context::annotationCopyNeedsModel);
    }

    boolean annotationCopyNeedsModel(JavaAnnotation annotation) {
        return !annotation.descriptor().equals("Ljava/lang/Deprecated;")
                && LombokAnnotationCopies.needsModel(annotation);
    }

    int generatedType(JavaDeclaration declaration) {
        if (!declaration.symbol().kind().equals("FIELD"))
            return declaration.symbol().declaredType();
        if (!context.lombokGeneratedMembers.hasBaseAnnotations(
                declaration.symbol().declaredType(), 0)) return declaration.symbol().declaredType();
        return context.generatedTypes.computeIfAbsent(
                declaration,
                field -> {
                    var reference = context.references.get(field);
                    if (reference == null) return field.symbol().declaredType();
                    if (context.compiledAnnotations == null) context.compiledAnnotations(field);
                    Set<String> excluded = new HashSet<>();
                    for (var annotation : context.compiledAnnotations.annotations(reference))
                        if (!LombokAnnotationCopies.willCopy(annotation))
                            excluded.add(annotation.descriptor());
                    return context.lombokGeneratedMembers.writtenType(
                            field.symbol().declaredType(), excluded, 0);
                });
    }

    int writtenFieldType(JavaDeclaration field) {
        if (!field.symbol().kind().equals("FIELD")
                || !context.lombokGeneratedMembers.hasBaseAnnotations(
                        field.symbol().declaredType(), 0)) return field.symbol().declaredType();
        var reference = context.references.get(field);
        if (reference == null) return field.symbol().declaredType();
        if (context.compiledAnnotations == null) context.compiledAnnotations(field);
        Set<String> excluded = new HashSet<>();
        for (var annotation : context.compiledAnnotations.annotations(reference))
            excluded.add(annotation.descriptor());
        return context.lombokGeneratedMembers.writtenType(
                field.symbol().declaredType(), excluded, 0);
    }

    List<JavaAnnotation> copiedMethodAnnotations(JavaDeclaration field, boolean getter) {
        var copied =
                new ArrayList<>(
                        field.annotations().stream()
                                .filter(
                                        annotation ->
                                                annotation
                                                                        .descriptor()
                                                                        .equals(
                                                                                "Ljava/lang/Deprecated;")
                                                                && annotation.values().isEmpty()
                                                        || getter
                                                                && annotation
                                                                        .descriptor()
                                                                        .equals("Llombok/NonNull;"))
                                .toList());
        if ((field.deprecated()
                        || field.annotations().stream()
                                .anyMatch(
                                        annotation ->
                                                annotation
                                                        .descriptor()
                                                        .equals("Ljava/lang/Deprecated;")))
                && copied.stream()
                        .noneMatch(
                                annotation ->
                                        annotation.descriptor().equals("Ljava/lang/Deprecated;"))) {
            copied.add(
                    new JavaAnnotation(
                            "Ljava/lang/Deprecated;", JavaAnnotation.Retention.RUNTIME, Map.of()));
        }
        return List.copyOf(copied);
    }

    List<JavaAnnotation> copiedParameterAnnotations(JavaDeclaration field) {
        return field.annotations().stream()
                .filter(annotation -> annotation.descriptor().equals("Llombok/NonNull;"))
                .toList();
    }

    int writtenType(int reference, Set<String> excluded, int depth) {
        if (depth > 128)
            throw new IllegalArgumentException("Lombok written type exceeds depth budget");
        JavaType type = context.types.get(reference);
        int component = type.component();
        int enclosing = type.enclosing();
        List<JavaAnnotation> annotations = type.annotations();
        if (type.kind() == JavaType.Kind.ARRAY)
            component = context.lombokGeneratedMembers.writtenType(component, excluded, depth + 1);
        else {
            annotations =
                    annotations.stream()
                            .filter(annotation -> !excluded.contains(annotation.descriptor()))
                            .toList();
            if (enclosing >= 0 && type.kind() == JavaType.Kind.DECLARED)
                enclosing =
                        context.lombokGeneratedMembers.writtenType(enclosing, excluded, depth + 1);
        }
        if (component == type.component()
                && enclosing == type.enclosing()
                && annotations.equals(type.annotations())) return reference;
        int index = context.types.size();
        context.types.add(
                new JavaType(
                        type.kind(),
                        type.name(),
                        type.display(),
                        enclosing,
                        type.arguments(),
                        type.upperBounds(),
                        type.lowerBound(),
                        component,
                        type.parameters(),
                        type.returns(),
                        type.exceptions(),
                        annotations,
                        type.binaryName()));
        return index;
    }

    boolean hasBaseAnnotations(int reference, int depth) {
        if (depth > 128)
            throw new IllegalArgumentException("Lombok written type exceeds depth budget");
        JavaType type = context.types.get(reference);
        if (type.kind() == JavaType.Kind.ARRAY)
            return context.lombokGeneratedMembers.hasBaseAnnotations(type.component(), depth + 1);
        return !type.annotations().isEmpty()
                || type.kind() == JavaType.Kind.DECLARED
                        && type.enclosing() >= 0
                        && context.lombokGeneratedMembers.hasBaseAnnotations(
                                type.enclosing(), depth + 1);
    }

    String descriptor(int reference) {
        return context.lombokGeneratedMembers.descriptor(reference, 0);
    }

    String descriptor(int reference, int depth) {
        if (depth > 128)
            throw new IllegalArgumentException("modeled signature exceeds depth budget");
        JavaType type = context.types.get(reference);
        return switch (type.kind()) {
            case VOID -> "V";
            case BOOLEAN -> "Z";
            case BYTE -> "B";
            case SHORT -> "S";
            case INT -> "I";
            case LONG -> "J";
            case CHAR -> "C";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case ARRAY ->
                    "[" + context.lombokGeneratedMembers.descriptor(type.component(), depth + 1);
            case DECLARED ->
                    "L"
                            + (type.binaryName().isEmpty() ? type.name() : type.binaryName())
                                    .replace('.', '/')
                            + ";";
            case TYPEVAR, INTERSECTION ->
                    context.lombokGeneratedMembers.descriptor(
                            type.upperBounds().getFirst(), depth + 1);
            default ->
                    throw new IllegalArgumentException(
                            "unavailable modeled signature type: " + type.kind());
        };
    }

    int primitive(JavaType.Kind kind) {
        for (int i = 0; i < context.types.size(); i++)
            if (context.types.get(i).kind() == kind && context.types.get(i).annotations().isEmpty())
                return i;
        int index = context.types.size();
        String name = kind.name().toLowerCase(Locale.ROOT);
        context.types.add(
                new JavaType(
                        kind, name, name, -1, List.of(), List.of(), -1, -1, List.of(), -1,
                        List.of()));
        return index;
    }

    int named(String name) {
        for (int i = 0; i < context.types.size(); i++)
            if (context.types.get(i).kind() == JavaType.Kind.DECLARED
                    && context.types.get(i).name().equals(name)
                    && context.types.get(i).arguments().isEmpty()
                    && context.types.get(i).annotations().isEmpty()) return i;
        int index = context.types.size();
        context.types.add(
                new JavaType(
                        JavaType.Kind.DECLARED,
                        name,
                        name,
                        -1,
                        List.of(),
                        List.of(),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of(),
                        List.of(),
                        name));
        return index;
    }

    boolean initialized(JavaDeclaration field) {
        var reference = context.references.get(field);
        return reference != null
                && context.model
                        .syntax(reference)
                        .map(syntax -> syntax.tree("getInitializer") != null)
                        .orElse(false);
    }

    boolean hasAny(List<JavaDeclaration> members, List<String> names, int arity) {
        return members.stream()
                .anyMatch(
                        member ->
                                member.symbol().kind().equals("METHOD")
                                        && names.stream()
                                                .anyMatch(
                                                        name ->
                                                                name.equalsIgnoreCase(
                                                                        member.symbol().name()))
                                        && context.types
                                                        .get(member.symbol().declaredType())
                                                        .parameters()
                                                        .size()
                                                == arity);
    }
}
