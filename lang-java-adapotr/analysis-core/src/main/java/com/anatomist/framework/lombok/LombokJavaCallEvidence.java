package com.anatomist.framework.lombok;

import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import com.anatomist.model.ProducerIds;

import java.nio.file.Path;
import java.util.*;

/** Source-use evidence for Lombok APIs, including capabilities without modeled declarations. */
public final class LombokJavaCallEvidence implements JavaCallSiteEvidenceProvider {
    private final LombokConfigurationScope configurations;

    public LombokJavaCallEvidence(Path project) {
        configurations = new LombokConfigurationScope(project);
    }

    public LombokJavaCallEvidence(Path project, Map<Path, String> capturedConfigurations) {
        configurations = new LombokConfigurationScope(project, capturedConfigurations);
    }

    @Override
    public String id() {
        return "lombok-call-site";
    }

    @Override
    public String producerId() {
        return ProducerIds.LOMBOK_AST;
    }

    @Override
    public String version() {
        return "detached-1";
    }

    @Override
    public String fingerprintMaterial() {
        return configurations.fingerprint();
    }

    @Override
    public Optional<Evidence> observe(JavaUnitAnalysis unit, JavaNode call, FallbackCallSite site) {
        if (site.ownerFqn() == null || !call.kind().equals("METHOD_INVOCATION"))
            return Optional.empty();
        var lookup = unit.model().type(site.ownerFqn());
        if (!(lookup instanceof JavaModel.FoundType found)) return Optional.empty();
        String name = methodName(call);
        int arity = call.trees("getArguments").size();
        for (var member : unit.model().members(found.declaration())) {
            var declaration = unit.model().declaration(member);
            if (declaration.origin() == JavaDeclaration.Origin.SOURCE
                    && declaration.symbol().kind().equals("METHOD")
                    && declaration.symbol().name().equals(name)
                    && unit.model()
                                    .unit(member.source())
                                    .types()
                                    .get(declaration.symbol().declaredType())
                                    .parameters()
                                    .size()
                            == arity) return Optional.empty();
        }
        var annotations = new JavaModelAnnotations(unit);
        var ownerAnnotations = lombok(annotations.annotations(found.declaration()));
        var builder = builder(unit, call, site, found.declaration(), ownerAnnotations, annotations);
        if (builder.isPresent()) return builder;
        if (arity > 1) return Optional.empty();
        List<AccessorMatch> matches = new ArrayList<>();
        var configuration = configurations.forSource(found.declaration().source());
        for (var member : unit.model().members(found.declaration())) {
            var field = unit.model().declaration(member);
            if (!field.symbol().kind().equals("FIELD")
                    || field.symbol().modifiers().contains("static")) continue;
            var fieldAnnotations = lombok(annotations.annotations(member));
            var accessors =
                    fieldAnnotations.getOrDefault("Accessors", ownerAnnotations.get("Accessors"));
            String base = logicalBase(field.symbol().name(), strings(accessors, "prefix"));
            if (base == null || base.isEmpty()) continue;
            boolean fluent = bool(accessors, "fluent", false);
            boolean primitiveBoolean =
                    unit.model()
                                    .unit(member.source())
                                    .types()
                                    .get(field.symbol().declaredType())
                                    .kind()
                            == JavaType.Kind.BOOLEAN;
            if (arity == 0
                    && getterEnabled(fieldAnnotations, ownerAnnotations)
                    && getter(base, fluent, primitiveBoolean, configuration.noIsPrefix())
                            .equals(name)) matches.add(new AccessorMatch(member, "read"));
            if (arity == 1
                    && setterEnabled(field, fieldAnnotations, ownerAnnotations)
                    && (fluent ? base : "set" + capitalize(booleanBase(base, primitiveBoolean)))
                            .equals(name)) matches.add(new AccessorMatch(member, "write"));
        }
        if (matches.isEmpty()) return Optional.empty();
        var value = base("accessor", site.ownerFqn());
        value.put("method", name);
        value.put("arity", arity);
        if (matches.size() == 1) {
            var match = matches.getFirst();
            var field = unit.model().declaration(match.field());
            value.put("mapping_status", "mapped");
            value.put("operation", match.operation());
            value.put("field", field.symbol().name());
            value.put("field_id", unit.identities().declaration(match.field()));
        } else {
            value.put("mapping_status", "ambiguous");
            value.put(
                    "candidates",
                    matches.stream()
                            .map(match -> unit.identities().declaration(match.field()))
                            .distinct()
                            .sorted()
                            .toList());
        }
        return Optional.of(
                new Evidence(
                        "lombok_usage",
                        value,
                        matches.size() == 1
                                ? "lombok_usage_accessor_observed"
                                : "lombok_usage_accessor_ambiguous"));
    }

    private record AccessorMatch(JavaModel.DeclarationRef field, String operation) {}

    private Optional<Evidence> builder(
            JavaUnitAnalysis unit,
            JavaNode call,
            FallbackCallSite site,
            JavaModel.DeclarationRef owner,
            Map<String, JavaAnnotation> ownerAnnotations,
            JavaModelAnnotations annotations) {
        if (!call.trees("getArguments").isEmpty()) return Optional.empty();
        boolean inherited = ownerAnnotations.containsKey("SuperBuilder");
        JavaAnnotation settings = ownerAnnotations.get(inherited ? "SuperBuilder" : "Builder");
        if (settings == null) {
            for (var member : unit.model().members(owner)) {
                var declaration = unit.model().declaration(member);
                if (!declaration.symbol().kind().equals("CONSTRUCTOR")) continue;
                var candidate = lombok(annotations.annotations(member)).get("Builder");
                if (candidate == null) continue;
                if (settings != null) return Optional.empty();
                settings = candidate;
            }
        }
        if (settings == null) return Optional.empty();
        String name = methodName(call);
        boolean root = name.equals(string(settings, "builderMethodName", "builder"));
        boolean toBuilder = name.equals("toBuilder") && bool(settings, "toBuilder", false);
        boolean typeQualifier = typeQualifier(unit, call);
        if (!(root && (typeQualifier || "STATIC".equals(site.callKind()))
                || toBuilder && !typeQualifier && !"STATIC".equals(site.callKind())))
            return Optional.empty();
        List<JavaModel.DeclarationRef> fields = new ArrayList<>();
        ArrayDeque<JavaModel.DeclarationRef> pending = new ArrayDeque<>();
        pending.add(owner);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            if (visited.size() >= 128 || fields.size() > 16_384)
                throw new IllegalArgumentException("Lombok usage hierarchy exceeds budget");
            var current = pending.removeFirst();
            var declaration = unit.model().declaration(current);
            if (!visited.add(declaration.canonicalName())) continue;
            for (var member : unit.model().members(current)) {
                var field = unit.model().declaration(member);
                if (field.symbol().kind().equals("FIELD")
                        && !field.symbol().modifiers().contains("static")) fields.add(member);
            }
            if (inherited && declaration.superclass() >= 0) {
                var types = unit.model().unit(current.source()).types();
                var parent =
                        unit.model().type(JavaTypeNames.erased(types, declaration.superclass()));
                if (parent instanceof JavaModel.FoundType found
                        && lombok(annotations.annotations(found.declaration()))
                                .containsKey("SuperBuilder")) pending.add(found.declaration());
            }
        }
        List<JavaNode> chain = new ArrayList<>();
        var syntax = unit.model().syntax(unit.source());
        JavaNode current = call;
        while (true) {
            JavaNode select = syntax.parent(current).orElse(null);
            JavaNode invocation = select == null ? null : syntax.parent(select).orElse(null);
            if (select == null
                    || !select.kind().equals("MEMBER_SELECT")
                    || select.tree("getExpression") != current
                    || invocation == null
                    || !invocation.kind().equals("METHOD_INVOCATION")
                    || invocation.tree("getMethodSelect") != select) break;
            if (chain.size() >= 128)
                throw new IllegalArgumentException("Lombok usage chain exceeds budget");
            chain.add(invocation);
            current = invocation;
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean ambiguous = false, unmapped = false;
        String prefix = string(settings, "setterPrefix", "");
        for (int index = 0; index < chain.size(); index++) {
            var step = chain.get(index);
            String method = methodName(step);
            int arity = step.trees("getArguments").size();
            boolean terminal =
                    index == chain.size() - 1
                            && arity == 0
                            && method.equals(string(settings, "buildMethodName", "build"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", method);
            row.put("arity", arity);
            row.put("role", terminal ? "terminal" : "property");
            if (!terminal) {
                String property =
                        prefix.isEmpty()
                                ? method
                                : method.startsWith(prefix) && method.length() > prefix.length()
                                        ? decapitalize(method.substring(prefix.length()))
                                        : null;
                var matches =
                        arity == 1
                                ? fields.stream()
                                        .filter(
                                                field ->
                                                        unit.model()
                                                                .declaration(field)
                                                                .symbol()
                                                                .name()
                                                                .equals(property))
                                        .toList()
                                : List.<JavaModel.DeclarationRef>of();
                if (matches.size() == 1) {
                    row.put("mapping_status", "mapped");
                    row.put("field", property);
                    row.put("field_id", unit.identities().declaration(matches.getFirst()));
                } else if (matches.size() > 1) {
                    ambiguous = true;
                    row.put("mapping_status", "ambiguous");
                    row.put(
                            "candidates",
                            matches.stream().map(unit.identities()::declaration).sorted().toList());
                } else {
                    unmapped = true;
                    row.put("mapping_status", "unmapped");
                }
            }
            steps.add(row);
        }
        var value = base(inherited ? "super_builder" : "builder", site.ownerFqn());
        value.put("root", name);
        value.put("builder_type", null);
        value.put("mapping_status", ambiguous ? "ambiguous" : unmapped ? "unmapped" : "mapped");
        value.put("steps", steps);
        return Optional.of(
                new Evidence("lombok_usage", value, "lombok_usage_builder_chain_observed"));
    }

    private static boolean typeQualifier(JavaUnitAnalysis unit, JavaNode call) {
        var select = call.tree("getMethodSelect");
        var expression = select == null ? null : select.tree("getExpression");
        var binding = expression == null ? null : unit.unit().bindings().get(expression.id());
        return binding != null
                && Set.of("CLASS", "ENUM", "INTERFACE", "ANNOTATION_TYPE", "RECORD")
                        .contains(binding.kind());
    }

    private static Map<String, Object> base(String capability, String owner) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "usage-observed");
        result.put("evidence", "call-site");
        result.put("capability", capability);
        result.put("owner", owner);
        return result;
    }

    private static String methodName(JavaNode call) {
        var select = call.tree("getMethodSelect");
        return select.kind().equals("MEMBER_SELECT")
                ? select.text("getIdentifier")
                : select.text("getName");
    }

    private static Map<String, JavaAnnotation> lombok(List<JavaAnnotation> annotations) {
        Map<String, JavaAnnotation> result = new LinkedHashMap<>();
        for (var annotation : annotations) {
            var name = annotation.descriptor();
            if (Set.of(
                            "Llombok/Getter;",
                            "Llombok/Setter;",
                            "Llombok/Data;",
                            "Llombok/Value;",
                            "Llombok/Builder;",
                            "Llombok/experimental/SuperBuilder;",
                            "Llombok/experimental/Accessors;")
                    .contains(name))
                result.put(
                        name.substring(name.lastIndexOf('/') + 1, name.length() - 1), annotation);
        }
        return result;
    }

    private static boolean getterEnabled(
            Map<String, JavaAnnotation> fields, Map<String, JavaAnnotation> owner) {
        var getter = fields.getOrDefault("Getter", owner.get("Getter"));
        return getter != null
                ? !none(getter)
                : owner.containsKey("Data") || owner.containsKey("Value");
    }

    private static boolean setterEnabled(
            JavaDeclaration field,
            Map<String, JavaAnnotation> fields,
            Map<String, JavaAnnotation> owner) {
        if (field.symbol().modifiers().contains("final")) return false;
        var setter = fields.getOrDefault("Setter", owner.get("Setter"));
        return setter != null
                ? !none(setter)
                : owner.containsKey("Data") && !owner.containsKey("Value");
    }

    private static boolean none(JavaAnnotation annotation) {
        return annotation.values().get("value") instanceof JavaBinaryType.EnumValue value
                && value.name().equals("NONE");
    }

    private static String string(JavaAnnotation annotation, String key, String fallback) {
        return annotation != null
                        && annotation.values().get(key) instanceof JavaBinaryType.Scalar value
                        && value.value() instanceof String text
                ? text
                : fallback;
    }

    private static boolean bool(JavaAnnotation annotation, String key, boolean fallback) {
        return annotation != null
                        && annotation.values().get(key) instanceof JavaBinaryType.Scalar value
                        && value.value() instanceof Boolean bool
                ? bool
                : fallback;
    }

    private static List<String> strings(JavaAnnotation annotation, String key) {
        if (annotation == null) return List.of();
        var value = annotation.values().get(key);
        if (value instanceof JavaBinaryType.ArrayValue array)
            return array.values().stream()
                    .filter(JavaBinaryType.Scalar.class::isInstance)
                    .map(JavaBinaryType.Scalar.class::cast)
                    .map(JavaBinaryType.Scalar::value)
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        return value instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof String text
                ? List.of(text)
                : List.of();
    }

    private static String getter(
            String base, boolean fluent, boolean primitiveBoolean, boolean noIsPrefix) {
        if (fluent) return base;
        if (primitiveBoolean && !noIsPrefix) return "is" + capitalize(booleanBase(base, true));
        return "get" + capitalize(base);
    }

    private static String booleanBase(String base, boolean primitiveBoolean) {
        return primitiveBoolean
                        && base.length() > 2
                        && base.startsWith("is")
                        && Character.isUpperCase(base.charAt(2))
                ? decapitalize(base.substring(2))
                : base;
    }

    private static String logicalBase(String name, List<String> prefixes) {
        if (prefixes.isEmpty()) return name;
        for (var prefix : prefixes) {
            if (prefix.isEmpty()) return name;
            if (!name.startsWith(prefix) || name.length() == prefix.length()) continue;
            if (Character.isLetter(prefix.charAt(prefix.length() - 1))
                    && Character.isLowerCase(name.charAt(prefix.length()))) continue;
            return decapitalize(name.substring(prefix.length()));
        }
        return null;
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String decapitalize(String text) {
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
