package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.io.IOException;
import java.util.*;

/** Static signature rules. Modeled metadata remains separate from javac evidence. */
final class LombokDeclarations {
    private static final Map<String, List<String>> CAPABILITIES =
            Map.ofEntries(
                    Map.entry("Getter", List.of("getter")),
                    Map.entry("Setter", List.of("setter")),
                    Map.entry(
                            "Data",
                            List.of(
                                    "getter",
                                    "setter",
                                    "required_constructor",
                                    "equals_hash_code",
                                    "to_string")),
                    Map.entry(
                            "Value",
                            List.of(
                                    "getter",
                                    "all_args_constructor",
                                    "equals_hash_code",
                                    "to_string",
                                    "value_modifiers")),
                    Map.entry("NoArgsConstructor", List.of("no_args_constructor")),
                    Map.entry("RequiredArgsConstructor", List.of("required_constructor")),
                    Map.entry("AllArgsConstructor", List.of("all_args_constructor")),
                    Map.entry("EqualsAndHashCode", List.of("equals_hash_code")),
                    Map.entry("ToString", List.of("to_string")),
                    Map.entry("Builder", List.of("builder")),
                    Map.entry("SuperBuilder", List.of("super_builder")),
                    Map.entry("UtilityClass", List.of("utility_class")));
    private static final Map<String, String> LOGGERS =
            Map.of(
                    "Slf4j",
                    "org.slf4j.Logger",
                    "XSlf4j",
                    "org.slf4j.ext.XLogger",
                    "Log",
                    "java.util.logging.Logger",
                    "Log4j",
                    "org.apache.log4j.Logger",
                    "Log4j2",
                    "org.apache.logging.log4j.Logger",
                    "CommonsLog",
                    "org.apache.commons.logging.Log",
                    "JBossLog",
                    "org.jboss.logging.Logger",
                    "Flogger",
                    "com.google.common.flogger.FluentLogger");
    private final Map<String, Object> model, context;
    private final List<Object> declarations;
    private final List<Object> additions = new ArrayList<>();
    private final Map<String, Map<String, Object>> known = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> members = new HashMap<>();
    private final Set<String> initialized = new HashSet<>();
    private final Set<String> removed = new HashSet<>();
    private final LombokConfiguration configuration;
    private LombokConfiguration.Effective effective;
    private Boolean generatedDefault;
    private boolean generatedChecked;
    private final java.util.function.Consumer<String> progress;
    private final Set<String> pendingSources;
    private record RebaseKey(dev.codeana.common.TypeGraph graph,
            Map<dev.codeana.common.TypeGraph.VariableIdentity, String> owners) {}
    // Bounded and confined to this overlay. Original archive leases belong to the task.
    private final Map<RebaseKey, dev.codeana.common.TypeGraph> rebasedGraphs =
            new LinkedHashMap<>(8, .75f, true);

    LombokDeclarations(Map<String, Object> model, Map<String, Object> context,
            java.util.function.Consumer<String> progress) {
        this.progress = progress;
        this.model = model;
        this.context = context;
        declarations = Json.list(model.get("declarations"));
        pendingSources = LombokModel.pendingSources(model);
        configuration = new LombokConfiguration(context);
        Set<String> missingInitializers = new HashSet<>();
        for (Object raw : declarations) {
            Map<String, Object> d = Json.map(raw);
            known.put(Json.string(d, "signature"), d);
            if (Boolean.TRUE.equals(attrs(d).get("has_initializer"))) initialized.add(Json.string(d, "signature"));
            if ("FIELD".equals(attrs(d).get("element_kind"))
                    && pendingSources.contains(Json.string(d, "source"))
                    && !attrs(d).containsKey("has_initializer"))
                missingInitializers.add(Json.string(d, "signature"));
            members.computeIfAbsent(Json.string(d, "parent"), ignored -> new ArrayList<>()).add(d);
        }
        progress.accept("initializers");
        // Current compiler rows carry explicit true/false metadata. Older detached inputs
        // need syntax only for fields whose initializer state is absent.
        if (!missingInitializers.isEmpty()) for (Object raw : Json.list(model.get("syntax"))) {
            Map<String, Object> n = Json.map(raw);
            if ("VARIABLE".equals(n.get("kind"))
                    && map(n.get("roles")).get("initializer") instanceof Number
                    && n.get("symbol") instanceof String symbol && missingInitializers.contains(symbol))
                initialized.add(symbol);
        }
    }

    void apply() {
        progress.accept("rules");
        for (Object raw : List.copyOf(declarations)) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            Map<String, Object> owner = Json.map(raw);
            if (!pendingSources.contains(Json.string(owner, "source"))) continue;
            if (!Set.of("CLASS", "ENUM", "INTERFACE", "ANNOTATION_TYPE")
                    .contains(attrs(owner).get("element_kind"))) continue;
            Map<String, Map<String, Object>> annotations = annotations(owner);
            List<Map<String, Object>> fields =
                    members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                            .filter(d -> "FIELD".equals(attrs(d).get("element_kind")))
                            .toList();
            Summary summary = new Summary(annotations);
            if (summary.detected.isEmpty()
                    && fields.stream().allMatch(f -> annotations(f).isEmpty())
                    && members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                            .noneMatch(m -> annotations(m).containsKey("Builder"))) continue;
            progress.accept("owner:" + Json.string(owner, "signature"));
            effective = configuration.forSource(Json.string(owner, "source"));
            configure(summary);
            if (!Set.of("CLASS", "ENUM").contains(attrs(owner).get("element_kind"))) {
                summary.degradeAll("unsupported_owner");
                describe(owner, summary, List.of());
                continue;
            }
            int before = additions.size();
            if (annotations.containsKey("UtilityClass")) {
                List<Object> effectiveModifiers = utility(owner, annotations, summary);
                summary.generated = additions.size() - before;
                describe(owner, summary, effectiveModifiers);
                continue;
            }
            builder(owner, annotations, fields, summary);
            superBuilder(owner, annotations, fields, summary);
            boolean value = annotations.containsKey("Value"),
                    data = annotations.containsKey("Data");
            List<Object> effective = new ArrayList<>(modifiers(owner));
            if (value) {
                if (!annotations.containsKey("NonFinal") && !effective.contains("final"))
                    effective.add("final");
                summary.model("value_modifiers");
            }
            Summary typePolicy = new Summary(annotations);
            configure(typePolicy);
            for (var field : fields)
                accessors(owner, field, annotations, data, value, typePolicy, summary);
            constructors(owner, fields, annotations, data, value, summary);
            if ((data || value || annotations.containsKey("EqualsAndHashCode"))
                    && !summary.blocked("equals_hash_code")) {
                if (!has(owner, List.of("equals"), 1) && !has(owner, List.of("hashCode"), 0)) {
                    var object = named("java.lang.Object");
                    method(
                            owner,
                            owner,
                            "equals",
                            List.of(object),
                            primitive("boolean"),
                            List.of("public"),
                            "equals_hash_code");
                    method(
                            owner,
                            owner,
                            "hashCode",
                            List.of(),
                            primitive("int"),
                            List.of("public"),
                            "equals_hash_code");
                    String parent =
                            text(map(attrs(owner).get("superclass_type_model")).get("element"));
                    if (!effective.contains("final")
                            || !parent.isEmpty() && !parent.equals("java.lang.Object"))
                        if (!has(owner, List.of("canEqual"), 1))
                            method(
                                    owner,
                                    owner,
                                    "canEqual",
                                    List.of(object),
                                    primitive("boolean"),
                                    List.of("protected"),
                                    "equals_hash_code");
                }
            }
            if ((data || value || annotations.containsKey("ToString"))
                    && !summary.blocked("to_string")
                    && !has(owner, List.of("toString"), 0))
                method(
                        owner,
                        owner,
                        "toString",
                        List.of(),
                        named("java.lang.String"),
                        List.of("public"),
                        "to_string");
            logger(owner, annotations, summary);
            summary.generated = additions.size() - before;
            describe(owner, summary, value ? effective : List.of());
        }
        declarations.removeIf(raw -> removed.contains(Json.string(Json.map(raw), "signature")));
        declarations.addAll(additions);
    }

    private void builder(
            Map<String, Object> owner,
            Map<String, Map<String, Object>> ownerAnnotations,
            List<Map<String, Object>> fields,
            Summary summary) {
        List<Map<String, Object>> targets = new ArrayList<>();
        if (ownerAnnotations.containsKey("Builder")) targets.add(owner);
        for (Map<String, Object> member :
                members.getOrDefault(Json.string(owner, "signature"), List.of()))
            if (annotations(member).containsKey("Builder")) targets.add(member);
        if (targets.isEmpty()) return;
        summary.detected.add("Builder");
        summary.model("builder");
        if (targets.size() != 1
                || summary.blocked("builder")
                || !"CLASS".equals(attrs(owner).get("element_kind"))
                || modifiers(owner).contains("abstract")
                || !Json.string(owner, "parent").isEmpty()
                        && !modifiers(owner).contains("static")) {
            summary.partial("builder", "builder_owner_or_targets");
            return;
        }
        Map<String, Object> target = targets.getFirst(),
                options = map(annotations(target).get("Builder").get("effective"));
        boolean methodTarget = "METHOD".equals(attrs(target).get("element_kind")),
                constructorTarget = "CONSTRUCTOR".equals(attrs(target).get("element_kind"));
        boolean instance = methodTarget && !modifiers(target).contains("static");
        if (target != owner && !methodTarget && !constructorTarget
                || Boolean.TRUE.equals(attrs(target).get("varargs"))
                || !items(map(attrs(target).get("type_model")).get("throws")).isEmpty()) {
            summary.partial("builder", "builder_target_shape");
            return;
        }
        List<Object> formals =
                items(attrs(methodTarget ? target : owner).get("type_parameter_models"));
        if (constructorTarget && !items(attrs(target).get("type_parameter_models")).isEmpty()
                || formals.stream()
                        .anyMatch(
                                f ->
                                        items(Json.map(f).get("bounds")).stream()
                                                        .anyMatch(
                                                                t ->
                                                                        !builderComplete(
                                                                                Json.map(t), 0))
                                                || !items(Json.map(f).get("annotations"))
                                                        .isEmpty())) {
            summary.partial("builder", "builder_type_parameters");
            return;
        }
        Map<String, Object> built =
                methodTarget
                        ? map(attrs(target).get("return_type_model"))
                        : map(attrs(owner).get("type_model"));
        boolean toBuilder = Boolean.TRUE.equals(options.get("toBuilder"));
        String defaultName = methodTarget ? text(built.get("element")) : Json.string(owner, "name");
        int simpleSeparator = Math.max(defaultName.lastIndexOf('.'), defaultName.lastIndexOf('$'));
        if (simpleSeparator >= 0) defaultName = defaultName.substring(simpleSeparator + 1);
        if (methodTarget && !defaultName.isEmpty()) defaultName = capitalize(defaultName);
        String
                builderName =
                        text(options.getOrDefault("builderClassName", defaultName + "Builder")),
                factory = text(options.getOrDefault("builderMethodName", "builder")),
                build = text(options.getOrDefault("buildMethodName", "build")),
                prefix = text(options.getOrDefault("setterPrefix", ""));
        if (builderName.isEmpty()) builderName = defaultName + "Builder";
        String access = access(annotations(target).get("Builder"), "access");
        if (methodTarget
                        && (Set.of("VOID", "ARRAY").contains(built.get("kind"))
                                || toBuilder
                                || "TYPEVAR".equals(built.get("kind"))
                                        && text(options.get("builderClassName")).isEmpty())
                || !builderComplete(built, 0)
                || access == null
                || Set.of("NONE", "MODULE").contains(access)
                || !builderIdentifier(builderName, false)
                || !builderIdentifier(factory, true)
                || !builderIdentifier(build, false)
                || !builderIdentifier(prefix, true)
                || options.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !Set.of(
                                                                "builderClassName",
                                                                "builderMethodName",
                                                                "buildMethodName",
                                                                "setterPrefix",
                                                                "toBuilder",
                                                                "access")
                                                        .contains(e.getKey())
                                                || Set.of(
                                                                        "builderClassName",
                                                                        "builderMethodName",
                                                                        "buildMethodName",
                                                                        "setterPrefix")
                                                                .contains(e.getKey())
                                                        && !(e.getValue() instanceof String)
                                                || e.getKey().equals("toBuilder")
                                                        && !(e.getValue() instanceof Boolean))) {
            summary.partial("builder", "builder_options_or_return");
            return;
        }
        List<Map<String, Object>> parameters =
                target == owner
                        ? fields.stream()
                                .filter(
                                        f ->
                                                !modifiers(f).contains("static")
                                                        && !Json.string(f, "name").startsWith("$")
                                                        && (!(modifiers(f).contains("final")
                                                                        || ownerAnnotations
                                                                                .containsKey(
                                                                                        "Value"))
                                                                || !initialized(f)
                                                                || annotations(f)
                                                                        .containsKey("Default")))
                                .toList()
                        : members.getOrDefault(Json.string(target, "signature"), List.of()).stream()
                                .filter(p -> "PARAMETER".equals(attrs(p).get("element_kind")))
                                .toList();
        List<Map<String, Object>> defaults =
                target == owner
                        ? parameters.stream()
                                .filter(p -> annotations(p).containsKey("Default"))
                                .toList()
                        : List.of();
        if (parameters.stream()
                        .anyMatch(
                                p ->
                                        !builderComplete(map(attrs(p).get("type_model")), 0)
                                                || needsAnnotationCopies(p))
                || fields.stream()
                        .anyMatch(
                                p ->
                                        annotations(p).containsKey("Singular")
                                                || annotations(p).containsKey("ObtainVia"))
                || defaults.stream()
                        .anyMatch(
                                p ->
                                        !initialized(p)
                                                || has(
                                                        owner,
                                                        List.of("$default$" + p.get("name")),
                                                        0))) {
            summary.partial("builder", "builder_parameter_default_or_annotation");
            return;
        }
        String binary = text(attrs(owner).get("binary_name"));
        if (binary.isEmpty()) binary = Json.string(owner, "signature");
        binary += "$" + builderName;
        Map<String, Object> custom = known.get(binary);
        List<Object> builderFormals = builderRebaseFormals(formals, binary);
        if (custom != null
                && (!"CLASS".equals(attrs(custom).get("element_kind"))
                        || modifiers(custom).contains("static") == instance
                        || !annotations(custom).isEmpty()
                        || !builderFormalShape(items(attrs(custom).get("type_parameter_models")))
                                .equals(builderFormalShape(builderFormals)))) {
            summary.partial("builder", "builder_custom_type");
            return;
        }
        for (Map<String, Object> parameter : parameters) {
            String fieldName =
                    Json.string(parameter, "name") + (defaults.contains(parameter) ? "$value" : "");
            for (Map<String, Object> existing :
                    custom == null
                            ? List.<Map<String, Object>>of()
                            : members.getOrDefault(binary, List.of()))
                if ("FIELD".equals(attrs(existing).get("element_kind"))
                        && fieldName.equals(existing.get("name"))
                        && (modifiers(existing).contains("static")
                                || !builderFormalShape(List.of(attrs(existing).get("type_model")))
                                        .equals(
                                                builderFormalShape(
                                                        List.of(
                                                                builderRebase(
                                                                        attrs(parameter)
                                                                                .get("type_model"),
                                                                        formals,
                                                                        builderFormals,
                                                                        0)))))) {
                    summary.partial("builder", "builder_custom_field");
                    return;
                }
        }
        Map<String, Object> builderType = builderNamed(binary, builderFormals);
        Map<String, Object> builder = custom;
        if (builder == null) {
            builder =
                    generated(
                            owner,
                            target,
                            builderName,
                            binary,
                            Json.object(
                                    "element_kind",
                                    "CLASS",
                                    "binary_name",
                                    binary,
                                    "canonical_name",
                                    Json.string(owner, "signature") + "." + builderName,
                                    "type_model",
                                    builderType,
                                    "declared_type",
                                    binary,
                                    "type_parameter_models",
                                    builderFormals,
                                    "modifiers",
                                    accessModifiers(access, !instance),
                                    "superclass_type_model",
                                    named("java.lang.Object"),
                                    "interface_type_models",
                                    List.of(),
                                    "modeled_capability",
                                    "builder"));
            known.put(binary, builder);
            members.put(binary, new ArrayList<>());
            additions.add(builder);
        }
        String builderSignature = Json.string(builder, "signature");
        // Only javac's implicit constructors may be replaced.
        builderRemoveDefaultConstructor(builder);
        if (!builderHasKind(builder, "CONSTRUCTOR"))
            method(builder, target, "<init>", List.of(), primitive("void"), List.of(), "builder");
        for (Map<String, Object> parameter : parameters) {
            String name = Json.string(parameter, "name");
            boolean defaulted = defaults.contains(parameter);
            Map<String, Object> fieldType =
                    Json.map(
                            builderRebase(
                                    generatedType(parameter),
                                    formals,
                                    builderFormals,
                                    0));
            builderField(
                    builder,
                    parameter,
                    name + (defaulted ? "$value" : ""),
                    fieldType,
                    List.of("private"));
            if (defaulted) {
                builderField(
                        builder,
                        parameter,
                        name + "$set",
                        primitive("boolean"),
                        List.of("private"));
                Map<String, Object> provider =
                        method(
                                owner,
                                parameter,
                                "$default$" + name,
                                List.of(),
                                generatedType(parameter),
                                List.of("private", "static"),
                                "builder");
                builderGenericMethod(provider, formals);
            }
            String setter = prefix.isEmpty() ? name : prefix + capitalize(name);
            if (!builderHasName(builder, setter)) {
                Map<String, Object> setterMethod =
                        method(
                                builder,
                                parameter,
                                setter,
                                List.of(fieldType),
                                builderType,
                                accessModifiers(
                                        access.equals("PROTECTED") ? "PUBLIC" : access, false),
                                "builder");
                if (setterMethod != null) {
                    attrs(setterMethod).put("parameter_names", List.of(name));
                    attrs(setterMethod)
                            .put(
                                    "parameter_annotations",
                                    List.of(copiedAnnotations(parameter, true)));
                }
            }
        }
        Map<String, Object> builtInBuilder =
                Json.map(builderRebase(built, formals, builderFormals, 0));
        if (!builderHasName(builder, build))
            method(
                    builder,
                    target,
                    build,
                    List.of(),
                    builtInBuilder,
                    accessModifiers(access.equals("PROTECTED") ? "PUBLIC" : access, false),
                    "builder");
        if (!builderHasName(builder, "toString"))
            method(
                    builder,
                    target,
                    "toString",
                    List.of(),
                    named("java.lang.String"),
                    List.of("public"),
                    "builder");
        if (!factory.isEmpty() && !builderHasName(owner, factory)) {
            Map<String, Object> factoryMethod =
                    method(
                            owner,
                            target,
                            factory,
                            List.of(),
                            builderNamed(builderSignature, formals),
                            accessModifiers(access, !instance),
                            "builder");
            builderGenericMethod(factoryMethod, formals);
        }
        if (toBuilder && !builderHasName(owner, "toBuilder"))
            method(
                    owner,
                    target,
                    "toBuilder",
                    List.of(),
                    builderNamed(builderSignature, formals),
                    List.of("public"),
                    "builder");
        if (target == owner
                && !Set.of("NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor")
                        .stream()
                        .anyMatch(ownerAnnotations::containsKey)) {
            if (builderHasCompilerConstructor(owner)) summary.constructorReplaced = true;
            builderRemoveDefaultConstructor(owner);
            List<Object> constructorTypes =
                    parameters.stream().map(p -> (Object) generatedType(p)).toList();
            Map<String, Object> constructor =
                    method(
                            owner,
                            owner,
                            "<init>",
                            constructorTypes,
                            primitive("void"),
                            List.of(),
                            "builder");
            if (constructor != null)
                attrs(constructor)
                        .put(
                                "parameter_names",
                                parameters.stream().map(p -> p.get("name")).toList());
        }
    }

    /** Mirrors the frozen reference's bounded hierarchy, without running processors. */
    private String superBuilderValidation(
            Map<String, Object> owner, Set<String> active, int depth) {
        String signature = Json.string(owner, "signature");
        if (depth >= 32 || !active.add(signature)) return "super_builder_hierarchy_budget";
        try {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            Map<String, Map<String, Object>> annotations = annotations(owner);
            Map<String, Object> annotation = annotations.get("SuperBuilder");
            if (!"CLASS".equals(attrs(owner).get("element_kind"))
                    || annotation == null
                    || !Boolean.TRUE.equals(annotation.get("complete"))
                    || !map(annotation.get("explicit")).isEmpty()
                    || annotations.containsKey("Builder")
                    || !configuration.forSource(Json.string(owner, "source")).uncertain().isEmpty()
                    || !builderComplete(map(attrs(owner).get("type_model")), 0))
                return "super_builder_owner_options_or_configuration";
            if (!Json.string(owner, "parent").isEmpty() && !modifiers(owner).contains("static"))
                return "super_builder_nonstatic_nested_owner";
            String binary = text(attrs(owner).get("binary_name"));
            if (binary.isEmpty()) binary = signature;
            Map<String, Object> custom =
                    known.get(binary + "$" + Json.string(owner, "name") + "Builder");
            if (custom != null && !"lombok_model".equals(attrs(custom).get("binding_origin"))
                    || members.getOrDefault(signature, List.of()).stream()
                            .anyMatch(
                                    m ->
                                            "METHOD".equals(attrs(m).get("element_kind"))
                                                    && "builder".equals(m.get("name"))
                                                    && !"lombok_model"
                                                            .equals(
                                                                    attrs(m).get(
                                                                                    "binding_origin"))))
                return "super_builder_custom_builder_or_entry";
            List<Object> formals = items(attrs(owner).get("type_parameter_models"));
            for (Object raw : formals) {
                Map<String, Object> formal = Json.map(raw);
                if (!items(formal.get("annotations")).isEmpty()
                        || items(formal.get("bounds")).stream()
                                .anyMatch(t -> !builderComplete(Json.map(t), 0)))
                    return "super_builder_type_parameters";
            }
            for (Map<String, Object> field : members.getOrDefault(signature, List.of())) {
                if (!"FIELD".equals(attrs(field).get("element_kind"))) continue;
                if (!builderComplete(map(attrs(field).get("type_model")), 0)
                        || needsAnnotationCopies(field)
                        || items(attrs(field).get("annotations")).stream()
                                .map(Json::map)
                                .anyMatch(
                                        a ->
                                                !Set.of(
                                                                "lombok.NonNull",
                                                                "lombok.Builder$Default",
                                                                "lombok.Builder.Default",
                                                                "java.lang.Deprecated")
                                                        .contains(a.get("type"))))
                    return "super_builder_field_propagation";
                if (annotations(field).containsKey("Default")
                        && (modifiers(field).contains("static")
                                || !initialized(field)
                                || has(owner, List.of("$default$" + field.get("name")), 0)))
                    return "super_builder_default_or_provider";
            }
            Map<String, Object> parentUse = map(attrs(owner).get("superclass_type_model"));
            String parentName = text(parentUse.get("element"));
            if (Set.of("", "java.lang.Object").contains(parentName)) return "";
            Map<String, Object> parent = known.get(parentName);
            if (parent == null || "lombok_model".equals(attrs(parent).get("binding_origin")))
                return "super_builder_parent_source_unavailable";
            if (items(parentUse.get("arguments")).size()
                    != items(attrs(parent).get("type_parameter_models")).size())
                return "super_builder_raw_parent_arguments";
            return superBuilderValidation(parent, active, depth + 1);
        } finally {
            active.remove(signature);
        }
    }

    private static String superBuilderFresh(String base, Set<String> names) {
        String name = base;
        for (int suffix = 2; !names.add(name); suffix++) name = base + suffix;
        return name;
    }

    private Map<String, Object> superBuilderClass(
            Map<String, Object> owner,
            String name,
            List<Object> formals,
            Map<String, Object> superclass,
            List<Object> modifiers) {
        String binary = text(attrs(owner).get("binary_name"));
        if (binary.isEmpty()) binary = Json.string(owner, "signature");
        binary += "$" + name;
        Map<String, Object> generated =
                generated(
                        owner,
                        owner,
                        name,
                        binary,
                        Json.object(
                                "element_kind",
                                "CLASS",
                                "binary_name",
                                binary,
                                "canonical_name",
                                Json.string(owner, "signature") + "." + name,
                                "type_model",
                                builderNamed(binary, formals),
                                "declared_type",
                                binary,
                                "type_parameter_models",
                                formals,
                                "modifiers",
                                modifiers,
                                "superclass_type_model",
                                superclass,
                                "interface_type_models",
                                List.of(),
                                "modeled_capability",
                                "super_builder"));
        known.put(binary, generated);
        members.put(binary, new ArrayList<>());
        additions.add(generated);
        return generated;
    }

    private void superBuilder(
            Map<String, Object> owner,
            Map<String, Map<String, Object>> annotations,
            List<Map<String, Object>> fields,
            Summary summary) {
        if (!annotations.containsKey("SuperBuilder")) return;
        String rejection = superBuilderValidation(owner, new HashSet<>(), 0);
        if (summary.blocked("super_builder") || !rejection.isEmpty()) {
            summary.partial(
                    "super_builder", rejection.isEmpty() ? "super_builder_policy" : rejection);
            return;
        }
        List<Object> formals = items(attrs(owner).get("type_parameter_models"));
        String ownerBinary = text(attrs(owner).get("binary_name"));
        if (ownerBinary.isEmpty()) ownerBinary = Json.string(owner, "signature");
        String builderName = Json.string(owner, "name") + "Builder",
                builderBinary = ownerBinary + "$" + builderName;
        List<Object> builderFormals = new ArrayList<>(builderRebaseFormals(formals, builderBinary));
        Set<String> names = new HashSet<>();
        formals.forEach(f -> names.add(text(Json.map(f).get("name"))));
        String productName = superBuilderFresh("C", names),
                selfName = superBuilderFresh("B", names);
        Map<String, Object> ownerInBuilder =
                Json.map(builderRebase(attrs(owner).get("type_model"), formals, builderFormals, 0));
        Map<String, Object> productFormal =
                Json.object(
                        "name",
                        productName,
                        "owner",
                        builderBinary,
                        "bounds",
                        List.of(ownerInBuilder),
                        "annotations",
                        List.of(),
                        "complete",
                        true);
        Map<String, Object> product = builderVariable(productFormal);
        // Finite identity cutoff for the self-bound B extends Builder<...,C,B>.
        Map<String, Object> selfIdentity =
                Json.object(
                        "version",
                        2,
                        "kind",
                        "TYPEVAR",
                        "name",
                        selfName,
                        "display",
                        selfName,
                        "owner",
                        builderBinary,
                        "recursive",
                        true,
                        "complete",
                        false);
        List<Object> boundArguments = new ArrayList<>();
        builderFormals.forEach(f -> boundArguments.add(builderVariable(Json.map(f))));
        boundArguments.add(product);
        boundArguments.add(selfIdentity);
        Map<String, Object> selfBound = named(builderBinary);
        selfBound.put("arguments", boundArguments);
        Map<String, Object> selfFormal =
                Json.object(
                        "name",
                        selfName,
                        "owner",
                        builderBinary,
                        "bounds",
                        List.of(selfBound),
                        "annotations",
                        List.of(),
                        "complete",
                        true);
        Map<String, Object> self = builderVariable(selfFormal);
        builderFormals.add(productFormal);
        builderFormals.add(selfFormal);
        Map<String, Object> superclass = named("java.lang.Object");
        Map<String, Object> parentUse = map(attrs(owner).get("superclass_type_model"));
        Map<String, Object> parent = known.get(text(parentUse.get("element")));
        if (parent != null && !"java.lang.Object".equals(parentUse.get("element"))) {
            String parentBinary = text(attrs(parent).get("binary_name"));
            if (parentBinary.isEmpty()) parentBinary = Json.string(parent, "signature");
            superclass = named(parentBinary + "$" + Json.string(parent, "name") + "Builder");
            List<Object> args =
                    new ArrayList<>(
                            items(
                                    Json.map(builderRebase(parentUse, formals, builderFormals, 0))
                                            .get("arguments")));
            args.add(product);
            args.add(self);
            superclass.put("arguments", args);
        }
        Map<String, Object> builder =
                superBuilderClass(
                        owner,
                        builderName,
                        builderFormals,
                        superclass,
                        List.of("public", "static", "abstract"));
        Map<String, Object> implicitConstructor =
                method(builder, owner, "<init>", List.of(), primitive("void"), List.of("public"), "super_builder");
        if (implicitConstructor != null) attrs(implicitConstructor).put("modeled_implicit", true);
        List<Map<String, Object>> selected =
                fields.stream()
                        .filter(
                                f ->
                                        !modifiers(f).contains("static")
                                                && !Json.string(f, "name").startsWith("$")
                                                && (!modifiers(f).contains("final")
                                                        || !initialized(f)
                                                        || annotations(f).containsKey("Default")))
                        .toList();
        for (Map<String, Object> field : selected) {
            String name = Json.string(field, "name");
            boolean defaulted = annotations(field).containsKey("Default");
            Map<String, Object> type =
                    Json.map(
                            builderRebase(
                                    generatedType(field), formals, builderFormals, 0));
            builderField(
                    builder, field, name + (defaulted ? "$value" : ""), type, List.of("private"));
            attrs(members.get(Json.string(builder, "signature")).getLast())
                    .put("modeled_capability", "super_builder");
            if (defaulted) {
                builderField(
                        builder, field, name + "$set", primitive("boolean"), List.of("private"));
                attrs(members.get(Json.string(builder, "signature")).getLast())
                        .put("modeled_capability", "super_builder");
                Map<String, Object> provider =
                        method(
                                owner,
                                field,
                                "$default$" + name,
                                List.of(),
                                map(attrs(field).get("type_model")),
                                List.of("private", "static"),
                                "super_builder");
                builderGenericMethod(provider, formals);
            }
            Map<String, Object> setter =
                    method(
                            builder,
                            field,
                            name,
                            List.of(type),
                            self,
                            List.of("public"),
                            "super_builder");
            attrs(setter).put("parameter_names", List.of(name));
            attrs(setter).put("parameter_annotations", List.of(copiedAnnotations(field, true)));
        }
        method(
                builder,
                owner,
                "self",
                List.of(),
                self,
                List.of("protected", "abstract"),
                "super_builder");
        method(
                builder,
                owner,
                "build",
                List.of(),
                product,
                List.of("public", "abstract"),
                "super_builder");
        method(
                builder,
                owner,
                "toString",
                List.of(),
                named("java.lang.String"),
                List.of("public"),
                "super_builder");
        Map<String, Object> wildcard =
                Json.object("version", 2, "kind", "WILDCARD", "display", "?", "complete", true);
        Map<String, Object> publicBuilder = builderNamed(builderBinary, formals);
        List<Object> publicArguments = new ArrayList<>(items(publicBuilder.get("arguments")));
        publicArguments.add(wildcard);
        publicArguments.add(wildcard);
        publicBuilder.put("arguments", publicArguments);
        if (builderHasCompilerConstructor(owner)) summary.constructorReplaced = true;
        builderRemoveDefaultConstructor(owner);
        Map<String, Object> constructor =
                method(
                        owner,
                        owner,
                        "<init>",
                        List.of(publicBuilder),
                        primitive("void"),
                        List.of("protected"),
                        "super_builder");
        if (constructor != null) attrs(constructor).put("parameter_names", List.of("b"));
        if (!modifiers(owner).contains("abstract")) {
            String implName = Json.string(owner, "name") + "BuilderImpl",
                    implBinary = ownerBinary + "$" + implName;
            List<Object> implFormals = builderRebaseFormals(formals, implBinary);
            Map<String, Object> implType = builderNamed(implBinary, implFormals);
            Map<String, Object> ownerInImpl =
                    Json.map(
                            builderRebase(attrs(owner).get("type_model"), formals, implFormals, 0));
            Map<String, Object> concreteBuilder = builderNamed(builderBinary, implFormals);
            List<Object> args = new ArrayList<>(items(concreteBuilder.get("arguments")));
            args.add(ownerInImpl);
            args.add(implType);
            concreteBuilder.put("arguments", args);
            Map<String, Object> impl =
                    superBuilderClass(
                            owner,
                            implName,
                            implFormals,
                            concreteBuilder,
                            List.of("private", "static", "final"));
            method(
                    impl,
                    owner,
                    "<init>",
                    List.of(),
                    primitive("void"),
                    List.of("private"),
                    "super_builder");
            method(impl, owner, "self", List.of(), implType, List.of("protected"), "super_builder");
            method(
                    impl,
                    owner,
                    "build",
                    List.of(),
                    ownerInImpl,
                    List.of("public"),
                    "super_builder");
            Map<String, Object> entry =
                    method(
                            owner,
                            owner,
                            "builder",
                            List.of(),
                            publicBuilder,
                            List.of("public", "static"),
                            "super_builder");
            builderGenericMethod(entry, formals);
        }
        summary.model("super_builder");
    }

    private static boolean builderIdentifier(String value, boolean empty) {
        return value.isEmpty()
                ? empty
                : javax.lang.model.SourceVersion.isIdentifier(value)
                        && !javax.lang.model.SourceVersion.isKeyword(
                                value, javax.lang.model.SourceVersion.RELEASE_8);
    }

    private boolean builderHasName(Map<String, Object> owner, String name) {
        return members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                .anyMatch(
                        d ->
                                "METHOD".equals(attrs(d).get("element_kind"))
                                        && name.equals(d.get("name")));
    }

    private boolean builderHasCompilerConstructor(Map<String, Object> owner) {
        return members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                .anyMatch(
                        d ->
                                "CONSTRUCTOR".equals(attrs(d).get("element_kind"))
                                        && Boolean.TRUE.equals(d.get("generated"))
                                        && !"lombok_model".equals(attrs(d).get("binding_origin")));
    }

    private boolean builderHasKind(Map<String, Object> owner, String kind) {
        return members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                .anyMatch(d -> kind.equals(attrs(d).get("element_kind")));
    }

    private void builderRemoveDefaultConstructor(Map<String, Object> owner) {
        for (Map<String, Object> member :
                List.copyOf(members.getOrDefault(Json.string(owner, "signature"), List.of())))
            if ("CONSTRUCTOR".equals(attrs(member).get("element_kind"))
                    && Boolean.TRUE.equals(member.get("generated"))
                    && !"lombok_model".equals(attrs(member).get("binding_origin"))) {
                removed.add(Json.string(member, "signature"));
                known.remove(Json.string(member, "signature"));
                members.get(Json.string(owner, "signature")).remove(member);
            }
    }

    private void builderField(
            Map<String, Object> owner,
            Map<String, Object> origin,
            String name,
            Map<String, Object> type,
            List<Object> modifiers) {
        if (members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                .anyMatch(
                        d ->
                                "FIELD".equals(attrs(d).get("element_kind"))
                                        && name.equals(d.get("name")))) return;
        String signature = Json.string(owner, "signature") + '#' + name;
        Map<String, Object> field =
                generated(
                        owner,
                        origin,
                        name,
                        signature,
                        Json.object(
                                "element_kind",
                                "FIELD",
                                "type_model",
                                type,
                                "declared_type",
                                display(type),
                                "modifiers",
                                modifiers,
                                "modeled_capability",
                                "builder"));
        // Anatomist exposes the original field's written annotations on the modeled
        // builder storage field. This is a source projection, not a processor copy.
        if ("FIELD".equals(attrs(origin).get("element_kind")) && !name.endsWith("$set"))
            attrs(field).put("source_declaration_origin", origin.get("signature"));
        known.put(signature, field);
        members.get(Json.string(owner, "signature")).add(field);
        additions.add(field);
    }

    private static Map<String, Object> builderVariable(Map<String, Object> formal) {
        List<Object> bounds = items(formal.get("bounds"));
        Object upper =
                bounds.size() == 1
                        ? bounds.getFirst()
                        : Json.object(
                                "version",
                                2,
                                "kind",
                                "INTERSECTION",
                                "bounds",
                                bounds,
                                "complete",
                                true);
        return Json.object(
                "version",
                2,
                "kind",
                "TYPEVAR",
                "name",
                formal.get("name"),
                "display",
                formal.get("name"),
                "owner",
                formal.get("owner"),
                "upper",
                upper,
                "complete",
                true);
    }

    private static boolean builderComplete(Map<String, Object> type, int depth) {
        if (dev.codeana.common.TypeGraph.isView(type))
            return Boolean.TRUE.equals(type.get("complete"));
        if (depth > 64 || type.isEmpty() || !type.containsKey("kind")) return false;
        if (Boolean.TRUE.equals(type.get("recursive")))
            return "TYPEVAR".equals(type.get("kind"))
                    ? !text(type.get("name")).isEmpty() && !text(type.get("owner")).isEmpty()
                    : "DECLARED".equals(type.get("kind")) && !text(type.get("element")).isEmpty();
        if (!Boolean.TRUE.equals(type.get("complete"))
                || Set.of("ERROR", "UNKNOWN").contains(type.get("kind"))) return false;
        for (String key : List.of("component", "upper", "lower", "extends", "super", "enclosing"))
            if (type.get(key) instanceof Map
                    && !builderComplete(Json.map(type.get(key)), depth + 1)) return false;
        for (String key : List.of("arguments", "bounds"))
            for (Object child : items(type.get(key)))
                if (!builderComplete(Json.map(child), depth + 1)) return false;
        return true;
    }

    private static Map<String, Object> builderNamed(String name, List<Object> formals) {
        Map<String, Object> type = named(name);
        type.put("arguments", formals.stream().map(f -> builderVariable(Json.map(f))).toList());
        return type;
    }

    private List<Object> builderRebaseFormals(List<Object> formals, String owner) {
        List<Object> result = new ArrayList<>();
        for (Object raw : formals) {
            Map<String, Object> formal = dev.codeana.common.TypeGraph.copyRoot(Json.map(raw));
            formal.put("owner", owner);
            result.add(formal);
        }
        List<Object> rebased = new ArrayList<>();
        for (Object raw : result) rebased.add(builderRebase(raw, formals, result, 0));
        return rebased;
    }

    private Object builderRebase(
            Object raw, List<Object> old, List<Object> replacements, int depth) {
        if (depth > 64) throw new IllegalArgumentException("BUILDER_TYPE_DEPTH");
        if (dev.codeana.common.TypeGraph.isView(raw)) {
            // Rebase graph metadata while retaining numeric edges and recursive bounds.
            // Traversing the compatibility view would turn real NULL lower bounds and
            // recursive references into absent edges in newly generated declarations.
            Map<String,Object> type = Json.map(raw);
            dev.codeana.common.TypeGraph graph = dev.codeana.common.TypeGraph.owner(type);
            Map<dev.codeana.common.TypeGraph.VariableIdentity, String> owners = new LinkedHashMap<>();
            for (int i = 0; i < old.size(); i++) {
                Map<String, Object> formal = Json.map(old.get(i));
                owners.put(new dev.codeana.common.TypeGraph.VariableIdentity(
                        Json.string(formal, "owner"), Json.string(formal, "name")),
                        Json.string(Json.map(replacements.get(i)), "owner"));
            }
            RebaseKey key = new RebaseKey(graph, Map.copyOf(owners));
            dev.codeana.common.TypeGraph rewritten = rebasedGraphs.get(key);
            if (rewritten == null) {
                rewritten = graph.rebaseOwners(key.owners());
                if (rebasedGraphs.size() >= 8)
                    rebasedGraphs.remove(rebasedGraphs.keySet().iterator().next());
                rebasedGraphs.put(key, rewritten);
            }
            return rewritten == graph ? raw
                    : rewritten.view(dev.codeana.common.TypeGraph.reference(type));
        }
        if (raw instanceof Map) {
            Map<String, Object> input = Json.map(raw);
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : input.entrySet())
                result.put(
                        entry.getKey(),
                        builderRebase(entry.getValue(), old, replacements, depth + 1));
            if ("TYPEVAR".equals(input.get("kind")))
                for (int i = 0; i < old.size(); i++)
                    if (Objects.equals(input.get("owner"), Json.map(old.get(i)).get("owner"))
                            && Objects.equals(input.get("name"), Json.map(old.get(i)).get("name")))
                        result.put("owner", Json.map(replacements.get(i)).get("owner"));
            return result;
        }
        if (raw instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : Json.list(raw))
                result.add(builderRebase(item, old, replacements, depth + 1));
            return result;
        }
        return raw;
    }

    private static Object builderFormalShape(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : Json.map(value).entrySet())
                if (!Set.of("owner", "display").contains(entry.getKey()))
                    result.put(entry.getKey(), builderFormalShape(entry.getValue()));
            return result;
        }
        if (value instanceof List)
            return Json.list(value).stream().map(LombokDeclarations::builderFormalShape).toList();
        return value;
    }

    private void builderGenericMethod(Map<String, Object> method, List<Object> formals) {
        if (method == null || formals.isEmpty()) return;
        List<Object> bound = builderRebaseFormals(formals, Json.string(method, "signature"));
        Map<String, Object> a = attrs(method);
        a.put("type_parameter_models", bound);
        a.put("return_type_model", builderRebase(a.get("return_type_model"), formals, bound, 0));
        a.put("type_model", new LinkedHashMap<>(map(a.get("type_model"))));
        map(a.get("type_model")).put("return", a.get("return_type_model"));
        map(a.get("type_model")).put("type_parameters", bound);
    }

    /**
     * Reference's bounded UtilityClass case: explicit static source members, declaration-only ctor.
     */
    private List<Object> utility(
            Map<String, Object> owner,
            Map<String, Map<String, Object>> annotations,
            Summary summary) {
        List<Map<String, Object>> ownerMembers =
                members.getOrDefault(Json.string(owner, "signature"), List.of());
        boolean supported =
                "CLASS".equals(attrs(owner).get("element_kind"))
                        && Json.string(owner, "parent").isEmpty()
                        && annotations.size() == 1
                        && !modifiers(owner).contains("abstract")
                        && items(attrs(owner).get("interface_type_models")).isEmpty()
                        && Set.of("", "java.lang.Object")
                                .contains(
                                        text(
                                                map(attrs(owner).get("superclass_type_model"))
                                                        .get("element")))
                        && !summary.blocked("utility_class")
                        && generatedPolicy() != null
                        && ownerMembers.stream()
                                .allMatch(
                                        member ->
                                                "CONSTRUCTOR"
                                                                .equals(
                                                                        attrs(member)
                                                                                .get(
                                                                                        "element_kind"))
                                                        ? Boolean.TRUE.equals(
                                                                member.get("generated"))
                                                        : Set.of("FIELD", "METHOD")
                                                                        .contains(
                                                                                attrs(member)
                                                                                        .get(
                                                                                                "element_kind"))
                                                                && modifiers(member)
                                                                        .contains("static")
                                                                && annotations(member).isEmpty());
        if (!supported) {
            summary.partial(
                    "utility_class",
                    "top_level_explicit_static_members_and_known_generation_policy_required");
            summary.degradeAll("utility_class_owner_policy");
            return List.of();
        }
        for (var declaration : List.copyOf(ownerMembers))
            if ("CONSTRUCTOR".equals(attrs(declaration).get("element_kind"))) {
                removed.add(Json.string(declaration, "signature"));
                known.remove(Json.string(declaration, "signature"));
                ownerMembers.remove(declaration);
            }
        method(
                owner,
                owner,
                "<init>",
                List.of(),
                primitive("void"),
                List.of("private"),
                "utility_class");
        summary.constructorReplaced = true;
        List<Object> effective = new ArrayList<>(modifiers(owner));
        if (!effective.contains("final")) effective.add("final");
        return effective;
    }

    private void accessors(
            Map<String, Object> owner,
            Map<String, Object> field,
            Map<String, Map<String, Object>> type,
            boolean data,
            boolean value,
            Summary typePolicy,
            Summary aggregate) {
        Map<String, Map<String, Object>> own = annotations(field);
        Summary summary = new Summary(own);
        configure(summary);
        int before = additions.size();
        boolean instance = !modifiers(field).contains("static");
        boolean getter =
                own.containsKey("Getter")
                        || instance && (data || value || type.containsKey("Getter"));
        boolean setter =
                !value
                        && !modifiers(field).contains("final")
                        && (own.containsKey("Setter")
                                || instance && (data || type.containsKey("Setter")));
        if (own.containsKey("Accessors") || type.containsKey("Accessors")) {
            if (getter) summary.partial("getter", "accessors_policy");
            if (setter) summary.partial("setter", "accessors_policy");
        }
        String name = Json.string(field, "name");
        if (name.isEmpty() || name.startsWith("$")) {
            aggregate.merge(summary);
            return;
        }
        Map<String, Object> fieldType = generatedType(field);
        if (!complete(fieldType)) {
            if (getter) summary.partial("getter", "incomplete_field_type");
            if (setter) summary.partial("setter", "incomplete_field_type");
        }
        boolean bool = "BOOLEAN".equals(fieldType.get("kind"));
        String cap = capitalize(name),
                stem =
                        bool
                                        && !effective.noIsPrefix()
                                        && name.startsWith("is")
                                        && name.length() > 2
                                        && !Character.isLowerCase(name.charAt(2))
                                ? name.substring(2)
                                : cap;
        Map<String, Object> get = own.getOrDefault("Getter", type.get("Getter"));
        Map<String, Object> set = own.getOrDefault("Setter", type.get("Setter"));
        if (getter && !summary.blocked("getter") && !typePolicy.blocked("getter")) {
            String access = access(get, "value");
            if (access == null) summary.partial("getter", "access_level");
            else if (!access.equals("NONE")
                    && !has(
                            owner,
                            bool && !effective.noIsPrefix()
                                    ? List.of("is" + cap, "get" + cap, "is" + stem, "get" + stem)
                                    : List.of("get" + cap),
                            0))
                method(
                        owner,
                        field,
                        (bool && !effective.noIsPrefix() ? "is" + stem : "get" + cap),
                        List.of(),
                        fieldType,
                        accessModifiers(access, !instance),
                        "getter");
        }
        if (setter && !summary.blocked("setter") && !typePolicy.blocked("setter")) {
            String access = access(set, "value");
            if (access == null) summary.partial("setter", "access_level");
            else if (!access.equals("NONE") && !has(owner, List.of("set" + stem), 1))
                method(
                        owner,
                        field,
                        "set" + stem,
                        List.of(fieldType),
                        primitive("void"),
                        accessModifiers(access, !instance),
                        "setter");
        }
        summary.generated = additions.size() - before;
        if (value) {
            List<Object> effective = new ArrayList<>(modifiers(field));
            if (!own.containsKey("NonFinal") && !effective.contains("final"))
                effective.add("final");
            if (!own.containsKey("PackagePrivate")
                    && Collections.disjoint(effective, List.of("public", "protected", "private")))
                effective.add("private");
            describe(field, summary, effective);
        } else if (!summary.detected.isEmpty()) describe(field, summary, List.of());
        if (needsAnnotationCopies(field))
            aggregate.partial("annotation_propagation", "annotation_copy_pending");
        aggregate.merge(summary);
    }

    private void constructors(
            Map<String, Object> owner,
            List<Map<String, Object>> fields,
            Map<String, Map<String, Object>> annotations,
            boolean data,
            boolean value,
            Summary summary) {
        boolean explicit =
                members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                        .anyMatch(
                                d ->
                                        "CONSTRUCTOR".equals(attrs(d).get("element_kind"))
                                                && !Boolean.TRUE.equals(d.get("generated")));
        boolean policy =
                List.of(
                                "NoArgsConstructor",
                                "RequiredArgsConstructor",
                                "AllArgsConstructor",
                                "Builder",
                                "SuperBuilder")
                        .stream()
                        .anyMatch(annotations::containsKey);
        for (String kind :
                List.of("NoArgsConstructor", "RequiredArgsConstructor", "AllArgsConstructor")) {
            String capability =
                    switch (kind) {
                        case "NoArgsConstructor" -> "no_args_constructor";
                        case "RequiredArgsConstructor" -> "required_constructor";
                        default -> "all_args_constructor";
                    };
            Map<String, Object> annotation = annotations.get(kind);
            if (annotation == null && !explicit && !policy)
                annotation =
                        kind.equals("RequiredArgsConstructor") && data
                                ? annotations.get("Data")
                                : kind.equals("AllArgsConstructor") && value
                                        ? annotations.get("Value")
                                        : null;
            if (annotation == null || summary.blocked(capability)) continue;
            Map<String, Object> options = map(annotation.get("effective"));
            String access = access(annotation, "access");
            if (access == null
                    || !text(options.get("staticName")).isEmpty()
                    || !text(options.get("staticConstructor")).isEmpty()) {
                summary.partial(capability, "constructor_options");
                continue;
            }
            if (access.equals("NONE")) continue;
            List<Map<String, Object>> selected =
                    fields.stream()
                            .filter(
                                    f ->
                                            !modifiers(f).contains("static")
                                                    && !Json.string(f, "name").startsWith("$"))
                            .filter(f -> !kind.equals("NoArgsConstructor"))
                            .filter(
                                    f ->
                                            !(modifiers(f).contains("final")
                                                            || value
                                                                    && !annotations(f)
                                                                            .containsKey(
                                                                                    "NonFinal"))
                                                    || !initialized(f))
                            .filter(
                                    f ->
                                            !kind.equals("RequiredArgsConstructor")
                                                    || !initialized(f)
                                                            && (modifiers(f).contains("final")
                                                                    || annotations(f)
                                                                            .containsKey(
                                                                                    "NonNull")))
                            .toList();
            if (selected.stream().anyMatch(f -> !complete(map(attrs(f).get("type_model"))))) {
                summary.partial(capability, "incomplete_parameter_type");
                continue;
            }
            if (kind.equals("NoArgsConstructor")
                    && !Boolean.TRUE.equals(options.get("force"))
                    && fields.stream()
                            .anyMatch(
                                    f ->
                                            !modifiers(f).contains("static")
                                                    && !initialized(f)
                                                    && (modifiers(f).contains("final")
                                                            || value
                                                                    && !annotations(f)
                                                                            .containsKey(
                                                                                    "NonFinal")))) {
                summary.partial(capability, "uninitialized_final_without_force");
                continue;
            }
            List<Map<String, Object>> ownerMembers =
                    members.getOrDefault(Json.string(owner, "signature"), List.of());
            for (var d : List.copyOf(ownerMembers))
                if ("CONSTRUCTOR".equals(attrs(d).get("element_kind"))
                        && Boolean.TRUE.equals(d.get("generated"))) {
                    removed.add(Json.string(d, "signature"));
                    known.remove(Json.string(d, "signature"));
                    ownerMembers.remove(d);
                }
            List<Object> parameters =
                    selected.stream().map(f -> (Object) generatedType(f)).toList();
            String signature = executableSignature(owner, "<init>", parameters);
            if (known.containsKey(signature)) {
                summary.partial(capability, "duplicate_constructor");
                continue;
            }
            Map<String, Object> constructor =
                    method(
                            owner,
                            owner,
                            "<init>",
                            parameters,
                            primitive("void"),
                            accessModifiers(
                                    "ENUM".equals(attrs(owner).get("element_kind"))
                                            ? "PRIVATE"
                                            : access,
                                    false),
                            capability);
            if (constructor != null) {
                attrs(constructor)
                        .put("parameter_names", selected.stream().map(f -> f.get("name")).toList());
                attrs(constructor)
                        .put(
                                "parameter_annotations",
                                selected.stream().map(f -> copiedAnnotations(f, true)).toList());
            }
            summary.constructorReplaced = true;
        }
    }

    private void logger(
            Map<String, Object> owner,
            Map<String, Map<String, Object>> annotations,
            Summary summary) {
        if (summary.blocked("logger_field")) return;
        for (var entry : new TreeMap<>(LOGGERS).entrySet())
            if (annotations.containsKey(entry.getKey())) {
                if (members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                        .anyMatch(
                                d ->
                                        "FIELD".equals(attrs(d).get("element_kind"))
                                                && effective.logName().equals(d.get("name"))))
                    return;
                if (!available(entry.getValue())) {
                    summary.partial("logger_field", "missing_logger_type");
                    return;
                }
                List<Object> modifiers = new ArrayList<>(List.of("private", "final"));
                if (effective.logStatic()) modifiers.add("static");
                Map<String, Object> field =
                        generated(
                                owner,
                                owner,
                                effective.logName(),
                                Json.string(owner, "signature") + '#' + effective.logName(),
                                Json.object(
                                        "element_kind",
                                        "FIELD",
                                        "declared_type",
                                        entry.getValue(),
                                        "type_model",
                                        named(entry.getValue()),
                                        "modifiers",
                                        modifiers,
                                        "modeled_capability",
                                        "logger_field"));
                additions.add(field);
                return;
            }
    }

    private boolean available(String name) {
        if (known.containsKey(name) || name.equals("java.util.logging.Logger")) return true;
        for (Object item : items(context.get("classpath")))
            try (FrozenArchive jar = new FrozenArchive(java.nio.file.Path.of(text(item)))) {
                if (jar.getEntry(name.replace('.', '/') + ".class") != null) return true;
            } catch (IOException failure) {
                return false;
            }
        return false;
    }

    private Map<String, Object> method(
            Map<String, Object> owner,
            Map<String, Object> origin,
            String name,
            List<Object> parameters,
            Map<String, Object> returns,
            List<Object> modifiers,
            String capability) {
        String signature = executableSignature(owner, name, parameters);
        if (known.containsKey(signature)) return null;
        String display =
                '('
                        + String.join(
                                ",", parameters.stream().map(p -> display(Json.map(p))).toList())
                        + ')'
                        + display(returns);
        Map<String, Object> executable =
                Json.object(
                        "version",
                        2,
                        "kind",
                        "EXECUTABLE",
                        "complete",
                        false,
                        "parameters",
                        parameters,
                        "return",
                        returns,
                        "display",
                        display,
                        "type_parameters",
                        List.of(),
                        "origin",
                        "lombok_model");
        Map<String, Object> method =
                generated(
                        owner,
                        origin,
                        name,
                        signature,
                        Json.object(
                                "element_kind",
                                name.equals("<init>") ? "CONSTRUCTOR" : "METHOD",
                                "declared_type",
                                display,
                                "type_model",
                                executable,
                                "return_type",
                                display(returns),
                                "return_type_model",
                                returns,
                                "parameter_type_models",
                                parameters,
                                "parameter_names",
                                capability.equals("equals_hash_code")
                                                && (name.equals("equals") || name.equals("canEqual"))
                                        ? List.of("other")
                                        : List.of(),
                                "varargs",
                                false,
                                "modifiers",
                                modifiers,
                                "modeled_capability",
                                capability));
        known.put(signature, method);
        members.computeIfAbsent(Json.string(owner, "signature"), ignored -> new ArrayList<>())
                .add(method);
        additions.add(method);
        if ("FIELD".equals(attrs(origin).get("element_kind"))) {
            List<Object> annotations = new ArrayList<>(items(attrs(method).get("annotations")));
            annotations.addAll(copiedAnnotations(origin, capability.equals("getter")));
            attrs(method).put("annotations", annotations);
            if (capability.equals("setter")) {
                attrs(method).put("parameter_names", List.of(origin.get("name")));
                attrs(method)
                        .put("parameter_annotations", List.of(copiedAnnotations(origin, true)));
            }
        }
        return method;
    }

    private Map<String, Object> generated(
            Map<String, Object> owner,
            Map<String, Object> origin,
            String name,
            String signature,
            Map<String, Object> attributes) {
        attributes.put("binding_origin", "lombok_model");
        Object prerequisite=attrs(origin).get("prerequisite_annotations");
        if (!(prerequisite instanceof Map)) prerequisite=attrs(owner).get("prerequisite_annotations");
        if (prerequisite instanceof Map) {
            var proof=new LinkedHashMap<>(Json.map(prerequisite));proof.remove("annotations");
            attributes.put("prerequisite_annotation_proof",Map.copyOf(proof));
        }
        attributes.put("generated_from", origin.get("signature"));
        attributes.put("body_available", false);
        List<Object> annotations = new ArrayList<>();
        if (Boolean.TRUE.equals(generatedPolicy()))
            annotations.add(annotation("lombok.Generated", "CLASS", Map.of()));
        if (effective.suppressWarnings())
            annotations.add(
                    annotation(
                            "java.lang.SuppressWarnings",
                            "SOURCE",
                            Json.object(
                                    "value",
                                    Json.object("kind", "array", "values", List.of("all")))));
        if (effective.javaxGenerated())
            annotations.add(
                    annotation(
                            "javax.annotation.Generated",
                            "SOURCE",
                            Json.object(
                                    "value",
                                    Json.object("kind", "array", "values", List.of("lombok")))));
        attributes.put("annotations", annotations);
        return Json.object(
                "kind",
                "declaration",
                "source",
                owner.get("source"),
                "start",
                -1,
                "end",
                -1,
                "parent",
                owner.get("signature"),
                "signature",
                signature,
                "name",
                name,
                "generated",
                true,
                "attributes",
                attributes);
    }

    private boolean has(Map<String, Object> owner, List<String> names, int arity) {
        return members.getOrDefault(Json.string(owner, "signature"), List.of()).stream()
                .filter(d -> "METHOD".equals(attrs(d).get("element_kind")))
                .anyMatch(
                        d ->
                                names.stream()
                                                .anyMatch(
                                                        n ->
                                                                n.equalsIgnoreCase(
                                                                        Json.string(d, "name")))
                                        && items(attrs(d).get("parameter_type_models")).size()
                                                == arity);
    }

    private boolean initialized(Map<String, Object> field) {
        return initialized.contains(Json.string(field, "signature"));
    }

    private static String executableSignature(
            Map<String, Object> owner, String name, List<Object> parameters) {
        return Json.string(owner, "signature")
                + '#'
                + name
                + '('
                + String.join(",", parameters.stream().map(p -> erased(Json.map(p))).toList())
                + ')';
    }

    private static String erased(Map<String, Object> type) {
        if ("ARRAY".equals(type.get("kind"))) return erased(map(type.get("component"))) + "[]";
        if ("INTERSECTION".equals(type.get("kind"))) {
            List<Object> bounds = items(type.get("bounds"));
            return bounds.isEmpty() ? "java.lang.Object" : erased(Json.map(bounds.getFirst()));
        }
        if ("TYPEVAR".equals(type.get("kind")))
            return type.get("upper") instanceof Map
                    ? erased(map(type.get("upper")))
                    : "java.lang.Object";
        if ("DECLARED".equals(type.get("kind"))) return Json.string(type, "element");
        if (Set.of("BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "CHAR", "FLOAT", "DOUBLE", "VOID")
                .contains(type.get("kind"))) return text(type.get("kind")).toLowerCase(Locale.ROOT);
        return display(type);
    }

    private static String display(Map<String, Object> type) {
        return type.get("display") instanceof String
                ? text(type.get("display"))
                : text(type.get("kind")).toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> named(String name) {
        return Json.object(
                "version",
                2,
                "kind",
                "DECLARED",
                "element",
                name,
                "display",
                name,
                "arguments",
                List.of(),
                "complete",
                true);
    }

    private static Map<String, Object> primitive(String name) {
        return Json.object(
                "version",
                2,
                "kind",
                name.toUpperCase(Locale.ROOT),
                "display",
                name,
                "complete",
                true);
    }

    private static boolean complete(Map<String, Object> type) {
        return complete(type, 0);
    }

    private static boolean complete(Map<String, Object> type, int depth) {
        // A sealed graph has already propagated incomplete children to this root.
        // Do not impose the legacy tree traversal's 32-level cutoff on that proof.
        if (dev.codeana.common.TypeGraph.isView(type))
            return Boolean.TRUE.equals(type.get("complete"));
        if (depth > 32 || Set.of("ERROR", "UNKNOWN").contains(type.get("kind"))) return false;
        if (Boolean.TRUE.equals(type.get("recursive")))
            return "TYPEVAR".equals(type.get("kind"))
                    && !(text(type.get("name")) + text(type.get("display"))).isEmpty();
        if (!Boolean.TRUE.equals(type.get("complete"))) return false;
        for (String key : List.of("component", "upper", "lower", "extends", "super", "enclosing"))
            if (type.get(key) instanceof Map child && !complete(Json.map(child), depth + 1))
                return false;
        for (String key : List.of("arguments", "bounds"))
            for (Object raw : items(type.get(key)))
                if (!complete(Json.map(raw), depth + 1)) return false;
        return true;
    }

    private static Map<String, Map<String, Object>> annotations(Map<String, Object> declaration) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        Set<String> compilerNames = new HashSet<>();
        for (Object raw : items(attrs(declaration).get("annotations")))
            compilerNames.add(Json.string(Json.map(raw), "type"));
        if (attrs(declaration).get("prerequisite_annotations") instanceof Map)
            for (Object raw : items(Json.map(attrs(declaration).get("prerequisite_annotations")).get("annotations")))
                compilerNames.add(Json.string(Json.map(raw),"type"));
        for (Object raw : ModelAnnotations.declared(declaration)) {
            Map<String, Object> annotation = Json.map(raw);
            String name = Json.string(annotation, "type");
            if (name.startsWith("lombok.") && compilerNames.contains(name))
                result.put(
                        name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1),
                        annotation);
        }
        return result;
    }

    private static String access(Map<String, Object> annotation, String field) {
        if (annotation == null) return "PUBLIC";
        Object value = map(annotation.get("effective")).get(field);
        if (value == null) return "PUBLIC";
        if (!(value instanceof Map)) return null;
        Map<String, Object> enumValue = Json.map(value);
        String name = Json.string(enumValue, "name");
        return "enum".equals(enumValue.get("kind"))
                        && "lombok.AccessLevel".equals(enumValue.get("type"))
                        && Set.of("PUBLIC", "PROTECTED", "PRIVATE", "PACKAGE", "MODULE", "NONE")
                                .contains(name)
                ? name
                : null;
    }

    private static List<Object> accessModifiers(String access, boolean isStatic) {
        List<Object> result = new ArrayList<>();
        if (!Set.of("PACKAGE", "MODULE", "NONE").contains(access))
            result.add(access.toLowerCase(Locale.ROOT));
        if (isStatic) result.add("static");
        return result;
    }

    private static List<Object> modifiers(Map<String, Object> d) {
        return items(attrs(d).get("modifiers"));
    }

    private static Map<String, Object> attrs(Map<String, Object> d) {
        return Json.map(d.get("attributes"));
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? Json.map(value) : Map.of();
    }

    private static List<Object> items(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) return name;
        char first = name.charAt(0);
        if (Character.isLowerCase(first)) {
            first = name.length() > 1
                            && (Character.isUpperCase(name.charAt(1))
                                    || Character.isTitleCase(name.charAt(1)))
                    ? Character.toUpperCase(first)
                    : Character.toTitleCase(first);
        }
        return first + name.substring(1);
    }

    private static void describe(Map<String, Object> d, Summary summary, List<Object> effective) {
        attrs(d).put("lombok_summary", summary.result());
        if (!effective.isEmpty()) attrs(d).put("lombok_effective_modifiers", effective);
    }

    private void configure(Summary summary) {
        for (String capability : effective.uncertain()) {
            if (capability.equals("*")) summary.degradeAll("configuration_policy");
            else if (summary.modeled.contains(capability))
                summary.partial(capability, "configuration_policy");
        }
        if (!summary.detected.isEmpty() && generatedPolicy() == null)
            summary.partial("generated_annotations", "lombok_version_or_configuration_required");
        summary.configurationInputs = effective.inputs();
        summary.generatedKnown = generatedPolicy() != null;
    }

    private Boolean generatedPolicy() {
        if (effective.generated() != null) return effective.generated();
        if (!generatedChecked) {
            generatedChecked = true;
            for (Object item : items(context.get("classpath")))
                try (FrozenArchive jar = new FrozenArchive(java.nio.file.Path.of(text(item)))) {
                    if (jar.getEntry("lombok/Getter.class") == null) continue;
                    var entry = jar.getEntry("META-INF/MANIFEST.MF");
                    if (entry == null) break;
                    byte[] data;
                    try (var input = jar.getInputStream(entry)) {
                        data = input.readNBytes(65537);
                    }
                    if (data.length > 65536) break;
                    String version =
                            new java.util.jar.Manifest(new java.io.ByteArrayInputStream(data))
                                    .getMainAttributes()
                                    .getValue("Lombok-Version");
                    if (version != null) {
                        var matcher =
                                java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*")
                                        .matcher(version);
                        if (matcher.matches()) {
                            int major = Integer.parseInt(matcher.group(1)),
                                    minor = Integer.parseInt(matcher.group(2)),
                                    patch = Integer.parseInt(matcher.group(3));
                            generatedDefault =
                                    major > 1
                                            || major == 1
                                                    && (minor > 18 || minor == 18 && patch >= 34);
                        }
                    }
                    break;
                } catch (IOException | NumberFormatException failure) {
                    break;
                }
        }
        return generatedDefault;
    }

    private static Map<String, Object> annotation(
            String type, String retention, Map<String, Object> values) {
        return Json.object(
                "type",
                type,
                "retention",
                retention,
                "explicit",
                values,
                "effective",
                values,
                "complete",
                true,
                "origin",
                "lombok_model");
    }

    /** Anatomist's written-field policy: modifier annotations are not automatically type copies. */
    private static Map<String, Object> generatedType(Map<String, Object> field) {
        Map<String, Object> type = map(attrs(field).get("type_model"));
        if (!"FIELD".equals(attrs(field).get("element_kind"))) return type;
        Set<String> excluded = new HashSet<>();
        for (Object name : items(attrs(field).get("modifier_annotation_types")))
            if (!LombokAnnotationCopies.willCopy((String) name)) excluded.add((String) name);
        for (Object raw : items(attrs(field).get("annotations"))) {
            String name = text(map(raw).get("type"));
            if (!LombokAnnotationCopies.willCopy(name)) excluded.add(name);
        }
        return writtenType(type, excluded, 0);
    }

    private static Map<String, Object> writtenType(Map<String, Object> type, Set<String> excluded, int depth) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        if (depth > 128) throw new IllegalArgumentException("LOMBOK_WRITTEN_TYPE_BUDGET");
        Map<String, Object> changes = new LinkedHashMap<>();
        if ("ARRAY".equals(type.get("kind"))) {
            Map<String, Object> component = map(type.get("component"));
            Map<String, Object> rewritten = writtenType(component, excluded, depth + 1);
            if (rewritten != component) changes.put("component", rewritten);
        } else {
            if (type.containsKey("annotations")) {
                List<Object> annotations = items(type.get("annotations"));
                List<Object> filtered = annotations.stream().filter(a -> !excluded.contains(a)).toList();
                if (filtered.size() != annotations.size()) changes.put("annotations", filtered);
            }
            if (type.containsKey("annotation_models")) {
                List<Object> annotations = items(type.get("annotation_models"));
                List<Object> filtered = annotations.stream()
                        .filter(a -> !excluded.contains(map(a).get("type"))).toList();
                if (filtered.size() != annotations.size()) changes.put("annotation_models", filtered);
            }
            if ("DECLARED".equals(type.get("kind")) && type.get("enclosing") instanceof Map) {
                Map<String, Object> enclosing = map(type.get("enclosing"));
                Map<String, Object> rewritten = writtenType(enclosing, excluded, depth + 1);
                if (rewritten != enclosing) changes.put("enclosing", rewritten);
            }
        }
        if (changes.isEmpty()) return type;
        Map<String, Object> result = dev.codeana.common.TypeGraph.copyRoot(type);
        result.putAll(changes);
        return result;
    }

    private static boolean annotationCopyNeedsModel(Map<String, Object> annotation) {
        return !"java.lang.Deprecated".equals(annotation.get("type"))
                && LombokAnnotationCopies.needsModel(annotation);
    }

    private static boolean needsAnnotationCopies(Map<String, Object> declaration) {
        if (Boolean.FALSE.equals(attrs(declaration).get("written_type_complete"))) return true;
        if (items(attrs(declaration).get("annotations")).stream()
                .anyMatch(a -> annotationCopyNeedsModel(map(a)))) return true;
        Set<Object> modifiers = new HashSet<>(items(attrs(declaration).get("modifier_annotation_types")));
        Map<String, Object> type = map(attrs(declaration).get("type_model"));
        for (int depth = 0; "ARRAY".equals(type.get("kind")) && depth < 128; depth++)
            type = map(type.get("component"));
        return items(type.get("annotation_models")).stream()
                .anyMatch(a -> modifiers.contains(map(a).get("type")) && annotationCopyNeedsModel(map(a)));
    }

    private static List<Object> copiedAnnotations(Map<String, Object> field, boolean nonNull) {
        List<Object> result = new ArrayList<>();
        for (Object raw : items(attrs(field).get("annotations"))) {
            Map<String, Object> annotation = Json.map(raw);
            String type = Json.string(annotation, "type");
            if (type.equals("java.lang.Deprecated") || nonNull && type.equals("lombok.NonNull"))
                result.add(annotation);
        }
        return result;
    }

    private static final class Summary {
        final Set<String> detected = new TreeSet<>(),
                modeled = new TreeSet<>(),
                partial = new TreeSet<>(),
                unmodeled = new TreeSet<>();
        final Set<String> reasons = new TreeSet<>();
        int generated;
        boolean constructorReplaced;
        List<Object> configurationInputs = List.of();
        boolean generatedKnown;

        Summary(Map<String, Map<String, Object>> annotations) {
            for (var entry : annotations.entrySet()) {
                String name = entry.getKey();
                detected.add(name);
                List<String> caps =
                        CAPABILITIES.getOrDefault(
                                name,
                                LOGGERS.containsKey(name) ? List.of("logger_field") : List.of());
                modeled.addAll(caps);
                if (caps.isEmpty()
                        && !Set.of(
                                        "NonNull",
                                        "NonFinal",
                                        "PackagePrivate",
                                        "Generated",
                                        "Exclude",
                                        "Include",
                                        "Default")
                                .contains(name)) unmodeled.add(name.toLowerCase(Locale.ROOT));
                Map<String, Object> values = map(entry.getValue().get("effective"));
                if (!Boolean.TRUE.equals(entry.getValue().get("complete"))
                        || Boolean.TRUE.equals(values.get("lazy")))
                    for (String capability : caps) partial(capability, "annotation_options");
            }
            // Anatomist's option policy examines written attributes. Default onParam={} and
            // onlyExplicitlyIncluded=false must not disable ordinary declaration modeling.
            if (annotations.values().stream().anyMatch(a -> map(a.get("explicit")).keySet().stream()
                    .anyMatch(name -> name.startsWith("on")))) degradeAll("annotation_options");
        }

        void model(String capability) {
            if (!partial.contains(capability) && !unmodeled.contains(capability))
                modeled.add(capability);
        }

        void partial(String capability, String reason) {
            modeled.remove(capability);
            partial.add(capability);
            reasons.add(reason);
        }

        boolean blocked(String capability) {
            return partial.contains(capability) || unmodeled.contains(capability);
        }

        void degradeAll(String reason) {
            for (String c : List.copyOf(modeled)) partial(c, reason);
        }

        void merge(Summary other) {
            detected.addAll(other.detected);
            modeled.addAll(other.modeled);
            partial.addAll(other.partial);
            unmodeled.addAll(other.unmodeled);
            modeled.removeAll(partial);
            modeled.removeAll(unmodeled);
            reasons.addAll(other.reasons);
        }

        Map<String, Object> result() {
            return Json.object(
                    "semantic_level",
                    "signature-only",
                    "inference_policy",
                    "hypothesis_only",
                    "detected_annotations",
                    List.copyOf(detected),
                    "modeled_capabilities",
                    List.copyOf(modeled),
                    "partial_capabilities",
                    List.copyOf(partial),
                    "unmodeled_capabilities",
                    List.copyOf(unmodeled),
                    "coverage",
                    partial.isEmpty() && unmodeled.isEmpty() && !modeled.isEmpty()
                            ? "complete"
                            : "partial",
                    "generated_member_count",
                    generated,
                    "constructor_replaced",
                    constructorReplaced,
                    "reasons",
                    List.copyOf(reasons),
                    "configuration_inputs",
                    configurationInputs,
                    "generated_annotations",
                    generatedKnown ? "modeled" : "partial",
                    "method_bodies",
                    "not_modeled");
        }
    }
}
