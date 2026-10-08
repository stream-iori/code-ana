package com.anatomist.extract;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/** Compiler-confirmed calls and callback entities; failed bindings retain their source sites. */
public final class JavaCallFacts {
    public record UnresolvedSite(
            String owner,
            Path source,
            SourceSpan span,
            String syntaxTarget,
            String reason,
            String receiverStaticType,
            String dispatchKind,
            String context,
            String symbol) {}

    private final JavaModel model;
    private final JavaIdentities identities;
    private final JavaSiteFacts sites;
    private final Path project;
    private final String module;
    private final String scope;
    private final JavaBinaryRepository binaries;
    private final JavaTypeFacts callbackTypes;

    public JavaCallFacts(JavaModel model, Path project, String module, String scope) {
        this(model, project, module, scope, null);
    }

    public JavaCallFacts(
            JavaModel model,
            Path project,
            String module,
            String scope,
            JavaBinaryRepository binaries) {
        this(model, project, module, scope, binaries, new com.anatomist.config.ProjectConfig());
    }

    public JavaCallFacts(
            JavaModel model,
            Path project,
            String module,
            String scope,
            JavaBinaryRepository binaries,
            com.anatomist.core.ExternalTypePolicy externalPolicy) {
        this.model = Objects.requireNonNull(model);
        identities = new JavaIdentities(model);
        sites = new JavaSiteFacts(model);
        this.project = project.toAbsolutePath().normalize();
        this.module = module;
        this.scope = scope;
        this.binaries = binaries;
        callbackTypes = new JavaTypeFacts(model, project, externalPolicy);
    }

    public List<UnresolvedSite> extract(Path path, ExtractionResult result) {
        JavaUnit unit = model.unit(path);
        model.source(path);
        List<UnresolvedSite> unresolved = new ArrayList<>();
        var recovery = new JavaModeledCallRecovery(model, unit, binaries);
        for (JavaNode node : model.syntax(path).nodes()) {
            // Enum initializers and compiler recovery trees can carry synthetic, absent ranges.
            // They are declarations, not source call sites.
            if (!node.span().present()) continue;
            if (node.kind().equals("NEW_CLASS")
                    && (node.tree("getIdentifier") == null
                            || !node.tree("getIdentifier").span().present())) continue;
            String kind = node.kind();
            if (kind.equals("LAMBDA_EXPRESSION") || kind.equals("MEMBER_REFERENCE"))
                callback(unit, node, result, recovery);
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS", "MEMBER_REFERENCE").contains(kind))
                call(unit, node, result, unresolved, recovery);
        }
        for (UnresolvedSite gap : unresolved) {
            var begin = model.source(path).position(gap.span().start());
            var end =
                    model.source(path).position(Math.max(gap.span().start(), gap.span().end() - 1));
            result.unresolvedCallSites.add(
                    new UnresolvedCallSite(
                            gap.owner(),
                            sourceFile(path),
                            begin.line(),
                            begin.column(),
                            end.line(),
                            end.column(),
                            0,
                            gap.syntaxTarget(),
                            gap.receiverStaticType(),
                            gap.dispatchKind(),
                            gap.context(),
                            Json.writeCompact(Map.of("compiler_reason", gap.reason())),
                            ProducerIds.JAVA_CORE));
        }
        return List.copyOf(unresolved);
    }

    private void call(
            JavaUnit unit,
            JavaNode site,
            ExtractionResult result,
            List<UnresolvedSite> unresolved,
            JavaModeledCallRecovery recovery) {
        Path path = unit.path();
        site = constructorStatement(unit, site);
        String owner =
                site.kind().equals("MEMBER_REFERENCE")
                        ? identities.callback(path, site, "methodref")
                        : identities.owner(path, site).orElse(null);
        if (owner == null) return;
        JavaBinding binding = unit.bindings().get(site.id());
        if (arrayConstructor(unit, site)) return;
        String syntax = syntax(unit, site);
        var recovered = recovery.resolveSite(site);
        if (recovered instanceof JavaModeledCallRecovery.Blocked blocked) {
            unresolved.add(unresolved(unit, site, owner, syntax, blocked.reason(), binding));
            return;
        }
        if (recovered instanceof JavaModeledCallRecovery.ExternalSelected selected) {
            var edge = new Edge();
            edge.sourceId = owner;
            edge.isExternal = true;
            edge.externalTargetFqn = selected.target();
            edge.relation = GraphConstants.Relation.CALLS;
            edge.confidence = GraphConstants.Confidence.INFERRED;
            edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
            edge.callKind = recoveredCallKind(site, selected.callKind(), selected.target(), null);
            edge.sourceFile = sourceFile(path);
            edge.producerId = ProducerIds.JAVA_CORE;
            edge.syntaxTarget = syntax;
            edge.receiverStaticType = selected.receiver();
            edge.context = sites.control(path, site);
            sites.coordinates(edge, path, site.span());
            edge.metadata =
                    Json.writeCompact(
                            Map.of(
                                    "bindingResolved",
                                    false,
                                    "evidenceComplete",
                                    false,
                                    "modeledInput",
                                    true,
                                    "targetEvidence",
                                    "class-file"));
            result.edges.add(edge);
            return;
        }
        if (recovered instanceof JavaModeledCallRecovery.Selected selected) {
            var edge = new Edge();
            edge.sourceId = owner;
            edge.targetId = identities.declaration(selected.declaration());
            edge.relation = GraphConstants.Relation.CALLS;
            edge.confidence = GraphConstants.Confidence.INFERRED;
            edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
            edge.callKind =
                    recoveredCallKind(site, selected.callKind(), null, selected.declaration());
            edge.sourceFile = sourceFile(path);
            edge.producerId = ProducerIds.JAVA_CORE;
            edge.syntaxTarget = syntax;
            edge.receiverStaticType = selected.receiver();
            edge.context = sites.control(path, site);
            sites.coordinates(edge, path, site.span());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("bindingResolved", false);
            metadata.put("evidenceComplete", false);
            if (model.declaration(selected.declaration()).origin() != JavaDeclaration.Origin.MODEL)
                metadata.put("modeledInput", true);
            var origin =
                    model.unit(selected.declaration().source())
                            .extensions()
                            .generated()
                            .get(edge.targetId);
            if (origin != null) {
                metadata.put("generator", origin.generator());
                metadata.put("generatorMode", origin.generatorMode());
                metadata.put("generatedFrom", origin.generatedFrom());
                metadata.put("bodyAvailable", origin.bodyAvailable());
            }
            edge.metadata = Json.writeCompact(metadata);
            result.edges.add(edge);
            return;
        }
        if (binding == null
                || !binding.declarationResolved()
                || !Set.of("METHOD", "CONSTRUCTOR").contains(binding.kind())) {
            unresolved.add(
                    unresolved(
                            unit,
                            site,
                            owner,
                            syntax,
                            binding != null && !binding.declarationResolved()
                                    ? "incomplete-evidence"
                                    : "binding-unavailable",
                            binding));
            return;
        }
        Edge edge = new Edge();
        edge.sourceId = owner;
        edge.relation = GraphConstants.Relation.CALLS;
        edge.confidence = GraphConstants.Confidence.EXTRACTED;
        edge.callKind = callKind(binding, site);
        edge.sourceFile = sourceFile(path);
        edge.producerId = ProducerIds.JAVA_CORE;
        edge.syntaxTarget = syntax;
        edge.receiverStaticType = receiver(unit, site, binding);
        edge.context = sites.control(path, site);
        sites.coordinates(edge, path, site.span());
        JavaModel.TypeLookup lookup =
                model.type(
                        binding.binaryOwner().isEmpty() ? binding.owner() : binding.binaryOwner());
        if (lookup instanceof JavaModel.FoundType found) {
            List<JavaModel.DeclarationRef> targets =
                    model.members(found.declaration()).stream()
                            .filter(
                                    reference -> {
                                        JavaSymbol symbol = model.declaration(reference).symbol();
                                        return symbol.kind().equals(binding.kind())
                                                && symbol.name().equals(binding.name())
                                                && matches(unit, binding, reference);
                                    })
                            .toList();
            if (targets.size() != 1) {
                unresolved.add(
                        unresolved(
                                unit,
                                site,
                                owner,
                                syntax,
                                targets.isEmpty()
                                        ? "declaration-unavailable"
                                        : "ambiguous-declaration",
                                binding));
                return;
            }
            var target = targets.getFirst();
            edge.targetId = identities.declaration(target);
            if (binding.descriptor().isEmpty()
                    && unit.types().get(binding.declaredType()).parameters().stream()
                            .anyMatch(parameter -> missingParameter(unit.types(), parameter))) {
                edge.confidence = GraphConstants.Confidence.INFERRED;
                edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
                edge.metadata =
                        Json.writeCompact(
                                Map.of(
                                        "bindingResolved",
                                        false,
                                        "evidenceComplete",
                                        false,
                                        "targetEvidence",
                                        "source-declaration"));
            }
            if (model.declaration(target).origin() == JavaDeclaration.Origin.PROCESSOR) {
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
                                            false,
                                            "bindingResolved",
                                            true,
                                            "evidenceComplete",
                                            binding.evidenceComplete()));
            }
            if (model.declaration(target).origin() == JavaDeclaration.Origin.MODEL) {
                edge.confidence = GraphConstants.Confidence.INFERRED;
                edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
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
        } else if (lookup instanceof JavaModel.AmbiguousType) {
            unresolved.add(unresolved(unit, site, owner, syntax, "ambiguous-owner", binding));
            return;
        } else {
            if (binding.owner().isEmpty()) {
                unresolved.add(unresolved(unit, site, owner, syntax, "owner-unavailable", binding));
                return;
            }
            edge.isExternal = true;
            edge.externalTargetFqn =
                    JavaTypeNames.binaryCallableId(
                            unit.types(), binding.symbol(), binding.binaryOwner());
            edge.resolution = GraphConstants.Resolution.CLASSPATH;
        }
        if (!binding.evidenceComplete()
                && edge.confidence.equals(GraphConstants.Confidence.EXTRACTED)) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (edge.metadata != null
                    && Json.parseTree(edge.metadata) instanceof Map<?, ?> existing)
                existing.forEach((key, value) -> metadata.put(key.toString(), value));
            metadata.put("bindingResolved", true);
            metadata.put("evidenceComplete", false);
            edge.metadata = Json.writeCompact(metadata);
        }
        var modeledInput = recovery.inputEvidence(site);
        if (modeledInput == JavaModeledCallRecovery.InputEvidence.INCOMPLETE) {
            unresolved.add(unresolved(unit, site, owner, syntax, "recovery-budget", binding));
            return;
        }
        if (modeledInput == JavaModeledCallRecovery.InputEvidence.PRESENT
                && edge.confidence.equals(GraphConstants.Confidence.EXTRACTED)) {
            edge.confidence = GraphConstants.Confidence.INFERRED;
            edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (edge.metadata != null
                    && Json.parseTree(edge.metadata) instanceof Map<?, ?> existing)
                existing.forEach((key, value) -> metadata.put(key.toString(), value));
            metadata.put("bindingResolved", false);
            metadata.put("evidenceComplete", false);
            metadata.put("modeledInput", true);
            edge.metadata = Json.writeCompact(metadata);
        }
        result.edges.add(edge);
    }

    private UnresolvedSite unresolved(
            JavaUnit unit,
            JavaNode site,
            String owner,
            String syntax,
            String reason,
            JavaBinding binding) {
        String kind =
                binding != null && Set.of("METHOD", "CONSTRUCTOR").contains(binding.kind())
                        ? callKind(binding, site)
                        : site.kind().equals("NEW_CLASS")
                                ? GraphConstants.CallKind.CONSTRUCTOR
                                : null;
        return new UnresolvedSite(
                owner,
                unit.path(),
                site.span(),
                syntax,
                reason,
                receiver(unit, site, binding),
                kind,
                sites.control(unit.path(), site),
                unresolvedSymbol(site));
    }

    private boolean matches(JavaUnit caller, JavaBinding binding, JavaModel.DeclarationRef target) {
        var symbol = model.declaration(target).symbol();
        if (!binding.descriptor().isEmpty() && !symbol.descriptor().isEmpty())
            return symbol.descriptor().equals(binding.descriptor());
        var declared = model.unit(target.source());
        var selectedType = caller.types().get(binding.declaredType());
        var declaredType = declared.types().get(symbol.declaredType());
        return selectedType.kind() == JavaType.Kind.EXECUTABLE
                && declaredType.kind() == JavaType.Kind.EXECUTABLE
                && selectedType.parameters().stream()
                        .map(parameter -> parameterEvidence(caller.types(), parameter, 0))
                        .toList()
                        .equals(
                                declaredType.parameters().stream()
                                        .map(
                                                parameter ->
                                                        parameterEvidence(
                                                                declared.types(), parameter, 0))
                                        .toList());
    }

    private static boolean missingParameter(JavaTypes types, int reference) {
        for (int depth = 0; reference >= 0 && depth < 128; depth++) {
            var type = types.get(reference);
            if (type.kind() == JavaType.Kind.ERROR) return true;
            if (type.kind() != JavaType.Kind.ARRAY) return false;
            reference = type.component();
        }
        return false;
    }

    private static String parameterEvidence(JavaTypes types, int reference, int depth) {
        if (reference < 0 || depth >= 128) return "<unresolved>";
        var type = types.get(reference);
        if (type.kind() == JavaType.Kind.ERROR) return "error:" + type.name();
        if (type.kind() == JavaType.Kind.ARRAY)
            return parameterEvidence(types, type.component(), depth + 1) + "[]";
        return JavaTypeNames.erased(types, reference);
    }

    private void callback(
            JavaUnit unit,
            JavaNode site,
            ExtractionResult result,
            JavaModeledCallRecovery recovery) {
        Path path = unit.path();
        String owner = identities.owner(path, site).orElse(null);
        if (owner == null) return;
        boolean reference = site.kind().equals("MEMBER_REFERENCE");
        String identifier =
                reference && site.text("getMode").equals("NEW") ? "new" : site.text("getName");
        JavaBinding binding = unit.bindings().get(site.id());
        var recovered =
                !reference
                                && (binding == null
                                        || binding.expressionType() < 0
                                        || unit.types().get(binding.expressionType()).kind()
                                                != JavaType.Kind.DECLARED)
                        ? recovery.callbackSignature(site)
                        : null;
        boolean intrinsic = arrayConstructor(unit, site);
        Node node = new Node();
        node.id = identities.callback(path, site, reference ? "methodref" : "lambda");
        node.qualifiedName = node.id;
        node.label = reference ? "$methodref:" + identifier : "$lambda";
        node.kind = reference ? GraphConstants.Kind.METHOD_REF : GraphConstants.Kind.LAMBDA;
        node.sourceFile = sourceFile(path);
        node.module = module;
        node.scope = scope;
        node.producerId = ProducerIds.JAVA_CORE;
        JavaSource.Position begin = model.source(path).position(site.start()),
                end = model.source(path).position(Math.max(site.start(), site.end() - 1));
        node.sourceLocation = "L" + begin.line();
        node.beginLine = begin.line();
        node.beginColumn = begin.column();
        node.endLine = end.line();
        node.endColumn = end.column();
        node.sourceOrdinal = 0;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_owner_id", sourceOwner(path, site));
        metadata.put("bindingResolved", binding != null && binding.declarationResolved());
        if (binding != null && !binding.evidenceComplete()) metadata.put("evidenceComplete", false);
        if (reference) {
            metadata.put("identifier", identifier);
            metadata.put("navigation_status", intrinsic ? "intrinsic" : navigation(unit, binding));
            if (intrinsic) {
                metadata.put("reference_kind", "array_constructor");
                metadata.put(
                        "array_type",
                        model.source(path).slice(site.tree("getQualifierExpression").span()));
            }
        } else {
            List<Map<String, String>> parameters =
                    site.trees("getParameters").stream()
                            .map(
                                    parameter -> {
                                        JavaBinding parameterBinding =
                                                unit.bindings().get(parameter.id());
                                        Map<String, String> description = new LinkedHashMap<>();
                                        description.put("name", parameter.text("getName"));
                                        description.put(
                                                "type",
                                                parameterBinding == null
                                                        ? "<unresolved>"
                                                        : JavaTypeNames.erased(
                                                                unit.types(),
                                                                parameterBinding.declaredType()));
                                        return description;
                                    })
                            .toList();
            String functionalType =
                    binding == null
                            ? null
                            : JavaTypeNames.erased(unit.types(), binding.expressionType());
            if (recovered != null) {
                functionalType = recovered.functionalType().name();
                parameters = new ArrayList<>();
                for (int i = 0; i < site.trees("getParameters").size(); i++) {
                    Map<String, String> description = new LinkedHashMap<>();
                    description.put("name", site.trees("getParameters").get(i).text("getName"));
                    description.put("type", recovered.parameters().get(i).name());
                    parameters.add(description);
                }
                metadata.put("bindingResolved", false);
                metadata.put("evidenceComplete", false);
                metadata.put("functional_type_resolution", "source_fallback");
                callbackTypes.recoveredCallback(unit, site, node.id, recovered, result);
            }
            metadata.put("parameters", parameters);
            metadata.put("returnType", functionalType);
            metadata.put(
                    "signature",
                    "("
                            + String.join(
                                    ", ",
                                    parameters.stream()
                                            .map(
                                                    parameter ->
                                                            parameter.get("type")
                                                                    + " "
                                                                    + parameter.get("name"))
                                            .toList())
                            + ") -> "
                            + (functionalType == null ? "<unresolved>" : functionalType));
        }
        node.metadata = Json.writeCompact(metadata);
        result.nodes.add(node);
        Edge contains = Edge.reference(owner, node.id, false);
        contains.relation = GraphConstants.Relation.CONTAINS;
        contains.sourceFile = node.sourceFile;
        contains.sourceLocation = node.sourceLocation;
        contains.producerId = ProducerIds.JAVA_CORE;
        result.edges.add(contains);
    }

    private String navigation(JavaUnit unit, JavaBinding binding) {
        if (binding == null
                || !binding.declarationResolved()
                || !Set.of("METHOD", "CONSTRUCTOR").contains(binding.kind())) return "unresolved";
        var lookup =
                model.type(
                        binding.binaryOwner().isEmpty() ? binding.owner() : binding.binaryOwner());
        if (lookup instanceof JavaModel.AmbiguousType) return "unresolved";
        if (lookup instanceof JavaModel.FoundType found) {
            long matches =
                    model.members(found.declaration()).stream()
                            .filter(
                                    reference -> {
                                        var symbol = model.declaration(reference).symbol();
                                        return symbol.kind().equals(binding.kind())
                                                && symbol.name().equals(binding.name())
                                                && matches(unit, binding, reference);
                                    })
                            .count();
            return matches == 1 ? "resolved" : "unresolved";
        }
        return binding.owner().isEmpty() ? "unresolved" : "resolved";
    }

    private String sourceOwner(Path path, JavaNode node) {
        JavaSyntax syntax = model.syntax(path);
        for (JavaNode parent = syntax.parent(node).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            for (JavaModel.DeclarationRef reference : model.declarationsAt(path, parent.span())) {
                String kind = model.declaration(reference).symbol().kind();
                if (Set.of(
                                "CLASS",
                                "INTERFACE",
                                "ENUM",
                                "ANNOTATION_TYPE",
                                "RECORD",
                                "METHOD",
                                "CONSTRUCTOR",
                                "FIELD",
                                "RECORD_COMPONENT",
                                "ENUM_CONSTANT")
                        .contains(kind)) return identities.declaration(reference);
            }
        }
        return null;
    }

    private boolean arrayConstructor(JavaUnit unit, JavaNode site) {
        if (!site.kind().equals("MEMBER_REFERENCE") || !site.text("getMode").equals("NEW"))
            return false;
        JavaNode qualifier = site.tree("getQualifierExpression");
        JavaBinding binding = qualifier == null ? null : unit.bindings().get(qualifier.id());
        return binding != null
                && binding.expressionType() >= 0
                && unit.types().get(binding.expressionType()).kind() == JavaType.Kind.ARRAY;
    }

    private JavaNode constructorStatement(JavaUnit unit, JavaNode site) {
        if (JavaModeledCallRecovery.explicitConstructorName(site) == null) return site;
        var statement = model.syntax(unit.path()).parent(site).orElse(null);
        return statement != null
                        && statement.kind().equals("EXPRESSION_STATEMENT")
                        && statement.span().present()
                ? new JavaNode(
                        site.id(), site.kind(), statement.start(), statement.end(), site.fields())
                : site;
    }

    private static String unresolvedSymbol(JavaNode site) {
        if (site.kind().equals("MEMBER_REFERENCE") && !site.text("getMode").equals("NEW"))
            return site.text("getName");
        JavaNode target =
                site.kind().equals("METHOD_INVOCATION")
                        ? site.tree("getMethodSelect")
                        : site.kind().equals("NEW_CLASS")
                                ? site.tree("getIdentifier")
                                : site.tree("getQualifierExpression");
        for (int depth = 0; target != null && depth < 64; depth++) {
            if (target.kind().equals("PARAMETERIZED_TYPE")) {
                target = target.tree("getType");
                continue;
            }
            return target.kind().equals("MEMBER_SELECT")
                    ? target.text("getIdentifier")
                    : target.text("getName");
        }
        return "<unresolved>";
    }

    private String syntax(JavaUnit unit, JavaNode site) {
        JavaSource source = model.source(unit.path());
        if (site.kind().equals("NEW_CLASS"))
            return "new " + source.slice(site.tree("getIdentifier").span());
        if (site.kind().equals("MEMBER_REFERENCE"))
            return source.slice(site.tree("getQualifierExpression").span())
                    + "::"
                    + (site.text("getMode").equals("NEW") ? "new" : site.text("getName"));
        JavaNode select = site.tree("getMethodSelect");
        return select.kind().equals("MEMBER_SELECT")
                ? source.slice(select.tree("getExpression").span())
                        + "."
                        + select.text("getIdentifier")
                : select.text("getName");
    }

    private String receiver(JavaUnit unit, JavaNode site, JavaBinding target) {
        JavaNode expression =
                site.kind().equals("MEMBER_REFERENCE")
                        ? site.tree("getQualifierExpression")
                        : site.kind().equals("METHOD_INVOCATION")
                                        && site.tree("getMethodSelect")
                                                .kind()
                                                .equals("MEMBER_SELECT")
                                ? site.tree("getMethodSelect").tree("getExpression")
                                : null;
        JavaBinding binding = expression == null ? null : unit.bindings().get(expression.id());
        return binding != null && binding.expressionType() >= 0
                ? JavaTypeNames.erased(unit.types(), binding.expressionType())
                : target == null || target.owner().isEmpty() ? null : target.owner();
    }

    private static String callKind(JavaBinding binding, JavaNode site) {
        if (binding.kind().equals("CONSTRUCTOR")) return GraphConstants.CallKind.CONSTRUCTOR;
        if (binding.flags().contains("static")) return GraphConstants.CallKind.STATIC;
        if (site.kind().equals("METHOD_INVOCATION")) {
            JavaNode select = site.tree("getMethodSelect");
            if (select.kind().equals("MEMBER_SELECT")
                    && select.tree("getExpression").text("getName").equals("super"))
                return GraphConstants.CallKind.SUPER;
        }
        return Set.of("INTERFACE", "ANNOTATION_TYPE").contains(binding.ownerKind())
                ? GraphConstants.CallKind.INTERFACE
                : GraphConstants.CallKind.INSTANCE;
    }

    private String recoveredCallKind(
            JavaNode site, String kind, String externalTarget, JavaModel.DeclarationRef target) {
        if (!kind.equals(GraphConstants.CallKind.INSTANCE)) return kind;
        if (site.kind().equals("METHOD_INVOCATION")) {
            JavaNode select = site.tree("getMethodSelect");
            if (select.kind().equals("MEMBER_SELECT")
                    && select.tree("getExpression").text("getName").equals("super"))
                return GraphConstants.CallKind.SUPER;
        }
        if (target != null) {
            var parent = model.parent(target);
            if (parent.isPresent()
                    && Set.of("INTERFACE", "ANNOTATION_TYPE")
                            .contains(model.declaration(parent.get()).symbol().kind()))
                return GraphConstants.CallKind.INTERFACE;
        } else if (binaries != null && externalTarget != null) {
            int separator = externalTarget.indexOf('#');
            if (separator > 0
                    && binaries.find(externalTarget.substring(0, separator))
                            .map(
                                    type ->
                                            (type.access()
                                                            & org.objectweb.asm.Opcodes
                                                                    .ACC_INTERFACE)
                                                    != 0)
                            .orElse(false)) return GraphConstants.CallKind.INTERFACE;
        }
        return kind;
    }

    private String sourceFile(Path path) {
        return project.relativize(path).toString().replace('\\', '/');
    }
}
