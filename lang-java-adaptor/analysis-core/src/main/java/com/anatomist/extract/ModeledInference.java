package com.anatomist.extract;

import static com.anatomist.extract.JavaModeledCallRecovery.*;

import com.anatomist.core.asmsolver.JavaBinaryTypes;
import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;

import java.util.*;

/** Task-local ModeledInference; shared state remains owned by JavaModeledCallRecovery. */
final class ModeledInference {
    private final JavaModeledCallRecovery context;

    ModeledInference(JavaModeledCallRecovery context) {
        this.context = context;
    }

    Match methodArguments(
            JavaNode site,
            List<Use> actual,
            List<Integer> parameters,
            int returns,
            JavaTypes types,
            List<Integer> formalTypes,
            Map<String, Use> variables,
            boolean variadic) {
        List<Formal> formals =
                formalTypes.stream().map(reference -> new Formal(types, reference)).toList();
        Use target =
                site.kind().equals("METHOD_INVOCATION")
                        ? context.modeledInference.writtenExpected(site)
                        : null;
        for (Formal formal : formals) variables.remove(formal.name());
        var explicit = site.trees("getTypeArguments");
        if (!explicit.isEmpty()) {
            if (explicit.size() != formals.size()) return Match.NO;
            for (int i = 0; i < formals.size(); i++) {
                Use value = context.expression(explicit.get(i));
                if (value == null) return Match.UNKNOWN;
                variables.put(formals.get(i).name(), value);
            }
        } else {
            List<Formal> inputFormals = formals;
            Use result = new Use(types, returns, variables);
            if (target != null
                    && result.type().kind() == JavaType.Kind.TYPEVAR
                    && context.modeledInference.formal(result, formals)
                    && context.modeledInference.returnTargetSeedable(
                            actual, parameters, types, variables, result, target)
                    && Set.of(JavaType.Kind.DECLARED, JavaType.Kind.ARRAY)
                            .contains(target.resolved().type().kind())
                    && context.modeledInference.satisfiesBounds(target, result, variables)) {
                variables.put(result.type().name(), target);
                inputFormals =
                        formals.stream()
                                .filter(formal -> !variables.containsKey(formal.name()))
                                .toList();
            }
            if (target != null
                    && result.type().kind() == JavaType.Kind.DECLARED
                    && target.resolved().type().kind() == JavaType.Kind.DECLARED
                    && !target.resolved().type().arguments().isEmpty()) {
                TypeProjection projection =
                        context.modeledTypeOps.projectType(result, target.name(), 0);
                Map<String, Use> hints = new LinkedHashMap<>(variables);
                if (projection.match() == Match.YES
                        && context.modeledInference.seedTarget(
                                        target,
                                        projection.type(),
                                        formals,
                                        hints,
                                        0,
                                        actual.isEmpty())
                                == Match.YES) {
                    variables.putAll(hints);
                    inputFormals =
                            formals.stream()
                                    .filter(formal -> !hints.containsKey(formal.name()))
                                    .toList();
                }
            }
            for (int i = 0; i < actual.size(); i++) {
                Use parameter =
                        new Use(
                                types,
                                parameters.get(Math.min(i, parameters.size() - 1)),
                                variables);
                if (variadic
                        && i >= parameters.size() - 1
                        && parameter.type().kind() == JavaType.Kind.ARRAY
                        && (i >= parameters.size()
                                || actual.get(i).resolved().type().kind() != JavaType.Kind.ARRAY))
                    parameter = parameter.child(parameter.type().component());
                Match constraint =
                        context.modeledInference.infer(
                                actual.get(i), parameter, inputFormals, variables, 0);
                if (constraint != Match.YES) return constraint;
            }
            if (target != null
                    && context.modeledInference.formal(result, formals)
                    && !variables.containsKey(result.type().name())
                    && context.modeledInference.satisfiesBounds(target, result, variables))
                variables.put(result.type().name(), target);
        }
        for (int reference : formalTypes) {
            var type = types.get(reference);
            Use value = variables.get(type.name());
            if (value == null) continue;
            for (int bound : type.upperBounds()) {
                Match match =
                        context.modeledTypeOps.assignable(
                                value, new Use(types, bound, variables), 0);
                if (match != Match.YES) return match;
            }
        }
        if (target != null && !formals.isEmpty()) {
            Match output =
                    context.modeledTypeOps.assignable(
                            new Use(types, returns, variables), target, 0);
            if (output != Match.YES
                    && !(output == Match.UNKNOWN
                            && context.modeledInference.unboundTarget(target, 0))) return output;
        }
        return Match.YES;
    }

    boolean satisfiesBounds(Use target, Use formal, Map<String, Use> variables) {
        Map<String, Use> hints = new LinkedHashMap<>(variables);
        hints.put(formal.type().name(), target);
        for (int bound : formal.type().upperBounds())
            if (context.modeledTypeOps.assignable(target, new Use(formal.types(), bound, hints), 0)
                    != Match.YES) return false;
        return true;
    }

    Match seedTarget(
            Use target,
            Use pattern,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth,
            boolean wildcardHints) {
        if (++context.work > 100_000 || depth >= 64) return Match.UNKNOWN;
        target = target.resolved();
        if (wildcardHints && target.type().kind() == JavaType.Kind.WILDCARD) {
            target = context.modeledTypeOps.ground(target);
            if (target == null || context.modeledInference.unboundTarget(target, 0))
                return Match.YES;
        }
        if (target.type().kind() == JavaType.Kind.TYPEVAR
                || target.type().kind() == JavaType.Kind.WILDCARD) return Match.YES;
        if (pattern.type().kind() == JavaType.Kind.TYPEVAR
                && context.modeledInference.formal(pattern, formals))
            return context.modeledInference.infer(target, pattern, formals, variables, depth + 1);
        pattern = pattern.resolved();
        if (pattern.type().kind() == JavaType.Kind.TYPEVAR
                && context.modeledInference.formal(pattern, formals))
            return context.modeledInference.infer(target, pattern, formals, variables, depth + 1);
        if (pattern.type().kind() == JavaType.Kind.DECLARED
                && target.type().kind() == JavaType.Kind.DECLARED
                && pattern.name().equals(target.name())
                && pattern.type().arguments().size() == target.type().arguments().size()) {
            for (int i = 0; i < pattern.type().arguments().size(); i++) {
                Match match =
                        context.modeledInference.seedTarget(
                                target.argument(i),
                                pattern.argument(i),
                                formals,
                                variables,
                                depth + 1,
                                wildcardHints);
                if (match != Match.YES) return match;
            }
        } else if (pattern.type().kind() == JavaType.Kind.ARRAY
                && target.type().kind() == JavaType.Kind.ARRAY)
            return context.modeledInference.seedTarget(
                    target.child(target.type().component()),
                    pattern.child(pattern.type().component()),
                    formals,
                    variables,
                    depth + 1,
                    wildcardHints);
        return Match.YES;
    }

    boolean unboundTarget(Use value, int depth) {
        if (++context.work > 100_000 || depth >= 64)
            throw new IllegalArgumentException("target type lookup exceeds budget");
        value = value.resolved();
        if (value.type().kind() == JavaType.Kind.TYPEVAR) return true;
        for (int argument : value.type().arguments())
            if (context.modeledInference.unboundTarget(value.child(argument), depth + 1))
                return true;
        if (value.type().component() >= 0
                && context.modeledInference.unboundTarget(
                        value.child(value.type().component()), depth + 1)) return true;
        if (value.type().lowerBound() >= 0
                && context.modeledInference.unboundTarget(
                        value.child(value.type().lowerBound()), depth + 1)) return true;
        for (int bound : value.type().upperBounds())
            if (context.modeledInference.unboundTarget(value.child(bound), depth + 1)) return true;
        return false;
    }

    boolean returnTargetSeedable(
            List<Use> actual,
            List<Integer> parameters,
            JavaTypes types,
            Map<String, Use> variables,
            Use formal,
            Use target) {
        for (int i = 0; i < parameters.size(); i++) {
            Use parameter = new Use(types, parameters.get(i), variables);
            if (!context.modeledInference.nestedFormal(parameter, formal, 0)) continue;
            if (i >= actual.size()) return false;
            JavaNode poly = context.polyNodes.get(actual.get(i));
            // A poly factory may infer its own arguments from this target. A prebuilt
            // invariant value, or a factory returning another subtype, must infer first.
            if (poly == null
                    || !poly.kind().equals("METHOD_INVOCATION")
                    || !context.modeledInference.sameNestedErasure(
                            actual.get(i), parameter, formal, target, 0)) return false;
        }
        return true;
    }

    boolean sameNestedErasure(Use actual, Use parameter, Use formal, Use target, int depth) {
        if (++context.work > 100_000 || depth >= 64) return false;
        if (parameter.types() == formal.types() && parameter.reference() == formal.reference())
            return actual.resolved().type().kind() == JavaType.Kind.DECLARED
                    && actual.name().equals(target.name());
        if (!context.modeledInference.containsFormal(
                parameter, List.of(new Formal(formal.types(), formal.reference())), depth + 1))
            return true;
        parameter = parameter.resolved();
        actual = actual.resolved();
        if (parameter.type().kind() == JavaType.Kind.DECLARED) {
            TypeProjection projection =
                    context.modeledTypeOps.projectType(actual, parameter.name(), depth + 1);
            if (projection.match() != Match.YES) return false;
            actual = projection.type().resolved();
            if (actual.type().arguments().size() != parameter.type().arguments().size())
                return false;
            for (int i = 0; i < parameter.type().arguments().size(); i++)
                if (!context.modeledInference.sameNestedErasure(
                        actual.argument(i), parameter.argument(i), formal, target, depth + 1))
                    return false;
            return true;
        }
        if (parameter.type().kind() == JavaType.Kind.ARRAY
                && actual.type().kind() == JavaType.Kind.ARRAY)
            return context.modeledInference.sameNestedErasure(
                    actual.child(actual.type().component()),
                    parameter.child(parameter.type().component()),
                    formal,
                    target,
                    depth + 1);
        if (parameter.type().kind() == JavaType.Kind.WILDCARD) {
            Use value = context.modeledTypeOps.ground(actual);
            if (value == null) return false;
            if (parameter.type().lowerBound() >= 0)
                return context.modeledInference.sameNestedErasure(
                        value,
                        parameter.child(parameter.type().lowerBound()),
                        formal,
                        target,
                        depth + 1);
            if (parameter.type().upperBounds().size() == 1)
                return context.modeledInference.sameNestedErasure(
                        value,
                        parameter.child(parameter.type().upperBounds().getFirst()),
                        formal,
                        target,
                        depth + 1);
        }
        return false;
    }

    boolean nestedFormal(Use parameter, Use formal, int depth) {
        if (++context.work > 100_000 || depth >= 64) return true;
        if (parameter.type().kind() == JavaType.Kind.ARRAY)
            return context.modeledInference.nestedFormal(
                    parameter.child(parameter.type().component()), formal, depth + 1);
        return parameter.type().kind() == JavaType.Kind.DECLARED
                && context.modeledInference.containsFormal(
                        parameter,
                        List.of(new Formal(formal.types(), formal.reference())),
                        depth + 1);
    }

    boolean formal(Use use, List<Formal> formals) {
        for (Formal formal : formals) {
            if (++context.work > 100_000)
                throw new IllegalArgumentException("formal identity lookup exceeds budget");
            if (formal.types() == use.types() && formal.reference() == use.reference()) return true;
        }
        return false;
    }

    boolean containsFormal(Use value, List<Formal> formals, int depth) {
        if (++context.work > 100_000 || depth >= 64)
            throw new IllegalArgumentException("formal type lookup exceeds budget");
        if (value.type().kind() == JavaType.Kind.TYPEVAR
                && context.modeledInference.formal(value, formals)) return true;
        value = value.resolved();
        if (value.type().kind() == JavaType.Kind.TYPEVAR)
            return context.modeledInference.formal(value, formals);
        for (int argument : value.type().arguments())
            if (context.modeledInference.containsFormal(value.child(argument), formals, depth + 1))
                return true;
        if (value.type().enclosing() >= 0
                && context.modeledInference.containsFormal(
                        value.child(value.type().enclosing()), formals, depth + 1)) return true;
        if (value.type().component() >= 0
                && context.modeledInference.containsFormal(
                        value.child(value.type().component()), formals, depth + 1)) return true;
        if (value.type().lowerBound() >= 0
                && context.modeledInference.containsFormal(
                        value.child(value.type().lowerBound()), formals, depth + 1)) return true;
        for (int bound : value.type().upperBounds())
            if (context.modeledInference.containsFormal(value.child(bound), formals, depth + 1))
                return true;
        return false;
    }

    Match infer(
            Use actual,
            Use parameter,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth) {
        if (++context.work > 100_000 || depth >= 64) return Match.UNKNOWN;
        if (!context.modeledInference.containsFormal(parameter, formals, 0))
            return context.modeledTypeOps.assignable(actual, parameter, depth + 1);
        var poly = context.polyNodes.get(actual);
        if (poly != null) {
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS").contains(poly.kind())) {
                Result result = context.modeledInference.contextualInvocation(poly, parameter);
                if (result instanceof Blocked) return Match.NO;
                Use value =
                        result instanceof Selected source
                                ? source.returns()
                                : result instanceof ExternalSelected external
                                        ? external.returns()
                                        : null;
                return value == null
                        ? Match.UNKNOWN
                        : context.modeledInference.infer(
                                value, parameter, formals, variables, depth + 1);
            }
            return context.modeledInference.inferPoly(
                    poly, parameter, formals, variables, depth + 1);
        }
        var source = actual.resolved();
        if (parameter.type().kind() != JavaType.Kind.TYPEVAR
                || !context.modeledInference.formal(parameter, formals))
            parameter = parameter.resolved();
        var target = parameter.type();
        if (source.type().kind() == JavaType.Kind.NULL) return Match.YES;
        if (target.kind() == JavaType.Kind.TYPEVAR
                && context.modeledInference.formal(parameter, formals)) {
            if (ModeledTypeOps.primitive(source.name()))
                source = ModeledTypeOps.namedType(BOXES.get(source.name()));
            boolean boundedVariable =
                    source.type().kind() == JavaType.Kind.TYPEVAR
                            && !source.type().upperBounds().isEmpty();
            if (!boundedVariable
                    && !Set.of(JavaType.Kind.DECLARED, JavaType.Kind.ARRAY, JavaType.Kind.WILDCARD)
                            .contains(source.type().kind())) return Match.UNKNOWN;
            // Keep the foreign formal and its capture substitutions, rather than erasing it
            // to its bound: subsequent receiver lookup can read the bound without equating
            // independently captured values during invariant argument inference.
            var previous = variables.putIfAbsent(target.name(), source);
            return previous == null
                    ? Match.YES
                    : context.modeledTypeOps.same(previous, source, depth + 1);
        }
        if (target.kind() == JavaType.Kind.ARRAY && source.type().kind() == JavaType.Kind.ARRAY)
            return context.modeledInference.infer(
                    source.child(source.type().component()),
                    parameter.child(target.component()),
                    formals,
                    variables,
                    depth + 1);
        if (target.kind() == JavaType.Kind.WILDCARD) {
            if (target.lowerBound() >= 0)
                return context.modeledInference.infer(
                        source,
                        parameter.child(target.lowerBound()),
                        formals,
                        variables,
                        depth + 1);
            if (target.upperBounds().size() == 1)
                return context.modeledInference.infer(
                        source,
                        parameter.child(target.upperBounds().getFirst()),
                        formals,
                        variables,
                        depth + 1);
            return Match.YES;
        }
        if (target.kind() == JavaType.Kind.DECLARED
                && source.type().kind() == JavaType.Kind.DECLARED
                && !target.arguments().isEmpty()) {
            if (!source.name().equals(parameter.name())) {
                TypeProjection projection =
                        context.modeledTypeOps.projectType(source, parameter.name(), depth + 1);
                if (projection.match() != Match.YES) return projection.match();
                source = projection.type();
            }
            if (source.type().arguments().size() != target.arguments().size()) return Match.UNKNOWN;
            for (int i = 0; i < target.arguments().size(); i++) {
                Match match =
                        context.modeledInference.infer(
                                source.argument(i),
                                parameter.argument(i),
                                formals,
                                variables,
                                depth + 1);
                if (match != Match.YES) return match;
            }
        }
        return Match.YES;
    }

    static Map<String, Use> binaryVariables(JavaBinaryTypes.ClassShape hierarchy, Use current) {
        Map<String, Use> variables = new LinkedHashMap<>();
        var actual = current.resolved();
        for (int i = 0; i < hierarchy.parameters().size(); i++)
            if (i < actual.type().arguments().size())
                variables.put(hierarchy.parameters().get(i), actual.argument(i));
        return Map.copyOf(variables);
    }

    Use genericInvocation(JavaNode site) {
        if (!site.kind().equals("METHOD_INVOCATION")
                || !site.trees("getTypeArguments").isEmpty()
                || !context.modeledInference.siteArgument(site)) return null;
        var binding = context.unit.bindings().get(site.id());
        if (binding == null || !binding.declarationResolved() || binding.declaredType() < 0)
            return null;
        var executable = context.unit.types().get(binding.declaredType());
        if (executable.kind() != JavaType.Kind.EXECUTABLE || executable.arguments().isEmpty())
            return null;
        Use returns = new Use(context.unit.types(), executable.returns());
        return context.modeledInference.mentionsFormal(
                        returns, Set.copyOf(executable.arguments()), 0)
                ? returns
                : null;
    }

    boolean mentionsFormal(Use value, Set<Integer> formals, int depth) {
        if (++context.work > 100_000 || depth >= 64)
            throw new IllegalArgumentException("return type lookup exceeds budget");
        if (value.type().kind() == JavaType.Kind.TYPEVAR)
            return formals.contains(value.reference());
        for (int argument : value.type().arguments())
            if (context.modeledInference.mentionsFormal(value.child(argument), formals, depth + 1))
                return true;
        if (value.type().component() >= 0
                && context.modeledInference.mentionsFormal(
                        value.child(value.type().component()), formals, depth + 1)) return true;
        if (value.type().lowerBound() >= 0
                && context.modeledInference.mentionsFormal(
                        value.child(value.type().lowerBound()), formals, depth + 1)) return true;
        for (int bound : value.type().upperBounds())
            if (context.modeledInference.mentionsFormal(value.child(bound), formals, depth + 1))
                return true;
        return false;
    }

    boolean siteArgument(JavaNode site) {
        var syntax = context.model.syntax(context.unit.path());
        var parent = syntax.parent(site).orElse(null);
        while (parent != null && parent.kind().equals("PARENTHESIZED")) {
            site = parent;
            parent = syntax.parent(site).orElse(null);
        }
        return parent != null
                && parent.kind().equals("METHOD_INVOCATION")
                && parent.trees("getArguments").contains(site);
    }

    Use expected(JavaNode site) {
        var override = context.expectedOverrides.get(site.id());
        if (override != null) return override;
        Use written = context.modeledInference.writtenExpected(site);
        if (written != null) return written;
        var binding = context.unit.bindings().get(site.id());
        return binding == null || binding.expressionType() < 0
                ? null
                : new Use(context.unit.types(), binding.expressionType());
    }

    Use writtenExpected(JavaNode site) {
        Use override = context.expectedOverrides.get(site.id());
        if (override != null) return override;
        var syntax = context.model.syntax(context.unit.path());
        JavaNode child = site;
        for (JavaNode parent = syntax.parent(child).orElse(null);
                parent != null;
                child = parent, parent = syntax.parent(child).orElse(null)) {
            // A cast supplies a functional target for a lambda/reference, but does not make
            // an invocation a poly expression in an assignment or invocation context.
            if (parent.kind().equals("TYPE_CAST") && site.kind().equals("METHOD_INVOCATION"))
                return null;
            if (parent.kind().equals("METHOD_INVOCATION")) {
                int index = parent.trees("getArguments").indexOf(child);
                Result selected = context.resultCache().get(parent.id());
                if (selected == null && !context.active.contains(parent.id()))
                    selected = context.resolve(parent);
                List<Use> parameters =
                        selected instanceof Selected source
                                ? source.parameters()
                                : selected instanceof ExternalSelected external
                                        ? external.parameters()
                                        : List.of();
                return index >= 0 && index < parameters.size() ? parameters.get(index) : null;
            }
            JavaNode target =
                    switch (parent.kind()) {
                        case "VARIABLE" -> parent.tree("getType");
                        case "TYPE_CAST" -> parent.tree("getType");
                        case "ASSIGNMENT" -> parent.tree("getVariable");
                        default -> null;
                    };
            if (target != null) return context.expression(target);
            if (parent.kind().equals("RETURN")) {
                for (JavaNode declaration = syntax.parent(parent).orElse(null);
                        declaration != null;
                        declaration = syntax.parent(declaration).orElse(null)) {
                    if (++context.work > 100_000) return null;
                    if (declaration.kind().equals("METHOD"))
                        return context.expression(declaration.tree("getReturnType"));
                    if (declaration.kind().equals("LAMBDA_EXPRESSION")) {
                        Sam descriptor =
                                context.modeledInference.sam(
                                        context.modeledInference.expected(declaration));
                        return descriptor == null ? null : descriptor.returns();
                    }
                    if (Set.of("CLASS", "INTERFACE", "ENUM", "RECORD").contains(declaration.kind()))
                        return null;
                }
            }
            if (!parent.kind().equals("PARENTHESIZED")) break;
        }
        return null;
    }

    Result contextualInvocation(JavaNode site, Use parameter) {
        Use previous = context.expectedOverrides.put(site.id(), parameter);
        context.provisionalResults.push(new HashMap<>());
        try {
            return context.select(site);
        } finally {
            context.provisionalResults.pop();
            if (previous == null) context.expectedOverrides.remove(site.id());
            else context.expectedOverrides.put(site.id(), previous);
        }
    }

    boolean modeledLambda(JavaNode expression) {
        var syntax = context.model.syntax(context.unit.path());
        for (var parent = syntax.parent(expression).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (!parent.kind().equals("LAMBDA_EXPRESSION")) continue;
            if (context.expectedOverrides.containsKey(parent.id())) return true;
            var outer = syntax.parent(parent).orElse(null);
            if (outer == null) return false;
            var result = context.resultCache().get(outer.id());
            return result instanceof Selected || result instanceof ExternalSelected;
        }
        return false;
    }

    Use lambdaParameter(JavaNode expression) {
        var binding = context.unit.bindings().get(expression.id());
        if (binding == null || !binding.kind().equals("PARAMETER")) return null;
        var syntax = context.model.syntax(context.unit.path());
        for (var parent = syntax.parent(expression).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (Set.of("METHOD", "CLASS", "INTERFACE", "ENUM", "RECORD").contains(parent.kind()))
                return null;
            if (!parent.kind().equals("LAMBDA_EXPRESSION")) continue;
            var parameters = parent.trees("getParameters");
            for (int i = 0; i < parameters.size(); i++) {
                if (!parameters.get(i).text("getName").equals(expression.text("getName"))) continue;
                var written = parameters.get(i).tree("getType");
                if (written != null
                        && written.span().present()
                        && !written.text("originalSourceTree").equals("false")) return null;
                var descriptor =
                        context.modeledInference.sam(context.modeledInference.expected(parent));
                return descriptor == null || i >= descriptor.parameters().size()
                        ? null
                        : descriptor.parameters().get(i);
            }
        }
        return null;
    }

    Match inferPoly(
            JavaNode poly,
            Use parameter,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth) {
        var descriptor = context.modeledInference.sam(parameter);
        if (descriptor == null) return Match.UNKNOWN;
        if (poly.kind().equals("MEMBER_REFERENCE")
                && !poly.text("getMode").equals("NEW")
                && !descriptor.parameters().isEmpty()) {
            Receiver receiver = context.modeledCandidates.receiver(poly);
            Use input = descriptor.parameters().getFirst();
            if (receiver != null
                    && receiver.typeQualifier()
                    && context.modeledInference.staticReference(
                                    receiver.type(),
                                    JavaModeledCallRecovery.methodName(poly),
                                    descriptor.parameters().size(),
                                    descriptor.parameters().getFirst())
                            == Match.NO
                    && context.modeledInference.containsFormal(input, formals, 0)
                    && context.modeledInference.unboundTarget(input, 0)) {
                Match constraint =
                        context.modeledInference.infer(
                                receiver.type(), input, formals, variables, depth + 1);
                if (constraint != Match.YES) return constraint;
            }
        }
        Use previous = context.expectedOverrides.put(poly.id(), parameter);
        context.provisionalResults.push(new HashMap<>());
        try {
            Use returns;
            if (poly.kind().equals("MEMBER_REFERENCE")) {
                if (!context.probingReferences.add(poly.id())) return Match.UNKNOWN;
                Result result;
                try {
                    result = context.select(poly);
                } finally {
                    context.probingReferences.remove(poly.id());
                }
                returns =
                        result instanceof Selected source
                                ? source.returns()
                                : result instanceof ExternalSelected external
                                        ? external.returns()
                                        : null;
            } else {
                if (poly.trees("getParameters").size() != descriptor.parameters().size())
                    return Match.NO;
                var body = poly.tree("getBody");
                if (body != null && body.kind().equals("BLOCK")) {
                    ReturnSites sites = context.modeledInference.returnSites(body);
                    if (!sites.complete()) return Match.UNKNOWN;
                    boolean voidReturn =
                            descriptor.returns().resolved().type().kind() == JavaType.Kind.VOID;
                    if (voidReturn)
                        return sites.nodes().stream()
                                        .allMatch(node -> node.tree("getExpression") == null)
                                ? Match.YES
                                : Match.NO;
                    Match completion = context.modeledInference.abruptCompletion(body, 0);
                    if (completion != Match.YES)
                        return completion == Match.NO ? Match.NO : Match.UNKNOWN;
                    if (sites.nodes().isEmpty()) return Match.UNKNOWN;
                    for (JavaNode site : sites.nodes()) {
                        JavaNode returned = site.tree("getExpression");
                        if (context.modeledInference.invalidConditional(returned)) return Match.NO;
                        Use value = context.expression(returned);
                        if (value == null) return Match.UNKNOWN;
                        Match constraint =
                                context.modeledInference.infer(
                                        value, descriptor.returns(), formals, variables, depth + 1);
                        if (constraint != Match.YES) return constraint;
                    }
                    return Match.YES;
                }
                if (context.modeledInference.invalidConditional(body)) return Match.NO;
                returns = body == null ? null : context.expression(body);
            }
            if (returns != null
                    && returns.resolved().type().kind() == JavaType.Kind.VOID
                    && descriptor.returns().resolved().type().kind() != JavaType.Kind.VOID)
                return Match.NO;
            return returns == null
                    ? Match.UNKNOWN
                    : context.modeledInference.infer(
                            returns, descriptor.returns(), formals, variables, depth + 1);
        } finally {
            context.provisionalResults.pop();
            if (previous == null) context.expectedOverrides.remove(poly.id());
            else context.expectedOverrides.put(poly.id(), previous);
        }
    }

    boolean invalidConditional(JavaNode node) {
        for (int depth = 0; node != null && node.kind().equals("PARENTHESIZED"); depth++) {
            if (++context.work > 100_000 || depth >= 64) return false;
            node = node.tree("getExpression");
        }
        if (node == null || !node.kind().equals("CONDITIONAL_EXPRESSION")) return false;
        Use condition = context.expression(node.tree("getCondition"));
        return condition != null
                && !ModeledTypeOps.booleanValue(condition)
                && !Set.of(
                                JavaType.Kind.ERROR,
                                JavaType.Kind.NONE,
                                JavaType.Kind.OTHER,
                                JavaType.Kind.TYPEVAR,
                                JavaType.Kind.WILDCARD)
                        .contains(condition.resolved().type().kind());
    }

    Sam sam(Use functional) {
        if (functional == null) return null;
        var root = functional.resolved();
        var lookup = context.model.type(root.name());
        if (lookup instanceof JavaModel.FoundType found) {
            if (!context.model.declaration(found.declaration()).symbol().kind().equals("INTERFACE"))
                return null;
        } else if (lookup instanceof JavaModel.MissingType && context.binaries != null) {
            var type = context.binaries.find(root.name());
            if (type.isEmpty() || (type.get().access() & Opcodes.ACC_INTERFACE) == 0) return null;
        } else return null;
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(root);
        Set<String> visited = new HashSet<>();
        Map<String, Sam> descriptors = new LinkedHashMap<>();
        Set<String> concrete = new HashSet<>();
        Map<String, Use> declaringOwners = new HashMap<>();
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || visited.size() >= 256) return null;
            Use current = pending.removeFirst().resolved();
            if (!visited.add(current.name()) || current.name().equals("java.lang.Object")) continue;
            var currentLookup = context.model.type(current.name());
            if (currentLookup instanceof JavaModel.FoundType source) {
                var owner = context.model.declaration(source.declaration());
                var declaring = context.model.unit(source.declaration().source());
                var variables =
                        context.modeledCandidates
                                .owner(source.declaration(), current, 0)
                                .variables();
                for (var member : context.model.members(source.declaration())) {
                    var method = context.model.declaration(member);
                    var flags = method.symbol().modifiers();
                    if (!method.symbol().kind().equals("METHOD")
                            || flags.contains("static")
                            || flags.contains("private")) continue;
                    if (method.members().stream()
                            .anyMatch(m -> m.symbol().kind().equals("TYPE_PARAMETER"))) {
                        if (flags.contains("abstract")) return null;
                        continue;
                    }
                    var executable = declaring.types().get(method.symbol().declaredType());
                    if (!context.modeledInference.samMember(
                            descriptors,
                            concrete,
                            declaringOwners,
                            current,
                            !flags.contains("abstract"),
                            method.symbol().name(),
                            method.symbol().descriptor(),
                            declaring.types(),
                            executable,
                            variables)) return null;
                }
                for (int parent : owner.interfaces())
                    pending.add(new Use(declaring.types(), parent, variables));
            } else if (currentLookup instanceof JavaModel.MissingType && context.binaries != null) {
                var found = context.binaries.find(current.name());
                if (found.isEmpty()) return null;
                var type = found.get();
                try {
                    var hierarchy = JavaBinaryTypes.hierarchy(type);
                    var variables = ModeledInference.binaryVariables(hierarchy, current);
                    for (var method : type.methods()) {
                        if ((method.access()
                                        & (Opcodes.ACC_STATIC
                                                | Opcodes.ACC_PRIVATE
                                                | Opcodes.ACC_BRIDGE
                                                | Opcodes.ACC_SYNTHETIC))
                                != 0) continue;
                        var shape = JavaBinaryTypes.method(type, method);
                        if (!shape.methodParameters().isEmpty()) {
                            if ((method.access() & Opcodes.ACC_ABSTRACT) != 0) return null;
                            continue;
                        }
                        if (!context.modeledInference.samMember(
                                descriptors,
                                concrete,
                                declaringOwners,
                                current,
                                (method.access() & Opcodes.ACC_ABSTRACT) == 0,
                                method.name(),
                                method.descriptor(),
                                shape.types(),
                                shape.types().get(shape.executable()),
                                variables)) return null;
                    }
                    for (int parent : hierarchy.interfaces())
                        pending.add(new Use(hierarchy.types(), parent, variables));
                } catch (IllegalArgumentException unavailable) {
                    return null;
                }
            } else return null;
        }
        return descriptors.size() == 1 ? descriptors.values().iterator().next() : null;
    }

    boolean samMember(
            Map<String, Sam> descriptors,
            Set<String> concrete,
            Map<String, Use> declaringOwners,
            Use declaringOwner,
            boolean defaultMethod,
            String name,
            String descriptor,
            JavaTypes types,
            JavaType executable,
            Map<String, Use> variables) {
        if (Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;")
                .contains(name + descriptor)) return true;
        if (executable.kind() != JavaType.Kind.EXECUTABLE) return false;
        List<Use> parameters = new ArrayList<>();
        for (int parameter : executable.parameters()) {
            Use type = context.modeledTypeOps.ground(new Use(types, parameter, variables));
            if (type == null) return false;
            parameters.add(type);
        }
        var candidate =
                new Sam(List.copyOf(parameters), new Use(types, executable.returns(), variables));
        String key =
                name + "(" + String.join(",", parameters.stream().map(Use::name).toList()) + ")";
        Use previousOwner = declaringOwners.putIfAbsent(key, declaringOwner);
        if (previousOwner != null && !previousOwner.name().equals(declaringOwner.name())) {
            if (context.modeledTypeOps.sourceHierarchy(
                            previousOwner, ModeledTypeOps.namedType(declaringOwner.name()), 0)
                    == Match.YES) return true;
            if (context.modeledTypeOps.sourceHierarchy(
                            declaringOwner, ModeledTypeOps.namedType(previousOwner.name()), 0)
                    == Match.YES) {
                declaringOwners.put(key, declaringOwner);
                descriptors.remove(key);
                concrete.remove(key);
            } else if (defaultMethod || concrete.contains(key)) return false;
        }
        if (defaultMethod) {
            concrete.add(key);
            descriptors.remove(key);
            return true;
        }
        if (concrete.contains(key)) return true;
        var previous = descriptors.putIfAbsent(key, candidate);
        if (previous == null) return true;
        if (context.modeledSelection.arguments(previous.parameters(), candidate.parameters(), true)
                        != Match.YES
                || context.modeledSelection.arguments(
                                candidate.parameters(), previous.parameters(), true)
                        != Match.YES) return false;
        if (context.modeledTypeOps.strictAssignable(candidate.returns(), previous.returns())
                == Match.YES) {
            descriptors.put(key, candidate);
            return true;
        }
        return context.modeledTypeOps.strictAssignable(previous.returns(), candidate.returns())
                == Match.YES;
    }

    Match staticReference(Use receiver, String name, int argumentCount, Use firstInput) {
        boolean statics = false, instances = false;
        boolean instancePossible =
                firstInput == null
                        || context.modeledTypeOps.strictAssignable(firstInput, receiver)
                                != Match.NO;
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(receiver);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || visited.size() >= 256) return Match.UNKNOWN;
            Use current = pending.removeFirst().resolved();
            if (!visited.add(current.name())) continue;
            var lookup = context.model.type(current.name());
            if (lookup instanceof JavaModel.FoundType found) {
                var declaring = context.model.unit(found.declaration().source());
                var owner = context.model.declaration(found.declaration());
                var variables =
                        context.modeledCandidates
                                .owner(found.declaration(), current, 0)
                                .variables();
                for (var member : context.model.members(found.declaration())) {
                    var method = context.model.declaration(member);
                    if (!method.symbol().kind().equals("METHOD")
                            || !method.symbol().name().equals(name)) continue;
                    var executable = declaring.types().get(method.symbol().declaredType());
                    if (executable.kind() != JavaType.Kind.EXECUTABLE) return Match.UNKNOWN;
                    if (method.symbol().modifiers().contains("static")) {
                        if (owner.symbol().kind().equals("INTERFACE")
                                && !current.name().equals(receiver.name())) continue;
                        if (ModeledSelection.arity(
                                executable.parameters().size(),
                                argumentCount,
                                method.symbol().variadic())) statics = true;
                    } else if (instancePossible
                            && ModeledSelection.arity(
                                    executable.parameters().size(),
                                    argumentCount - 1,
                                    method.symbol().variadic())) instances = true;
                }
                if (owner.superclass() >= 0)
                    pending.add(new Use(declaring.types(), owner.superclass(), variables));
                for (int parent : owner.interfaces())
                    pending.add(new Use(declaring.types(), parent, variables));
            } else if (lookup instanceof JavaModel.MissingType && context.binaries != null) {
                var found = context.binaries.find(current.name());
                if (found.isEmpty()) return Match.UNKNOWN;
                var type = found.get();
                try {
                    for (var method : type.methods()) {
                        if (!method.name().equals(name)
                                || (method.access() & Opcodes.ACC_PUBLIC) == 0
                                || (method.access() & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC))
                                        != 0) continue;
                        var shape = JavaBinaryTypes.method(type, method);
                        int count = shape.types().get(shape.executable()).parameters().size();
                        boolean variadic = (method.access() & Opcodes.ACC_VARARGS) != 0;
                        if ((method.access() & Opcodes.ACC_STATIC) != 0) {
                            if ((type.access() & Opcodes.ACC_INTERFACE) != 0
                                    && !current.name().equals(receiver.name())) continue;
                            if (ModeledSelection.arity(count, argumentCount, variadic))
                                statics = true;
                        } else if (instancePossible
                                && ModeledSelection.arity(count, argumentCount - 1, variadic))
                            instances = true;
                    }
                    var hierarchy = JavaBinaryTypes.hierarchy(type);
                    var variables = ModeledInference.binaryVariables(hierarchy, current);
                    if (hierarchy.superclass() >= 0)
                        pending.add(new Use(hierarchy.types(), hierarchy.superclass(), variables));
                    for (int parent : hierarchy.interfaces())
                        pending.add(new Use(hierarchy.types(), parent, variables));
                } catch (IllegalArgumentException unavailable) {
                    return Match.UNKNOWN;
                }
            } else return Match.UNKNOWN;
        }
        return statics && instances ? Match.UNKNOWN : statics ? Match.YES : Match.NO;
    }

    Match polyReturnCompatible(JavaNode poly, Use parameter, Sam descriptor, int depth) {
        Use previous = context.expectedOverrides.put(poly.id(), parameter);
        context.provisionalResults.push(new HashMap<>());
        try {
            if (poly.kind().equals("MEMBER_REFERENCE")) {
                Result selected = context.select(poly);
                return selected instanceof Selected || selected instanceof ExternalSelected
                        ? Match.YES
                        : Match.UNKNOWN;
            }
            JavaNode body = poly.tree("getBody");
            if (body == null) return Match.UNKNOWN;
            boolean voidReturn =
                    descriptor.returns().resolved().type().kind() == JavaType.Kind.VOID;
            if (!body.kind().equals("BLOCK")) {
                if (voidReturn)
                    return Set.of(
                                            "METHOD_INVOCATION",
                                            "NEW_CLASS",
                                            "ASSIGNMENT",
                                            "MULTIPLY_ASSIGNMENT",
                                            "DIVIDE_ASSIGNMENT",
                                            "REMAINDER_ASSIGNMENT",
                                            "PLUS_ASSIGNMENT",
                                            "MINUS_ASSIGNMENT",
                                            "LEFT_SHIFT_ASSIGNMENT",
                                            "RIGHT_SHIFT_ASSIGNMENT",
                                            "UNSIGNED_RIGHT_SHIFT_ASSIGNMENT",
                                            "AND_ASSIGNMENT",
                                            "XOR_ASSIGNMENT",
                                            "OR_ASSIGNMENT",
                                            "PREFIX_INCREMENT",
                                            "PREFIX_DECREMENT",
                                            "POSTFIX_INCREMENT",
                                            "POSTFIX_DECREMENT")
                                    .contains(body.kind())
                            ? Match.YES
                            : Match.NO;
                if (context.modeledInference.invalidConditional(body)) return Match.NO;
                Use returns = context.expression(body);
                return returns == null
                        ? Match.UNKNOWN
                        : context.modeledTypeOps.assignable(
                                returns, descriptor.returns(), depth + 1);
            }
            Match match =
                    voidReturn ? Match.YES : context.modeledInference.abruptCompletion(body, 0);
            if (match != Match.YES) return match;
            ReturnSites sites = context.modeledInference.returnSites(body);
            if (!sites.complete()) return Match.UNKNOWN;
            for (JavaNode node : sites.nodes()) {
                JavaNode value = node.tree("getExpression");
                if (voidReturn != (value == null)) return Match.NO;
                if (!voidReturn) {
                    if (context.modeledInference.invalidConditional(value)) return Match.NO;
                    Use returns = context.expression(value);
                    Match compatible =
                            returns == null
                                    ? Match.UNKNOWN
                                    : context.modeledTypeOps.assignable(
                                            returns, descriptor.returns(), depth + 1);
                    if (compatible == Match.NO) return Match.NO;
                    if (compatible == Match.UNKNOWN) match = Match.UNKNOWN;
                }
            }
            return match;
        } finally {
            context.provisionalResults.pop();
            if (previous == null) context.expectedOverrides.remove(poly.id());
            else context.expectedOverrides.put(poly.id(), previous);
        }
    }

    ReturnSites returnSites(JavaNode body) {
        List<JavaNode> returns = new ArrayList<>();
        ArrayDeque<JavaNode> pending = new ArrayDeque<>();
        pending.push(body);
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || pending.size() > 4096)
                return new ReturnSites(List.of(), false);
            JavaNode node = pending.pop();
            if (Set.of("LAMBDA_EXPRESSION", "METHOD", "CLASS", "INTERFACE", "ENUM", "RECORD")
                    .contains(node.kind())) continue;
            if (node.kind().equals("RETURN")) {
                returns.add(node);
                continue;
            }
            for (Object value : node.fields().values()) {
                if (value instanceof JavaNode child) pending.push(child);
                else if (value instanceof List<?> children)
                    for (Object child : children)
                        if (child instanceof JavaNode tree) pending.push(tree);
            }
        }
        return new ReturnSites(List.copyOf(returns), true);
    }

    Match abruptCompletion(JavaNode statement, int depth) {
        if (statement == null) return Match.NO;
        if (++context.work > 100_000 || depth >= 64) return Match.UNKNOWN;
        return switch (statement.kind()) {
            case "RETURN", "THROW" -> Match.YES;
            case "BLOCK" -> {
                Match completion = Match.NO;
                for (JavaNode child : statement.trees("getStatements")) {
                    Match next = context.modeledInference.abruptCompletion(child, depth + 1);
                    if (next == Match.YES) {
                        completion = Match.YES;
                        break;
                    }
                    if (next == Match.UNKNOWN) completion = Match.UNKNOWN;
                }
                yield completion;
            }
            case "IF" -> {
                Match thenPart =
                        context.modeledInference.abruptCompletion(
                                statement.tree("getThenStatement"), depth + 1);
                Match elsePart =
                        context.modeledInference.abruptCompletion(
                                statement.tree("getElseStatement"), depth + 1);
                yield thenPart == Match.YES && elsePart == Match.YES
                        ? Match.YES
                        : thenPart == Match.UNKNOWN || elsePart == Match.UNKNOWN
                                ? Match.UNKNOWN
                                : Match.NO;
            }
            case "TRY" -> {
                Match cleanup =
                        context.modeledInference.abruptCompletion(
                                statement.tree("getFinallyBlock"), depth + 1);
                if (cleanup == Match.YES || cleanup == Match.UNKNOWN) yield cleanup;
                Match body =
                        context.modeledInference.abruptCompletion(
                                statement.tree("getBlock"), depth + 1);
                for (JavaNode handler : statement.trees("getCatches")) {
                    Match branch =
                            context.modeledInference.abruptCompletion(
                                    handler.tree("getBlock"), depth + 1);
                    if (branch == Match.NO) {
                        body = Match.NO;
                        break;
                    }
                    if (branch == Match.UNKNOWN && body != Match.NO) body = Match.UNKNOWN;
                }
                yield body;
            }
            case "VARIABLE", "EXPRESSION_STATEMENT", "EMPTY_STATEMENT", "ASSERT" -> Match.NO;
            default -> Match.UNKNOWN;
        };
    }
}
