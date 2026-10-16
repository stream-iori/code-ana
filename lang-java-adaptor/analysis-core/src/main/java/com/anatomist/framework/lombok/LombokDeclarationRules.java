package com.anatomist.framework.lombok;

import static com.anatomist.framework.lombok.LombokJavaModelExtension.*;
import static com.anatomist.framework.lombok.LombokJavaModelExtension.Overlay.*;

import com.anatomist.core.java.*;
import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.JavaModelAnnotations;
import com.anatomist.framework.JavaUnitAnalysis;

import java.util.*;

/** Task-local LombokDeclarationRules; shared state remains owned by LombokJavaModelExtension. */
final class LombokDeclarationRules {
    private final LombokJavaModelExtension.Overlay context;

    LombokDeclarationRules(LombokJavaModelExtension.Overlay context) {
        this.context = context;
    }

    JavaDeclaration valueModifiers(JavaDeclaration owner) {
        if (context.unit.hasProcessorEvidence()
                || !context.annotations(owner).containsKey("Value")
                || !owner.symbol().kind().equals("CLASS")
                || context.configuration.uncertainCapabilities().contains("*")
                || context.configuration
                        .uncertainCapabilities()
                        .contains(LombokCapabilitySummary.VALUE_MODIFIERS)) return owner;
        var members = new ArrayList<JavaDeclaration>();
        for (var member : owner.members()) {
            if (!member.symbol().kind().equals("FIELD")
                    || member.symbol().modifiers().contains("static")) {
                members.add(member);
                continue;
            }
            var modifiers = new ArrayList<>(member.symbol().modifiers());
            if (modifiers.stream().noneMatch(Set.of("public", "protected", "private")::contains)
                    && !context.lombokDeclarationRules.hasAnnotation(
                            member, "Llombok/experimental/PackagePrivate;"))
                modifiers.add("private");
            if (!modifiers.contains("final")
                    && !context.lombokDeclarationRules.hasAnnotation(
                            member, "Llombok/experimental/NonFinal;")) modifiers.add("final");
            var replacement =
                    context.lombokDeclarationRules.withModifiers(
                            member, modifiers, member.members());
            if (!member.symbol().modifiers().contains("final")
                    && modifiers.contains("final")
                    && context.lombokGeneratedMembers.initialized(member)
                    && context.lombokDeclarationRules.constantType(member))
                replacement = context.lombokDeclarationRules.valueConstant(member, replacement);
            members.add(replacement);
        }
        var modifiers = new ArrayList<>(owner.symbol().modifiers());
        if (!modifiers.contains("final")
                && !context.lombokDeclarationRules.hasAnnotation(
                        owner, "Llombok/experimental/NonFinal;")) modifiers.add("final");
        return context.lombokDeclarationRules.withModifiers(owner, modifiers, members);
    }

    boolean constantType(JavaDeclaration field) {
        JavaType type = context.types.get(field.symbol().declaredType());
        return Set.of(
                                JavaType.Kind.BOOLEAN,
                                JavaType.Kind.BYTE,
                                JavaType.Kind.SHORT,
                                JavaType.Kind.INT,
                                JavaType.Kind.LONG,
                                JavaType.Kind.CHAR,
                                JavaType.Kind.FLOAT,
                                JavaType.Kind.DOUBLE)
                        .contains(type.kind())
                || type.kind() == JavaType.Kind.DECLARED && type.name().equals("java.lang.String");
    }

    JavaDeclaration valueConstant(JavaDeclaration original, JavaDeclaration replacement) {
        JavaNode expression =
                context.model
                        .syntax(context.references.get(original))
                        .orElseThrow()
                        .tree("getInitializer");
        if (Set.of("METHOD_INVOCATION", "NEW_CLASS", "NEW_ARRAY", "NULL_LITERAL")
                .contains(expression.kind())) return replacement;
        if (context.compiledAnnotations == null)
            context.compiledAnnotations =
                    new JavaModelAnnotations(
                            new JavaUnitAnalysis(
                                    new JavaFrameworkContext(
                                            context.extension.project,
                                            List.of(context.unit.path()),
                                            new com.anatomist.config.ProjectConfig(),
                                            false),
                                    context.model,
                                    context.binaries,
                                    context.unit.path(),
                                    context.context.identity(context.unit.path())));
        var constant = context.compiledAnnotations.constant(context.unit.path(), expression);
        if (!(constant instanceof JavaBinaryType.Scalar scalar))
            return incomplete(replacement, "lombok:value-constant-unavailable");
        Object value = scalar.value();
        var kind = context.types.get(original.symbol().declaredType()).kind();
        if (!context.lombokDeclarationRules.assignableConstant(kind, value))
            return incomplete(replacement, "lombok:value-constant-type-unavailable");
        if (value instanceof Character character && kind != JavaType.Kind.CHAR)
            value = (int) character;
        else if (value instanceof Integer number && kind == JavaType.Kind.CHAR)
            value = (char) number.intValue();
        if (value instanceof Number number)
            value =
                    switch (kind) {
                        case BYTE, SHORT, INT -> number.intValue();
                        case LONG -> number.longValue();
                        case FLOAT -> number.floatValue();
                        case DOUBLE -> number.doubleValue();
                        default -> value;
                    };
        var result =
                new JavaDeclaration(
                        replacement.symbol(),
                        replacement.canonicalName(),
                        replacement.binaryName(),
                        replacement.signature(),
                        replacement.span(),
                        replacement.origin(),
                        replacement.superclass(),
                        replacement.interfaces(),
                        replacement.permitted(),
                        replacement.annotations(),
                        new JavaBinaryType.Scalar(value),
                        replacement.annotationDefault(),
                        replacement.members(),
                        replacement.incompleteReasons(),
                        replacement.overrides(),
                        replacement.deprecated());
        context.references.put(result, context.references.get(original));
        return result;
    }

    boolean assignableConstant(JavaType.Kind kind, Object value) {
        return switch (kind) {
            case BOOLEAN -> value instanceof Boolean;
            case CHAR ->
                    value instanceof Character
                            || value instanceof Integer number
                                    && number >= Character.MIN_VALUE
                                    && number <= Character.MAX_VALUE;
            case BYTE ->
                    value instanceof Integer number
                            && number >= Byte.MIN_VALUE
                            && number <= Byte.MAX_VALUE;
            case SHORT ->
                    value instanceof Integer number
                            && number >= Short.MIN_VALUE
                            && number <= Short.MAX_VALUE;
            case INT -> value instanceof Integer || value instanceof Character;
            case LONG ->
                    value instanceof Integer || value instanceof Long || value instanceof Character;
            case FLOAT ->
                    value instanceof Number && !(value instanceof Double)
                            || value instanceof Character;
            case DOUBLE -> value instanceof Number || value instanceof Character;
            case DECLARED -> value instanceof String;
            default -> false;
        };
    }

    JavaDeclaration withModifiers(
            JavaDeclaration declaration, List<String> modifiers, List<JavaDeclaration> members) {
        var symbol = declaration.symbol();
        var replacement =
                new JavaDeclaration(
                        new JavaSymbol(
                                symbol.kind(),
                                symbol.owner(),
                                symbol.name(),
                                symbol.descriptor(),
                                symbol.declaredType(),
                                modifiers,
                                symbol.variadic()),
                        declaration.canonicalName(),
                        declaration.binaryName(),
                        declaration.signature(),
                        declaration.span(),
                        declaration.origin(),
                        declaration.superclass(),
                        declaration.interfaces(),
                        declaration.permitted(),
                        declaration.annotations(),
                        declaration.constant(),
                        declaration.annotationDefault(),
                        members,
                        declaration.incompleteReasons(),
                        declaration.overrides(),
                        declaration.deprecated());
        context.references.put(replacement, context.references.get(declaration));
        return replacement;
    }

    boolean hasAnnotation(JavaDeclaration declaration, String descriptor) {
        return declaration.annotations().stream()
                .anyMatch(annotation -> annotation.descriptor().equals(descriptor));
    }

    JavaDeclaration utilityClass(JavaDeclaration owner, Map<String, JavaAnnotation> annotations) {
        context.seen = true;
        var policy = context.summary(annotations);
        // The first bounded case already has static source members. Rewriting instance
        // members, nested owners or other generators needs separate declaration evidence.
        boolean supported =
                owner.symbol().kind().equals("CLASS")
                        && context.model.parent(context.references.get(owner)).isEmpty()
                        && annotations.size() == 1
                        && owner.annotations().stream()
                                .noneMatch(
                                        annotation ->
                                                annotation.descriptor().startsWith("Llombok/")
                                                        && !annotation
                                                                .descriptor()
                                                                .equals(
                                                                        "Llombok/experimental/UtilityClass;")
                                                        && !annotation
                                                                .descriptor()
                                                                .equals("Llombok/Generated;"))
                        && owner.interfaces().isEmpty()
                        && !owner.symbol().modifiers().contains("abstract")
                        && (owner.superclass() < 0
                                || JavaTypeNames.erased(context.unit.types(), owner.superclass())
                                        .equals("java.lang.Object"))
                        && !policy.isPartial("utility_class")
                        && owner.members().stream()
                                .allMatch(
                                        member ->
                                                member.symbol().kind().equals("CONSTRUCTOR")
                                                        ? member.origin()
                                                                == JavaDeclaration.Origin
                                                                        .COMPILER_SYNTHETIC
                                                        : Set.of("FIELD", "METHOD")
                                                                        .contains(
                                                                                member.symbol()
                                                                                        .kind())
                                                                && member.symbol()
                                                                        .modifiers()
                                                                        .contains("static")
                                                                && member.annotations().stream()
                                                                        .noneMatch(
                                                                                annotation ->
                                                                                        annotation
                                                                                                .descriptor()
                                                                                                .startsWith(
                                                                                                        "Llombok/")));
        if (!supported || !context.generatedAnnotationKnown()) {
            policy.partial("utility_class");
            context.put(owner.canonicalName(), "lombok", policy.toMap());
            context.unsupported(
                    owner,
                    "UtilityClass requires a top-level class with explicit static members and no"
                            + " other generators");
            return incomplete(owner, "lombok:incomplete-utility-class");
        }
        var members = new ArrayList<>(owner.members());
        members.removeIf(member -> member.symbol().kind().equals("CONSTRUCTOR"));
        context.lombokGeneratedMembers.method(
                owner,
                members,
                "<init>",
                List.of(),
                context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                "()V",
                List.of("private"),
                owner.span(),
                "@UtilityClass");
        var modifiers = new ArrayList<>(owner.symbol().modifiers());
        if (!modifiers.contains("final")) modifiers.add("final");
        policy.generatedMemberCount(1);
        context.put(owner.canonicalName(), "lombok", policy.toMap());
        context.increment("lombok_types_augmented", 1);
        context.increment("lombok_members_generated", 1);
        return context.lombokDeclarationRules.withModifiers(owner, modifiers, members);
    }

    void constructors(
            JavaDeclaration owner,
            Map<String, JavaAnnotation> annotations,
            List<JavaDeclaration> fields,
            List<JavaDeclaration> members,
            LombokCapabilitySummary policy,
            LombokCapabilitySummary aggregate) {
        boolean explicit =
                owner.members().stream()
                        .anyMatch(
                                member ->
                                        member.symbol().kind().equals("CONSTRUCTOR")
                                                && member.origin()
                                                        == JavaDeclaration.Origin.SOURCE);
        boolean explicitPolicy =
                annotations.containsKey("NoArgsConstructor")
                        || annotations.containsKey("RequiredArgsConstructor")
                        || annotations.containsKey("AllArgsConstructor")
                        || annotations.containsKey("Builder")
                        || annotations.containsKey("SuperBuilder");
        for (String kind :
                List.of("NoArgsConstructor", "RequiredArgsConstructor", "AllArgsConstructor")) {
            String capability =
                    switch (kind) {
                        case "NoArgsConstructor" -> LombokCapabilitySummary.NO_ARGS_CONSTRUCTOR;
                        case "RequiredArgsConstructor" ->
                                LombokCapabilitySummary.REQUIRED_CONSTRUCTOR;
                        default -> LombokCapabilitySummary.ALL_ARGS_CONSTRUCTOR;
                    };
            var annotation = annotations.get(kind);
            String from = "@" + kind;
            if (annotation == null && !explicit && !explicitPolicy) {
                if (kind.equals("RequiredArgsConstructor") && annotations.containsKey("Data")) {
                    annotation = annotations.get("Data");
                    from = "@Data";
                } else if (kind.equals("AllArgsConstructor") && annotations.containsKey("Value")) {
                    annotation = annotations.get("Value");
                    from = "@Value";
                }
            }
            if (annotation == null || policy.isPartial(capability)) continue;
            var access = access(annotation, "access");
            if (access == null
                    || !string(annotation, "staticName", "").isEmpty()
                    || !string(annotation, "staticConstructor", "").isEmpty()) {
                aggregate.partial(capability);
                context.unsupported(owner, from + " options");
                continue;
            }
            if (access.equals("NONE")) continue;
            List<JavaDeclaration> parameters =
                    fields.stream()
                            .filter(
                                    field ->
                                            !field.symbol().modifiers().contains("static")
                                                    && !field.symbol().name().startsWith("$"))
                            .filter(field -> !kind.equals("NoArgsConstructor"))
                            .filter(
                                    field ->
                                            !field.symbol().modifiers().contains("final")
                                                    || !context.lombokGeneratedMembers.initialized(
                                                            field))
                            .filter(
                                    field ->
                                            !kind.equals("RequiredArgsConstructor")
                                                    || (field.symbol().modifiers().contains("final")
                                                                    || context.annotations(field)
                                                                            .containsKey("NonNull"))
                                                            && !context.lombokGeneratedMembers
                                                                    .initialized(field))
                            .toList();
            if (parameters.stream().anyMatch(field -> !field.complete())) {
                aggregate.partial(capability);
                continue;
            }
            if (kind.equals("NoArgsConstructor")
                    && !bool(annotation, "force", false)
                    && fields.stream()
                            .anyMatch(
                                    field ->
                                            field.symbol().modifiers().contains("final")
                                                    && !field.symbol()
                                                            .modifiers()
                                                            .contains("static")
                                                    && !context.lombokGeneratedMembers.initialized(
                                                            field))) {
                aggregate.partial(capability);
                context.unsupported(owner, "uninitialized final fields without force");
                continue;
            }
            members.removeIf(
                    member ->
                            member.symbol().kind().equals("CONSTRUCTOR")
                                    && member.origin()
                                            == JavaDeclaration.Origin.COMPILER_SYNTHETIC);
            String signature =
                    "("
                            + String.join(
                                    "",
                                    parameters.stream().map(JavaDeclaration::signature).toList())
                            + ")V";
            var parameterTypes =
                    parameters.stream().map(field -> field.symbol().declaredType()).toList();
            String erasedConstructor =
                    "("
                            + String.join(
                                    "", parameterTypes.stream().map(context::descriptor).toList())
                            + ")V";
            if (members.stream()
                    .anyMatch(
                            member ->
                                    member.symbol().kind().equals("CONSTRUCTOR")
                                            && member.symbol()
                                                    .descriptor()
                                                    .equals(erasedConstructor))) {
                aggregate.partial(capability);
                context.unsupported(owner, "duplicate constructor signature");
                continue;
            }
            context.lombokGeneratedMembers.method(
                    owner,
                    members,
                    "<init>",
                    parameters,
                    context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                    signature,
                    modifiers(owner.symbol().kind().equals("ENUM") ? "PRIVATE" : access, false),
                    owner.span(),
                    from);
        }
    }

    void logger(
            JavaDeclaration owner,
            Map<String, JavaAnnotation> annotations,
            List<JavaDeclaration> members,
            LombokCapabilitySummary policy,
            LombokCapabilitySummary aggregate) {
        if (policy.isPartial(LombokCapabilitySummary.LOGGER_FIELD)) return;
        for (var log : LOG_TYPES.entrySet())
            if (annotations.containsKey(log.getKey())) {
                if (members.stream()
                        .anyMatch(
                                member ->
                                        member.symbol().kind().equals("FIELD")
                                                && member.symbol()
                                                        .name()
                                                        .equals(
                                                                context.configuration
                                                                        .logFieldName()))) return;
                if (context.binaries == null || context.binaries.find(log.getValue()).isEmpty()) {
                    aggregate.partial(LombokCapabilitySummary.LOGGER_FIELD);
                    context.unsupported(owner, "missing logger type " + log.getValue());
                    return;
                }
                int type = context.lombokGeneratedMembers.named(log.getValue());
                List<String> modifiers = new ArrayList<>(List.of("private", "final"));
                if (context.configuration.logFieldStatic()) modifiers.add("static");
                var field =
                        new JavaDeclaration(
                                new JavaSymbol(
                                        "FIELD",
                                        owner.canonicalName(),
                                        context.configuration.logFieldName(),
                                        context.lombokGeneratedMembers.descriptor(type),
                                        type,
                                        modifiers,
                                        false),
                                "",
                                "",
                                context.lombokGeneratedMembers.descriptor(type),
                                owner.span(),
                                JavaDeclaration.Origin.MODEL,
                                -1,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of());
                members.add(field);
                context.generated.put(fieldId(owner, field), origin("@" + log.getKey()));
                return;
            }
    }

    String getterName(JavaDeclaration field) {
        return context.lombokDeclarationRules.primitiveBoolean(field)
                        && !context.configuration.noIsPrefix()
                ? "is" + context.lombokDeclarationRules.booleanStem(field)
                : "get" + capitalize(field.symbol().name());
    }

    List<String> getterAlternatives(JavaDeclaration field) {
        String name = field.symbol().name();
        if (!context.lombokDeclarationRules.primitiveBoolean(field)
                || context.configuration.noIsPrefix())
            return List.of(context.lombokDeclarationRules.getterName(field));
        return List.of(
                "is" + capitalize(name),
                "get" + capitalize(name),
                "is" + context.lombokDeclarationRules.booleanStem(field),
                "get" + context.lombokDeclarationRules.booleanStem(field));
    }

    String booleanStem(JavaDeclaration field) {
        String name = field.symbol().name();
        return context.lombokDeclarationRules.primitiveBoolean(field)
                        && !context.configuration.noIsPrefix()
                        && name.startsWith("is")
                        && name.length() > 2
                        && !Character.isLowerCase(name.charAt(2))
                ? name.substring(2)
                : capitalize(name);
    }

    boolean primitiveBoolean(JavaDeclaration field) {
        return context.types.get(field.symbol().declaredType()).kind() == JavaType.Kind.BOOLEAN;
    }
}
