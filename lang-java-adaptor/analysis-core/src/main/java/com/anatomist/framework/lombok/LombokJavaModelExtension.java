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
    static final Map<String, String> LOG_TYPES =
            Map.ofEntries(
                    Map.entry("Slf4j", "org.slf4j.Logger"),
                            Map.entry("XSlf4j", "org.slf4j.ext.XLogger"),
                    Map.entry("Log", "java.util.logging.Logger"),
                            Map.entry("Log4j", "org.apache.log4j.Logger"),
                    Map.entry("Log4j2", "org.apache.logging.log4j.Logger"),
                            Map.entry("CommonsLog", "org.apache.commons.logging.Log"),
                    Map.entry("JBossLog", "org.jboss.logging.Logger"),
                            Map.entry("Flogger", "com.google.common.flogger.FluentLogger"));
    static final Set<String> BASIC =
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
    final Path project;
    final boolean strict;
    final LombokConfigurationScope configurations;

    public LombokJavaModelExtension(Path project, boolean strict) {
        this.project = project.toAbsolutePath().normalize();
        this.strict = strict;
        configurations = new LombokConfigurationScope(this.project);
    }

    /** Explicit configuration inventory; code-ana does not discover lombok.config. */
    public LombokJavaModelExtension(
            Path project, boolean strict, Map<Path, String> capturedConfigurations) {
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

    static Boolean generatedSince34(String version) {
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

    static boolean usesLombok(List<JavaDeclaration> declarations) {
        for (var declaration : declarations)
            if (!annotations(declaration).isEmpty() || usesLombok(declaration.members()))
                return true;
        return false;
    }

    final class Overlay {
        final LombokBuilderRules lombokBuilderRules = new LombokBuilderRules(this);
        final LombokDeclarationRules lombokDeclarationRules = new LombokDeclarationRules(this);
        final LombokGeneratedMembers lombokGeneratedMembers = new LombokGeneratedMembers(this);
        final LombokJavaModelExtension extension = LombokJavaModelExtension.this;

        final JavaAnalysisContext context;
        final JavaModel model;
        final JavaUnit unit;
        final LombokConfiguration configuration;
        final JavaBinaryRepository binaries;
        final List<JavaType> types;
        final Map<JavaDeclaration, JavaModel.DeclarationRef> references = new IdentityHashMap<>();
        final Map<String, JavaGeneratedOrigin> generated;
        final Map<String, Map<String, Object>> metadata;
        final List<IndexDiagnostic> diagnostics;
        final Map<String, Long> counters;
        boolean seen;
        JavaModelAnnotations compiledAnnotations;
        final Map<JavaDeclaration, Map<String, JavaAnnotation>> effectiveAnnotations =
                new IdentityHashMap<>();
        final Map<JavaDeclaration, Integer> generatedTypes = new IdentityHashMap<>();
        final Map<String, JavaDeclaration> customBuilders = new HashMap<>();

        Overlay(
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

        Boolean generatedAnnotationDefault;
        boolean generatedAnnotationChecked;

        boolean addGeneratedAnnotation() {
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

        boolean generatedAnnotationKnown() {
            addGeneratedAnnotation();
            return configuration.addLombokGeneratedAnnotation() != null
                    || generatedAnnotationDefault != null;
        }

        JavaDeclaration generatedAnnotations(JavaDeclaration declaration) {
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

        JavaUnit apply() {
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
                        "Generated declarations have signatures only; method bodies are"
                                + " unavailable.");
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

        JavaDeclaration valueModifiers(JavaDeclaration owner) {
            return lombokDeclarationRules.valueModifiers(owner);
        }

        boolean constantType(JavaDeclaration field) {
            return lombokDeclarationRules.constantType(field);
        }

        JavaDeclaration valueConstant(JavaDeclaration original, JavaDeclaration replacement) {
            return lombokDeclarationRules.valueConstant(original, replacement);
        }

        boolean assignableConstant(JavaType.Kind kind, Object value) {
            return lombokDeclarationRules.assignableConstant(kind, value);
        }

        JavaDeclaration withModifiers(
                JavaDeclaration declaration,
                List<String> modifiers,
                List<JavaDeclaration> members) {
            return lombokDeclarationRules.withModifiers(declaration, modifiers, members);
        }

        boolean hasAnnotation(JavaDeclaration declaration, String descriptor) {
            return lombokDeclarationRules.hasAnnotation(declaration, descriptor);
        }

        JavaDeclaration type(JavaDeclaration owner) {
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
                        "Generated annotation policy requires a Lombok manifest version or explicit"
                                + " configuration");
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

        JavaDeclaration utilityClass(
                JavaDeclaration owner, Map<String, JavaAnnotation> annotations) {
            return lombokDeclarationRules.utilityClass(owner, annotations);
        }

        void builder(
                JavaDeclaration owner,
                Map<String, JavaAnnotation> annotations,
                List<JavaDeclaration> fields,
                List<JavaDeclaration> members,
                LombokCapabilitySummary policy,
                LombokCapabilitySummary aggregate) {
            lombokBuilderRules.builder(owner, annotations, fields, members, policy, aggregate);
        }

        void builderDefault(
                JavaDeclaration owner,
                List<JavaDeclaration> members,
                List<JavaDeclaration> formals,
                String formalHeader,
                JavaDeclaration field) {
            lombokBuilderRules.builderDefault(owner, members, formals, formalHeader, field);
        }

        sealed interface SuperBuilderValidation permits SuperBuilderReady, SuperBuilderRejected {}

        /** A null parent denotes the Object root. */
        record SuperBuilderReady(JavaDeclaration parent) implements SuperBuilderValidation {}

        record SuperBuilderRejected(String reason) implements SuperBuilderValidation {}

        SuperBuilderValidation validateSuperBuilder(
                JavaDeclaration owner, Set<String> active, int depth) {
            return lombokBuilderRules.validateSuperBuilder(owner, active, depth);
        }

        void superBuilder(
                JavaDeclaration owner,
                Map<String, JavaAnnotation> annotations,
                List<JavaDeclaration> fields,
                List<JavaDeclaration> members,
                LombokCapabilitySummary policy,
                LombokCapabilitySummary aggregate) {
            lombokBuilderRules.superBuilder(owner, annotations, fields, members, policy, aggregate);
        }

        static String freshFormal(String base, Set<String> names) {
            return LombokBuilderRules.freshFormal(base, names);
        }

        int declaredType(String canonical, String binary, List<Integer> arguments) {
            return lombokBuilderRules.declaredType(canonical, binary, arguments);
        }

        JavaDeclaration modeledClass(
                JavaDeclaration owner,
                String name,
                int type,
                String signature,
                int superclass,
                List<String> modifiers) {
            return lombokBuilderRules.modeledClass(
                    owner, name, type, signature, superclass, modifiers);
        }

        JavaDeclaration typeFormal(String owner, String name, int type) {
            return lombokBuilderRules.typeFormal(owner, name, type);
        }

        String typeSignature(int reference, int depth) {
            return lombokBuilderRules.typeSignature(reference, depth);
        }

        static boolean hasNamedMethod(List<JavaDeclaration> members, String name) {
            return LombokBuilderRules.hasNamedMethod(members, name);
        }

        void methodFormals(List<JavaDeclaration> members, List<JavaDeclaration> formals) {
            lombokBuilderRules.methodFormals(members, formals);
        }

        static JavaDeclaration modeledFormal(JavaDeclaration formal, String owner) {
            return LombokBuilderRules.modeledFormal(formal, owner);
        }

        static String genericTypeSignature(String binary, List<JavaDeclaration> formals) {
            return LombokBuilderRules.genericTypeSignature(binary, formals);
        }

        static String returnSignature(String signature) {
            return LombokBuilderRules.returnSignature(signature);
        }

        static String formalHeader(String signature) {
            return LombokBuilderRules.formalHeader(signature);
        }

        boolean identifier(String value, boolean emptyAllowed) {
            return lombokBuilderRules.identifier(value, emptyAllowed);
        }

        JavaDeclaration compiledType(JavaDeclaration owner) {
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

        JavaDeclaration compiledOrigin(JavaDeclaration value, List<JavaDeclaration> members) {
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

        JavaGeneratedOrigin compiledOrigin() {
            return new JavaGeneratedOrigin(
                    ProducerIds.LOMBOK_AST,
                    "lombok",
                    partialProcessor() ? "processor-partial" : "compile",
                    "@Lombok",
                    partialProcessor() ? "INFERRED" : "EXTRACTED",
                    false);
        }

        boolean partialProcessor() {
            return unit.extensions().counters().containsKey("lombok_processor_partial");
        }

        Map<String, JavaAnnotation> annotations(JavaDeclaration declaration) {
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

        Map<String, JavaAnnotation> compiledAnnotations(JavaDeclaration declaration) {
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

        void constructors(
                JavaDeclaration owner,
                Map<String, JavaAnnotation> annotations,
                List<JavaDeclaration> fields,
                List<JavaDeclaration> members,
                LombokCapabilitySummary policy,
                LombokCapabilitySummary aggregate) {
            lombokDeclarationRules.constructors(
                    owner, annotations, fields, members, policy, aggregate);
        }

        void logger(
                JavaDeclaration owner,
                Map<String, JavaAnnotation> annotations,
                List<JavaDeclaration> members,
                LombokCapabilitySummary policy,
                LombokCapabilitySummary aggregate) {
            lombokDeclarationRules.logger(owner, annotations, members, policy, aggregate);
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
            lombokGeneratedMembers.method(
                    owner, members, name, parameters, returns, signature, modifiers, span, from);
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
            lombokGeneratedMembers.method(
                    owner,
                    members,
                    name,
                    parameters,
                    returns,
                    signature,
                    modifiers,
                    span,
                    from,
                    methodAnnotations);
        }

        JavaDeclaration parameter(JavaDeclaration owner, String name, int type) {
            return lombokGeneratedMembers.parameter(owner, name, type);
        }

        JavaDeclaration parameter(
                JavaDeclaration owner, String name, int type, List<JavaAnnotation> annotations) {
            return lombokGeneratedMembers.parameter(owner, name, type, annotations);
        }

        /** Lombok copies the written field type, excluding annotations in its modifier list. */
        boolean needsAnnotationCopies(JavaDeclaration declaration) {
            return lombokGeneratedMembers.needsAnnotationCopies(declaration);
        }

        boolean annotationCopyNeedsModel(JavaAnnotation annotation) {
            return lombokGeneratedMembers.annotationCopyNeedsModel(annotation);
        }

        int generatedType(JavaDeclaration declaration) {
            return lombokGeneratedMembers.generatedType(declaration);
        }

        int writtenFieldType(JavaDeclaration field) {
            return lombokGeneratedMembers.writtenFieldType(field);
        }

        List<JavaAnnotation> copiedMethodAnnotations(JavaDeclaration field, boolean getter) {
            return lombokGeneratedMembers.copiedMethodAnnotations(field, getter);
        }

        List<JavaAnnotation> copiedParameterAnnotations(JavaDeclaration field) {
            return lombokGeneratedMembers.copiedParameterAnnotations(field);
        }

        int writtenType(int reference, Set<String> excluded, int depth) {
            return lombokGeneratedMembers.writtenType(reference, excluded, depth);
        }

        boolean hasBaseAnnotations(int reference, int depth) {
            return lombokGeneratedMembers.hasBaseAnnotations(reference, depth);
        }

        String descriptor(int reference) {
            return lombokGeneratedMembers.descriptor(reference);
        }

        String descriptor(int reference, int depth) {
            return lombokGeneratedMembers.descriptor(reference, depth);
        }

        int primitive(JavaType.Kind kind) {
            return lombokGeneratedMembers.primitive(kind);
        }

        int named(String name) {
            return lombokGeneratedMembers.named(name);
        }

        boolean initialized(JavaDeclaration field) {
            return lombokGeneratedMembers.initialized(field);
        }

        boolean hasAny(List<JavaDeclaration> members, List<String> names, int arity) {
            return lombokGeneratedMembers.hasAny(members, names, arity);
        }

        String getterName(JavaDeclaration field) {
            return lombokDeclarationRules.getterName(field);
        }

        List<String> getterAlternatives(JavaDeclaration field) {
            return lombokDeclarationRules.getterAlternatives(field);
        }

        String booleanStem(JavaDeclaration field) {
            return lombokDeclarationRules.booleanStem(field);
        }

        boolean primitiveBoolean(JavaDeclaration field) {
            return lombokDeclarationRules.primitiveBoolean(field);
        }

        LombokCapabilitySummary summary(Map<String, JavaAnnotation> annotations) {
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

        void unsupported(JavaDeclaration owner, String feature) {
            increment("lombok_unsupported_features", 1);
            diagnostic(
                    "LOMBOK_FEATURE_UNSUPPORTED",
                    owner.canonicalName(),
                    "Detached Lombok model does not yet cover " + feature + ".");
        }

        void diagnostic(String code, String symbol, String sample) {
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

        void put(String id, String key, Object value) {
            var row = new LinkedHashMap<>(metadata.getOrDefault(id, Map.of()));
            row.put(key, value);
            metadata.put(id, row);
        }

        void increment(String key, long value) {
            counters.merge(key, value, Long::sum);
        }
    }

    static Map<String, JavaAnnotation> annotations(JavaDeclaration declaration) {
        return annotations(declaration.annotations());
    }

    static Map<String, JavaAnnotation> annotations(List<JavaAnnotation> declared) {
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

    static String annotationPackage(String name) {
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

    static String access(JavaAnnotation annotation, String attribute) {
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

    static boolean bool(JavaAnnotation annotation, String name, boolean fallback) {
        var value = annotation.values().get(name);
        return value instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof Boolean flag
                ? flag
                : fallback;
    }

    static String string(JavaAnnotation annotation, String name, String fallback) {
        var value = annotation.values().get(name);
        return value instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof String text
                ? text
                : fallback;
    }

    static List<String> modifiers(String access, boolean isStatic) {
        List<String> modifiers = new ArrayList<>();
        if (Set.of("PUBLIC", "PROTECTED", "PRIVATE").contains(access))
            modifiers.add(access.toLowerCase(Locale.ROOT));
        if (isStatic) modifiers.add("static");
        return modifiers;
    }

    static String capitalize(String name) {
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

    static String fieldId(JavaDeclaration owner, JavaDeclaration field) {
        return owner.canonicalName() + "#" + field.symbol().name();
    }

    static JavaGeneratedOrigin origin(String from) {
        return new JavaGeneratedOrigin(
                ProducerIds.LOMBOK_AST, "lombok", "ast", from, "INFERRED", false);
    }

    static JavaDeclaration incomplete(JavaDeclaration value, String reason) {
        var reasons = new ArrayList<>(value.incompleteReasons());
        if (!reasons.contains(reason)) reasons.add(reason);
        return copy(value, value.members(), reasons);
    }

    static JavaDeclaration copy(
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
