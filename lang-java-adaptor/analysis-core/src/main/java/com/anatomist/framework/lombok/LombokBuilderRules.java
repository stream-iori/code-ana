package com.anatomist.framework.lombok;

import static com.anatomist.framework.lombok.LombokJavaModelExtension.*;
import static com.anatomist.framework.lombok.LombokJavaModelExtension.Overlay.*;

import com.anatomist.core.java.*;

import java.util.*;

/** Task-local LombokBuilderRules; shared state remains owned by LombokJavaModelExtension. */
final class LombokBuilderRules {
    private final LombokJavaModelExtension.Overlay context;

    LombokBuilderRules(LombokJavaModelExtension.Overlay context) {
        this.context = context;
    }

    void builder(
            JavaDeclaration owner,
            Map<String, JavaAnnotation> annotations,
            List<JavaDeclaration> fields,
            List<JavaDeclaration> members,
            LombokCapabilitySummary policy,
            LombokCapabilitySummary aggregate) {
        List<JavaDeclaration> targets = new ArrayList<>();
        if (annotations.containsKey("Builder")) targets.add(owner);
        owner.members().stream()
                .filter(member -> context.annotations(member).containsKey("Builder"))
                .forEach(targets::add);
        if (targets.isEmpty()) return;
        aggregate.detect("Builder");
        if (annotations.containsKey("Builder")
                && owner.symbol().kind().equals("CLASS")
                && Set.of("NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor")
                        .stream()
                        .noneMatch(annotations::containsKey))
            members.removeIf(
                    member ->
                            member.symbol().kind().equals("CONSTRUCTOR")
                                    && member.origin()
                                            == JavaDeclaration.Origin.COMPILER_SYNTHETIC);
        var target = targets.getFirst();
        var annotation = context.annotations(target).get("Builder");
        var parent = context.model.parent(context.references.get(owner)).orElse(null);
        if (targets.size() != 1
                || policy.isPartial("builder")
                || context.configuration.uncertainCapabilities().contains("*")
                || context.configuration.uncertainCapabilities().contains("builder")
                || !owner.symbol().kind().equals("CLASS")
                || owner.symbol().modifiers().contains("abstract")
                || parent != null && !owner.symbol().modifiers().contains("static")
                || target != owner
                        && !target.symbol().kind().equals("CONSTRUCTOR")
                        && !target.symbol().kind().equals("METHOD")
                || target != owner
                        && (target.symbol().variadic()
                                || !context.types
                                        .get(target.symbol().declaredType())
                                        .exceptions()
                                        .isEmpty())
                || target != owner
                        && target.symbol().kind().equals("CONSTRUCTOR")
                        && target.members().stream()
                                .anyMatch(
                                        member ->
                                                member.symbol().kind().equals("TYPE_PARAMETER"))) {
            aggregate.partial("builder");
            context.unsupported(owner, "Builder owner, target or generic parameters");
            return;
        }
        boolean methodTarget = target.symbol().kind().equals("METHOD");
        boolean instanceTarget = methodTarget && !target.symbol().modifiers().contains("static");
        var genericOwner = methodTarget ? target : owner;
        var formals =
                genericOwner.members().stream()
                        .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                        .toList();
        String formalHeader = LombokBuilderRules.formalHeader(genericOwner.signature());
        int builtType =
                methodTarget
                        ? context.types.get(target.symbol().declaredType()).returns()
                        : owner.symbol().declaredType();
        var built = context.types.get(builtType);
        if (methodTarget
                && (built.kind() == JavaType.Kind.VOID
                        || built.kind() == JavaType.Kind.ARRAY
                        || bool(annotation, "toBuilder", false)
                        || built.kind() == JavaType.Kind.TYPEVAR
                                && !annotation.values().containsKey("builderClassName"))) {
            aggregate.partial("builder");
            context.unsupported(owner, "Builder method return type or toBuilder mapping");
            return;
        }
        String builtName = methodTarget ? built.name() : owner.symbol().name();
        if (builtName.contains("."))
            builtName = builtName.substring(builtName.lastIndexOf('.') + 1);
        if (methodTarget && !builtName.isEmpty()) builtName = capitalize(builtName);
        if (formals.stream()
                        .anyMatch(
                                formal ->
                                        !formal.complete()
                                                || !formal.annotations().isEmpty()
                                                || !context.types
                                                        .get(formal.symbol().declaredType())
                                                        .annotations()
                                                        .isEmpty())
                || !formals.isEmpty() && formalHeader.isEmpty()) {
            aggregate.partial("builder");
            context.unsupported(owner, "Builder annotated or incomplete type parameters");
            return;
        }
        String builderName = string(annotation, "builderClassName", builtName + "Builder");
        String factoryName = string(annotation, "builderMethodName", "builder");
        String buildName = string(annotation, "buildMethodName", "build");
        String prefix = string(annotation, "setterPrefix", "");
        String access = access(annotation, "access");
        var customLookup = context.model.type(owner.binaryName() + "$" + builderName);
        JavaDeclaration custom =
                customLookup instanceof JavaModel.FoundType found
                        ? context.model.declaration(found.declaration())
                        : null;
        boolean validOptions =
                annotation.values().entrySet().stream()
                        .allMatch(
                                entry -> {
                                    if (Set.of(
                                                    "builderClassName",
                                                    "builderMethodName",
                                                    "buildMethodName",
                                                    "setterPrefix")
                                            .contains(entry.getKey()))
                                        return entry.getValue()
                                                        instanceof JavaBinaryType.Scalar scalar
                                                && scalar.value() instanceof String;
                                    if (entry.getKey().equals("toBuilder"))
                                        return entry.getValue()
                                                        instanceof JavaBinaryType.Scalar scalar
                                                && scalar.value() instanceof Boolean;
                                    return entry.getKey().equals("access") && access != null;
                                });
        if (!validOptions
                || access == null
                || Set.of("NONE", "MODULE").contains(access)
                || !context.lombokBuilderRules.identifier(builderName, false)
                || !context.lombokBuilderRules.identifier(factoryName, true)
                || !context.lombokBuilderRules.identifier(buildName, false)
                || !context.lombokBuilderRules.identifier(prefix, true)
                || customLookup instanceof JavaModel.AmbiguousType
                || custom != null
                        && (!custom.complete()
                                || !custom.symbol().kind().equals("CLASS")
                                || custom.symbol().modifiers().contains("static") == instanceTarget
                                || !LombokBuilderRules.formalHeader(custom.signature())
                                        .equals(formalHeader)
                                || !context.annotations(custom).isEmpty())) {
            aggregate.partial("builder");
            context.unsupported(owner, "Builder options or custom builder class");
            return;
        }
        List<JavaDeclaration> parameters =
                target == owner
                        ? fields.stream()
                                .filter(
                                        field ->
                                                !field.symbol().modifiers().contains("static")
                                                        && !field.symbol().name().startsWith("$")
                                                        && (!(field.symbol()
                                                                                .modifiers()
                                                                                .contains("final")
                                                                        || annotations.containsKey(
                                                                                "Value"))
                                                                || !context.lombokGeneratedMembers
                                                                        .initialized(field)
                                                                || context.lombokDeclarationRules
                                                                        .hasAnnotation(
                                                                                field,
                                                                                "Llombok/Builder$Default;")))
                                .toList()
                        : target.members().stream()
                                .filter(member -> member.symbol().kind().equals("PARAMETER"))
                                .toList();
        if (parameters.stream()
                        .anyMatch(
                                parameter ->
                                        !parameter.complete()
                                                || context.lombokGeneratedMembers
                                                        .needsAnnotationCopies(parameter)
                                                || parameter.symbol().kind().equals("PARAMETER")
                                                        && !context.types
                                                                .get(
                                                                        parameter
                                                                                .symbol()
                                                                                .declaredType())
                                                                .annotations()
                                                                .isEmpty())
                || fields.stream()
                        .anyMatch(
                                field ->
                                        field.annotations().stream()
                                                .anyMatch(
                                                        value ->
                                                                Set.of(
                                                                                "Llombok/Singular;",
                                                                                "Llombok/Builder$ObtainVia;")
                                                                        .contains(
                                                                                value
                                                                                        .descriptor())))) {
            aggregate.partial("builder");
            context.unsupported(owner, "Builder annotated, default or collection parameters");
            return;
        }
        var defaults =
                target == owner
                        ? parameters.stream()
                                .filter(
                                        field ->
                                                context.lombokDeclarationRules.hasAnnotation(
                                                        field, "Llombok/Builder$Default;"))
                                .toList()
                        : List.<JavaDeclaration>of();
        if (custom != null
                && parameters.stream()
                        .anyMatch(
                                parameter ->
                                        custom.members().stream()
                                                .filter(
                                                        member ->
                                                                member.symbol()
                                                                                .kind()
                                                                                .equals("FIELD")
                                                                        && member.symbol()
                                                                                .name()
                                                                                .equals(
                                                                                        defaults
                                                                                                        .contains(
                                                                                                                parameter)
                                                                                                ? parameter
                                                                                                                .symbol()
                                                                                                                .name()
                                                                                                        + "$value"
                                                                                                : parameter
                                                                                                        .symbol()
                                                                                                        .name()))
                                                .anyMatch(
                                                        member ->
                                                                member.symbol()
                                                                                .modifiers()
                                                                                .contains("static")
                                                                        || !member.symbol()
                                                                                .descriptor()
                                                                                .equals(
                                                                                        parameter
                                                                                                .symbol()
                                                                                                .descriptor())
                                                                        || !member.signature()
                                                                                .equals(
                                                                                        parameter
                                                                                                .signature())))) {
            aggregate.partial("builder");
            context.unsupported(owner, "Builder custom field type or modifiers");
            return;
        }
        if (defaults.stream()
                .anyMatch(
                        field ->
                                !context.lombokGeneratedMembers.initialized(field)
                                        || context.lombokGeneratedMembers.hasAny(
                                                members,
                                                List.of("$default$" + field.symbol().name()),
                                                0))) {
            aggregate.partial("builder");
            context.unsupported(
                    owner, "Builder.Default without initializer or with provider collision");
            return;
        }
        for (var field : defaults)
            context.lombokBuilderRules.builderDefault(owner, members, formals, formalHeader, field);
        int builderType = context.types.size();
        String canonical = owner.canonicalName() + "." + builderName;
        String binary = owner.binaryName() + "$" + builderName;
        context.types.add(
                new JavaType(
                        JavaType.Kind.DECLARED,
                        canonical,
                        canonical,
                        instanceTarget ? owner.symbol().declaredType() : -1,
                        formals.stream().map(formal -> formal.symbol().declaredType()).toList(),
                        List.of(),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of(),
                        List.of(),
                        binary));
        var builderModifiers = new ArrayList<>(modifiers(access, !instanceTarget));
        var builder =
                custom != null
                        ? custom
                        : new JavaDeclaration(
                                new JavaSymbol(
                                        "CLASS",
                                        owner.canonicalName(),
                                        builderName,
                                        context.lombokGeneratedMembers.descriptor(builderType),
                                        builderType,
                                        builderModifiers,
                                        false),
                                canonical,
                                binary,
                                formalHeader + "Ljava/lang/Object;",
                                owner.span(),
                                JavaDeclaration.Origin.MODEL,
                                context.lombokGeneratedMembers.named("java.lang.Object"),
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of());
        var builderMembers = new ArrayList<JavaDeclaration>(builder.members());
        builderMembers.removeIf(
                member ->
                        member.symbol().kind().equals("CONSTRUCTOR")
                                && member.origin() == JavaDeclaration.Origin.COMPILER_SYNTHETIC);
        if (custom == null)
            builderMembers.addAll(
                    formals.stream()
                            .map(formal -> LombokBuilderRules.modeledFormal(formal, canonical))
                            .toList());
        String builderSignature = LombokBuilderRules.genericTypeSignature(binary, formals);
        var enclosingFormals =
                owner.members().stream()
                        .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                        .toList();
        if (instanceTarget && !enclosingFormals.isEmpty()) {
            String enclosingSignature =
                    LombokBuilderRules.genericTypeSignature(owner.binaryName(), enclosingFormals);
            builderSignature =
                    enclosingSignature.substring(0, enclosingSignature.length() - 1)
                            + "."
                            + LombokBuilderRules.genericTypeSignature(builderName, formals)
                                    .substring(1);
        }
        if (builderMembers.stream()
                .noneMatch(member -> member.symbol().kind().equals("CONSTRUCTOR")))
            context.lombokGeneratedMembers.method(
                    builder,
                    builderMembers,
                    "<init>",
                    List.of(),
                    context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                    "()V",
                    List.of(),
                    owner.span(),
                    "@Builder");
        String memberAccess = access.equals("PROTECTED") ? "PUBLIC" : access;
        for (var parameter : parameters) {
            String name = parameter.symbol().name();
            boolean defaulted = defaults.contains(parameter);
            var field =
                    new JavaDeclaration(
                            new JavaSymbol(
                                    "FIELD",
                                    canonical,
                                    defaulted ? name + "$value" : name,
                                    context.lombokGeneratedMembers.descriptor(
                                            parameter.symbol().declaredType()),
                                    context.lombokGeneratedMembers.writtenFieldType(parameter),
                                    List.of("private"),
                                    false),
                            "",
                            "",
                            parameter.signature(),
                            parameter.span(),
                            JavaDeclaration.Origin.MODEL,
                            -1,
                            List.of(),
                            List.of(),
                            List.of(),
                            null,
                            null,
                            List.of(),
                            List.of());
            if (builderMembers.stream()
                    .noneMatch(
                            member ->
                                    member.symbol().kind().equals("FIELD")
                                            && member.symbol()
                                                    .name()
                                                    .equals(field.symbol().name()))) {
                builderMembers.add(field);
                context.generated.put(fieldId(builder, field), origin("@Builder"));
            }
            if (defaulted) {
                int booleanType = context.lombokGeneratedMembers.primitive(JavaType.Kind.BOOLEAN);
                var flag =
                        new JavaDeclaration(
                                new JavaSymbol(
                                        "FIELD",
                                        canonical,
                                        name + "$set",
                                        "Z",
                                        booleanType,
                                        List.of("private"),
                                        false),
                                "",
                                "",
                                "Z",
                                parameter.span(),
                                JavaDeclaration.Origin.MODEL,
                                -1,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of());
                if (builderMembers.stream()
                        .noneMatch(
                                member ->
                                        member.symbol().kind().equals("FIELD")
                                                && member.symbol()
                                                        .name()
                                                        .equals(flag.symbol().name()))) {
                    builderMembers.add(flag);
                    context.generated.put(fieldId(builder, flag), origin("@Builder.Default"));
                }
            }
            String setter = prefix.isEmpty() ? name : prefix + capitalize(name);
            if (!LombokBuilderRules.hasNamedMethod(builderMembers, setter))
                context.lombokGeneratedMembers.method(
                        builder,
                        builderMembers,
                        setter,
                        List.of(parameter),
                        builderType,
                        "(" + parameter.signature() + ")" + builderSignature,
                        modifiers(memberAccess, false),
                        parameter.span(),
                        "@Builder",
                        context.lombokGeneratedMembers.copiedMethodAnnotations(parameter, false));
        }
        if (!LombokBuilderRules.hasNamedMethod(builderMembers, buildName))
            context.lombokGeneratedMembers.method(
                    builder,
                    builderMembers,
                    buildName,
                    List.of(),
                    builtType,
                    "()"
                            + (methodTarget
                                    ? LombokBuilderRules.returnSignature(target.signature())
                                    : LombokBuilderRules.genericTypeSignature(
                                            owner.binaryName(), formals)),
                    modifiers(memberAccess, false),
                    owner.span(),
                    "@Builder");
        if (!LombokBuilderRules.hasNamedMethod(builderMembers, "toString"))
            context.lombokGeneratedMembers.method(
                    builder,
                    builderMembers,
                    "toString",
                    List.of(),
                    context.lombokGeneratedMembers.named("java.lang.String"),
                    "()Ljava/lang/String;",
                    List.of("public"),
                    owner.span(),
                    "@Builder");
        var completedBuilder = copy(builder, builderMembers, builder.incompleteReasons());
        if (custom == null) {
            members.add(completedBuilder);
            context.generated.put(canonical, origin("@Builder"));
        } else {
            context.customBuilders.put(binary, completedBuilder);
            members.replaceAll(
                    member -> member.binaryName().equals(binary) ? completedBuilder : member);
        }
        if (!factoryName.isEmpty()
                && !context.lombokGeneratedMembers.hasAny(members, List.of(factoryName), 0)) {
            context.lombokGeneratedMembers.method(
                    owner,
                    members,
                    factoryName,
                    List.of(),
                    builderType,
                    formalHeader + "()" + builderSignature,
                    modifiers(access, !instanceTarget),
                    owner.span(),
                    "@Builder");
            context.lombokBuilderRules.methodFormals(members, formals);
        }
        if (bool(annotation, "toBuilder", false)
                && !context.lombokGeneratedMembers.hasAny(members, List.of("toBuilder"), 0))
            context.lombokGeneratedMembers.method(
                    owner,
                    members,
                    "toBuilder",
                    List.of(),
                    builderType,
                    "()" + builderSignature,
                    modifiers(access, false),
                    owner.span(),
                    "@Builder");
        if (target == owner
                && owner.members().stream()
                        .noneMatch(
                                member ->
                                        member.symbol().kind().equals("CONSTRUCTOR")
                                                && member.origin() == JavaDeclaration.Origin.SOURCE)
                && Set.of("NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor")
                        .stream()
                        .noneMatch(annotations::containsKey)) {
            members.removeIf(member -> member.symbol().kind().equals("CONSTRUCTOR"));
            context.lombokGeneratedMembers.method(
                    owner,
                    members,
                    "<init>",
                    parameters,
                    context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                    "("
                            + String.join(
                                    "",
                                    parameters.stream().map(JavaDeclaration::signature).toList())
                            + ")V",
                    List.of(),
                    owner.span(),
                    "@Builder");
        }
    }

    void builderDefault(
            JavaDeclaration owner,
            List<JavaDeclaration> members,
            List<JavaDeclaration> formals,
            String formalHeader,
            JavaDeclaration field) {
        context.lombokGeneratedMembers.method(
                owner,
                members,
                "$default$" + field.symbol().name(),
                List.of(),
                context.lombokGeneratedMembers.generatedType(field),
                formalHeader + "()" + field.signature(),
                List.of("private", "static"),
                field.span(),
                "@Builder.Default");
        context.lombokBuilderRules.methodFormals(members, formals);
        // The initializer moves to the provider, so a final field ceases to be a constant.
        int index = members.indexOf(field);
        if (index >= 0 && field.constant() != null) {
            members.set(
                    index,
                    new JavaDeclaration(
                            field.symbol(),
                            field.canonicalName(),
                            field.binaryName(),
                            field.signature(),
                            field.span(),
                            field.origin(),
                            field.superclass(),
                            field.interfaces(),
                            field.permitted(),
                            field.annotations(),
                            null,
                            field.annotationDefault(),
                            field.members(),
                            field.incompleteReasons(),
                            field.overrides(),
                            field.deprecated()));
        }
    }

    SuperBuilderValidation validateSuperBuilder(
            JavaDeclaration owner, Set<String> active, int depth) {
        if (depth >= 32 || !active.add(owner.canonicalName()))
            return new SuperBuilderRejected("cyclic or over-budget hierarchy");
        try {
            var lookup = context.model.type(owner.canonicalName());
            if (!(lookup instanceof JavaModel.FoundType found))
                return new SuperBuilderRejected("ambiguous owner");
            var source = context.model.unit(found.declaration().source());
            var annotation = context.annotations(owner).get("SuperBuilder");
            if (!owner.complete()
                    || !owner.symbol().kind().equals("CLASS")
                    || annotation == null
                    || !annotation.values().isEmpty()
                    || context.annotations(owner).containsKey("Builder")
                    || !context.extension
                            .configurations
                            .forSource(source.path())
                            .uncertainCapabilities()
                            .isEmpty())
                return new SuperBuilderRejected("owner, options or configuration");
            if (context.model.parent(found.declaration()).isPresent()
                    && !owner.symbol().modifiers().contains("static"))
                return new SuperBuilderRejected("non-static nested owner");
            var custom =
                    context.model.type(
                            owner.binaryName() + "$" + owner.symbol().name() + "Builder");
            if (custom instanceof JavaModel.AmbiguousType
                    || custom instanceof JavaModel.FoundType existing
                            && context.model.declaration(existing.declaration()).origin()
                                    != JavaDeclaration.Origin.MODEL
                    || owner.members().stream()
                            .anyMatch(
                                    member ->
                                            member.symbol().kind().equals("METHOD")
                                                    && member.symbol().name().equals("builder")
                                                    && member.origin()
                                                            == JavaDeclaration.Origin.SOURCE))
                return new SuperBuilderRejected("custom builder or entry");
            for (var member : owner.members()) {
                if (member.symbol().kind().equals("TYPE_PARAMETER")
                        && (!member.complete()
                                || !member.annotations().isEmpty()
                                || !source.types()
                                        .get(member.symbol().declaredType())
                                        .annotations()
                                        .isEmpty()))
                    return new SuperBuilderRejected("annotated or incomplete type parameters");
                if (member.symbol().kind().equals("FIELD")
                        && member.annotations().stream()
                                .anyMatch(
                                        value ->
                                                Set.of(
                                                                        "Llombok/Singular;",
                                                                        "Llombok/Builder$ObtainVia;")
                                                                .contains(value.descriptor())
                                                        || LombokAnnotationCopies.needsModel(
                                                                value)))
                    return new SuperBuilderRejected("field propagation or collection options");
            }
            for (var field : owner.members()) {
                if (field.symbol().kind().equals("FIELD")
                        && context.lombokDeclarationRules.hasAnnotation(
                                field, "Llombok/Builder$Default;")
                        && (field.symbol().modifiers().contains("static")
                                || !context.lombokGeneratedMembers.initialized(field)
                                || context.lombokGeneratedMembers.hasAny(
                                        owner.members(),
                                        List.of("$default$" + field.symbol().name()),
                                        0)))
                    return new SuperBuilderRejected("invalid default or provider collision");
            }
            if (owner.superclass() < 0
                    || JavaTypeNames.erased(source.types(), owner.superclass())
                            .equals("java.lang.Object")) return new SuperBuilderReady(null);
            var parentUse = source.types().get(owner.superclass());
            var parentLookup =
                    context.model.type(JavaTypeNames.erased(source.types(), owner.superclass()));
            if (!(parentLookup instanceof JavaModel.FoundType parent))
                return new SuperBuilderRejected("parent source unavailable");
            var declaration = context.model.declaration(parent.declaration());
            long formals =
                    declaration.members().stream()
                            .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                            .count();
            if (parentUse.arguments().size() != formals)
                return new SuperBuilderRejected("raw parent arguments");
            var validation =
                    context.lombokBuilderRules.validateSuperBuilder(declaration, active, depth + 1);
            return validation instanceof SuperBuilderRejected rejected
                    ? rejected
                    : new SuperBuilderReady(declaration);
        } finally {
            active.remove(owner.canonicalName());
        }
    }

    void superBuilder(
            JavaDeclaration owner,
            Map<String, JavaAnnotation> annotations,
            List<JavaDeclaration> fields,
            List<JavaDeclaration> members,
            LombokCapabilitySummary policy,
            LombokCapabilitySummary aggregate) {
        if (!annotations.containsKey("SuperBuilder")) return;
        var validation = context.lombokBuilderRules.validateSuperBuilder(owner, new HashSet<>(), 0);
        if (policy.isPartial("super_builder") || validation instanceof SuperBuilderRejected) {
            aggregate.partial("super_builder");
            context.unsupported(
                    owner,
                    "SuperBuilder "
                            + (validation instanceof SuperBuilderRejected rejected
                                    ? rejected.reason()
                                    : "partial policy"));
            return;
        }
        JavaDeclaration parent = ((SuperBuilderReady) validation).parent();
        var formals =
                owner.members().stream()
                        .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                        .toList();
        String header = LombokBuilderRules.formalHeader(owner.signature());
        if (!formals.isEmpty() && header.isEmpty()
                || fields.stream()
                        .anyMatch(
                                field ->
                                        !field.complete()
                                                || context.lombokGeneratedMembers
                                                        .needsAnnotationCopies(field))) {
            aggregate.partial("super_builder");
            context.unsupported(owner, "SuperBuilder incomplete generics or field propagation");
            return;
        }
        for (var field : fields)
            if (context.lombokDeclarationRules.hasAnnotation(field, "Llombok/Builder$Default;"))
                context.lombokBuilderRules.builderDefault(owner, members, formals, header, field);
        Set<String> names = new HashSet<>();
        formals.forEach(formal -> names.add(formal.symbol().name()));
        String productName = LombokBuilderRules.freshFormal("C", names),
                selfName = LombokBuilderRules.freshFormal("B", names);
        String builderName = owner.symbol().name() + "Builder";
        String canonical = owner.canonicalName() + "." + builderName;
        String binary = owner.binaryName() + "$" + builderName;
        int product = context.types.size(), self = product + 1, builderType = product + 2;
        context.types.add(
                new JavaType(
                        JavaType.Kind.TYPEVAR,
                        productName,
                        productName,
                        -1,
                        List.of(),
                        List.of(owner.symbol().declaredType()),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of()));
        context.types.add(
                new JavaType(
                        JavaType.Kind.TYPEVAR,
                        selfName,
                        selfName,
                        -1,
                        List.of(),
                        List.of(builderType),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of()));
        List<Integer> arguments =
                new ArrayList<>(
                        formals.stream().map(formal -> formal.symbol().declaredType()).toList());
        arguments.add(product);
        arguments.add(self);
        context.lombokBuilderRules.declaredType(canonical, binary, arguments);
        var builderFormals =
                new ArrayList<>(
                        formals.stream()
                                .map(formal -> LombokBuilderRules.modeledFormal(formal, canonical))
                                .toList());
        builderFormals.add(context.lombokBuilderRules.typeFormal(canonical, productName, product));
        builderFormals.add(context.lombokBuilderRules.typeFormal(canonical, selfName, self));
        String ownerSignature =
                LombokBuilderRules.genericTypeSignature(owner.binaryName(), formals);
        String builderSignature = LombokBuilderRules.genericTypeSignature(binary, builderFormals);
        int superclass = context.lombokGeneratedMembers.named("java.lang.Object");
        if (parent != null) {
            var parentArguments =
                    new ArrayList<>(context.types.get(owner.superclass()).arguments());
            parentArguments.add(product);
            parentArguments.add(self);
            String parentBuilder = parent.symbol().name() + "Builder";
            superclass =
                    context.lombokBuilderRules.declaredType(
                            parent.canonicalName() + "." + parentBuilder,
                            parent.binaryName() + "$" + parentBuilder,
                            parentArguments);
        }
        String extendedHeader =
                (header.isEmpty() ? "<" : header.substring(0, header.length() - 1))
                        + productName
                        + ":"
                        + ownerSignature
                        + selfName
                        + ":"
                        + builderSignature
                        + ">";
        var builder =
                context.lombokBuilderRules.modeledClass(
                        owner,
                        builderName,
                        builderType,
                        extendedHeader + context.lombokBuilderRules.typeSignature(superclass, 0),
                        superclass,
                        List.of("public", "static", "abstract"));
        List<JavaDeclaration> builderMembers = new ArrayList<>(builderFormals);
        context.lombokGeneratedMembers.method(
                builder,
                builderMembers,
                "<init>",
                List.of(),
                context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                "()V",
                List.of("public"),
                SourceSpan.ABSENT,
                "@SuperBuilder");
        int constructor = builderMembers.size() - 1;
        var implicit = builderMembers.get(constructor);
        builderMembers.set(
                constructor,
                new JavaDeclaration(
                        implicit.symbol(),
                        implicit.canonicalName(),
                        implicit.binaryName(),
                        implicit.signature(),
                        implicit.span(),
                        JavaDeclaration.Origin.COMPILER_SYNTHETIC,
                        implicit.superclass(),
                        implicit.interfaces(),
                        implicit.permitted(),
                        implicit.annotations(),
                        implicit.constant(),
                        implicit.annotationDefault(),
                        implicit.members(),
                        implicit.incompleteReasons()));
        context.generated.remove(canonical + "#" + builderName + "()");
        for (var field : fields) {
            if (field.symbol().modifiers().contains("static")
                    || field.symbol().name().startsWith("$")
                    || field.symbol().modifiers().contains("final")
                            && context.lombokGeneratedMembers.initialized(field)
                            && !context.lombokDeclarationRules.hasAnnotation(
                                    field, "Llombok/Builder$Default;")) continue;
            boolean defaulted =
                    context.lombokDeclarationRules.hasAnnotation(field, "Llombok/Builder$Default;");
            int fieldType = context.lombokGeneratedMembers.writtenFieldType(field);
            var storage =
                    new JavaDeclaration(
                            new JavaSymbol(
                                    "FIELD",
                                    canonical,
                                    field.symbol().name() + (defaulted ? "$value" : ""),
                                    context.lombokGeneratedMembers.descriptor(fieldType),
                                    fieldType,
                                    List.of("private"),
                                    false),
                            "",
                            "",
                            field.signature(),
                            field.span(),
                            JavaDeclaration.Origin.MODEL,
                            -1,
                            List.of(),
                            List.of(),
                            List.of(),
                            null,
                            null,
                            List.of(),
                            List.of());
            builderMembers.add(storage);
            context.generated.put(fieldId(builder, storage), origin("@SuperBuilder"));
            if (defaulted) {
                int flagType = context.lombokGeneratedMembers.primitive(JavaType.Kind.BOOLEAN);
                var flag =
                        new JavaDeclaration(
                                new JavaSymbol(
                                        "FIELD",
                                        canonical,
                                        field.symbol().name() + "$set",
                                        "Z",
                                        flagType,
                                        List.of("private"),
                                        false),
                                "",
                                "",
                                "Z",
                                field.span(),
                                JavaDeclaration.Origin.MODEL,
                                -1,
                                List.of(),
                                List.of(),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                List.of());
                builderMembers.add(flag);
                context.generated.put(fieldId(builder, flag), origin("@Builder.Default"));
            }
            context.lombokGeneratedMembers.method(
                    builder,
                    builderMembers,
                    field.symbol().name(),
                    List.of(field),
                    self,
                    "(" + field.signature() + ")T" + selfName + ";",
                    List.of("public"),
                    field.span(),
                    "@SuperBuilder",
                    context.lombokGeneratedMembers.copiedMethodAnnotations(field, false));
        }
        context.lombokGeneratedMembers.method(
                builder,
                builderMembers,
                "self",
                List.of(),
                self,
                "()T" + selfName + ";",
                List.of("protected", "abstract"),
                owner.span(),
                "@SuperBuilder");
        context.lombokGeneratedMembers.method(
                builder,
                builderMembers,
                "build",
                List.of(),
                product,
                "()T" + productName + ";",
                List.of("public", "abstract"),
                owner.span(),
                "@SuperBuilder");
        context.lombokGeneratedMembers.method(
                builder,
                builderMembers,
                "toString",
                List.of(),
                context.lombokGeneratedMembers.named("java.lang.String"),
                "()Ljava/lang/String;",
                List.of("public"),
                owner.span(),
                "@SuperBuilder");
        members.add(copy(builder, builderMembers, List.of()));
        context.generated.put(canonical, origin("@SuperBuilder"));
        int wildcard = context.types.size();
        context.types.add(
                new JavaType(
                        JavaType.Kind.WILDCARD,
                        "?",
                        "?",
                        -1,
                        List.of(),
                        List.of(),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of()));
        var publicArguments =
                new ArrayList<>(
                        formals.stream().map(formal -> formal.symbol().declaredType()).toList());
        publicArguments.add(wildcard);
        publicArguments.add(wildcard);
        int publicBuilder =
                context.lombokBuilderRules.declaredType(canonical, binary, publicArguments);
        String publicSignature = context.lombokBuilderRules.typeSignature(publicBuilder, 0);
        members.removeIf(
                member ->
                        member.symbol().kind().equals("CONSTRUCTOR")
                                && member.origin() == JavaDeclaration.Origin.COMPILER_SYNTHETIC);
        context.lombokGeneratedMembers.method(
                owner,
                members,
                "<init>",
                List.of(context.lombokGeneratedMembers.parameter(owner, "b", publicBuilder)),
                context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                "(" + publicSignature + ")V",
                List.of("protected"),
                owner.span(),
                "@SuperBuilder");
        if (!owner.symbol().modifiers().contains("abstract")) {
            String implName = owner.symbol().name() + "BuilderImpl",
                    implBinary = owner.binaryName() + "$" + implName;
            int implType =
                    context.lombokBuilderRules.declaredType(
                            owner.canonicalName() + "." + implName,
                            implBinary,
                            formals.stream()
                                    .map(formal -> formal.symbol().declaredType())
                                    .toList());
            var concreteArguments =
                    new ArrayList<>(
                            formals.stream()
                                    .map(formal -> formal.symbol().declaredType())
                                    .toList());
            concreteArguments.add(owner.symbol().declaredType());
            concreteArguments.add(implType);
            int concreteBuilder =
                    context.lombokBuilderRules.declaredType(canonical, binary, concreteArguments);
            var impl =
                    context.lombokBuilderRules.modeledClass(
                            owner,
                            implName,
                            implType,
                            header + context.lombokBuilderRules.typeSignature(concreteBuilder, 0),
                            concreteBuilder,
                            List.of("private", "static", "final"));
            var implMembers =
                    new ArrayList<>(
                            formals.stream()
                                    .map(
                                            formal ->
                                                    LombokBuilderRules.modeledFormal(
                                                            formal, impl.canonicalName()))
                                    .toList());
            context.lombokGeneratedMembers.method(
                    impl,
                    implMembers,
                    "<init>",
                    List.of(),
                    context.lombokGeneratedMembers.primitive(JavaType.Kind.VOID),
                    "()V",
                    List.of("private"),
                    owner.span(),
                    "@SuperBuilder");
            context.lombokGeneratedMembers.method(
                    impl,
                    implMembers,
                    "self",
                    List.of(),
                    implType,
                    "()" + context.lombokBuilderRules.typeSignature(implType, 0),
                    List.of("protected"),
                    owner.span(),
                    "@SuperBuilder");
            context.lombokGeneratedMembers.method(
                    impl,
                    implMembers,
                    "build",
                    List.of(),
                    owner.symbol().declaredType(),
                    "()" + ownerSignature,
                    List.of("public"),
                    owner.span(),
                    "@SuperBuilder");
            members.add(copy(impl, implMembers, List.of()));
            context.generated.put(impl.canonicalName(), origin("@SuperBuilder"));
            context.lombokGeneratedMembers.method(
                    owner,
                    members,
                    "builder",
                    List.of(),
                    publicBuilder,
                    header + "()" + publicSignature,
                    List.of("public", "static"),
                    owner.span(),
                    "@SuperBuilder");
            context.lombokBuilderRules.methodFormals(members, formals);
        }
        aggregate.complete("super_builder");
    }

    static String freshFormal(String base, Set<String> names) {
        String name = base;
        for (int suffix = 2; !names.add(name); suffix++) name = base + suffix;
        return name;
    }

    int declaredType(String canonical, String binary, List<Integer> arguments) {
        int reference = context.types.size();
        context.types.add(
                new JavaType(
                        JavaType.Kind.DECLARED,
                        canonical,
                        canonical,
                        -1,
                        arguments,
                        List.of(),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of(),
                        List.of(),
                        binary));
        return reference;
    }

    JavaDeclaration modeledClass(
            JavaDeclaration owner,
            String name,
            int type,
            String signature,
            int superclass,
            List<String> modifiers) {
        var declared = context.types.get(type);
        return new JavaDeclaration(
                new JavaSymbol(
                        "CLASS",
                        owner.canonicalName(),
                        name,
                        context.lombokGeneratedMembers.descriptor(type),
                        type,
                        modifiers,
                        false),
                declared.name(),
                declared.binaryName(),
                signature,
                owner.span(),
                JavaDeclaration.Origin.MODEL,
                superclass,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                List.of());
    }

    JavaDeclaration typeFormal(String owner, String name, int type) {
        return new JavaDeclaration(
                new JavaSymbol(
                        "TYPE_PARAMETER",
                        owner,
                        name,
                        context.lombokGeneratedMembers.descriptor(type),
                        type,
                        List.of(),
                        false),
                "",
                "",
                "T" + name + ";",
                SourceSpan.ABSENT,
                JavaDeclaration.Origin.MODEL,
                -1,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                List.of());
    }

    String typeSignature(int reference, int depth) {
        if (depth >= 128)
            throw new IllegalArgumentException("SuperBuilder signature exceeds budget");
        var type = context.types.get(reference);
        return switch (type.kind()) {
            case TYPEVAR -> "T" + type.name() + ";";
            case ARRAY ->
                    "[" + context.lombokBuilderRules.typeSignature(type.component(), depth + 1);
            case WILDCARD ->
                    type.lowerBound() >= 0
                            ? "-"
                                    + context.lombokBuilderRules.typeSignature(
                                            type.lowerBound(), depth + 1)
                            : type.upperBounds().isEmpty()
                                    ? "*"
                                    : "+"
                                            + context.lombokBuilderRules.typeSignature(
                                                    type.upperBounds().getFirst(), depth + 1);
            case DECLARED ->
                    "L"
                            + (type.binaryName().isEmpty() ? type.name() : type.binaryName())
                                    .replace('.', '/')
                            + (type.arguments().isEmpty()
                                    ? ""
                                    : "<"
                                            + String.join(
                                                    "",
                                                    type.arguments().stream()
                                                            .map(
                                                                    argument ->
                                                                            context
                                                                                    .lombokBuilderRules
                                                                                    .typeSignature(
                                                                                            argument,
                                                                                            depth
                                                                                                    + 1))
                                                            .toList())
                                            + ">")
                            + ";";
            default -> context.lombokGeneratedMembers.descriptor(reference);
        };
    }

    static boolean hasNamedMethod(List<JavaDeclaration> members, String name) {
        return members.stream()
                .anyMatch(
                        member ->
                                member.symbol().kind().equals("METHOD")
                                        && member.symbol().name().equals(name));
    }

    void methodFormals(List<JavaDeclaration> members, List<JavaDeclaration> formals) {
        if (formals.isEmpty()) return;
        int index = members.size() - 1;
        var method = members.get(index);
        var children = new ArrayList<>(method.members());
        children.addAll(
                formals.stream()
                        .map(
                                formal ->
                                        LombokBuilderRules.modeledFormal(
                                                formal, method.symbol().owner()))
                        .toList());
        members.set(index, copy(method, children, method.incompleteReasons()));
    }

    static JavaDeclaration modeledFormal(JavaDeclaration formal, String owner) {
        var symbol = formal.symbol();
        return new JavaDeclaration(
                new JavaSymbol(
                        symbol.kind(),
                        owner,
                        symbol.name(),
                        symbol.descriptor(),
                        symbol.declaredType(),
                        symbol.modifiers(),
                        symbol.variadic()),
                formal.canonicalName(),
                formal.binaryName(),
                formal.signature(),
                formal.span(),
                JavaDeclaration.Origin.MODEL,
                formal.superclass(),
                formal.interfaces(),
                formal.permitted(),
                formal.annotations(),
                formal.constant(),
                formal.annotationDefault(),
                formal.members(),
                formal.incompleteReasons());
    }

    static String genericTypeSignature(String binary, List<JavaDeclaration> formals) {
        return "L"
                + binary.replace('.', '/')
                + (formals.isEmpty()
                        ? ""
                        : "<"
                                + String.join(
                                        "",
                                        formals.stream()
                                                .map(formal -> "T" + formal.symbol().name() + ";")
                                                .toList())
                                + ">")
                + ";";
    }

    static String returnSignature(String signature) {
        int close = signature.indexOf(')');
        if (close < 0 || close + 1 >= signature.length())
            throw new IllegalArgumentException("Incomplete Lombok method signature");
        int exception = signature.indexOf('^', close + 1);
        return signature.substring(close + 1, exception < 0 ? signature.length() : exception);
    }

    static String formalHeader(String signature) {
        if (signature == null || !signature.startsWith("<")) return "";
        int depth = 0;
        for (int i = 0; i < signature.length(); i++) {
            if (i > 100_000)
                throw new IllegalArgumentException("Lombok formal signature exceeds budget");
            if (signature.charAt(i) == '<') depth++;
            else if (signature.charAt(i) == '>' && --depth == 0)
                return signature.substring(0, i + 1);
        }
        throw new IllegalArgumentException("Incomplete Lombok formal signature");
    }

    boolean identifier(String value, boolean emptyAllowed) {
        return value.isEmpty()
                ? emptyAllowed
                : value.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                        && !Set.of(
                                        "abstract",
                                        "assert",
                                        "boolean",
                                        "break",
                                        "byte",
                                        "case",
                                        "catch",
                                        "char",
                                        "class",
                                        "const",
                                        "continue",
                                        "default",
                                        "do",
                                        "double",
                                        "else",
                                        "enum",
                                        "extends",
                                        "final",
                                        "finally",
                                        "float",
                                        "for",
                                        "goto",
                                        "if",
                                        "implements",
                                        "import",
                                        "instanceof",
                                        "int",
                                        "interface",
                                        "long",
                                        "native",
                                        "new",
                                        "package",
                                        "private",
                                        "protected",
                                        "public",
                                        "return",
                                        "short",
                                        "static",
                                        "strictfp",
                                        "super",
                                        "switch",
                                        "synchronized",
                                        "this",
                                        "throw",
                                        "throws",
                                        "transient",
                                        "try",
                                        "void",
                                        "volatile",
                                        "while",
                                        "true",
                                        "false",
                                        "null",
                                        "_")
                                .contains(value);
    }
}
