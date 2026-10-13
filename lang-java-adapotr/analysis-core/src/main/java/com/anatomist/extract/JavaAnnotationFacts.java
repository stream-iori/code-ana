package com.anatomist.extract;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/** Source annotation uses and bounded meta closure, without a parser AST or resolver. */
public final class JavaAnnotationFacts {
    private static final Set<String> TYPES =
            Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD");
    private static final Set<String> CALLABLES = Set.of("METHOD", "CONSTRUCTOR");
    private static final Set<String> VALUES = Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT");
    private final JavaModel model;
    private final JavaIdentities identities;
    private final Path project;
    private final JavaBinaryRepository binaries;
    private final AnnotationMetaCache metaCache = new AnnotationMetaCache();

    public JavaAnnotationFacts(JavaModel model, Path project, JavaBinaryRepository binaries) {
        this.model = Objects.requireNonNull(model);
        identities = new JavaIdentities(model);
        this.project = project.toAbsolutePath().normalize();
        this.binaries = binaries;
    }

    public void extract(Path path, ExtractionResult result) {
        JavaUnit unit = model.unit(path);
        model.source(path);
        Set<AnnotationMetaCache.Origin> emitted = new HashSet<>();
        for (var reference : model.declarations(path)) {
            JavaDeclaration declaration = model.declaration(reference);
            String kind = declaration.symbol().kind();
            if (!(TYPES.contains(kind)
                    || CALLABLES.contains(kind)
                    || VALUES.contains(kind)
                    || kind.equals("PARAMETER"))) continue;
            JavaNode syntax = model.syntax(reference).orElse(null);
            JavaNode modifiers = syntax == null ? null : syntax.tree("getModifiers");
            if (modifiers == null) continue;
            String owner;
            String target =
                    TYPES.contains(kind) ? "type" : VALUES.contains(kind) ? "value" : "callable";
            String targetPath = null;
            Map<String, Object> extra = new LinkedHashMap<>();
            if (kind.equals("PARAMETER") || kind.equals("RECORD_COMPONENT")) {
                var parent = model.parent(reference).orElseThrow();
                int index =
                        model.members(parent).stream()
                                .filter(
                                        member ->
                                                model.declaration(member)
                                                        .symbol()
                                                        .kind()
                                                        .equals(kind))
                                .toList()
                                .indexOf(reference);
                targetPath = (kind.equals("PARAMETER") ? "parameter[" : "component[") + index + "]";
                extra.put("_param", index);
                extra.put("_name", declaration.symbol().name());
                owner =
                        kind.equals("PARAMETER")
                                ? identities.declaration(parent)
                                : identities.declaration(reference);
            } else owner = identities.declaration(reference);
            for (JavaNode annotation : modifiers.trees("getAnnotations")) {
                if (!annotation.span().present()) continue;
                String raw = model.source(path).slice(annotation.tree("getAnnotationType").span());
                String fqn = confirmedName(unit, annotation.tree("getAnnotationType"));
                String status = fqn == null ? "unresolved" : "exact";
                if (fqn == null) {
                    fqn = importedName(unit, raw);
                    if (fqn != null) status = "heuristic";
                }
                Map<String, Object> attributes = new LinkedHashMap<>(extra);
                // Public annotation facts describe the written use. Compiler values remain
                // in the declaration model for defaults, constants and framework analysis.
                syntaxAttributes(unit, annotation, attributes);
                Annotation fact = new Annotation();
                fact.nodeId = owner;
                fact.annotationFqn = fqn;
                fact.rawName = raw;
                fact.attributes = Json.writeCompact(attributes);
                fact.targetKind = target;
                fact.targetPath = targetPath;
                fact.resolutionStatus = status;
                fact.sourceFile = sourceFile(path);
                fact.producerId = ProducerIds.JAVA_CORE;
                coordinates(fact, path, annotation.span());
                result.annotations.add(fact);
                if (status.equals("exact"))
                    meta(
                            fqn,
                            fact.sourceFile,
                            fact.sourceLocation,
                            new HashSet<>(),
                            0,
                            emitted,
                            result);
            }
        }
    }

    private void meta(
            String name,
            String source,
            String location,
            Set<String> active,
            int depth,
            Set<AnnotationMetaCache.Origin> emitted,
            ExtractionResult result) {
        if (depth >= 16 || !active.add(name)) return;
        var definition = definition(name);
        if (definition.source() != null) {
            source = definition.source();
            if (definition.location() != null) location = definition.location();
        }
        for (String parent : definition.parents()) {
            if (emitted.add(new AnnotationMetaCache.Origin(name, parent, source))) {
                AnnotationMetaRelation relation = new AnnotationMetaRelation();
                relation.annotationFqn = name;
                relation.metaAnnotationFqn = parent;
                relation.rawName = parent;
                relation.resolutionStatus = "exact";
                relation.sourceFile = source;
                relation.sourceLocation = location;
                relation.producerId = ProducerIds.JAVA_CORE;
                result.annotationMetaRelations.add(relation);
            }
            // An edge seen on a deeper path must not suppress a shallower traversal.
            meta(parent, source, location, new HashSet<>(active), depth + 1, emitted, result);
        }
    }

    private AnnotationMetaCache.Definition definition(String name) {
        var cached = metaCache.get(name);
        if (cached != null) return cached;
        String source = null;
        String location = null;
        List<String> parents = new ArrayList<>();
        var lookup = model.type(name);
        if (lookup instanceof JavaModel.FoundType found) {
            JavaDeclaration declaration = model.declaration(found.declaration());
            source = sourceFile(found.declaration().source());
            if (declaration.span().present()) {
                var position =
                        model.source(found.declaration().source())
                                .position(declaration.span().start());
                location = "L" + position.line() + ":" + position.column();
            }
            declaration
                    .annotations()
                    .forEach(annotation -> parents.add(name(annotation.descriptor())));
        } else if (lookup instanceof JavaModel.MissingType && binaries != null) {
            binaries.find(name)
                    .ifPresent(
                            type ->
                                    type.annotations()
                                            .forEach(
                                                    annotation ->
                                                            parents.add(
                                                                    name(
                                                                            annotation
                                                                                    .descriptor()))));
        }
        var definition = new AnnotationMetaCache.Definition(source, location, parents);
        metaCache.put(name, definition);
        return definition;
    }

    private String name(String descriptor) {
        String binary = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        return model.type(binary) instanceof JavaModel.FoundType found
                ? model.declaration(found.declaration()).canonicalName()
                : binary;
    }

    private static String confirmedName(JavaUnit unit, JavaNode annotationType) {
        JavaBinding binding = unit.bindings().get(annotationType.id());
        if (binding == null || binding.declaredType() < 0) return null;
        JavaType type = unit.types().get(binding.declaredType());
        return type.kind() == JavaType.Kind.DECLARED && binding.kind().equals("ANNOTATION_TYPE")
                ? type.binaryName().isEmpty() ? type.name() : type.binaryName()
                : null;
    }

    private String importedName(JavaUnit unit, String raw) {
        if (raw.contains(".")) return raw;
        List<String> imports =
                unit.tree().trees("getImports").stream()
                        .filter(value -> !value.text("isStatic").equals("true"))
                        .map(
                                value ->
                                        model.source(unit.path())
                                                .slice(value.tree("getQualifiedIdentifier").span()))
                        .filter(value -> value.endsWith("." + raw))
                        .distinct()
                        .toList();
        return imports.size() == 1 ? imports.getFirst() : null;
    }

    private void syntaxAttributes(
            JavaUnit unit, JavaNode annotation, Map<String, Object> attributes) {
        for (JavaNode argument : annotation.trees("getArguments")) {
            JavaNode expression =
                    argument.kind().equals("ASSIGNMENT")
                            ? argument.tree("getExpression")
                            : argument;
            String key =
                    argument.kind().equals("ASSIGNMENT")
                            ? argument.tree("getVariable").text("getName")
                            : "value";
            attributes.put(key, syntaxValue(unit, expression));
        }
    }

    private Object syntaxValue(JavaUnit unit, JavaNode expression) {
        if (expression.kind().equals("NEW_ARRAY"))
            return expression.trees("getInitializers").stream()
                    .map(item -> syntaxValue(unit, item))
                    .toList();
        if (expression.kind().endsWith("_LITERAL")) {
            Object value = expression.fields().get("getValue");
            return switch (expression.kind()) {
                case "BOOLEAN_LITERAL" -> Boolean.valueOf(String.valueOf(value));
                case "INT_LITERAL" -> Integer.valueOf(String.valueOf(value));
                case "LONG_LITERAL" -> Long.valueOf(String.valueOf(value));
                case "FLOAT_LITERAL", "DOUBLE_LITERAL" ->
                        Double.valueOf(
                                model.source(unit.path())
                                        .slice(expression.span())
                                        .replace("_", ""));
                default -> value;
            };
        }
        return model.source(unit.path()).slice(expression.span());
    }

    private String sourceFile(Path path) {
        return project.relativize(path).toString().replace('\\', '/');
    }

    private void coordinates(Annotation annotation, Path path, SourceSpan span) {
        var begin = model.source(path).position(span.start());
        var end = model.source(path).position(Math.max(span.start(), span.end() - 1));
        annotation.beginLine = begin.line();
        annotation.beginColumn = begin.column();
        annotation.endLine = end.line();
        annotation.endColumn = end.column();
        annotation.sourceLocation = "L" + begin.line() + ":" + begin.column();
    }
}
