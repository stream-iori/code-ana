package com.anatomist.framework.lombok;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.JavaModelAnnotations;
import com.anatomist.framework.JavaModelExtension;
import com.anatomist.framework.JavaUnitAnalysis;
import com.anatomist.model.ProducerIds;

import java.nio.file.Path;
import java.util.*;

/**
 * Signature-only detached Lombok overlay. No compiler, processor, AST or method body is created.
 */
public final class LombokJavaModelExtension implements JavaModelExtension {
    public static final String VERSION = "detached-19";
    private static final Map<String, String> LOG_TYPES =
            Map.ofEntries(
                    Map.entry("Slf4j", "org.slf4j.Logger"),
                            Map.entry("XSlf4j", "org.slf4j.ext.XLogger"),
                    Map.entry("Log", "java.util.logging.Logger"),
                            Map.entry("Log4j", "org.apache.log4j.Logger"),
                    Map.entry("Log4j2", "org.apache.logging.log4j.Logger"),
                            Map.entry("CommonsLog", "org.apache.commons.logging.Log"),
                    Map.entry("JBossLog", "org.jboss.logging.Logger"),
                            Map.entry("Flogger", "com.google.common.flogger.FluentLogger"));
    private static final Set<String> BASIC =
            Set.of(
                    "Getter",
                    "Setter",
                    "NoArgsConstructor",
                    "RequiredArgsConstructor",
                    "AllArgsConstructor",
                    "Data",
                    "Value",
                    "ToString",
                    "EqualsAndHashCode",
                    "NonNull",
                    "Builder",
                    "SuperBuilder",
                    "Accessors",
                    "With",
                    "CustomLog",
                    "Cleanup",
                    "SneakyThrows",
                    "Synchronized",
                    "Locked",
                    "Delegate",
                    "UtilityClass",
                    "ExtensionMethod");
    private final Path project;
    private final boolean strict;
    private final LombokConfigurationScope configurations;

    public LombokJavaModelExtension(Path project, boolean strict) {
        this.project = project.toAbsolutePath().normalize();
        this.strict = strict;
        configurations = new LombokConfigurationScope(this.project);
    }

    /** Explicit configuration inventory; code-ana does not discover lombok.config. */
    public LombokJavaModelExtension(Path project, boolean strict, Map<Path, String> capturedConfigurations) {
        this.project = project.toAbsolutePath().normalize();
        this.strict = strict;
        configurations = new LombokConfigurationScope(this.project, capturedConfigurations);
    }

    @Override
    public String id() {
        return ProducerIds.LOMBOK_AST;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String fingerprintMaterial() {
        return "strict=" + strict + "|config=" + configurations.fingerprint();
    }

    @Override
    public void verifyInputs() {
        configurations.verify();
    }

    @Override
    public JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries) {
        Set<JavaUnit> pending = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var unit : context.units())
            if (!unit.extensions().counters().containsKey("lombok_files_seen")
                    && (unit.hasProcessorEvidence() || usesLombok(unit.declarationInventory())))
                pending.add(unit);
        if (pending.isEmpty()) return context;
        List<JavaUnit> units = new ArrayList<>();
        try (var model = new JavaModel(context)) {
            for (var unit : context.units()) {
                // Dependency catalogues already carry the overlay; repeated application is
                // idempotent.
                if (!pending.contains(unit)) units.add(unit);
                else units.add(new Overlay(context, model, unit, binaries).apply());
            }
        }
        return new JavaAnalysisContext(
                context.module(),
                context.scope(),
                units,
                context.publish(),
                context.diagnostics(),
                context.analysisFailure(),
                context.sourceIdentities(),
                context.classpath(),
                context.classpathSource(),
                context.classpathEntries(),
                context.targetRelease());
    }

    private static Boolean generatedSince34(String version) {
        String[] components = version.split("[.-]", 4);
        if (components.length < 3) return null;
        try {
            int major = Integer.parseInt(components[0]),
                    minor = Integer.parseInt(components[1]),
                    patch = Integer.parseInt(components[2]);
            return major > 1 || major == 1 && (minor > 18 || minor == 18 && patch >= 34);
        } catch (NumberFormatException unknown) {
            return null;
        }
    }

    private static boolean usesLombok(List<JavaDeclaration> declarations) {
        for (var declaration : declarations)
            if (!annotations(declaration).isEmpty() || usesLombok(declaration.members()))
                return true;
        return false;
    }

    private final class Overlay {
        private final JavaAnalysisContext context;
        private final JavaModel model;
        private final JavaUnit unit;
        private final LombokConfiguration configuration;
        private final JavaBinaryRepository binaries;
        private final List<JavaType> types;
        private final Map<JavaDeclaration, JavaModel.DeclarationRef> references =
                new IdentityHashMap<>();
        private final Map<String, JavaGeneratedOrigin> generated;
        private final Map<String, Map<String, Object>> metadata;
        private final List<IndexDiagnostic> diagnostics;
        private final Map<String, Long> counters;
        private boolean seen;
        private JavaModelAnnotations compiledAnnotations;
        private final Map<JavaDeclaration, Map<String, JavaAnnotation>> effectiveAnnotations =
                new IdentityHashMap<>();
        private final Map<JavaDeclaration, Integer> generatedTypes = new IdentityHashMap<>();
        private final Map<String, JavaDeclaration> customBuilders = new HashMap<>();

        private Overlay(
                JavaAnalysisContext context,
                JavaModel model,
                JavaUnit unit,
                JavaBinaryRepository binaries) {
            this.context = context;
            this.model = model;
            this.unit = unit;
            configuration = configurations.forSource(unit.path());
            this.binaries = binaries;
            types = new ArrayList<>(unit.types().entries());
            for (var reference : model.declarations(unit.path()))
                references.put(model.declaration(reference), reference);
            generated = new LinkedHashMap<>(unit.extensions().generated());
            metadata = new LinkedHashMap<>(unit.extensions().declarationMetadata());
            diagnostics = new ArrayList<>(unit.extensions().diagnostics());
            counters = new LinkedHashMap<>(unit.extensions().counters());
        }

        private Boolean generatedAnnotationDefault;
        private boolean generatedAnnotationChecked;

        private boolean addGeneratedAnnotation() {
            if (configuration.addLombokGeneratedAnnotation() != null)
                return configuration.addLombokGeneratedAnnotation();
            if (!generatedAnnotationChecked) {
                generatedAnnotationChecked = true;
                String version =
                        binaries == null
                                ? ""
                                : binaries.artifactAttribute("lombok.Getter", "Lombok-Version")
                                        .orElse("");
                generatedAnnotationDefault = generatedSince34(version);
            }
            return Boolean.TRUE.equals(generatedAnnotationDefault);
        }

        private boolean generatedAnnotationKnown() {
            addGeneratedAnnotation();
            return configuration.addLombokGeneratedAnnotation() != null
                    || generatedAnnotationDefault != null;
        }

        private JavaDeclaration generatedAnnotations(JavaDeclaration declaration) {
            List<JavaDeclaration> members =
                    declaration.members().stream().map(this::generatedAnnotations).toList();
            List<JavaAnnotation> annotations = declaration.annotations();
            if (declaration.origin() == JavaDeclaration.Origin.MODEL
                    && Set.of("CLASS", "METHOD", "CONSTRUCTOR", "FIELD")
                            .contains(declaration.symbol().kind())) {
                annotations = new ArrayList<>(annotations);
                if (addGeneratedAnnotation())
                    annotations.add(
                            new JavaAnnotation(
                                    "Llombok/Generated;",
                                    JavaAnnotation.Retention.CLASS,
                                    Map.of()));
                if (configuration.addJavaxGeneratedAnnotation())
                    annotations.add(
                            new JavaAnnotation(
                                    "Ljavax/annotation/Generated;",
                                    JavaAnnotation.Retention.SOURCE,
                                    Map.of(
                                            "value",
                                            new JavaBinaryType.ArrayValue(
                                                    List.of(
                                                            new JavaBinaryType.Scalar(
                                                                    "lombok"))))));
                if (configuration.addSuppressWarnings())
                    annotations.add(
                            new JavaAnnotation(
                                    "Ljava/lang/SuppressWarnings;",
                                    JavaAnnotation.Retention.SOURCE,
                                    Map.of(
                                            "value",
                                            new JavaBinaryType.ArrayValue(
                                                    List.of(new JavaBinaryType.Scalar("all"))))));
            }
            return new JavaDeclaration(
                    declaration.symbol(),
                    declaration.canonicalName(),
                    declaration.binaryName(),
                    declaration.signature(),
                    declaration.span(),
                    declaration.origin(),
                    declaration.superclass(),
                    declaration.interfaces(),
                    declaration.permitted(),
                    annotations,
                    declaration.constant(),
                    declaration.annotationDefault(),
                    members,
                    declaration.incompleteReasons(),
                    declaration.overrides(),
                    declaration.deprecated());
        }

        private JavaUnit apply() {
            var augmented =
                    unit.declarationInventory().stream()
                            .map(this::valueModifiers)
                            .map(this::type)
                            .toList();
            var inventory =
                    augmented.stream()
                            .map(
                                    declaration ->
                                            customBuilders.getOrDefault(
                                                    declaration.binaryName(), declaration))
                            .map(this::generatedAnnotations)
                            .toList();
            if (!seen) return unit;
            increment("lombok_files_seen", 1);
            if (configuration.partial())
                diagnostic(
                        "LOMBOK_CONFIG_PARTIAL",
                        null,
                        "Some effective Lombok configuration settings are not modeled.");
            if (generated.size() > unit.extensions().generated().size())
                diagnostic(
                        "LOMBOK_BODY_SEMANTICS_OMITTED",
                        null,
                        "Generated declarations have signatures only; method bodies are unavailable.");
            return new JavaUnit(
                    unit.path(),
                    unit.tree(),
                    unit.processing(),
                    unit.bindings(),
                    new JavaTypes(types),
                    unit.declarations(),
                    inventory,
                    unit.sourceHash(),
                    new JavaExtensionData(generated, metadata, diagnostics, counters));
        }

        private JavaDeclaration valueModifiers(JavaDeclaration owner) {
            if (unit.hasProcessorEvidence()
                    || !annotations(owner).containsKey("Value")
                    || !owner.symbol().kind().equals("CLASS")
                    || configuration.uncertainCapabilities().contains("*")
                    || configuration
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
                        && !hasAnnotation(member, "Llombok/experimental/PackagePrivate;"))
                    modifiers.add("private");
                if (!modifiers.contains("final")
                        && !hasAnnotation(member, "Llombok/experimental/NonFinal;"))
                    modifiers.add("final");
                var replacement = withModifiers(member, modifiers, member.members());
                if (!member.symbol().modifiers().contains("final")
                        && modifiers.contains("final")
                        && initialized(member)
                        && constantType(member)) replacement = valueConstant(member, replacement);
                members.add(replacement);
            }
            var modifiers = new ArrayList<>(owner.symbol().modifiers());
            if (!modifiers.contains("final")
                    && !hasAnnotation(owner, "Llombok/experimental/NonFinal;"))
                modifiers.add("final");
            return withModifiers(owner, modifiers, members);
        }

        private boolean constantType(JavaDeclaration field) {
            JavaType type = types.get(field.symbol().declaredType());
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
                    || type.kind() == JavaType.Kind.DECLARED
                            && type.name().equals("java.lang.String");
        }

        private JavaDeclaration valueConstant(
                JavaDeclaration original, JavaDeclaration replacement) {
            JavaNode expression =
                    model.syntax(references.get(original)).orElseThrow().tree("getInitializer");
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS", "NEW_ARRAY", "NULL_LITERAL")
                    .contains(expression.kind())) return replacement;
            if (compiledAnnotations == null)
                compiledAnnotations =
                        new JavaModelAnnotations(
                                new JavaUnitAnalysis(
                                        new JavaFrameworkContext(
                                                project,
                                                List.of(unit.path()),
                                                new com.anatomist.config.ProjectConfig(),
                                                false),
                                        model,
                                        binaries,
                                        unit.path(),
                                        context.identity(unit.path())));
            var constant = compiledAnnotations.constant(unit.path(), expression);
            if (!(constant instanceof JavaBinaryType.Scalar scalar))
                return incomplete(replacement, "lombok:value-constant-unavailable");
            Object value = scalar.value();
            var kind = types.get(original.symbol().declaredType()).kind();
            if (!assignableConstant(kind, value))
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
            references.put(result, references.get(original));
            return result;
        }

        private boolean assignableConstant(JavaType.Kind kind, Object value) {
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
                        value instanceof Integer
                                || value instanceof Long
                                || value instanceof Character;
                case FLOAT ->
                        value instanceof Number && !(value instanceof Double)
                                || value instanceof Character;
                case DOUBLE -> value instanceof Number || value instanceof Character;
                case DECLARED -> value instanceof String;
                default -> false;
            };
        }

        private JavaDeclaration withModifiers(
                JavaDeclaration declaration,
                List<String> modifiers,
                List<JavaDeclaration> members) {
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
            references.put(replacement, references.get(declaration));
            return replacement;
        }

        private boolean hasAnnotation(JavaDeclaration declaration, String descriptor) {
            return declaration.annotations().stream()
                    .anyMatch(annotation -> annotation.descriptor().equals(descriptor));
        }

        private JavaDeclaration type(JavaDeclaration owner) {
            if (unit.hasProcessorEvidence()) return compiledType(owner);
            var annotations = annotations(owner);
            if (annotations.containsKey("UtilityClass")) return utilityClass(owner, annotations);
            List<JavaDeclaration> fields =
                    owner.members().stream()
                            .filter(member -> member.symbol().kind().equals("FIELD"))
                            .toList();
            boolean detected =
                    !annotations.isEmpty()
                            || owner.members().stream()
                                    .anyMatch(member -> !annotations(member).isEmpty());
            if (!detected) return owner;
            seen = true;
            if (!Set.of("CLASS", "ENUM").contains(owner.symbol().kind())
                    || owner.canonicalName().isEmpty()) {
                diagnostic(
                        "LOMBOK_FEATURE_UNSUPPORTED",
                        owner.symbol().name(),
                        "Detached Lombok modeling requires a named class or enum.");
                return incomplete(owner, "lombok:unsupported-owner");
            }
            var policy = summary(annotations);
            var aggregate = new LombokCapabilitySummary();
            aggregate.merge(policy);
            List<JavaDeclaration> members = new ArrayList<>(owner.members());
            int before = generated.size();
            boolean data = annotations.containsKey("Data"),
                    value = annotations.containsKey("Value");
            if (value
                    && owner.symbol().kind().equals("CLASS")
                    && !configuration.uncertainCapabilities().contains("*")
                    && !configuration
                            .uncertainCapabilities()
                            .contains(LombokCapabilitySummary.VALUE_MODIFIERS))
                aggregate.complete(LombokCapabilitySummary.VALUE_MODIFIERS);
            for (var field : fields) {
                var fieldAnnotations = annotations(field);
                var fieldPolicy = summary(fieldAnnotations);
                if (annotations.containsKey("Accessors")
                        || fieldAnnotations.containsKey("Accessors")) {
                    if (data
                            || value
                            || annotations.containsKey("Getter")
                            || fieldAnnotations.containsKey("Getter"))
                        fieldPolicy.partial(LombokCapabilitySummary.GETTER);
                    if (data
                            || annotations.containsKey("Setter")
                            || fieldAnnotations.containsKey("Setter"))
                        fieldPolicy.partial(LombokCapabilitySummary.SETTER);
                }
                if (!field.symbol().name().startsWith("$")) {
                    var getter = fieldAnnotations.getOrDefault("Getter", annotations.get("Getter"));
                    var setter = fieldAnnotations.getOrDefault("Setter", annotations.get("Setter"));
                    boolean instance = !field.symbol().modifiers().contains("static");
                    if ((instance && (data || value || getter != null)
                                    || fieldAnnotations.containsKey("Getter"))
                            && !policy.isPartial(LombokCapabilitySummary.GETTER)
                            && !fieldPolicy.isPartial(LombokCapabilitySummary.GETTER)) {
                        var access = access(getter, "value");
                        if (access == null || !field.complete())
                            fieldPolicy.partial(LombokCapabilitySummary.GETTER);
                        else if (!access.equals("NONE")) {
                            String name = getterName(field);
                            if (!hasAny(members, getterAlternatives(field), 0))
                                method(
                                        owner,
                                        members,
                                        name,
                                        List.of(),
                                        generatedType(field),
                                        "()" + field.signature(),
                                        modifiers(access, !instance),
                                        field.span(),
                                        getter != null ? "@Getter" : data ? "@Data" : "@Value",
                                        copiedMethodAnnotations(field, true));
                        }
                    }
                    if ((instance && (data || setter != null)
                                    || fieldAnnotations.containsKey("Setter"))
                            && !value
                            && !field.symbol().modifiers().contains("final")
                            && !policy.isPartial(LombokCapabilitySummary.SETTER)
                            && !fieldPolicy.isPartial(LombokCapabilitySummary.SETTER)) {
                        var access = access(setter, "value");
                        if (access == null || !field.complete())
                            fieldPolicy.partial(LombokCapabilitySummary.SETTER);
                        else if (!access.equals("NONE")) {
                            String name = "set" + booleanStem(field);
                            if (!hasAny(members, List.of(name), 1))
                                method(
                                        owner,
                                        members,
                                        name,
                                        List.of(field),
                                        primitive(JavaType.Kind.VOID),
                                        "(" + field.signature() + ")V",
                                        modifiers(access, !instance),
                                        field.span(),
                                        setter != null ? "@Setter" : "@Data",
                                        copiedMethodAnnotations(field, false));
                        }
                    }
                }
                if (fieldPolicy.hasEvidence())
                    put(fieldId(owner, field), "lombok", fieldPolicy.toMap());
                aggregate.merge(fieldPolicy);
                // Annotation propagation and lazy backing-field rewrites need their own proof.
                if (needsAnnotationCopies(field)) aggregate.partial("annotation_propagation");
            }
            constructors(owner, annotations, fields, members, policy, aggregate);
            if (data
                    || value
                    || annotations.containsKey("ToString")
                    || annotations.containsKey("EqualsAndHashCode")) {
                String source = data ? "@Data" : value ? "@Value" : "@EqualsAndHashCode";
                if ((data || value || annotations.containsKey("EqualsAndHashCode"))
                        && !policy.isPartial(LombokCapabilitySummary.EQUALS_HASH_CODE)
                        && !hasAny(members, List.of("equals"), 1)
                        && !hasAny(members, List.of("hashCode"), 0)) {
                    int object = named("java.lang.Object");
                    var parameter = parameter(owner, "other", object);
                    method(
                            owner,
                            members,
                            "equals",
                            List.of(parameter),
                            primitive(JavaType.Kind.BOOLEAN),
                            "(Ljava/lang/Object;)Z",
                            List.of("public"),
                            owner.span(),
                            source);
                    method(
                            owner,
                            members,
                            "hashCode",
                            List.of(),
                            primitive(JavaType.Kind.INT),
                            "()I",
                            List.of("public"),
                            owner.span(),
                            source);
                    if (!owner.symbol().modifiers().contains("final")
                            || owner.superclass() >= 0
                                    && !JavaTypeNames.erased(unit.types(), owner.superclass())
                                            .equals("java.lang.Object"))
                        if (!hasAny(members, List.of("canEqual"), 1))
                            method(
                                    owner,
                                    members,
                                    "canEqual",
                                    List.of(parameter),
                                    primitive(JavaType.Kind.BOOLEAN),
                                    "(Ljava/lang/Object;)Z",
                                    List.of("protected"),
                                    owner.span(),
                                    source);
                }
                if ((data || value || annotations.containsKey("ToString"))
                        && !policy.isPartial(LombokCapabilitySummary.TO_STRING)
                        && !hasAny(members, List.of("toString"), 0))
                    method(
                            owner,
                            members,
                            "toString",
                            List.of(),
                            named("java.lang.String"),
                            "()Ljava/lang/String;",
                            List.of("public"),
                            owner.span(),
                            data ? "@Data" : value ? "@Value" : "@ToString");
                if (value && aggregate.isPartial(LombokCapabilitySummary.VALUE_MODIFIERS))
                    diagnostic(
                            "LOMBOK_VALUE_MODIFIER_SEMANTICS_OMITTED",
                            owner.canonicalName(),
                            "The signature model does not rewrite explicit field/type modifiers.");
            }
            logger(owner, annotations, members, policy, aggregate);
            builder(owner, annotations, fields, members, policy, aggregate);
            superBuilder(owner, annotations, fields, members, policy, aggregate);
            int count = generated.size() - before;
            if (count > 0 && !generatedAnnotationKnown()) {
                aggregate.partial("generated_annotations");
                unsupported(
                        owner,
                        "Generated annotation policy requires a Lombok manifest version or explicit configuration");
            }
            if (count > 0) {
                increment("lombok_types_augmented", 1);
                increment("lombok_members_generated", count);
            }
            aggregate.generatedMemberCount(count);
            put(owner.canonicalName(), "lombok", aggregate.toMap());
            var replacement = copy(owner, members, owner.incompleteReasons());
            if (!aggregate.toMap().get("coverage").equals("complete")) {
                unsupported(owner, "partial or unmodeled capabilities " + aggregate.toMap());
                replacement = incomplete(replacement, "lombok:incomplete-capabilities");
            }
            return replacement;
        }

        private JavaDeclaration utilityClass(
                JavaDeclaration owner, Map<String, JavaAnnotation> annotations) {
            seen = true;
            var policy = summary(annotations);
            // The first bounded case already has static source members. Rewriting instance
            // members, nested owners or other generators needs separate declaration evidence.
            boolean supported =
                    owner.symbol().kind().equals("CLASS")
                            && model.parent(references.get(owner)).isEmpty()
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
                                    || JavaTypeNames.erased(unit.types(), owner.superclass())
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
            if (!supported || !generatedAnnotationKnown()) {
                policy.partial("utility_class");
                put(owner.canonicalName(), "lombok", policy.toMap());
                unsupported(
                        owner,
                        "UtilityClass requires a top-level class with explicit static members and no other generators");
                return incomplete(owner, "lombok:incomplete-utility-class");
            }
            var members = new ArrayList<>(owner.members());
            members.removeIf(member -> member.symbol().kind().equals("CONSTRUCTOR"));
            method(
                    owner,
                    members,
                    "<init>",
                    List.of(),
                    primitive(JavaType.Kind.VOID),
                    "()V",
                    List.of("private"),
                    owner.span(),
                    "@UtilityClass");
            var modifiers = new ArrayList<>(owner.symbol().modifiers());
            if (!modifiers.contains("final")) modifiers.add("final");
            policy.generatedMemberCount(1);
            put(owner.canonicalName(), "lombok", policy.toMap());
            increment("lombok_types_augmented", 1);
            increment("lombok_members_generated", 1);
            return withModifiers(owner, modifiers, members);
        }

        private void builder(
                JavaDeclaration owner,
                Map<String, JavaAnnotation> annotations,
                List<JavaDeclaration> fields,
                List<JavaDeclaration> members,
                LombokCapabilitySummary policy,
                LombokCapabilitySummary aggregate) {
            List<JavaDeclaration> targets = new ArrayList<>();
            if (annotations.containsKey("Builder")) targets.add(owner);
            owner.members().stream()
                    .filter(member -> annotations(member).containsKey("Builder"))
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
            var annotation = annotations(target).get("Builder");
            var parent = model.parent(references.get(owner)).orElse(null);
            if (targets.size() != 1
                    || policy.isPartial("builder")
                    || configuration.uncertainCapabilities().contains("*")
                    || configuration.uncertainCapabilities().contains("builder")
                    || !owner.symbol().kind().equals("CLASS")
                    || owner.symbol().modifiers().contains("abstract")
                    || parent != null && !owner.symbol().modifiers().contains("static")
                    || target != owner
                            && !target.symbol().kind().equals("CONSTRUCTOR")
                            && !target.symbol().kind().equals("METHOD")
                    || target != owner
                            && (target.symbol().variadic()
                                    || !types.get(target.symbol().declaredType())
                                            .exceptions()
                                            .isEmpty())
                    || target != owner
                            && target.symbol().kind().equals("CONSTRUCTOR")
                            && target.members().stream()
                                    .anyMatch(
                                            member ->
                                                    member.symbol()
                                                            .kind()
                                                            .equals("TYPE_PARAMETER"))) {
                aggregate.partial("builder");
                unsupported(owner, "Builder owner, target or generic parameters");
                return;
            }
            boolean methodTarget = target.symbol().kind().equals("METHOD");
            boolean instanceTarget =
                    methodTarget && !target.symbol().modifiers().contains("static");
            var genericOwner = methodTarget ? target : owner;
            var formals =
                    genericOwner.members().stream()
                            .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                            .toList();
            String formalHeader = formalHeader(genericOwner.signature());
            int builtType =
                    methodTarget
                            ? types.get(target.symbol().declaredType()).returns()
                            : owner.symbol().declaredType();
            var built = types.get(builtType);
            if (methodTarget
                    && (built.kind() == JavaType.Kind.VOID
                            || built.kind() == JavaType.Kind.ARRAY
                            || bool(annotation, "toBuilder", false)
                            || built.kind() == JavaType.Kind.TYPEVAR
                                    && !annotation.values().containsKey("builderClassName"))) {
                aggregate.partial("builder");
                unsupported(owner, "Builder method return type or toBuilder mapping");
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
                                                    || !types.get(formal.symbol().declaredType())
                                                            .annotations()
                                                            .isEmpty())
                    || !formals.isEmpty() && formalHeader.isEmpty()) {
                aggregate.partial("builder");
                unsupported(owner, "Builder annotated or incomplete type parameters");
                return;
            }
            String builderName = string(annotation, "builderClassName", builtName + "Builder");
            String factoryName = string(annotation, "builderMethodName", "builder");
            String buildName = string(annotation, "buildMethodName", "build");
            String prefix = string(annotation, "setterPrefix", "");
            String access = access(annotation, "access");
            var customLookup = model.type(owner.binaryName() + "$" + builderName);
            JavaDeclaration custom =
                    customLookup instanceof JavaModel.FoundType found
                            ? model.declaration(found.declaration())
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
                    || !identifier(builderName, false)
                    || !identifier(factoryName, true)
                    || !identifier(buildName, false)
                    || !identifier(prefix, true)
                    || customLookup instanceof JavaModel.AmbiguousType
                    || custom != null
                            && (!custom.complete()
                                    || !custom.symbol().kind().equals("CLASS")
                                    || custom.symbol().modifiers().contains("static")
                                            == instanceTarget
                                    || !formalHeader(custom.signature()).equals(formalHeader)
                                    || !annotations(custom).isEmpty())) {
                aggregate.partial("builder");
                unsupported(owner, "Builder options or custom builder class");
                return;
            }
            List<JavaDeclaration> parameters =
                    target == owner
                            ? fields.stream()
                                    .filter(
                                            field ->
                                                    !field.symbol().modifiers().contains("static")
                                                            && !field.symbol()
                                                                    .name()
                                                                    .startsWith("$")
                                                            && (!(field.symbol()
                                                                                    .modifiers()
                                                                                    .contains(
                                                                                            "final")
                                                                            || annotations
                                                                                    .containsKey(
                                                                                            "Value"))
                                                                    || !initialized(field)
                                                                    || hasAnnotation(
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
                                                    || needsAnnotationCopies(parameter)
                                                    || parameter.symbol().kind().equals("PARAMETER")
                                                            && !types.get(
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
                unsupported(owner, "Builder annotated, default or collection parameters");
                return;
            }
            var defaults =
                    target == owner
                            ? parameters.stream()
                                    .filter(
                                            field ->
                                                    hasAnnotation(
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
                                                                                    .contains(
                                                                                            "static")
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
                unsupported(owner, "Builder custom field type or modifiers");
                return;
            }
            if (defaults.stream()
                    .anyMatch(
                            field ->
                                    !initialized(field)
                                            || hasAny(
                                                    members,
                                                    List.of("$default$" + field.symbol().name()),
                                                    0))) {
                aggregate.partial("builder");
                unsupported(
                        owner, "Builder.Default without initializer or with provider collision");
                return;
            }
            for (var field : defaults) builderDefault(owner, members, formals, formalHeader, field);
            int builderType = types.size();
            String canonical = owner.canonicalName() + "." + builderName;
            String binary = owner.binaryName() + "$" + builderName;
            types.add(
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
                                            descriptor(builderType),
                                            builderType,
                                            builderModifiers,
                                            false),
                                    canonical,
                                    binary,
                                    formalHeader + "Ljava/lang/Object;",
                                    owner.span(),
                                    JavaDeclaration.Origin.MODEL,
                                    named("java.lang.Object"),
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
                                    && member.origin()
                                            == JavaDeclaration.Origin.COMPILER_SYNTHETIC);
            if (custom == null)
                builderMembers.addAll(
                        formals.stream().map(formal -> modeledFormal(formal, canonical)).toList());
            String builderSignature = genericTypeSignature(binary, formals);
            var enclosingFormals =
                    owner.members().stream()
                            .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                            .toList();
            if (instanceTarget && !enclosingFormals.isEmpty()) {
                String enclosingSignature =
                        genericTypeSignature(owner.binaryName(), enclosingFormals);
                builderSignature =
                        enclosingSignature.substring(0, enclosingSignature.length() - 1)
                                + "."
                                + genericTypeSignature(builderName, formals).substring(1);
            }
            if (builderMembers.stream()
                    .noneMatch(member -> member.symbol().kind().equals("CONSTRUCTOR")))
                method(
                        builder,
                        builderMembers,
                        "<init>",
                        List.of(),
                        primitive(JavaType.Kind.VOID),
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
                                        descriptor(parameter.symbol().declaredType()),
                                        writtenFieldType(parameter),
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
                    generated.put(fieldId(builder, field), origin("@Builder"));
                }
                if (defaulted) {
                    int booleanType = primitive(JavaType.Kind.BOOLEAN);
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
                        generated.put(fieldId(builder, flag), origin("@Builder.Default"));
                    }
                }
                String setter = prefix.isEmpty() ? name : prefix + capitalize(name);
                if (!hasNamedMethod(builderMembers, setter))
                    method(
                            builder,
                            builderMembers,
                            setter,
                            List.of(parameter),
                            builderType,
                            "(" + parameter.signature() + ")" + builderSignature,
                            modifiers(memberAccess, false),
                            parameter.span(),
                            "@Builder",
                            copiedMethodAnnotations(parameter, false));
            }
            if (!hasNamedMethod(builderMembers, buildName))
                method(
                        builder,
                        builderMembers,
                        buildName,
                        List.of(),
                        builtType,
                        "()"
                                + (methodTarget
                                        ? returnSignature(target.signature())
                                        : genericTypeSignature(owner.binaryName(), formals)),
                        modifiers(memberAccess, false),
                        owner.span(),
                        "@Builder");
            if (!hasNamedMethod(builderMembers, "toString"))
                method(
                        builder,
                        builderMembers,
                        "toString",
                        List.of(),
                        named("java.lang.String"),
                        "()Ljava/lang/String;",
                        List.of("public"),
                        owner.span(),
                        "@Builder");
            var completedBuilder = copy(builder, builderMembers, builder.incompleteReasons());
            if (custom == null) {
                members.add(completedBuilder);
                generated.put(canonical, origin("@Builder"));
            } else {
                customBuilders.put(binary, completedBuilder);
                members.replaceAll(
                        member -> member.binaryName().equals(binary) ? completedBuilder : member);
            }
            if (!factoryName.isEmpty() && !hasAny(members, List.of(factoryName), 0)) {
                method(
                        owner,
                        members,
                        factoryName,
                        List.of(),
                        builderType,
                        formalHeader + "()" + builderSignature,
                        modifiers(access, !instanceTarget),
                        owner.span(),
                        "@Builder");
                methodFormals(members, formals);
            }
            if (bool(annotation, "toBuilder", false) && !hasAny(members, List.of("toBuilder"), 0))
                method(
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
                                                    && member.origin()
                                                            == JavaDeclaration.Origin.SOURCE)
                    && Set.of("NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor")
                            .stream()
                            .noneMatch(annotations::containsKey)) {
                members.removeIf(member -> member.symbol().kind().equals("CONSTRUCTOR"));
                method(
                        owner,
                        members,
                        "<init>",
                        parameters,
                        primitive(JavaType.Kind.VOID),
                        "("
                                + String.join(
                                        "",
                                        parameters.stream()
                                                .map(JavaDeclaration::signature)
                                                .toList())
                                + ")V",
                        List.of(),
                        owner.span(),
                        "@Builder");
            }
        }

        private void builderDefault(
                JavaDeclaration owner,
                List<JavaDeclaration> members,
                List<JavaDeclaration> formals,
                String formalHeader,
                JavaDeclaration field) {
            method(
                    owner,
                    members,
                    "$default$" + field.symbol().name(),
                    List.of(),
                    generatedType(field),
                    formalHeader + "()" + field.signature(),
                    List.of("private", "static"),
                    field.span(),
                    "@Builder.Default");
            methodFormals(members, formals);
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

        private sealed interface SuperBuilderValidation
                permits SuperBuilderReady, SuperBuilderRejected {}

        /** A null parent denotes the Object root. */
        private record SuperBuilderReady(JavaDeclaration parent)
                implements SuperBuilderValidation {}

        private record SuperBuilderRejected(String reason) implements SuperBuilderValidation {}

        private SuperBuilderValidation validateSuperBuilder(
                JavaDeclaration owner, Set<String> active, int depth) {
            if (depth >= 32 || !active.add(owner.canonicalName()))
                return new SuperBuilderRejected("cyclic or over-budget hierarchy");
            try {
                var lookup = model.type(owner.canonicalName());
                if (!(lookup instanceof JavaModel.FoundType found))
                    return new SuperBuilderRejected("ambiguous owner");
                var source = model.unit(found.declaration().source());
                var annotation = annotations(owner).get("SuperBuilder");
                if (!owner.complete()
                        || !owner.symbol().kind().equals("CLASS")
                        || annotation == null
                        || !annotation.values().isEmpty()
                        || annotations(owner).containsKey("Builder")
                        || !configurations
                                .forSource(source.path())
                                .uncertainCapabilities()
                                .isEmpty())
                    return new SuperBuilderRejected("owner, options or configuration");
                if (model.parent(found.declaration()).isPresent()
                        && !owner.symbol().modifiers().contains("static"))
                    return new SuperBuilderRejected("non-static nested owner");
                var custom =
                        model.type(owner.binaryName() + "$" + owner.symbol().name() + "Builder");
                if (custom instanceof JavaModel.AmbiguousType
                        || custom instanceof JavaModel.FoundType existing
                                && model.declaration(existing.declaration()).origin()
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
                            && hasAnnotation(field, "Llombok/Builder$Default;")
                            && (field.symbol().modifiers().contains("static")
                                    || !initialized(field)
                                    || hasAny(
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
                        model.type(JavaTypeNames.erased(source.types(), owner.superclass()));
                if (!(parentLookup instanceof JavaModel.FoundType parent))
                    return new SuperBuilderRejected("parent source unavailable");
                var declaration = model.declaration(parent.declaration());
                long formals =
                        declaration.members().stream()
                                .filter(member -> member.symbol().kind().equals("TYPE_PARAMETER"))
                                .count();
                if (parentUse.arguments().size() != formals)
                    return new SuperBuilderRejected("raw parent arguments");
                var validation = validateSuperBuilder(declaration, active, depth + 1);
                return validation instanceof SuperBuilderRejected rejected
                        ? rejected
                        : new SuperBuilderReady(declaration);
            } finally {
                active.remove(owner.canonicalName());
            }
        }

        private void superBuilder(
                JavaDeclaration owner,
                Map<String, JavaAnnotation> annotations,
                List<JavaDeclaration> fields,
                List<JavaDeclaration> members,
                LombokCapabilitySummary policy,
                LombokCapabilitySummary aggregate) {
            if (!annotations.containsKey("SuperBuilder")) return;
            var validation = validateSuperBuilder(owner, new HashSet<>(), 0);
            if (policy.isPartial("super_builder") || validation instanceof SuperBuilderRejected) {
                aggregate.partial("super_builder");
                unsupported(
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
            String header = formalHeader(owner.signature());
            if (!formals.isEmpty() && header.isEmpty()
                    || fields.stream()
                            .anyMatch(field -> !field.complete() || needsAnnotationCopies(field))) {
                aggregate.partial("super_builder");
                unsupported(owner, "SuperBuilder incomplete generics or field propagation");
                return;
            }
            for (var field : fields)
                if (hasAnnotation(field, "Llombok/Builder$Default;"))
                    builderDefault(owner, members, formals, header, field);
            Set<String> names = new HashSet<>();
            formals.forEach(formal -> names.add(formal.symbol().name()));
            String productName = freshFormal("C", names), selfName = freshFormal("B", names);
            String builderName = owner.symbol().name() + "Builder";
            String canonical = owner.canonicalName() + "." + builderName;
            String binary = owner.binaryName() + "$" + builderName;
            int product = types.size(), self = product + 1, builderType = product + 2;
            types.add(
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
            types.add(
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
                            formals.stream()
                                    .map(formal -> formal.symbol().declaredType())
                                    .toList());
            arguments.add(product);
            arguments.add(self);
            declaredType(canonical, binary, arguments);
            var builderFormals =
                    new ArrayList<>(
                            formals.stream()
                                    .map(formal -> modeledFormal(formal, canonical))
                                    .toList());
            builderFormals.add(typeFormal(canonical, productName, product));
            builderFormals.add(typeFormal(canonical, selfName, self));
            String ownerSignature = genericTypeSignature(owner.binaryName(), formals);
            String builderSignature = genericTypeSignature(binary, builderFormals);
            int superclass = named("java.lang.Object");
            if (parent != null) {
                var parentArguments = new ArrayList<>(types.get(owner.superclass()).arguments());
                parentArguments.add(product);
                parentArguments.add(self);
                String parentBuilder = parent.symbol().name() + "Builder";
                superclass =
                        declaredType(
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
                    modeledClass(
                            owner,
                            builderName,
                            builderType,
                            extendedHeader + typeSignature(superclass, 0),
                            superclass,
                            List.of("public", "static", "abstract"));
            List<JavaDeclaration> builderMembers = new ArrayList<>(builderFormals);
            method(
                    builder,
                    builderMembers,
                    "<init>",
                    List.of(),
                    primitive(JavaType.Kind.VOID),
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
            generated.remove(canonical + "#" + builderName + "()");
            for (var field : fields) {
                if (field.symbol().modifiers().contains("static")
                        || field.symbol().name().startsWith("$")
                        || field.symbol().modifiers().contains("final")
                                && initialized(field)
                                && !hasAnnotation(field, "Llombok/Builder$Default;")) continue;
                boolean defaulted = hasAnnotation(field, "Llombok/Builder$Default;");
                int fieldType = writtenFieldType(field);
                var storage =
                        new JavaDeclaration(
                                new JavaSymbol(
                                        "FIELD",
                                        canonical,
                                        field.symbol().name() + (defaulted ? "$value" : ""),
                                        descriptor(fieldType),
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
                generated.put(fieldId(builder, storage), origin("@SuperBuilder"));
                if (defaulted) {
                    int flagType = primitive(JavaType.Kind.BOOLEAN);
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
                    generated.put(fieldId(builder, flag), origin("@Builder.Default"));
                }
                method(
                        builder,
                        builderMembers,
                        field.symbol().name(),
                        List.of(field),
                        self,
                        "(" + field.signature() + ")T" + selfName + ";",
                        List.of("public"),
                        field.span(),
                        "@SuperBuilder",
                        copiedMethodAnnotations(field, false));
            }
            method(
                    builder,
                    builderMembers,
                    "self",
                    List.of(),
                    self,
                    "()T" + selfName + ";",
                    List.of("protected", "abstract"),
                    owner.span(),
                    "@SuperBuilder");
            method(
                    builder,
                    builderMembers,
                    "build",
                    List.of(),
                    product,
                    "()T" + productName + ";",
                    List.of("public", "abstract"),
                    owner.span(),
                    "@SuperBuilder");
            method(
                    builder,
                    builderMembers,
                    "toString",
                    List.of(),
                    named("java.lang.String"),
                    "()Ljava/lang/String;",
                    List.of("public"),
                    owner.span(),
                    "@SuperBuilder");
            members.add(copy(builder, builderMembers, List.of()));
            generated.put(canonical, origin("@SuperBuilder"));
            int wildcard = types.size();
            types.add(
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
                            formals.stream()
                                    .map(formal -> formal.symbol().declaredType())
                                    .toList());
            publicArguments.add(wildcard);
            publicArguments.add(wildcard);
            int publicBuilder = declaredType(canonical, binary, publicArguments);
            String publicSignature = typeSignature(publicBuilder, 0);
            members.removeIf(
                    member ->
                            member.symbol().kind().equals("CONSTRUCTOR")
                                    && member.origin()
                                            == JavaDeclaration.Origin.COMPILER_SYNTHETIC);
            method(
                    owner,
                    members,
                    "<init>",
                    List.of(parameter(owner, "b", publicBuilder)),
                    primitive(JavaType.Kind.VOID),
                    "(" + publicSignature + ")V",
                    List.of("protected"),
                    owner.span(),
                    "@SuperBuilder");
            if (!owner.symbol().modifiers().contains("abstract")) {
                String implName = owner.symbol().name() + "BuilderImpl",
                        implBinary = owner.binaryName() + "$" + implName;
                int implType =
                        declaredType(
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
                int concreteBuilder = declaredType(canonical, binary, concreteArguments);
                var impl =
                        modeledClass(
                                owner,
                                implName,
                                implType,
                                header + typeSignature(concreteBuilder, 0),
                                concreteBuilder,
                                List.of("private", "static", "final"));
                var implMembers =
                        new ArrayList<>(
                                formals.stream()
                                        .map(formal -> modeledFormal(formal, impl.canonicalName()))
                                        .toList());
                method(
                        impl,
                        implMembers,
                        "<init>",
                        List.of(),
                        primitive(JavaType.Kind.VOID),
                        "()V",
                        List.of("private"),
                        owner.span(),
                        "@SuperBuilder");
                method(
                        impl,
                        implMembers,
                        "self",
                        List.of(),
                        implType,
                        "()" + typeSignature(implType, 0),
                        List.of("protected"),
                        owner.span(),
                        "@SuperBuilder");
                method(
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
                generated.put(impl.canonicalName(), origin("@SuperBuilder"));
                method(
                        owner,
                        members,
                        "builder",
                        List.of(),
                        publicBuilder,
                        header + "()" + publicSignature,
                        List.of("public", "static"),
                        owner.span(),
                        "@SuperBuilder");
                methodFormals(members, formals);
            }
            aggregate.complete("super_builder");
        }

        private static String freshFormal(String base, Set<String> names) {
            String name = base;
            for (int suffix = 2; !names.add(name); suffix++) name = base + suffix;
            return name;
        }

        private int declaredType(String canonical, String binary, List<Integer> arguments) {
            int reference = types.size();
            types.add(
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

        private JavaDeclaration modeledClass(
                JavaDeclaration owner,
                String name,
                int type,
                String signature,
                int superclass,
                List<String> modifiers) {
            var declared = types.get(type);
            return new JavaDeclaration(
                    new JavaSymbol(
                            "CLASS",
                            owner.canonicalName(),
                            name,
                            descriptor(type),
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

        private JavaDeclaration typeFormal(String owner, String name, int type) {
            return new JavaDeclaration(
                    new JavaSymbol(
                            "TYPE_PARAMETER",
                            owner,
                            name,
                            descriptor(type),
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

        private String typeSignature(int reference, int depth) {
            if (depth >= 128)
                throw new IllegalArgumentException("SuperBuilder signature exceeds budget");
            var type = types.get(reference);
            return switch (type.kind()) {
                case TYPEVAR -> "T" + type.name() + ";";
                case ARRAY -> "[" + typeSignature(type.component(), depth + 1);
                case WILDCARD ->
                        type.lowerBound() >= 0
                                ? "-" + typeSignature(type.lowerBound(), depth + 1)
                                : type.upperBounds().isEmpty()
                                        ? "*"
                                        : "+"
                                                + typeSignature(
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
                                                                                typeSignature(
                                                                                        argument,
                                                                                        depth + 1))
                                                                .toList())
                                                + ">")
                                + ";";
                default -> descriptor(reference);
            };
        }

        private static boolean hasNamedMethod(List<JavaDeclaration> members, String name) {
            return members.stream()
                    .anyMatch(
                            member ->
                                    member.symbol().kind().equals("METHOD")
                                            && member.symbol().name().equals(name));
        }

        private void methodFormals(List<JavaDeclaration> members, List<JavaDeclaration> formals) {
            if (formals.isEmpty()) return;
            int index = members.size() - 1;
            var method = members.get(index);
            var children = new ArrayList<>(method.members());
            children.addAll(
                    formals.stream()
                            .map(formal -> modeledFormal(formal, method.symbol().owner()))
                            .toList());
            members.set(index, copy(method, children, method.incompleteReasons()));
        }

        private static JavaDeclaration modeledFormal(JavaDeclaration formal, String owner) {
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

        private static String genericTypeSignature(String binary, List<JavaDeclaration> formals) {
            return "L"
                    + binary.replace('.', '/')
                    + (formals.isEmpty()
                            ? ""
                            : "<"
                                    + String.join(
                                            "",
                                            formals.stream()
                                                    .map(
                                                            formal ->
                                                                    "T"
                                                                            + formal.symbol().name()
                                                                            + ";")
                                                    .toList())
                                    + ">")
                    + ";";
        }

        private static String returnSignature(String signature) {
            int close = signature.indexOf(')');
            if (close < 0 || close + 1 >= signature.length())
                throw new IllegalArgumentException("Incomplete Lombok method signature");
            int exception = signature.indexOf('^', close + 1);
            return signature.substring(close + 1, exception < 0 ? signature.length() : exception);
        }

        private static String formalHeader(String signature) {
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

        private boolean identifier(String value, boolean emptyAllowed) {
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

        private JavaDeclaration compiledType(JavaDeclaration owner) {
            var reference = references.get(owner);
            JavaDeclaration sourceOwner = owner;
            if (owner.origin() != JavaDeclaration.Origin.SOURCE && reference != null) {
                var parent = model.parent(reference);
                if (parent.isPresent()) sourceOwner = model.declaration(parent.get());
            }
            Map<String, JavaAnnotation> detected =
                    new LinkedHashMap<>(compiledAnnotations(sourceOwner));
            sourceOwner.members().forEach(member -> detected.putAll(compiledAnnotations(member)));
            if (detected.isEmpty()
                    || !Set.of("CLASS", "ENUM").contains(sourceOwner.symbol().kind())) return owner;
            seen = true;
            var summary = new LombokCapabilitySummary();
            detected.keySet().forEach(summary::detect);
            // A successful processor supplies complete signatures even for capabilities whose
            // bodies cannot be modeled. Partial processor recovery must keep its weaker evidence.
            if (!partialProcessor() && owner.complete()) {
                if (detected.containsKey("Value"))
                    summary.complete(LombokCapabilitySummary.VALUE_MODIFIERS);
                for (var capability :
                        Map.of(
                                        "Delegate",
                                        "delegate",
                                        "Accessors",
                                        "accessors",
                                        "With",
                                        "with",
                                        "CustomLog",
                                        "custom_log")
                                .entrySet())
                    if (detected.containsKey(capability.getKey()))
                        summary.complete(capability.getValue());
            }
            summary.degrade(configuration.uncertainCapabilities());
            boolean generatedClass =
                    sourceOwner != owner
                            && owner.origin() != JavaDeclaration.Origin.SOURCE
                            && (detected.containsKey("Builder")
                                    || detected.containsKey("SuperBuilder"));
            List<JavaDeclaration> members = new ArrayList<>();
            int count = 0;
            for (var member : owner.members()) {
                String kind = member.symbol().kind();
                boolean constructor =
                        kind.equals("CONSTRUCTOR")
                                && (generatedClass
                                        || detected.keySet().stream()
                                                .anyMatch(
                                                        name ->
                                                                Set.of(
                                                                                "Data",
                                                                                "Value",
                                                                                "NoArgsConstructor",
                                                                                "RequiredArgsConstructor",
                                                                                "AllArgsConstructor",
                                                                                "Builder",
                                                                                "SuperBuilder")
                                                                        .contains(name)));
                boolean enumMethod =
                        owner.symbol().kind().equals("ENUM")
                                && Set.of("values", "valueOf").contains(member.symbol().name());
                boolean generatedMember =
                        member.origin() != JavaDeclaration.Origin.SOURCE
                                && (constructor
                                        || kind.equals("METHOD") && !enumMethod
                                        || kind.equals("FIELD"));
                if (generatedMember) {
                    members.add(compiledOrigin(member, member.members()));
                    generated.put(
                            new JavaIdentities(model).declaration(references.get(member)),
                            compiledOrigin());
                    count++;
                } else members.add(member);
            }
            if (generatedClass) {
                generated.put(owner.canonicalName(), compiledOrigin());
                count++;
            }
            summary.generatedMemberCount(count);
            if (summary.hasEvidence()) put(owner.canonicalName(), "lombok", summary.toMap());
            if (count > 0) {
                increment("lombok_types_augmented", 1);
                increment("lombok_members_generated", count);
            }
            for (String category : List.of("partial_capabilities", "unmodeled_capabilities"))
                if (summary.toMap().get(category) instanceof Collection<?> values
                        && !values.isEmpty())
                    unsupported(
                            owner,
                            "Compiled signatures available; unsupported body capabilities: "
                                    + values);
            return generatedClass
                    ? compiledOrigin(owner, members)
                    : copy(owner, members, owner.incompleteReasons());
        }

        private JavaDeclaration compiledOrigin(
                JavaDeclaration value, List<JavaDeclaration> members) {
            return new JavaDeclaration(
                    value.symbol(),
                    value.canonicalName(),
                    value.binaryName(),
                    value.signature(),
                    value.span(),
                    partialProcessor()
                            ? JavaDeclaration.Origin.MODEL
                            : JavaDeclaration.Origin.PROCESSOR,
                    value.superclass(),
                    value.interfaces(),
                    value.permitted(),
                    value.annotations(),
                    value.constant(),
                    value.annotationDefault(),
                    members,
                    value.incompleteReasons(),
                    value.overrides(),
                    value.deprecated());
        }

        private JavaGeneratedOrigin compiledOrigin() {
            return new JavaGeneratedOrigin(
                    ProducerIds.LOMBOK_AST,
                    "lombok",
                    partialProcessor() ? "processor-partial" : "compile",
                    "@Lombok",
                    partialProcessor() ? "INFERRED" : "EXTRACTED",
                    false);
        }

        private boolean partialProcessor() {
            return unit.extensions().counters().containsKey("lombok_processor_partial");
        }

        private Map<String, JavaAnnotation> annotations(JavaDeclaration declaration) {
            return effectiveAnnotations.computeIfAbsent(
                    declaration,
                    value -> {
                        var declared = LombokJavaModelExtension.annotations(value);
                        var reference = references.get(value);
                        if (declared.isEmpty() || reference == null) return declared;
                        var syntax = model.syntax(reference).orElse(null);
                        var modifiers = syntax == null ? null : syntax.tree("getModifiers");
                        if (modifiers == null) return declared;
                        var result = new LinkedHashMap<>(declared);
                        for (var annotation : modifiers.trees("getAnnotations")) {
                            var type = annotation.tree("getAnnotationType");
                            if (type == null) continue;
                            String name =
                                    type.kind().equals("IDENTIFIER")
                                            ? type.text("getName")
                                            : type.text("getIdentifier");
                            var original = result.get(name);
                            if (original == null || annotation.trees("getArguments").isEmpty())
                                continue;
                            var attributes = new LinkedHashMap<>(original.values());
                            for (var argument : annotation.trees("getArguments")) {
                                String key =
                                        argument.kind().equals("ASSIGNMENT")
                                                ? argument.tree("getVariable").text("getName")
                                                : "value";
                                attributes.putIfAbsent(
                                        key,
                                        new JavaBinaryType.UnavailableValue(
                                                "source-annotation-value-unavailable"));
                            }
                            result.put(
                                    name,
                                    new JavaAnnotation(
                                            original.descriptor(),
                                            original.retention(),
                                            attributes));
                        }
                        return Collections.unmodifiableMap(result);
                    });
        }

        private Map<String, JavaAnnotation> compiledAnnotations(JavaDeclaration declaration) {
            if (compiledAnnotations == null)
                compiledAnnotations =
                        new JavaModelAnnotations(
                                new JavaUnitAnalysis(
                                        new JavaFrameworkContext(
                                                project,
                                                List.of(),
                                                new com.anatomist.config.ProjectConfig(),
                                                false),
                                        model,
                                        binaries,
                                        unit.path(),
                                        context.identity(unit.path())));
            var reference = references.get(declaration);
            return reference == null
                    ? LombokJavaModelExtension.annotations(declaration)
                    : LombokJavaModelExtension.annotations(
                            compiledAnnotations.annotations(reference));
        }

        private void constructors(
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
                    } else if (kind.equals("AllArgsConstructor")
                            && annotations.containsKey("Value")) {
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
                    unsupported(owner, from + " options");
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
                                                        || !initialized(field))
                                .filter(
                                        field ->
                                                !kind.equals("RequiredArgsConstructor")
                                                        || (field.symbol()
                                                                                .modifiers()
                                                                                .contains("final")
                                                                        || annotations(field)
                                                                                .containsKey(
                                                                                        "NonNull"))
                                                                && !initialized(field))
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
                                                        && !initialized(field))) {
                    aggregate.partial(capability);
                    unsupported(owner, "uninitialized final fields without force");
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
                                        parameters.stream()
                                                .map(JavaDeclaration::signature)
                                                .toList())
                                + ")V";
                var parameterTypes =
                        parameters.stream().map(field -> field.symbol().declaredType()).toList();
                String erasedConstructor =
                        "("
                                + String.join(
                                        "", parameterTypes.stream().map(this::descriptor).toList())
                                + ")V";
                if (members.stream()
                        .anyMatch(
                                member ->
                                        member.symbol().kind().equals("CONSTRUCTOR")
                                                && member.symbol()
                                                        .descriptor()
                                                        .equals(erasedConstructor))) {
                    aggregate.partial(capability);
                    unsupported(owner, "duplicate constructor signature");
                    continue;
                }
                method(
                        owner,
                        members,
                        "<init>",
                        parameters,
                        primitive(JavaType.Kind.VOID),
                        signature,
                        modifiers(owner.symbol().kind().equals("ENUM") ? "PRIVATE" : access, false),
                        owner.span(),
                        from);
            }
        }

        private void logger(
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
                                                            .equals(configuration.logFieldName())))
                        return;
                    if (binaries == null || binaries.find(log.getValue()).isEmpty()) {
                        aggregate.partial(LombokCapabilitySummary.LOGGER_FIELD);
                        unsupported(owner, "missing logger type " + log.getValue());
                        return;
                    }
                    int type = named(log.getValue());
                    List<String> modifiers = new ArrayList<>(List.of("private", "final"));
                    if (configuration.logFieldStatic()) modifiers.add("static");
                    var field =
                            new JavaDeclaration(
                                    new JavaSymbol(
                                            "FIELD",
                                            owner.canonicalName(),
                                            configuration.logFieldName(),
                                            descriptor(type),
                                            type,
                                            modifiers,
                                            false),
                                    "",
                                    "",
                                    descriptor(type),
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
                    generated.put(fieldId(owner, field), origin("@" + log.getKey()));
                    return;
                }
        }

        private void method(
                JavaDeclaration owner,
                List<JavaDeclaration> members,
                String name,
                List<JavaDeclaration> parameters,
                int returns,
                String signature,
                List<String> modifiers,
                SourceSpan span,
                String from) {
            method(
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

        private void method(
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
            var parameterTypes = parameters.stream().map(this::generatedType).toList();
            int executable = types.size();
            types.add(
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
                            + String.join(
                                    "", parameterTypes.stream().map(this::descriptor).toList())
                            + ")"
                            + descriptor(returns);
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
                                                    parameter(
                                                            owner,
                                                            parameter.symbol().name(),
                                                            generatedType(parameter),
                                                            copiedParameterAnnotations(parameter)))
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
                                                                    new JavaTypes(types), type))
                                            .toList())
                            + ")";
            generated.put(id, origin(from));
        }

        private JavaDeclaration parameter(JavaDeclaration owner, String name, int type) {
            return parameter(owner, name, type, List.of());
        }

        private JavaDeclaration parameter(
                JavaDeclaration owner, String name, int type, List<JavaAnnotation> annotations) {
            return new JavaDeclaration(
                    new JavaSymbol(
                            "PARAMETER",
                            owner.canonicalName(),
                            name,
                            descriptor(type),
                            type,
                            List.of(),
                            false),
                    "",
                    "",
                    descriptor(type),
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

        /** Lombok copies the written field type, excluding annotations in its modifier list. */
        private boolean needsAnnotationCopies(JavaDeclaration declaration) {
            if (declaration.annotations().stream().anyMatch(this::annotationCopyNeedsModel))
                return true;
            if (!hasBaseAnnotations(declaration.symbol().declaredType(), 0)) return false;
            var reference = references.get(declaration);
            if (reference == null)
                return declaration.annotations().stream().anyMatch(this::annotationCopyNeedsModel);
            if (compiledAnnotations == null) compiledAnnotations(declaration);
            return compiledAnnotations.annotations(reference).stream()
                    .anyMatch(this::annotationCopyNeedsModel);
        }

        private boolean annotationCopyNeedsModel(JavaAnnotation annotation) {
            return !annotation.descriptor().equals("Ljava/lang/Deprecated;")
                    && LombokAnnotationCopies.needsModel(annotation);
        }

        private int generatedType(JavaDeclaration declaration) {
            if (!declaration.symbol().kind().equals("FIELD"))
                return declaration.symbol().declaredType();
            if (!hasBaseAnnotations(declaration.symbol().declaredType(), 0))
                return declaration.symbol().declaredType();
            return generatedTypes.computeIfAbsent(
                    declaration,
                    field -> {
                        var reference = references.get(field);
                        if (reference == null) return field.symbol().declaredType();
                        if (compiledAnnotations == null) compiledAnnotations(field);
                        Set<String> excluded = new HashSet<>();
                        for (var annotation : compiledAnnotations.annotations(reference))
                            if (!LombokAnnotationCopies.willCopy(annotation))
                                excluded.add(annotation.descriptor());
                        return writtenType(field.symbol().declaredType(), excluded, 0);
                    });
        }

        private int writtenFieldType(JavaDeclaration field) {
            if (!field.symbol().kind().equals("FIELD")
                    || !hasBaseAnnotations(field.symbol().declaredType(), 0))
                return field.symbol().declaredType();
            var reference = references.get(field);
            if (reference == null) return field.symbol().declaredType();
            if (compiledAnnotations == null) compiledAnnotations(field);
            Set<String> excluded = new HashSet<>();
            for (var annotation : compiledAnnotations.annotations(reference))
                excluded.add(annotation.descriptor());
            return writtenType(field.symbol().declaredType(), excluded, 0);
        }

        private List<JavaAnnotation> copiedMethodAnnotations(
                JavaDeclaration field, boolean getter) {
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
                                                                            .equals(
                                                                                    "Llombok/NonNull;"))
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
                                            annotation
                                                    .descriptor()
                                                    .equals("Ljava/lang/Deprecated;"))) {
                copied.add(
                        new JavaAnnotation(
                                "Ljava/lang/Deprecated;",
                                JavaAnnotation.Retention.RUNTIME,
                                Map.of()));
            }
            return List.copyOf(copied);
        }

        private List<JavaAnnotation> copiedParameterAnnotations(JavaDeclaration field) {
            return field.annotations().stream()
                    .filter(annotation -> annotation.descriptor().equals("Llombok/NonNull;"))
                    .toList();
        }

        private int writtenType(int reference, Set<String> excluded, int depth) {
            if (depth > 128)
                throw new IllegalArgumentException("Lombok written type exceeds depth budget");
            JavaType type = types.get(reference);
            int component = type.component();
            int enclosing = type.enclosing();
            List<JavaAnnotation> annotations = type.annotations();
            if (type.kind() == JavaType.Kind.ARRAY)
                component = writtenType(component, excluded, depth + 1);
            else {
                annotations =
                        annotations.stream()
                                .filter(annotation -> !excluded.contains(annotation.descriptor()))
                                .toList();
                if (enclosing >= 0 && type.kind() == JavaType.Kind.DECLARED)
                    enclosing = writtenType(enclosing, excluded, depth + 1);
            }
            if (component == type.component()
                    && enclosing == type.enclosing()
                    && annotations.equals(type.annotations())) return reference;
            int index = types.size();
            types.add(
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

        private boolean hasBaseAnnotations(int reference, int depth) {
            if (depth > 128)
                throw new IllegalArgumentException("Lombok written type exceeds depth budget");
            JavaType type = types.get(reference);
            if (type.kind() == JavaType.Kind.ARRAY)
                return hasBaseAnnotations(type.component(), depth + 1);
            return !type.annotations().isEmpty()
                    || type.kind() == JavaType.Kind.DECLARED
                            && type.enclosing() >= 0
                            && hasBaseAnnotations(type.enclosing(), depth + 1);
        }

        private String descriptor(int reference) {
            return descriptor(reference, 0);
        }

        private String descriptor(int reference, int depth) {
            if (depth > 128)
                throw new IllegalArgumentException("modeled signature exceeds depth budget");
            JavaType type = types.get(reference);
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
                case ARRAY -> "[" + descriptor(type.component(), depth + 1);
                case DECLARED ->
                        "L"
                                + (type.binaryName().isEmpty() ? type.name() : type.binaryName())
                                        .replace('.', '/')
                                + ";";
                case TYPEVAR, INTERSECTION -> descriptor(type.upperBounds().getFirst(), depth + 1);
                default ->
                        throw new IllegalArgumentException(
                                "unavailable modeled signature type: " + type.kind());
            };
        }

        private int primitive(JavaType.Kind kind) {
            for (int i = 0; i < types.size(); i++)
                if (types.get(i).kind() == kind && types.get(i).annotations().isEmpty()) return i;
            int index = types.size();
            String name = kind.name().toLowerCase(Locale.ROOT);
            types.add(
                    new JavaType(
                            kind, name, name, -1, List.of(), List.of(), -1, -1, List.of(), -1,
                            List.of()));
            return index;
        }

        private int named(String name) {
            for (int i = 0; i < types.size(); i++)
                if (types.get(i).kind() == JavaType.Kind.DECLARED
                        && types.get(i).name().equals(name)
                        && types.get(i).arguments().isEmpty()
                        && types.get(i).annotations().isEmpty()) return i;
            int index = types.size();
            types.add(
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

        private boolean initialized(JavaDeclaration field) {
            var reference = references.get(field);
            return reference != null
                    && model.syntax(reference)
                            .map(syntax -> syntax.tree("getInitializer") != null)
                            .orElse(false);
        }

        private boolean hasAny(List<JavaDeclaration> members, List<String> names, int arity) {
            return members.stream()
                    .anyMatch(
                            member ->
                                    member.symbol().kind().equals("METHOD")
                                            && names.stream()
                                                    .anyMatch(
                                                            name ->
                                                                    name.equalsIgnoreCase(
                                                                            member.symbol().name()))
                                            && types.get(member.symbol().declaredType())
                                                            .parameters()
                                                            .size()
                                                    == arity);
        }

        private String getterName(JavaDeclaration field) {
            return primitiveBoolean(field) && !configuration.noIsPrefix()
                    ? "is" + booleanStem(field)
                    : "get" + capitalize(field.symbol().name());
        }

        private List<String> getterAlternatives(JavaDeclaration field) {
            String name = field.symbol().name();
            if (!primitiveBoolean(field) || configuration.noIsPrefix())
                return List.of(getterName(field));
            return List.of(
                    "is" + capitalize(name),
                    "get" + capitalize(name),
                    "is" + booleanStem(field),
                    "get" + booleanStem(field));
        }

        private String booleanStem(JavaDeclaration field) {
            String name = field.symbol().name();
            return primitiveBoolean(field)
                            && !configuration.noIsPrefix()
                            && name.startsWith("is")
                            && name.length() > 2
                            && !Character.isLowerCase(name.charAt(2))
                    ? name.substring(2)
                    : capitalize(name);
        }

        private boolean primitiveBoolean(JavaDeclaration field) {
            return types.get(field.symbol().declaredType()).kind() == JavaType.Kind.BOOLEAN;
        }

        private LombokCapabilitySummary summary(Map<String, JavaAnnotation> annotations) {
            var summary = new LombokCapabilitySummary();
            annotations.forEach(
                    (name, annotation) -> {
                        summary.detect(name);
                        for (var attribute : annotation.values().entrySet()) {
                            if (attribute.getValue() instanceof JavaBinaryType.UnavailableValue
                                    || attribute.getKey().startsWith("on")
                                    || attribute.getKey().equals("lazy")
                                            && bool(annotation, "lazy", false))
                                summary.degrade(Set.of(LombokCapabilitySummary.WILDCARD));
                        }
                    });
            summary.degrade(configuration.uncertainCapabilities());
            return summary;
        }

        private void unsupported(JavaDeclaration owner, String feature) {
            increment("lombok_unsupported_features", 1);
            diagnostic(
                    "LOMBOK_FEATURE_UNSUPPORTED",
                    owner.canonicalName(),
                    "Detached Lombok model does not yet cover " + feature + ".");
        }

        private void diagnostic(String code, String symbol, String sample) {
            var identity = context.identity(unit.path());
            diagnostics.add(
                    new IndexDiagnostic(
                            code.equals("LOMBOK_BODY_SEMANTICS_OMITTED")
                                    ? "info"
                                    : strict ? "error" : "warning",
                            code,
                            "EXTENSION_AST",
                            project.relativize(unit.path()).toString().replace('\\', '/'),
                            identity.module(),
                            identity.scope().name(),
                            symbol,
                            1,
                            sample));
        }

        private void put(String id, String key, Object value) {
            var row = new LinkedHashMap<>(metadata.getOrDefault(id, Map.of()));
            row.put(key, value);
            metadata.put(id, row);
        }

        private void increment(String key, long value) {
            counters.merge(key, value, Long::sum);
        }
    }

    private static Map<String, JavaAnnotation> annotations(JavaDeclaration declaration) {
        return annotations(declaration.annotations());
    }

    private static Map<String, JavaAnnotation> annotations(List<JavaAnnotation> declared) {
        Map<String, JavaAnnotation> annotations = new LinkedHashMap<>();
        for (var annotation : declared) {
            String name = annotation.descriptor();
            if (!name.startsWith("Llombok/") || !name.endsWith(";")) continue;
            String simple = name.substring(name.lastIndexOf('/') + 1, name.length() - 1);
            if ((BASIC.contains(simple) || LOG_TYPES.containsKey(simple))
                    && name.equals("L" + annotationPackage(simple) + "/" + simple + ";"))
                annotations.put(simple, annotation);
        }
        return annotations;
    }

    private static String annotationPackage(String name) {
        return switch (name) {
            case "Accessors",
                    "SuperBuilder",
                    "Delegate",
                    "ExtensionMethod",
                    "With",
                    "UtilityClass" ->
                    "lombok/experimental";
            case "Slf4j", "XSlf4j", "CustomLog" -> "lombok/extern/slf4j";
            case "Log4j", "Log4j2" -> "lombok/extern/log4j";
            case "CommonsLog" -> "lombok/extern/apachecommons";
            case "JBossLog" -> "lombok/extern/jbosslog";
            case "Flogger" -> "lombok/extern/flogger";
            case "Log" -> "lombok/extern/java";
            default -> "lombok";
        };
    }

    private static String access(JavaAnnotation annotation, String attribute) {
        if (annotation == null || !annotation.values().containsKey(attribute)) return "PUBLIC";
        var value = annotation.values().get(attribute);
        if (value instanceof JavaBinaryType.EnumValue enumeration
                && enumeration.descriptor().equals("Llombok/AccessLevel;"))
            return Set.of("PUBLIC", "PROTECTED", "PRIVATE", "PACKAGE", "MODULE", "NONE")
                            .contains(enumeration.name())
                    ? enumeration.name()
                    : null;
        return null;
    }

    private static boolean bool(JavaAnnotation annotation, String name, boolean fallback) {
        var value = annotation.values().get(name);
        return value instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof Boolean flag
                ? flag
                : fallback;
    }

    private static String string(JavaAnnotation annotation, String name, String fallback) {
        var value = annotation.values().get(name);
        return value instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof String text
                ? text
                : fallback;
    }

    private static List<String> modifiers(String access, boolean isStatic) {
        List<String> modifiers = new ArrayList<>();
        if (Set.of("PUBLIC", "PROTECTED", "PRIVATE").contains(access))
            modifiers.add(access.toLowerCase(Locale.ROOT));
        if (isStatic) modifiers.add("static");
        return modifiers;
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) return name;
        char first = name.charAt(0);
        if (Character.isLowerCase(first)) {
            first =
                    name.length() > 1
                                    && (Character.isUpperCase(name.charAt(1))
                                            || Character.isTitleCase(name.charAt(1)))
                            ? Character.toUpperCase(first)
                            : Character.toTitleCase(first);
        }
        return first + name.substring(1);
    }

    private static String fieldId(JavaDeclaration owner, JavaDeclaration field) {
        return owner.canonicalName() + "#" + field.symbol().name();
    }

    private static JavaGeneratedOrigin origin(String from) {
        return new JavaGeneratedOrigin(
                ProducerIds.LOMBOK_AST, "lombok", "ast", from, "INFERRED", false);
    }

    private static JavaDeclaration incomplete(JavaDeclaration value, String reason) {
        var reasons = new ArrayList<>(value.incompleteReasons());
        if (!reasons.contains(reason)) reasons.add(reason);
        return copy(value, value.members(), reasons);
    }

    private static JavaDeclaration copy(
            JavaDeclaration value, List<JavaDeclaration> members, List<String> reasons) {
        return new JavaDeclaration(
                value.symbol(),
                value.canonicalName(),
                value.binaryName(),
                value.signature(),
                value.span(),
                value.origin(),
                value.superclass(),
                value.interfaces(),
                value.permitted(),
                value.annotations(),
                value.constant(),
                value.annotationDefault(),
                members,
                reasons,
                value.overrides(),
                value.deprecated());
    }
}
