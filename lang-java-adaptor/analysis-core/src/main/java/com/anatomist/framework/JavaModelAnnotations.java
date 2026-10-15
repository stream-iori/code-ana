package com.anatomist.framework;

import com.anatomist.core.java.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Bounded source/class-file annotation matching and static cross-annotation AliasFor forwarding.
 */
public final class JavaModelAnnotations {
    private static final String ALIAS = "org.springframework.core.annotation.AliasFor";
    private static final int CACHE_LIMIT = 4096;
    private final JavaUnitAnalysis unit;
    private int work;
    private int totalWork;
    private long firstCalls;
    private long metaPathVisits;
    private long cacheHits;
    private final Map<String, AnnotationType> types = new HashMap<>();
    private final Map<JavaModel.DeclarationRef, List<JavaAnnotation>> declared = new HashMap<>();
    private final Map<PathKey, List<String>> paths = new HashMap<>();

    private record PathKey(String annotation, Set<String> roots) {
        private PathKey {
            roots = Set.copyOf(roots);
        }
    }

    public record Match(
            String directFqn,
            String rootFqn,
            List<String> metaPath,
            String resolutionStatus,
            String attributeStatus,
            Map<String, JavaBinaryType.Value> attributes) {
        public Match {
            metaPath = List.copyOf(metaPath);
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        public Map<String, Object> evidence() {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("annotationFqn", directFqn);
            evidence.put("frameworkAnnotationRoot", rootFqn);
            evidence.put("metaPath", metaPath);
            evidence.put("annotationResolutionStatus", resolutionStatus);
            evidence.put("annotationAttributeStatus", attributeStatus);
            return Collections.unmodifiableMap(evidence);
        }

        public String string(String name) {
            return text(attributes.get(name));
        }

        public List<String> strings(String name) {
            return texts(attributes.get(name));
        }
    }

    private record AnnotationType(
            List<JavaAnnotation> annotations,
            Map<String, JavaBinaryType.Value> defaults,
            List<JavaDeclaration> members,
            boolean source,
            boolean found) {}

    private record Effective(Map<String, JavaBinaryType.Value> attributes, boolean complete) {}

    public JavaModelAnnotations(JavaUnitAnalysis unit) {
        this.unit = Objects.requireNonNull(unit);
    }

    /** Literal or compiler-confirmed constant, without evaluating executable expressions. */
    public JavaBinaryType.Value constant(Path source, JavaNode expression) {
        return literal(source, expression, 0);
    }

    public Optional<Match> first(JavaModel.DeclarationRef declaration, Set<String> roots) {
        firstCalls++;
        if (!mayHaveAnnotations(declaration)) return Optional.empty();
        for (var annotation : annotations(declaration)) {
            String name = name(annotation.descriptor());
            if (roots.contains(name))
                return Optional.of(
                        new Match(
                                name,
                                name,
                                List.of(name),
                                type(name).found() ? "exact" : "heuristic",
                                "direct",
                                attributes(annotation)));
            PathKey key = new PathKey(name, roots);
            List<String> path = paths.get(key);
            if (path == null) {
                path = path(name, roots, new HashSet<>(), 0);
                if (paths.size() >= CACHE_LIMIT) paths.clear();
                paths.put(key, path);
            } else cacheHits++;
            if (path.isEmpty()) continue;
            Effective effective = effective(annotation, path, 0);
            return Optional.of(
                    new Match(
                            name,
                            path.getLast(),
                            path,
                            "exact",
                            effective.complete() ? "static" : "unsupported",
                            effective.attributes()));
        }
        return Optional.empty();
    }

    public List<JavaAnnotation> annotations(JavaModel.DeclarationRef reference) {
        List<JavaAnnotation> cached = declared.get(reference);
        if (cached != null) return cached;
        visit(0);
        var declaration = unit.model().declaration(reference);
        List<JavaAnnotation> result = new ArrayList<>(declaration.annotations());
        JavaNode syntax = unit.model().syntax(reference).orElse(null);
        JavaNode modifiers = syntax == null ? null : syntax.tree("getModifiers");
        if (modifiers == null) return remember(reference, result);
        Map<String, ArrayDeque<Integer>> occurrences = new HashMap<>();
        for (int index = 0; index < result.size(); index++)
            occurrences
                    .computeIfAbsent(
                            name(result.get(index).descriptor()), key -> new ArrayDeque<>())
                    .add(index);
        for (var syntaxAnnotation : modifiers.trees("getAnnotations")) {
            String fqn = qualified(reference.source(), syntaxAnnotation.tree("getAnnotationType"));
            if (fqn == null) continue;
            var matches = occurrences.get(fqn);
            Integer matched = matches == null || matches.isEmpty() ? null : matches.removeFirst();
            if (matched != null && knownAnnotation(fqn)) continue;
            Map<String, JavaBinaryType.Value> values = new LinkedHashMap<>();
            for (var argument : syntaxAnnotation.trees("getArguments")) {
                String key =
                        argument.kind().equals("ASSIGNMENT")
                                ? argument.tree("getVariable").text("getName")
                                : "value";
                JavaNode expression =
                        argument.kind().equals("ASSIGNMENT")
                                ? argument.tree("getExpression")
                                : argument;
                values.put(key, literal(reference.source(), expression, 0));
            }
            var recovered =
                    new JavaAnnotation(
                            "L" + fqn.replace('.', '/') + ";",
                            JavaAnnotation.Retention.SOURCE,
                            values);
            if (matched == null) result.add(recovered);
            else result.set(matched, recovered);
        }
        return remember(reference, result);
    }

    /** Cheap guard; syntax annotations can be absent from the compiler declaration inventory. */
    public boolean mayHaveAnnotations(JavaModel.DeclarationRef reference) {
        if (!unit.model().declaration(reference).annotations().isEmpty()) return true;
        JavaNode syntax = unit.model().syntax(reference).orElse(null);
        JavaNode modifiers = syntax == null ? null : syntax.tree("getModifiers");
        return modifiers != null && !modifiers.trees("getAnnotations").isEmpty();
    }

    /** Give each declaration an independent budget without losing the unit-wide bound. */
    public void beginDeclaration() {
        work = 0;
    }

    public record Counters(long firstCalls, long metaPathVisits, long cacheHits) {}

    public Counters counters() {
        return new Counters(firstCalls, metaPathVisits, cacheHits);
    }

    private List<JavaAnnotation> remember(
            JavaModel.DeclarationRef reference, List<JavaAnnotation> values) {
        List<JavaAnnotation> snapshot = List.copyOf(values);
        if (declared.size() >= CACHE_LIMIT) declared.clear();
        declared.put(reference, snapshot);
        return snapshot;
    }

    /** Written enum selections only; this does not confirm a missing enum declaration. */
    public List<String> sourceEnumNames(
            JavaModel.DeclarationRef reference,
            String annotationName,
            String attribute,
            String enumName) {
        JavaNode syntax = unit.model().syntax(reference).orElse(null);
        JavaNode modifiers = syntax == null ? null : syntax.tree("getModifiers");
        if (modifiers == null) return List.of();
        for (var annotation : modifiers.trees("getAnnotations")) {
            if (!annotationName.equals(
                    qualified(reference.source(), annotation.tree("getAnnotationType")))) continue;
            for (var argument : annotation.trees("getArguments")) {
                if (!argument.kind().equals("ASSIGNMENT")
                        || !attribute.equals(argument.tree("getVariable").text("getName")))
                    continue;
                JavaNode expression = argument.tree("getExpression");
                var values =
                        expression.kind().equals("NEW_ARRAY")
                                ? expression.trees("getInitializers")
                                : List.of(expression);
                if (values.size() > 256)
                    throw new IllegalArgumentException("annotation enum selections exceed budget");
                var names = new LinkedHashSet<String>();
                for (var value : values) {
                    if (!value.kind().equals("MEMBER_SELECT")
                            || !enumName.equals(
                                    qualified(reference.source(), value.tree("getExpression"))))
                        return List.of();
                    names.add(value.text("getIdentifier"));
                }
                return List.copyOf(names);
            }
        }
        return List.of();
    }

    private boolean knownAnnotation(String name) {
        var lookup = unit.model().type(name);
        if (lookup instanceof JavaModel.FoundType found)
            return unit.model()
                    .declaration(found.declaration())
                    .symbol()
                    .kind()
                    .equals("ANNOTATION_TYPE");
        if (lookup instanceof JavaModel.AmbiguousType || unit.binaries() == null) return false;
        return unit.binaries()
                .find(name)
                .map(type -> (type.access() & org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0)
                .orElse(false);
    }

    public Map<String, JavaBinaryType.Value> attributes(JavaAnnotation annotation) {
        Map<String, JavaBinaryType.Value> values =
                new LinkedHashMap<>(type(name(annotation.descriptor())).defaults());
        values.putAll(annotation.values());
        return Collections.unmodifiableMap(values);
    }

    /** Explicit source expressions only; defaults do not masquerade as written attributes. */
    public Map<String, Object> raw(JavaModel.DeclarationRef reference, String annotationName) {
        JavaNode syntax = unit.model().syntax(reference).orElse(null);
        JavaNode modifiers = syntax == null ? null : syntax.tree("getModifiers");
        if (modifiers == null) return Map.of();
        for (var annotation : modifiers.trees("getAnnotations")) {
            if (!annotationName.equals(
                    qualified(reference.source(), annotation.tree("getAnnotationType")))) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            for (var argument : annotation.trees("getArguments")) {
                String key =
                        argument.kind().equals("ASSIGNMENT")
                                ? argument.tree("getVariable").text("getName")
                                : "value";
                JavaNode expression =
                        argument.kind().equals("ASSIGNMENT")
                                ? argument.tree("getExpression")
                                : argument;
                if (expression.span().present())
                    result.put(
                            key, unit.model().source(reference.source()).slice(expression.span()));
            }
            return Collections.unmodifiableMap(result);
        }
        return Map.of();
    }

    public SourceSpan span(JavaModel.DeclarationRef reference, String annotationName) {
        JavaNode syntax = unit.model().syntax(reference).orElse(null);
        JavaNode modifiers = syntax == null ? null : syntax.tree("getModifiers");
        if (modifiers != null)
            for (var annotation : modifiers.trees("getAnnotations"))
                if (annotationName.equals(
                        qualified(reference.source(), annotation.tree("getAnnotationType"))))
                    return annotation.span();
        return unit.model().declaration(reference).span();
    }

    public Optional<Set<String>> members(String annotationName) {
        var lookup = unit.model().type(annotationName);
        if (lookup instanceof JavaModel.FoundType found) {
            var declaration = unit.model().declaration(found.declaration());
            if (!declaration.symbol().kind().equals("ANNOTATION_TYPE")) return Optional.empty();
            Set<String> names = new TreeSet<>();
            for (var member : declaration.members())
                if (member.symbol().kind().equals("METHOD")) names.add(member.symbol().name());
            return Optional.of(Collections.unmodifiableSet(names));
        }
        if (lookup instanceof JavaModel.AmbiguousType || unit.binaries() == null)
            return Optional.empty();
        var binary = unit.binaries().find(annotationName);
        if (binary.isEmpty()
                || (binary.get().access() & org.objectweb.asm.Opcodes.ACC_ANNOTATION) == 0)
            return Optional.empty();
        Set<String> names = new TreeSet<>();
        for (var member : binary.get().methods())
            if (!member.name().startsWith("<")) names.add(member.name());
        return Optional.of(Collections.unmodifiableSet(names));
    }

    private List<String> path(String current, Set<String> roots, Set<String> seen, int depth) {
        metaPathVisits++;
        visit(depth);
        if (depth >= 16 || !seen.add(current)) return List.of();
        for (var annotation :
                type(current).annotations().stream()
                        .sorted(Comparator.comparing(JavaAnnotation::descriptor))
                        .toList()) {
            String target = name(annotation.descriptor());
            if (roots.contains(target)) return List.of(current, target);
            List<String> suffix = path(target, roots, new HashSet<>(seen), depth + 1);
            if (!suffix.isEmpty()) {
                List<String> full = new ArrayList<>();
                full.add(current);
                full.addAll(suffix);
                return List.copyOf(full);
            }
        }
        return List.of();
    }

    private Effective effective(JavaAnnotation use, List<String> path, int depth) {
        visit(depth);
        if (depth == path.size() - 1) return new Effective(attributes(use), true);
        var declaration = type(path.get(depth));
        JavaAnnotation meta =
                declaration.annotations().stream()
                        .filter(
                                annotation ->
                                        name(annotation.descriptor()).equals(path.get(depth + 1)))
                        .findFirst()
                        .orElse(null);
        if (meta == null) return new Effective(Map.of(), false);
        Effective base = effective(meta, path, depth + 1);
        Map<String, JavaBinaryType.Value> values = new LinkedHashMap<>(base.attributes());
        if (!declaration.source()) return new Effective(values, false);
        Map<String, JavaBinaryType.Value> supplied = attributes(use);
        Map<String, JavaBinaryType.Value> forwarded = new LinkedHashMap<>();
        boolean complete = base.complete();
        for (var member : declaration.members()) {
            for (var annotation : member.annotations()) {
                if (!name(annotation.descriptor()).equals(ALIAS)) continue;
                var alias = attributes(annotation);
                String target = text(alias.get("attribute"));
                if (target == null || target.isBlank()) target = text(alias.get("value"));
                if (target == null || target.isBlank()) target = member.symbol().name();
                String annotationName = className(alias.get("annotation"));
                if (!path.getLast().equals(annotationName)) {
                    complete = false;
                    continue;
                }
                var value = supplied.get(member.symbol().name());
                if (value == null) continue;
                var prior = forwarded.putIfAbsent(target, value);
                if (prior != null && !prior.equals(value)) complete = false;
            }
        }
        if (!complete) return new Effective(base.attributes(), false);
        values.putAll(forwarded);
        return new Effective(values, true);
    }

    private AnnotationType type(String name) {
        visit(0);
        AnnotationType known = types.get(name);
        if (known != null) return known;
        AnnotationType value = readType(name);
        if (types.size() >= CACHE_LIMIT) types.clear();
        types.put(name, value);
        return value;
    }

    private AnnotationType readType(String name) {
        var lookup = unit.model().type(name);
        if (lookup instanceof JavaModel.FoundType found) {
            var declaration = unit.model().declaration(found.declaration());
            if (!declaration.symbol().kind().equals("ANNOTATION_TYPE"))
                return new AnnotationType(List.of(), Map.of(), List.of(), false, false);
            Map<String, JavaBinaryType.Value> defaults = new LinkedHashMap<>();
            for (var member : declaration.members())
                if (member.annotationDefault() != null)
                    defaults.put(member.symbol().name(), member.annotationDefault());
            return new AnnotationType(
                    annotations(found.declaration()), defaults, declaration.members(), true, true);
        }
        if (lookup instanceof JavaModel.AmbiguousType || unit.binaries() == null)
            return new AnnotationType(List.of(), Map.of(), List.of(), false, false);
        var binary = unit.binaries().find(name);
        if (binary.isEmpty()
                || (binary.get().access() & org.objectweb.asm.Opcodes.ACC_ANNOTATION) == 0)
            return new AnnotationType(List.of(), Map.of(), List.of(), false, false);
        Map<String, JavaBinaryType.Value> defaults = new LinkedHashMap<>();
        for (var member : binary.get().methods())
            if (member.annotationDefault() != null)
                defaults.put(member.name(), member.annotationDefault());
        return new AnnotationType(
                binary.get().annotations().stream()
                        .map(
                                annotation ->
                                        new JavaAnnotation(
                                                annotation.descriptor(),
                                                annotation.visible()
                                                        ? JavaAnnotation.Retention.RUNTIME
                                                        : JavaAnnotation.Retention.CLASS,
                                                annotation.values()))
                        .toList(),
                defaults,
                List.of(),
                false,
                true);
    }

    private String qualified(Path source, JavaNode syntax) {
        if (syntax == null || !syntax.span().present()) return null;
        var compilation = unit.model().unit(source);
        var binding = compilation.bindings().get(syntax.id());
        if (binding != null && binding.expressionType() >= 0) {
            var type = compilation.types().get(binding.expressionType());
            if (type.kind() == JavaType.Kind.DECLARED) return type.name();
        }
        String raw = unit.model().source(source).slice(syntax.span());
        List<String> imports =
                compilation.tree().trees("getImports").stream()
                        .filter(value -> !value.text("isStatic").equals("true"))
                        .map(
                                value ->
                                        unit.model()
                                                .source(source)
                                                .slice(value.tree("getQualifiedIdentifier").span()))
                        .filter(value -> value.endsWith("." + raw))
                        .distinct()
                        .toList();
        if (imports.size() == 1) return imports.getFirst();
        if (imports.size() > 1) return null;
        if (raw.contains(".")) return raw;
        JavaNode packageName = compilation.tree().tree("getPackageName");
        String pkg =
                packageName == null ? "" : unit.model().source(source).slice(packageName.span());
        String local = pkg.isEmpty() ? raw : pkg + "." + raw;
        if (unit.model().type(local) instanceof JavaModel.FoundType) return local;
        Set<String> candidates = new TreeSet<>();
        for (var importTree : compilation.tree().trees("getImports")) {
            if (importTree.text("isStatic").equals("true")) continue;
            String imported =
                    unit.model()
                            .source(source)
                            .slice(importTree.tree("getQualifiedIdentifier").span());
            if (!imported.endsWith(".*")) continue;
            String candidate = imported.substring(0, imported.length() - 1) + raw;
            if (unit.model().type(candidate) instanceof JavaModel.FoundType
                    || unit.binaries() != null && unit.binaries().find(candidate).isPresent())
                candidates.add(candidate);
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private JavaBinaryType.Value literal(Path source, JavaNode node, int depth) {
        visit(depth);
        if (node == null) return new JavaBinaryType.UnavailableValue("missing-expression");
        var compilation = unit.model().unit(source);
        var binding = compilation.bindings().get(node.id());
        if (binding != null && binding.declarationResolved()) {
            if (binding.kind().equals("ENUM_CONSTANT") && !binding.owner().isEmpty())
                return new JavaBinaryType.EnumValue(
                        "L" + binding.owner().replace('.', '/') + ";", binding.name());
            if (binding.kind().equals("FIELD")) {
                var owner = unit.model().type(binding.owner());
                if (owner instanceof JavaModel.FoundType found)
                    for (var member : unit.model().declaration(found.declaration()).members())
                        if (member.symbol().name().equals(binding.name())
                                && member.constant() != null) return member.constant();
                if (!(owner instanceof JavaModel.AmbiguousType) && unit.binaries() != null)
                    for (var field :
                            unit.binaries()
                                    .find(binding.owner())
                                    .map(JavaBinaryType::fields)
                                    .orElse(List.of()))
                        if (field.name().equals(binding.name())
                                && field.descriptor().equals(binding.descriptor())
                                && field.constant() != null) return field.constant();
            }
        }
        if (node.kind().equals("NEW_ARRAY"))
            return new JavaBinaryType.ArrayValue(
                    node.trees("getInitializers").stream()
                            .map(value -> literal(source, value, depth + 1))
                            .toList());
        String value = node.text("getValue");
        try {
            return switch (node.kind()) {
                case "STRING_LITERAL" -> new JavaBinaryType.Scalar(value);
                case "BOOLEAN_LITERAL" -> new JavaBinaryType.Scalar(Boolean.parseBoolean(value));
                case "CHAR_LITERAL" ->
                        value.length() == 1
                                ? new JavaBinaryType.Scalar(value.charAt(0))
                                : new JavaBinaryType.UnavailableValue("invalid-character");
                case "INT_LITERAL" -> new JavaBinaryType.Scalar(Integer.parseInt(value));
                case "LONG_LITERAL" -> new JavaBinaryType.Scalar(Long.parseLong(value));
                case "FLOAT_LITERAL" -> new JavaBinaryType.Scalar(Float.parseFloat(value));
                case "DOUBLE_LITERAL" -> new JavaBinaryType.Scalar(Double.parseDouble(value));
                case "PARENTHESIZED" -> literal(source, node.tree("getExpression"), depth + 1);
                case "MEMBER_SELECT" -> {
                    if (!node.text("getIdentifier").equals("class"))
                        yield new JavaBinaryType.UnavailableValue("unresolved-static-value");
                    String name = qualified(source, node.tree("getExpression"));
                    yield name == null
                            ? new JavaBinaryType.UnavailableValue("unresolved-class-literal")
                            : new JavaBinaryType.ClassLiteral("L" + name.replace('.', '/') + ";");
                }
                default -> new JavaBinaryType.UnavailableValue("unresolved-static-value");
            };
        } catch (NumberFormatException failure) {
            return new JavaBinaryType.UnavailableValue("invalid-number");
        }
    }

    private void visit(int depth) {
        if (depth > 64 || ++work > 100_000 || ++totalWork > 1_000_000)
            throw new IllegalArgumentException("framework annotation work exceeds budget");
    }

    public static String name(String descriptor) {
        return descriptor.startsWith("L") && descriptor.endsWith(";")
                ? descriptor
                        .substring(1, descriptor.length() - 1)
                        .replace('/', '.')
                        .replace('$', '.')
                : descriptor;
    }

    public static String text(JavaBinaryType.Value value) {
        return value instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof String string
                ? string
                : null;
    }

    public static List<String> texts(JavaBinaryType.Value value) {
        if (value instanceof JavaBinaryType.ArrayValue array) {
            if (array.values().stream().anyMatch(item -> text(item) == null)) return List.of();
            return array.values().stream().map(JavaModelAnnotations::text).toList();
        }
        String text = text(value);
        return text == null ? List.of() : List.of(text);
    }

    public static String className(JavaBinaryType.Value value) {
        return value instanceof JavaBinaryType.ClassLiteral literal
                ? org.objectweb.asm.Type.getType(literal.descriptor())
                        .getClassName()
                        .replace('$', '.')
                : null;
    }
}
