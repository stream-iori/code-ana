package com.anatomist.extract;

import com.anatomist.core.ExternalTypePolicy;
import com.anatomist.core.java.*;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/** Detached declaration hierarchy and structured signature references. */
public final class JavaTypeFacts {
    public record UnresolvedType(
            String owner, Path source, String relation, String target, String reason) {}

    private static final Set<String> TYPES =
            Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD");
    private final JavaModel model;
    private final JavaIdentities identities;
    private final JavaSiteFacts sites;
    private final Path project;
    private final ExternalTypePolicy externalPolicy;

    public JavaTypeFacts(JavaModel model, Path project) {
        this(model, project, name -> false);
    }

    public JavaTypeFacts(JavaModel model, Path project, ExternalTypePolicy externalPolicy) {
        this.model = Objects.requireNonNull(model);
        identities = new JavaIdentities(model);
        sites = new JavaSiteFacts(model);
        this.project = project.toAbsolutePath().normalize();
        this.externalPolicy = Objects.requireNonNull(externalPolicy);
    }

    public List<UnresolvedType> extract(Path path, ExtractionResult result) {
        JavaUnit unit = model.unit(path);
        model.source(path);
        List<UnresolvedType> unresolved = new ArrayList<>();
        for (var reference : model.declarations(path)) {
            JavaDeclaration declaration = model.declaration(reference);
            JavaSymbol symbol = declaration.symbol();
            if (TYPES.contains(symbol.kind())) {
                hierarchy(unit, reference, declaration, result, unresolved);
            } else if (Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT")
                    .contains(symbol.kind())) {
                references(
                        unit,
                        identities.declaration(reference),
                        symbol.declaredType(),
                        "field_type",
                        result,
                        unresolved,
                        0);
            } else if (Set.of("METHOD", "CONSTRUCTOR").contains(symbol.kind())) {
                String owner = identities.declaration(reference);
                JavaType executable = unit.types().get(symbol.declaredType());
                if (symbol.kind().equals("METHOD"))
                    references(
                            unit,
                            owner,
                            executable.returns(),
                            "return_type",
                            result,
                            unresolved,
                            0);
                for (int parameter : executable.parameters())
                    references(unit, owner, parameter, "parameter_type", result, unresolved, 0);
            }
        }
        for (JavaNode node : model.syntax(path).nodes()) {
            if (!node.kind().equals("LAMBDA_EXPRESSION") || !node.span().present()) continue;
            if (identities.owner(path, node).isEmpty()) continue;
            String owner = identities.callback(path, node, "lambda");
            JavaBinding binding = unit.bindings().get(node.id());
            if (binding != null)
                references(
                        unit,
                        owner,
                        binding.expressionType(),
                        "functional_interface",
                        result,
                        unresolved,
                        0);
            for (JavaNode parameter : node.trees("getParameters")) {
                JavaBinding parameterBinding = unit.bindings().get(parameter.id());
                if (parameterBinding != null)
                    references(
                            unit,
                            owner,
                            parameterBinding.declaredType(),
                            "parameter_type",
                            result,
                            unresolved,
                            0);
            }
        }
        declarationSyntax(unit, result, unresolved);
        return List.copyOf(unresolved);
    }

    /** Audit explicit declaration types, including throws and bounded wildcards. */
    private void declarationSyntax(
            JavaUnit unit, ExtractionResult result, List<UnresolvedType> unresolved) {
        var references = new DeclarationReferences();
        for (var gap : unresolved)
            references.unresolvedKeys.add(edgeKey(gap.owner(), gap.relation(), gap.target()));
        for (var edge : result.edges) {
            if (edge.beginLine == null
                    && Set.of("REFERENCES", "INHERITS", "IMPLEMENTS").contains(edge.relation)) {
                String target = edge.targetId == null ? edge.externalTargetFqn : edge.targetId;
                references
                        .unpositioned
                        .computeIfAbsent(
                                edgeKey(edge.sourceId, edge.relation, target),
                                ignored -> new ArrayDeque<>())
                        .add(edge);
            }
        }
        for (var ref : model.declarations(unit.path())) {
            var declaration = model.declaration(ref);
            if (declaration.origin() != JavaDeclaration.Origin.SOURCE) continue;
            var syntax = model.syntax(ref).orElse(null);
            if (syntax == null) continue;
            String kind = declaration.symbol().kind();
            if (!TYPES.contains(kind)
                    && !Set.of(
                                    "FIELD",
                                    "ENUM_CONSTANT",
                                    "RECORD_COMPONENT",
                                    "METHOD",
                                    "CONSTRUCTOR")
                            .contains(kind)) continue;
            String owner = identities.declaration(ref);
            if (Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT").contains(kind)) {
                syntaxType(
                        unit,
                        owner,
                        syntax.tree("getType"),
                        "REFERENCES",
                        result,
                        unresolved,
                        references,
                        0);
            } else if (Set.of("METHOD", "CONSTRUCTOR").contains(kind)) {
                if (kind.equals("METHOD"))
                    syntaxType(
                            unit,
                            owner,
                            syntax.tree("getReturnType"),
                            "REFERENCES",
                            result,
                            unresolved,
                            references,
                            0);
                for (var parameter : syntax.trees("getParameters"))
                    syntaxType(
                            unit,
                            owner,
                            parameter.tree("getType"),
                            "REFERENCES",
                            result,
                            unresolved,
                            references,
                            0);
                for (var thrown : syntax.trees("getThrows"))
                    syntaxType(
                            unit, owner, thrown, "REFERENCES", result, unresolved, references, 0);
                syntaxBounds(unit, owner, syntax, result, unresolved, references);
            } else if (TYPES.contains(kind)) {
                syntaxType(
                        unit,
                        owner,
                        syntax.tree("getExtendsClause"),
                        "INHERITS",
                        result,
                        unresolved,
                        references,
                        0);
                String relation =
                        Set.of("INTERFACE", "ANNOTATION_TYPE").contains(kind)
                                ? "INHERITS"
                                : "IMPLEMENTS";
                for (var implemented : syntax.trees("getImplementsClause"))
                    syntaxType(
                            unit, owner, implemented, relation, result, unresolved, references, 0);
                syntaxBounds(unit, owner, syntax, result, unresolved, references);
            }
        }
    }

    private void syntaxBounds(
            JavaUnit unit,
            String owner,
            JavaNode declaration,
            ExtractionResult result,
            List<UnresolvedType> unresolved,
            DeclarationReferences edges) {
        for (var parameter : declaration.trees("getTypeParameters"))
            for (var bound : parameter.trees("getBounds"))
                syntaxType(unit, owner, bound, "REFERENCES", result, unresolved, edges, 0);
    }

    private void syntaxType(
            JavaUnit unit,
            String owner,
            JavaNode node,
            String relation,
            ExtractionResult result,
            List<UnresolvedType> unresolved,
            DeclarationReferences edges,
            int depth) {
        if (node == null || !node.span().present() || depth > 128) return;
        switch (node.kind()) {
            case "ARRAY_TYPE" ->
                    syntaxType(
                            unit,
                            owner,
                            node.tree("getType"),
                            relation,
                            result,
                            unresolved,
                            edges,
                            depth + 1);
            case "ANNOTATED_TYPE" ->
                    syntaxType(
                            unit,
                            owner,
                            node.tree("getUnderlyingType"),
                            relation,
                            result,
                            unresolved,
                            edges,
                            depth + 1);
            case "PARAMETERIZED_TYPE" -> {
                syntaxType(
                        unit,
                        owner,
                        node.tree("getType"),
                        relation,
                        result,
                        unresolved,
                        edges,
                        depth + 1);
                for (var argument : node.trees("getTypeArguments"))
                    syntaxType(
                            unit,
                            owner,
                            argument,
                            "REFERENCES",
                            result,
                            unresolved,
                            edges,
                            depth + 1);
            }
            case "EXTENDS_WILDCARD", "SUPER_WILDCARD" ->
                    syntaxType(
                            unit,
                            owner,
                            node.tree("getBound"),
                            "REFERENCES",
                            result,
                            unresolved,
                            edges,
                            depth + 1);
            case "INTERSECTION_TYPE", "UNION_TYPE" -> {
                for (var bound :
                        node.trees(
                                node.kind().equals("INTERSECTION_TYPE")
                                        ? "getBounds"
                                        : "getTypeAlternatives"))
                    syntaxType(
                            unit, owner, bound, "REFERENCES", result, unresolved, edges, depth + 1);
            }
            case "IDENTIFIER", "MEMBER_SELECT" -> {
                var binding = unit.bindings().get(node.id());
                if (binding == null || binding.expressionType() < 0) {
                    String target = model.source(unit.path()).slice(node.span());
                    if (edges.unresolvedKeys.add(edgeKey(owner, relation, target)))
                        unresolved.add(
                                new UnresolvedType(
                                        owner,
                                        unit.path(),
                                        relation,
                                        target,
                                        "binding-unavailable"));
                    return;
                }
                var type = unit.types().get(binding.expressionType());
                if (!Set.of(JavaType.Kind.DECLARED, JavaType.Kind.ERROR).contains(type.kind()))
                    return;
                var lookup = lookup(type);
                String target =
                        lookup instanceof JavaModel.FoundType found
                                ? identities.declaration(found.declaration())
                                : type.binaryName().isEmpty() ? type.name() : type.binaryName();
                if (!(lookup instanceof JavaModel.FoundType)
                        && relation.equals("REFERENCES")
                        && externalPolicy.isExternalExcluded(target)
                        && !externalPolicy.isSourceSpelledExcluded(target)) {
                    // Default aggregate exclusions do not erase source-written type evidence.
                    var written = new Edge();
                    written.sourceId = owner;
                    written.relation = relation;
                    written.confidence = GraphConstants.Confidence.EXTRACTED;
                    written.producerId = ProducerIds.JAVA_CORE;
                    written.sourceFile = sourceFile(unit.path());
                    written.isExternal = true;
                    written.externalTargetFqn = target;
                    written.resolution = GraphConstants.Resolution.CLASSPATH;
                    sites.coordinates(written, unit.path(), node.span());
                    result.edges.add(written);
                    return;
                }
                var available = edges.unpositioned.get(edgeKey(owner, relation, target));
                Edge edge = available == null ? null : available.poll();
                if (edge == null) {
                    int start = result.edges.size();
                    relation(
                            unit,
                            owner,
                            binding.expressionType(),
                            relation,
                            null,
                            result,
                            unresolved);
                    if (result.edges.size() == start) return;
                    edge = result.edges.getLast();
                }
                sites.coordinates(edge, unit.path(), node.span());
                if (relation.equals("REFERENCES")) edge.producerId = ProducerIds.JAVA_CORE;
            }
            default -> {}
        }
    }

    private static String edgeKey(String owner, String relation, String target) {
        return owner + "\n" + relation + "\n" + target;
    }

    private static final class DeclarationReferences {
        private final Map<String, ArrayDeque<Edge>> unpositioned = new HashMap<>();
        private final Set<String> unresolvedKeys = new HashSet<>();
    }

    void recoveredCallback(
            JavaUnit unit,
            JavaNode site,
            String owner,
            JavaModeledCallRecovery.CallbackSignature signature,
            ExtractionResult result) {
        recoveredReference(
                unit, site, owner, signature.functionalType(), "functional_interface", result, 0);
        for (var parameter : signature.parameters())
            recoveredReference(unit, site, owner, parameter, "parameter_type", result, 0);
    }

    private void recoveredReference(
            JavaUnit unit,
            JavaNode site,
            String owner,
            JavaModeledCallRecovery.Use input,
            String context,
            ExtractionResult result,
            int depth) {
        if (depth >= 64) return;
        var use = input.resolved();
        var type = use.type();
        if (type.kind() == JavaType.Kind.ARRAY) {
            recoveredReference(
                    unit, site, owner, use.child(type.component()), context, result, depth + 1);
            return;
        }
        if (type.kind() != JavaType.Kind.DECLARED) return;
        var lookup = model.type(use.name());
        if (lookup instanceof JavaModel.AmbiguousType) return;
        var edge = new Edge();
        edge.sourceId = owner;
        edge.relation = GraphConstants.Relation.REFERENCES;
        edge.context = context;
        edge.confidence = GraphConstants.Confidence.INFERRED;
        edge.resolution = "source_fallback";
        edge.producerId = ProducerIds.JAVA_SEMANTICS;
        edge.sourceFile = sourceFile(unit.path());
        edge.metadata =
                com.anatomist.json.Json.writeCompact(
                        Map.of(
                                "bindingResolved",
                                false,
                                "evidenceComplete",
                                false,
                                "modeledInput",
                                true));
        if (lookup instanceof JavaModel.FoundType found)
            edge.targetId = identities.declaration(found.declaration());
        else {
            edge.isExternal = true;
            edge.externalTargetFqn = type.binaryName().isEmpty() ? type.name() : type.binaryName();
            if (externalPolicy.isExternalExcluded(edge.externalTargetFqn)) return;
        }
        if (result.edges.stream()
                .anyMatch(
                        existing ->
                                existing.sourceId.equals(owner)
                                        && existing.relation.equals(edge.relation)
                                        && Objects.equals(existing.context, context)
                                        && Objects.equals(existing.targetId, edge.targetId)
                                        && Objects.equals(
                                                existing.externalTargetFqn,
                                                edge.externalTargetFqn))) return;
        sites.coordinates(edge, unit.path(), site.span());
        result.edges.add(edge);
    }

    private void hierarchy(
            JavaUnit unit,
            JavaModel.DeclarationRef reference,
            JavaDeclaration declaration,
            ExtractionResult result,
            List<UnresolvedType> unresolved) {
        String owner = identities.declaration(reference);
        if (declaration.superclass() >= 0) {
            String parent = JavaTypeNames.erased(unit.types(), declaration.superclass());
            if (!Set.of("java.lang.Object", "java.lang.Enum").contains(parent))
                relation(
                        unit,
                        owner,
                        declaration.superclass(),
                        GraphConstants.Relation.INHERITS,
                        null,
                        result,
                        unresolved);
        }
        String relation =
                Set.of("INTERFACE", "ANNOTATION_TYPE").contains(declaration.symbol().kind())
                        ? GraphConstants.Relation.INHERITS
                        : GraphConstants.Relation.IMPLEMENTS;
        for (int implemented : declaration.interfaces())
            relation(unit, owner, implemented, relation, null, result, unresolved);
        for (int permitted : declaration.permitted()) {
            JavaType child = unit.types().get(permitted);
            var lookup = lookup(child);
            if (child.kind() == JavaType.Kind.DECLARED
                    && lookup instanceof JavaModel.FoundType found) {
                Edge edge =
                        Edge.reference(identities.declaration(found.declaration()), owner, false);
                edge.relation = GraphConstants.Relation.PERMITS;
                edge.producerId = ProducerIds.JAVA_SEMANTICS;
                edge.sourceFile = sourceFile(unit.path());
                result.edges.add(edge);
            } else
                unresolved.add(
                        new UnresolvedType(
                                owner,
                                unit.path(),
                                GraphConstants.Relation.PERMITS,
                                child.name(),
                                "source-child-unavailable"));
        }
    }

    private void references(
            JavaUnit unit,
            String owner,
            int reference,
            String context,
            ExtractionResult result,
            List<UnresolvedType> unresolved,
            int depth) {
        if (reference < 0 || depth > 5) return;
        JavaType type = unit.types().get(reference);
        if (type.kind() == JavaType.Kind.ARRAY) {
            references(unit, owner, type.component(), context, result, unresolved, depth);
            return;
        }
        if (!Set.of(JavaType.Kind.DECLARED, JavaType.Kind.ERROR).contains(type.kind())) return;
        relation(
                unit,
                owner,
                reference,
                GraphConstants.Relation.REFERENCES,
                context,
                result,
                unresolved);
        for (int argument : type.arguments())
            references(unit, owner, argument, "generic_arg", result, unresolved, depth + 1);
    }

    private void relation(
            JavaUnit unit,
            String owner,
            int reference,
            String relation,
            String context,
            ExtractionResult result,
            List<UnresolvedType> unresolved) {
        JavaType type = unit.types().get(reference);
        var lookup = lookup(type);
        if (type.kind() != JavaType.Kind.DECLARED || lookup instanceof JavaModel.AmbiguousType) {
            unresolved.add(
                    new UnresolvedType(
                            owner,
                            unit.path(),
                            relation,
                            type.name(),
                            type.kind() == JavaType.Kind.ERROR
                                    ? "error-type"
                                    : "ambiguous-or-unavailable-type"));
            return;
        }
        Edge edge = new Edge();
        edge.sourceId = owner;
        edge.relation = relation;
        edge.context = context;
        edge.confidence = GraphConstants.Confidence.EXTRACTED;
        edge.producerId = ProducerIds.JAVA_SEMANTICS;
        edge.sourceFile = sourceFile(unit.path());
        if (lookup instanceof JavaModel.FoundType found)
            edge.targetId = identities.declaration(found.declaration());
        else {
            edge.isExternal = true;
            edge.externalTargetFqn = type.binaryName().isEmpty() ? type.name() : type.binaryName();
            if (relation.equals(GraphConstants.Relation.REFERENCES)
                    && externalPolicy.isExternalExcluded(edge.externalTargetFqn)) return;
            edge.resolution = GraphConstants.Resolution.CLASSPATH;
        }
        result.edges.add(edge);
    }

    private JavaModel.TypeLookup lookup(JavaType type) {
        return model.type(type.binaryName().isEmpty() ? type.name() : type.binaryName());
    }

    private String sourceFile(Path path) {
        return project.relativize(path).toString().replace('\\', '/');
    }
}
