package com.anatomist.extract;

import com.anatomist.core.ExternalTypePolicy;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Compiler-selected field accesses; local variables, qualifiers and array length are not fields.
 */
public final class JavaFieldFacts {
    public record UnresolvedSite(String owner, Path source, SourceSpan span, String reason) {}

    private static final Set<String> MUTATIONS =
            Set.of(
                    "PREFIX_INCREMENT",
                    "PREFIX_DECREMENT",
                    "POSTFIX_INCREMENT",
                    "POSTFIX_DECREMENT");
    private final JavaModel model;
    private final JavaIdentities identities;
    private final JavaSiteFacts sites;
    private final Path project;
    private final ExternalTypePolicy externalPolicy;
    private final JavaBinaryRepository binaries;

    public JavaFieldFacts(JavaModel model, Path project) {
        this(model, project, name -> false);
    }

    public JavaFieldFacts(JavaModel model, Path project, ExternalTypePolicy externalPolicy) {
        this(model, project, externalPolicy, null);
    }

    public JavaFieldFacts(
            JavaModel model,
            Path project,
            ExternalTypePolicy externalPolicy,
            JavaBinaryRepository binaries) {
        this.model = Objects.requireNonNull(model);
        identities = new JavaIdentities(model);
        sites = new JavaSiteFacts(model);
        this.project = project.toAbsolutePath().normalize();
        this.externalPolicy = Objects.requireNonNull(externalPolicy);
        this.binaries = binaries;
    }

    public List<UnresolvedSite> extract(Path path, ExtractionResult result) {
        JavaUnit unit = model.unit(path);
        model.source(path);
        List<UnresolvedSite> unresolved = new ArrayList<>();
        var recovery = new JavaModeledCallRecovery(model, unit, binaries);
        for (JavaNode node : model.syntax(path).nodes()) {
            if (!node.span().present()
                    || !Set.of("IDENTIFIER", "MEMBER_SELECT").contains(node.kind())) continue;
            if (node.kind().equals("MEMBER_SELECT") && node.text("getIdentifier").equals("class"))
                continue;
            JavaBinding binding = unit.bindings().get(node.id());
            if (binding == null
                    || !binding.declarationResolved()
                    || !Set.of("FIELD", "ENUM_CONSTANT").contains(binding.kind())) {
                var recovered = modeledEnumLabel(unit, node, recovery);
                if (recovered != null) {
                    var owner = identities.owner(path, node).orElse(null);
                    if (owner != null)
                        emit(
                                unit,
                                node,
                                node,
                                owner,
                                recovered,
                                GraphConstants.Relation.READS,
                                result);
                    continue;
                }
            }
            if ((binding == null || !Set.of("FIELD", "ENUM_CONSTANT").contains(binding.kind()))
                    && unresolvedValueSelection(unit, node)) {
                identities
                        .owner(path, node)
                        .ifPresent(
                                owner ->
                                        unresolved.add(
                                                new UnresolvedSite(
                                                        owner,
                                                        path,
                                                        node.span(),
                                                        "receiver-unavailable")));
            }
            if (binding == null
                    || !Set.of("FIELD", "ENUM_CONSTANT").contains(binding.kind())
                    || Set.of("this", "super").contains(binding.name())
                    || arrayLength(unit, node, binding)) continue;
            String owner = identities.owner(path, node).orElse(null);
            if (owner == null) continue;
            if (!binding.declarationResolved()) {
                unresolved.add(new UnresolvedSite(owner, path, node.span(), "incomplete-evidence"));
                continue;
            }
            Edge target = target(unit, node, owner, binding, unresolved);
            if (target == null) continue;
            JavaNode expression = node;
            JavaNode parent = model.syntax(path).parent(expression).orElse(null);
            while (parent != null && parent.kind().equals("PARENTHESIZED")) {
                expression = parent;
                parent = model.syntax(path).parent(parent).orElse(null);
            }
            boolean assignment =
                    parent != null
                            && (parent.kind().equals("ASSIGNMENT")
                                    || parent.kind().endsWith("_ASSIGNMENT"))
                            && same(parent.tree("getVariable"), expression);
            boolean mutation =
                    parent != null
                            && MUTATIONS.contains(parent.kind())
                            && same(parent.tree("getExpression"), expression);
            if (assignment || mutation)
                emit(unit, node, parent, owner, target, GraphConstants.Relation.WRITES, result);
            if (!mutation && (!assignment || !parent.kind().equals("ASSIGNMENT")))
                emit(
                        unit,
                        node,
                        assignment || mutation ? parent : node,
                        owner,
                        target,
                        GraphConstants.Relation.READS,
                        result);
        }
        return List.copyOf(unresolved);
    }

    private Edge modeledEnumLabel(JavaUnit unit, JavaNode node, JavaModeledCallRecovery recovery) {
        if (!node.kind().equals("IDENTIFIER")) return null;
        var syntax = model.syntax(unit.path());
        JavaNode label = node;
        var parent = syntax.parent(label).orElse(null);
        if (parent != null
                && parent.kind().equals("CONSTANT_CASE_LABEL")
                && same(parent.tree("getConstantExpression"), label)) {
            label = parent;
            parent = syntax.parent(label).orElse(null);
        }
        if (parent == null || !parent.kind().equals("CASE")) return null;
        boolean caseLabel = same(parent.tree("getExpression"), node);
        for (var value : parent.trees("getExpressions")) caseLabel |= same(value, node);
        for (var value : parent.trees("getLabels")) caseLabel |= same(value, label);
        if (!caseLabel) return null;
        var selection = syntax.parent(parent).orElse(null);
        if (selection == null || !Set.of("SWITCH", "SWITCH_EXPRESSION").contains(selection.kind()))
            return null;
        var selector = selection.tree("getExpression");
        while (selector != null && selector.kind().equals("PARENTHESIZED"))
            selector = selector.tree("getExpression");
        if (selector == null || !selector.kind().equals("METHOD_INVOCATION")) return null;
        var selected = recovery.resolveSite(selector);
        JavaModeledCallRecovery.Use returned =
                switch (selected) {
                    case JavaModeledCallRecovery.Selected value -> value.returns();
                    case JavaModeledCallRecovery.ExternalSelected value -> value.returns();
                    default -> null;
                };
        if (returned == null
                || returned.reference() < 0
                || returned.types().get(returned.reference()).kind() != JavaType.Kind.DECLARED)
            return null;
        var lookup = model.type(JavaTypeNames.erased(returned.types(), returned.reference()));
        if (!(lookup instanceof JavaModel.FoundType found)
                || !model.declaration(found.declaration()).symbol().kind().equals("ENUM"))
            return null;
        var constants =
                model.members(found.declaration()).stream()
                        .filter(
                                reference -> {
                                    var symbol = model.declaration(reference).symbol();
                                    return symbol.kind().equals("ENUM_CONSTANT")
                                            && symbol.name().equals(node.text("getName"));
                                })
                        .toList();
        if (constants.size() != 1) return null;
        var edge = new Edge();
        edge.targetId = identities.declaration(constants.getFirst());
        edge.confidence = GraphConstants.Confidence.INFERRED;
        edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
        edge.metadata =
                Json.writeCompact(
                        Map.of(
                                "bindingResolved",
                                false,
                                "evidenceComplete",
                                false,
                                "modeledInput",
                                true));
        return edge;
    }

    private Edge target(
            JavaUnit unit,
            JavaNode node,
            String owner,
            JavaBinding binding,
            List<UnresolvedSite> unresolved) {
        var lookup =
                model.type(
                        binding.binaryOwner().isEmpty() ? binding.owner() : binding.binaryOwner());
        Edge edge = new Edge();
        if (lookup instanceof JavaModel.FoundType found) {
            List<JavaModel.DeclarationRef> members =
                    model.members(found.declaration()).stream()
                            .filter(
                                    reference -> {
                                        JavaSymbol symbol = model.declaration(reference).symbol();
                                        return Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT")
                                                        .contains(symbol.kind())
                                                && symbol.name().equals(binding.name())
                                                && (symbol.descriptor().equals(binding.descriptor())
                                                        || binding.descriptor().isEmpty());
                                    })
                            .toList();
            // A record component and its backing field deliberately share one graph identity.
            List<String> targets =
                    members.stream().map(identities::declaration).distinct().toList();
            if (targets.size() == 1) {
                edge.targetId = targets.getFirst();
                if (members.stream()
                        .anyMatch(
                                reference ->
                                        model.declaration(reference).origin()
                                                == JavaDeclaration.Origin.MODEL)) {
                    edge.confidence = GraphConstants.Confidence.INFERRED;
                    edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
                    var target = members.getFirst();
                    var origin =
                            model.unit(target.source()).extensions().generated().get(edge.targetId);
                    if (origin != null)
                        edge.metadata =
                                Json.writeCompact(
                                        Map.of(
                                                "generator",
                                                origin.generator(),
                                                "generatorMode",
                                                origin.generatorMode(),
                                                "generatedFrom",
                                                origin.generatedFrom(),
                                                "bodyAvailable",
                                                origin.bodyAvailable(),
                                                "bindingResolved",
                                                false,
                                                "evidenceComplete",
                                                false));
                }
            } else {
                unresolved.add(
                        new UnresolvedSite(
                                owner,
                                unit.path(),
                                node.span(),
                                targets.isEmpty()
                                        ? "declaration-unavailable"
                                        : "ambiguous-declaration"));
                return null;
            }
        } else if (lookup instanceof JavaModel.AmbiguousType || binding.owner().isEmpty()) {
            unresolved.add(
                    new UnresolvedSite(
                            owner, unit.path(), node.span(), "owner-unavailable-or-ambiguous"));
            return null;
        } else {
            edge.isExternal = true;
            edge.externalTargetFqn =
                    (binding.binaryOwner().isEmpty() ? binding.owner() : binding.binaryOwner())
                            + "#"
                            + binding.name();
            if (externalPolicy.isExternalExcluded(edge.externalTargetFqn)) return null;
            edge.resolution = GraphConstants.Resolution.CLASSPATH;
        }
        return edge;
    }

    private void emit(
            JavaUnit unit,
            JavaNode node,
            JavaNode context,
            String owner,
            Edge target,
            String relation,
            ExtractionResult result) {
        Edge edge = new Edge();
        edge.sourceId = owner;
        edge.targetId = target.targetId;
        edge.externalTargetFqn = target.externalTargetFqn;
        edge.isExternal = target.isExternal;
        edge.resolution = target.resolution;
        edge.relation = relation;
        edge.confidence =
                target.confidence == null ? GraphConstants.Confidence.EXTRACTED : target.confidence;
        edge.metadata = target.metadata;
        var binding = unit.bindings().get(node.id());
        if (binding != null
                && !binding.evidenceComplete()
                && edge.confidence.equals(GraphConstants.Confidence.EXTRACTED))
            edge.metadata =
                    Json.writeCompact(Map.of("bindingResolved", true, "evidenceComplete", false));
        edge.producerId = ProducerIds.JAVA_CORE;
        edge.sourceFile = project.relativize(unit.path()).toString().replace('\\', '/');
        edge.syntaxTarget = model.source(unit.path()).slice(node.span());
        edge.context = sites.control(unit.path(), context);
        sites.coordinates(edge, unit.path(), node.span());
        result.edges.add(edge);
    }

    private static boolean same(JavaNode first, JavaNode second) {
        return first != null && first.id() == second.id();
    }

    private boolean unresolvedValueSelection(JavaUnit unit, JavaNode node) {
        if (!node.kind().equals("MEMBER_SELECT")) return false;
        JavaSyntax syntax = model.syntax(unit.path());
        var parent = syntax.parent(node).orElse(null);
        if (parent == null
                || parent.kind().equals("METHOD_INVOCATION")
                        && same(parent.tree("getMethodSelect"), node)) return false;
        for (JavaNode ancestor = parent;
                ancestor != null;
                ancestor = syntax.parent(ancestor).orElse(null)) {
            if (Set.of(
                            "IMPORT",
                            "ANNOTATION",
                            "PARAMETERIZED_TYPE",
                            "ARRAY_TYPE",
                            "ANNOTATED_TYPE",
                            "NEW_CLASS")
                    .contains(ancestor.kind())) return false;
            if (ancestor.kind().equals("VARIABLE") && same(ancestor.tree("getType"), node))
                return false;
            if (Set.of(
                            "METHOD_INVOCATION",
                            "RETURN",
                            "ASSIGNMENT",
                            "EXPRESSION_STATEMENT",
                            "VARIABLE")
                    .contains(ancestor.kind())) break;
            if (ancestor.kind().equals("METHOD")
                    || Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                            .contains(ancestor.kind())) return false;
        }
        JavaNode receiver = node.tree("getExpression");
        if (receiver == null) return false;
        JavaBinding binding = unit.bindings().get(receiver.id());
        return binding != null
                && Set.of("FIELD", "LOCAL_VARIABLE", "PARAMETER").contains(binding.kind())
                && binding.expressionType() >= 0
                && unit.types().get(binding.expressionType()).kind() == JavaType.Kind.ERROR;
    }

    private static boolean arrayLength(JavaUnit unit, JavaNode node, JavaBinding binding) {
        if (!binding.name().equals("length") || !node.kind().equals("MEMBER_SELECT")) return false;
        JavaNode expression = node.tree("getExpression");
        JavaBinding receiver = expression == null ? null : unit.bindings().get(expression.id());
        return receiver != null
                && receiver.expressionType() >= 0
                && unit.types().get(receiver.expressionType()).kind() == JavaType.Kind.ARRAY;
    }
}
