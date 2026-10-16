package com.anatomist.extract;

import static com.anatomist.extract.JavaModeledCallRecovery.*;

import com.anatomist.core.asmsolver.JavaBinaryTypes;
import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;

import java.util.*;

/** Task-local ModeledSelection; shared state remains owned by JavaModeledCallRecovery. */
final class ModeledSelection {
    private final JavaModeledCallRecovery context;

    ModeledSelection(JavaModeledCallRecovery context) {
        this.context = context;
    }

    TypeProjection constructorType(JavaNode site, Receiver receiver, boolean noArguments) {
        boolean reference = site.kind().equals("MEMBER_REFERENCE");
        JavaNode written =
                reference ? site.tree("getQualifierExpression") : site.tree("getIdentifier");
        if (written == null || !reference && !written.kind().equals("PARAMETERIZED_TYPE"))
            return new TypeProjection(receiver.type(), Match.YES);
        boolean explicit = !written.trees("getTypeArguments").isEmpty();
        Use root;
        List<Formal> formals = new ArrayList<>();
        var lookup = context.model.type(receiver.type().name());
        if (lookup instanceof JavaModel.FoundType found) {
            JavaUnit source = context.model.unit(found.declaration().source());
            root =
                    new Use(
                            source.types(),
                            context.model.declaration(found.declaration()).symbol().declaredType());
            for (int argument : root.type().arguments())
                if (source.types().get(argument).kind() == JavaType.Kind.TYPEVAR)
                    formals.add(new Formal(source.types(), argument));
        } else {
            var binary =
                    context.binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : context.binaries.find(receiver.type().name());
            if (binary.isEmpty()) return new TypeProjection(receiver.type(), Match.YES);
            var shape = JavaBinaryTypes.hierarchy(binary.get());
            List<Integer> arguments = new ArrayList<>();
            Map<String, Integer> references = new HashMap<>();
            for (int i = 0; i < shape.types().entries().size(); i++) {
                if (++context.work > 100_000) return new TypeProjection(null, Match.UNKNOWN);
                var type = shape.types().get(i);
                if (type.kind() == JavaType.Kind.TYPEVAR) references.putIfAbsent(type.name(), i);
            }
            for (String name : shape.parameters())
                if (references.containsKey(name)) arguments.add(references.get(name));
            if (arguments.size() != shape.parameters().size())
                return new TypeProjection(null, Match.UNKNOWN);
            var graph = new ArrayList<>(shape.types().entries());
            graph.add(
                    new JavaType(
                            JavaType.Kind.DECLARED,
                            binary.get().name().replace('$', '.'),
                            receiver.type().name(),
                            -1,
                            arguments,
                            List.of(),
                            -1,
                            -1,
                            List.of(),
                            -1,
                            List.of()));
            JavaTypes types = new JavaTypes(graph);
            root = new Use(types, graph.size() - 1);
            for (int argument : arguments) formals.add(new Formal(types, argument));
        }
        if (formals.isEmpty()) return new TypeProjection(receiver.type(), Match.YES);
        Map<String, Use> variables = new LinkedHashMap<>();
        if (explicit) {
            Use actual = receiver.type().resolved();
            if (actual.type().arguments().size() != formals.size())
                return new TypeProjection(null, Match.UNKNOWN);
            for (int i = 0; i < formals.size(); i++)
                variables.put(formals.get(i).name(), actual.argument(i));
        } else {
            Use target;
            if (reference) {
                Sam descriptor =
                        context.modeledInference.sam(context.modeledInference.expected(site));
                target =
                        descriptor == null
                                ? null
                                : context.modeledTypeOps.ground(descriptor.returns());
            } else target = context.modeledInference.writtenExpected(site);
            if (target == null || target.resolved().type().kind() != JavaType.Kind.DECLARED)
                return new TypeProjection(receiver.type(), Match.YES);
            TypeProjection projection = context.modeledTypeOps.projectType(root, target.name(), 0);
            if (projection.match() != Match.YES) return projection;
            Match seeded =
                    context.modeledInference.seedTarget(
                            target, projection.type(), formals, variables, 0, noArguments);
            if (seeded != Match.YES) return new TypeProjection(null, seeded);
        }
        for (Formal formal : formals) {
            Use value = variables.get(formal.name());
            var bounds = formal.types().get(formal.reference()).upperBounds();
            if (value == null && noArguments && bounds.size() == 1) {
                Use upper = new Use(formal.types(), bounds.getFirst(), variables);
                if (!context.modeledInference.unboundTarget(upper, 0)) {
                    value = upper;
                    variables.put(formal.name(), value);
                }
            }
            if (value == null) return new TypeProjection(null, Match.UNKNOWN);
            for (int bound : bounds) {
                Match match =
                        context.modeledTypeOps.assignable(
                                value, new Use(formal.types(), bound, variables), 0);
                if (match != Match.YES) return new TypeProjection(null, match);
            }
        }
        return new TypeProjection(
                explicit
                        ? receiver.type()
                        : new Use(root.types(), root.reference(), Map.copyOf(variables)),
                Match.YES);
    }

    Result referenceResult(JavaNode site, Receiver receiver, boolean constructor, Result result) {
        if (constructor
                && (site.kind().equals("NEW_CLASS") || site.kind().equals("MEMBER_REFERENCE"))) {
            result =
                    switch (result) {
                        case Selected selected ->
                                new Selected(
                                        selected.declaration(),
                                        receiver.type(),
                                        selected.receiver(),
                                        selected.callKind(),
                                        selected.parameters());
                        case ExternalSelected selected ->
                                new ExternalSelected(
                                        selected.target(),
                                        receiver.type(),
                                        selected.receiver(),
                                        selected.callKind(),
                                        selected.parameters());
                        case Unavailable unavailable -> unavailable;
                        case Blocked blocked -> blocked;
                    };
        }
        if (!site.kind().equals("MEMBER_REFERENCE")
                || context.probingReferences.contains(site.id())) return result;
        Use returns =
                result instanceof Selected source
                        ? source.returns()
                        : result instanceof ExternalSelected external ? external.returns() : null;
        if (returns == null) return result;
        var descriptor = context.modeledInference.sam(context.modeledInference.expected(site));
        if (descriptor == null) return new Unavailable("functional-descriptor-unavailable");
        if (descriptor.returns().resolved().type().kind() != JavaType.Kind.VOID
                && context.modeledTypeOps.assignable(
                                constructor ? receiver.type() : returns, descriptor.returns(), 0)
                        != Match.YES)
            return new Blocked("functional-return-incompatible-or-incomplete");
        return result;
    }

    static boolean arity(int parameters, int arguments, boolean variadic) {
        return variadic ? parameters > 0 && arguments >= parameters - 1 : arguments == parameters;
    }

    Applicability applicability(List<Use> actual, List<Use> parameters, boolean variadic) {
        if (actual.size() == parameters.size()) {
            Match strict = context.modeledSelection.arguments(actual, parameters, true);
            if (strict != Match.NO) return new Applicability(strict, 0, parameters);
            Match loose = context.modeledSelection.arguments(actual, parameters, false);
            if (loose != Match.NO) return new Applicability(loose, 1, parameters);
        }
        if (!variadic || !ModeledSelection.arity(parameters.size(), actual.size(), true))
            return new Applicability(Match.NO, 2, parameters);
        var array = parameters.getLast().resolved();
        if (array.type().kind() != JavaType.Kind.ARRAY)
            return new Applicability(Match.UNKNOWN, 2, parameters);
        List<Use> expanded = new ArrayList<>(parameters.subList(0, parameters.size() - 1));
        while (expanded.size() < actual.size()) expanded.add(array.child(array.type().component()));
        Match match = context.modeledSelection.arguments(actual, expanded, false);
        // Zero trailing arguments still compare component types for most-specific selection.
        if (expanded.size() == parameters.size() - 1)
            expanded.add(array.child(array.type().component()));
        return new Applicability(match, 2, List.copyOf(expanded));
    }

    Match arguments(List<Use> actual, List<Use> parameters, boolean strict) {
        if (actual.size() != parameters.size()) return Match.NO;
        Match result = Match.YES;
        for (int i = 0; i < actual.size(); i++) {
            Match match =
                    strict
                            ? context.modeledTypeOps.strictAssignable(
                                    actual.get(i), parameters.get(i))
                            : context.modeledTypeOps.assignable(
                                    actual.get(i), parameters.get(i), 0);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    Candidate mostSpecific(List<Candidate> candidates) {
        if (candidates.size() == 1) return candidates.getFirst();
        Candidate result = null;
        for (Candidate candidate : candidates) {
            boolean specific = true;
            for (Candidate other : candidates) {
                if (candidate == other) continue;
                if (context.modeledSelection.arguments(
                                candidate.parameters(), other.parameters(), true)
                        != Match.YES) {
                    specific = false;
                    break;
                }
                if (context.modeledSelection.arguments(
                                        other.parameters(), candidate.parameters(), true)
                                == Match.YES
                        && !context.modeledSelection.overrides(candidate, other)) {
                    specific = false;
                    break;
                }
            }
            if (specific) {
                if (result != null) return null;
                result = candidate;
            }
        }
        return result;
    }

    boolean overrides(Candidate first, Candidate second) {
        var target = context.model.declaration(second.declaration());
        var declaration = context.model.declaration(first.declaration());
        boolean proven =
                declaration.overrides().targets().stream()
                        .anyMatch(
                                evidence ->
                                        evidence.symbol().owner().equals(target.symbol().owner())
                                                && evidence.symbol()
                                                        .name()
                                                        .equals(target.symbol().name())
                                                && evidence.symbol()
                                                        .descriptor()
                                                        .equals(target.symbol().descriptor()));
        if (proven) return true;
        if (!declaration.complete()
                || !target.complete()
                || target.symbol().modifiers().contains("final")
                || target.symbol().modifiers().contains("private")
                || ModeledCandidates.visibility(declaration.symbol().modifiers())
                        < ModeledCandidates.visibility(target.symbol().modifiers())
                || declaration.symbol().modifiers().contains("static")
                        != target.symbol().modifiers().contains("static")) return false;
        if (!target.symbol().modifiers().contains("public")
                && !target.symbol().modifiers().contains("protected")
                && !ModeledCandidates.packageName(declaration.symbol().owner())
                        .equals(ModeledCandidates.packageName(target.symbol().owner())))
            return false;
        return context.modeledSelection.arguments(first.parameters(), second.parameters(), true)
                        == Match.YES
                && context.modeledSelection.arguments(second.parameters(), first.parameters(), true)
                        == Match.YES
                && context.modeledTypeOps.sourceHierarchy(
                                ModeledTypeOps.namedType(declaration.symbol().owner()),
                                ModeledTypeOps.namedType(target.symbol().owner()),
                                0)
                        == Match.YES
                && context.modeledTypeOps.strictAssignable(first.returns(), second.returns())
                        == Match.YES;
    }

    static boolean overrideVisible(
            List<String> own, List<String> parent, String parentPackage, Set<String> pathPackages) {
        if (parent.contains("private")
                || parent.contains("static")
                || parent.contains("final")
                || ModeledCandidates.visibility(own) < ModeledCandidates.visibility(parent))
            return false;
        return parent.contains("public")
                || parent.contains("protected")
                || pathPackages.stream().allMatch(parentPackage::equals);
    }

    Match overrideSignature(List<Use> own, Use returns, List<Use> parent, Use parentReturns) {
        if (context.modeledSelection.overrideParameters(own, parent) != Match.YES)
            return Match.UNKNOWN;
        return context.modeledSelection.overrideReturns(returns, parentReturns);
    }

    Match overrideParameters(List<Use> own, List<Use> parent) {
        if (own.size() != parent.size()) return Match.NO;
        for (int i = 0; i < own.size(); i++) {
            var match = context.modeledTypeOps.same(own.get(i), parent.get(i), 0);
            if (match == Match.YES) continue;
            var child = own.get(i).resolved();
            var ancestor = parent.get(i).resolved();
            // A raw overriding parameter is a subsignature of its parameterized parent.
            if (child.type().kind() == JavaType.Kind.DECLARED
                    && ancestor.type().kind() == JavaType.Kind.DECLARED
                    && child.type().arguments().isEmpty()
                    && child.name().equals(ancestor.name())) continue;
            return match;
        }
        return Match.YES;
    }

    Match overrideReturns(Use own, Use parent) {
        if (ModeledTypeOps.primitive(own.name())
                || ModeledTypeOps.primitive(parent.name())
                || own.name().equals("void")
                || parent.name().equals("void"))
            return own.name().equals(parent.name()) ? Match.YES : Match.NO;
        return context.modeledTypeOps.strictAssignable(own, parent);
    }

    boolean invalidOverride(
            List<String> own,
            List<String> parent,
            String parentPackage,
            Set<String> pathPackages,
            List<Use> ownParameters,
            Use ownReturns,
            List<Use> parentParameters,
            Use parentReturns) {
        boolean inherited =
                !parent.contains("private")
                        && (parent.contains("public")
                                || parent.contains("protected")
                                || pathPackages.stream().allMatch(parentPackage::equals));
        var parameters =
                context.modeledSelection.overrideParameters(ownParameters, parentParameters);
        boolean erasureClash =
                parameters == Match.NO && ownParameters.size() == parentParameters.size();
        for (int i = 0; erasureClash && i < ownParameters.size(); i++)
            erasureClash = ownParameters.get(i).name().equals(parentParameters.get(i).name());
        return inherited
                && (erasureClash
                        || parameters == Match.YES
                                && (parent.contains("final")
                                        || parent.contains("static")
                                        || ModeledCandidates.visibility(own)
                                                < ModeledCandidates.visibility(parent)
                                        || context.modeledSelection.overrideReturns(
                                                        ownReturns, parentReturns)
                                                == Match.NO));
    }

    Result binary(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots) {
        BinarySearch search =
                context.modeledCandidates.binaryCandidates(
                        site, receiver, constructor, arguments, roots);
        if (search.failure() != null) return search.failure();
        List<BinaryCandidate> candidates = search.candidates();
        int uncertainPhase = search.uncertainPhase(), namedMethods = search.namedMethods();
        boolean unknown = search.incomplete();
        // A javac declaration can support direct generic constraints only when no competing
        // callable could have been selected using the placeholder type of a modeled input.
        if (!unknown
                && namedMethods == 1
                && candidates.isEmpty()
                && !site.kind().equals("MEMBER_REFERENCE")) {
            Result confirmed =
                    context.modeledSelection.confirmedBinary(site, receiver, constructor);
            if (confirmed != null) return confirmed;
        }
        int phase =
                candidates.stream()
                        .mapToInt(BinaryCandidate::phase)
                        .min()
                        .orElse(Integer.MAX_VALUE);
        if (unknown || uncertainPhase != Integer.MAX_VALUE && uncertainPhase <= phase)
            return new Blocked("ambiguous-or-incomplete-binary-overload");
        if (candidates.isEmpty()) return new Blocked("binary-member-unavailable");
        var best = candidates.stream().filter(candidate -> candidate.phase() == phase).toList();
        BinaryCandidate selected = null;
        for (var candidate : best) {
            boolean specific = true;
            for (var other : best) {
                if (candidate == other) continue;
                if (context.modeledSelection.binaryOverrides(candidate, other)) continue;
                if (context.modeledSelection.arguments(
                                        candidate.parameters(), other.parameters(), true)
                                != Match.YES
                        || context.modeledSelection.arguments(
                                                other.parameters(), candidate.parameters(), true)
                                        == Match.YES
                                && !context.modeledSelection.binaryOverrides(candidate, other)) {
                    specific = false;
                    break;
                }
            }
            if (specific) {
                if (selected != null) return new Blocked("ambiguous-or-incomplete-binary-overload");
                selected = candidate;
            }
        }
        return selected == null
                ? new Blocked("ambiguous-or-incomplete-binary-overload")
                : selected.selected();
    }

    Result selectMixed(List<MixedCandidate> candidates, int uncertainPhase) {
        int phase =
                candidates.stream().mapToInt(MixedCandidate::phase).min().orElse(Integer.MAX_VALUE);
        if (uncertainPhase != Integer.MAX_VALUE && uncertainPhase <= phase)
            return new Blocked("ambiguous-or-incomplete-mixed-overload");
        if (candidates.isEmpty()) return new Blocked("modeled-member-unavailable");
        var best = candidates.stream().filter(candidate -> candidate.phase() == phase).toList();
        MixedCandidate selected = null;
        for (MixedCandidate candidate : best) {
            boolean specific = true;
            for (MixedCandidate other : best) {
                if (candidate == other || context.modeledSelection.mixedOverrides(candidate, other))
                    continue;
                if (context.modeledSelection.arguments(
                                        candidate.parameters(), other.parameters(), true)
                                != Match.YES
                        || context.modeledSelection.arguments(
                                        other.parameters(), candidate.parameters(), true)
                                == Match.YES) {
                    specific = false;
                    break;
                }
            }
            if (specific) {
                if (selected != null) return new Blocked("ambiguous-or-incomplete-mixed-overload");
                selected = candidate;
            }
        }
        return selected == null
                ? new Blocked("ambiguous-or-incomplete-mixed-overload")
                : selected.selected();
    }

    boolean mixedOverrides(MixedCandidate first, MixedCandidate second) {
        if (first.selected() instanceof Selected source
                && second.selected() instanceof Selected parent)
            return context.modeledSelection.overrides(
                    new Candidate(
                            source.declaration(),
                            source.returns(),
                            source.callKind(),
                            first.parameters(),
                            first.phase()),
                    new Candidate(
                            parent.declaration(),
                            parent.returns(),
                            parent.callKind(),
                            second.parameters(),
                            second.phase()));
        if (first.selected() instanceof ExternalSelected binary
                && second.selected() instanceof ExternalSelected parent)
            return context.modeledSelection.binaryOverrides(
                    new BinaryCandidate(
                            binary,
                            first.parameters(),
                            first.phase(),
                            first.access(),
                            first.descriptor()),
                    new BinaryCandidate(
                            parent,
                            second.parameters(),
                            second.phase(),
                            second.access(),
                            second.descriptor()));
        if (first.selected() instanceof ExternalSelected binary
                && second.selected() instanceof Selected parent) {
            var declaration = context.model.declaration(parent.declaration());
            String binaryOwner =
                    binary.target().substring(0, binary.target().indexOf('#')).replace('$', '.');
            if (!declaration.complete()
                    || !declaration.symbol().modifiers().contains("public")
                    || declaration.symbol().modifiers().contains("final")
                    || !binary.callKind().equals(parent.callKind())
                    || context.modeledSelection.arguments(
                                    first.parameters(), second.parameters(), true)
                            != Match.YES
                    || context.modeledSelection.arguments(
                                    second.parameters(), first.parameters(), true)
                            != Match.YES
                    || context.modeledTypeOps.sourceHierarchy(
                                    ModeledTypeOps.namedType(binaryOwner),
                                    ModeledTypeOps.namedType(declaration.symbol().owner()),
                                    0)
                            != Match.YES
                    || context.modeledTypeOps.strictAssignable(binary.returns(), parent.returns())
                            != Match.YES) return false;
            if (first.descriptor().equals(second.descriptor())) return true;
            var type =
                    context.binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : context.binaries.find(binaryOwner);
            String name = declaration.symbol().name();
            return type.isPresent()
                    && type.get().methods().stream()
                            .anyMatch(
                                    method ->
                                            method.name().equals(name)
                                                    && method.descriptor()
                                                            .equals(second.descriptor())
                                                    && (method.access() & Opcodes.ACC_BRIDGE) != 0);
        }
        if (!(first.selected() instanceof Selected source)
                || !(second.selected() instanceof ExternalSelected parent)) return false;
        var declaration = context.model.declaration(source.declaration());
        String parentOwner =
                parent.target().substring(0, parent.target().indexOf('#')).replace('$', '.');
        return declaration.complete()
                && declaration.symbol().modifiers().contains("public")
                && (second.access() & Opcodes.ACC_FINAL) == 0
                && source.callKind().equals(parent.callKind())
                && context.modeledSelection.arguments(first.parameters(), second.parameters(), true)
                        == Match.YES
                && context.modeledSelection.arguments(second.parameters(), first.parameters(), true)
                        == Match.YES
                && context.modeledTypeOps.sourceHierarchy(
                                ModeledTypeOps.namedType(declaration.symbol().owner()),
                                ModeledTypeOps.namedType(parentOwner),
                                0)
                        == Match.YES
                && context.modeledTypeOps.strictAssignable(source.returns(), parent.returns())
                        == Match.YES;
    }

    boolean binaryOverrides(BinaryCandidate first, BinaryCandidate second) {
        String firstOwner =
                first.selected().target().substring(0, first.selected().target().indexOf('#'));
        String secondOwner =
                second.selected().target().substring(0, second.selected().target().indexOf('#'));
        String firstCallable =
                first.selected().target().substring(first.selected().target().indexOf('#'));
        String secondCallable =
                second.selected().target().substring(second.selected().target().indexOf('#'));
        if (firstOwner.equals(secondOwner)
                || (second.access() & Opcodes.ACC_FINAL) != 0
                || !first.selected().callKind().equals(second.selected().callKind())) return false;
        boolean signature = firstCallable.equals(secondCallable);
        if (!signature) {
            String firstName = firstCallable.substring(1, firstCallable.indexOf('('));
            String secondName = secondCallable.substring(1, secondCallable.indexOf('('));
            if (!firstName.equals(secondName)
                    || context.binaries == null
                    || second.descriptor().isEmpty()
                    || context.modeledSelection.arguments(
                                    first.parameters(), second.parameters(), true)
                            != Match.YES
                    || context.modeledSelection.arguments(
                                    second.parameters(), first.parameters(), true)
                            != Match.YES) return false;
            var type = context.binaries.find(firstOwner);
            signature =
                    type.isPresent()
                            && type.get().methods().stream()
                                    .anyMatch(
                                            method ->
                                                    method.name().equals(firstName)
                                                            && method.descriptor()
                                                                    .equals(second.descriptor())
                                                            && (method.access()
                                                                            & Opcodes.ACC_BRIDGE)
                                                                    != 0);
        }
        return signature
                && context.modeledTypeOps.sourceHierarchy(
                                ModeledTypeOps.namedType(firstOwner.replace('$', '.')),
                                ModeledTypeOps.namedType(secondOwner.replace('$', '.')),
                                0)
                        == Match.YES
                && context.modeledTypeOps.strictAssignable(
                                first.selected().returns(), second.selected().returns())
                        == Match.YES;
    }

    Result confirmedBinary(JavaNode site, Receiver receiver, boolean constructor) {
        var binding = context.unit.bindings().get(site.id());
        if (binding == null
                || !binding.declarationResolved()
                || !binding.kind().equals(constructor ? "CONSTRUCTOR" : "METHOD")
                || !binding.owner().equals(receiver.type().name())
                || binding.expressionType() < 0) return null;
        var found = context.binaries.find(binding.owner());
        if (found.isEmpty()) return null;
        var owner = found.get();
        if (owner.methods().size() > 16_384) return new Unavailable("recovery-budget");
        var methods =
                owner.methods().stream()
                        .filter(
                                method ->
                                        method.name().equals(binding.name())
                                                && method.descriptor().equals(binding.descriptor()))
                        .toList();
        if (methods.size() != 1) return null;
        var method = methods.getFirst();
        if ((method.access() & Opcodes.ACC_PUBLIC) == 0
                || (method.access() & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0)
            return null;
        boolean isStatic = (method.access() & Opcodes.ACC_STATIC) != 0;
        if (!constructor && receiver.typeQualifier() && !isStatic) return null;
        JavaBinaryTypes.MethodShape shape;
        try {
            shape = JavaBinaryTypes.method(owner, method);
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
        Map<String, Use> variables = new LinkedHashMap<>();
        var actual = receiver.type().resolved();
        for (int i = 0; i < shape.classParameters().size(); i++)
            if (i < actual.type().arguments().size())
                variables.put(shape.classParameters().get(i), actual.argument(i));
        var executable = shape.types().get(shape.executable());
        var parameters = executable.parameters();
        for (String formal : shape.methodParameters()) variables.remove(formal);
        boolean variadic = (method.access() & Opcodes.ACC_VARARGS) != 0;
        var arguments = site.trees("getArguments");
        if (!variadic && arguments.size() != parameters.size()
                || variadic && (parameters.isEmpty() || arguments.size() < parameters.size() - 1))
            return null;
        if (!shape.methodParameters().isEmpty()) {
            var actualArguments = new ArrayList<Use>();
            for (JavaNode argument : arguments) {
                Use value = context.expression(argument);
                if (value == null) break;
                actualArguments.add(value);
            }
            if (actualArguments.size() == arguments.size()) {
                Match constraints =
                        context.modeledInference.methodArguments(
                                site,
                                actualArguments,
                                parameters,
                                executable.returns(),
                                shape.types(),
                                shape.methodParameterTypes(),
                                variables,
                                variadic);
                if (constraints == Match.NO)
                    return new Blocked("compiler-binary-bound-incompatible-with-modeled-input");
                if (constraints == Match.UNKNOWN)
                    for (String formal : shape.methodParameters()) variables.remove(formal);
            } else if (!site.trees("getTypeArguments").isEmpty()) {
                return new Blocked("explicit-binary-constraints-unavailable");
            }
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (++context.work > 100_000) return new Unavailable("recovery-budget");
            Use argument = context.expression(arguments.get(i));
            if (argument == null)
                continue; // Missing local evidence does not disprove a selected declaration.
            Use parameter =
                    new Use(
                            shape.types(),
                            parameters.get(Math.min(i, parameters.size() - 1)),
                            variables);
            // Only a direct reference-type formal with agreeing inputs is inferred here.
            // Other constraints retain the declaration without manufacturing a concrete return.
            if (parameter.type().kind() == JavaType.Kind.TYPEVAR
                    && shape.methodParameterTypes().contains(parameter.reference())
                    && argument.resolved().type().kind() == JavaType.Kind.DECLARED) {
                Use previous = variables.get(parameter.type().name());
                if (previous == null) variables.put(parameter.type().name(), argument);
                else if (context.modeledTypeOps.same(previous, argument, 0) != Match.YES)
                    return new Unavailable("binary-generic-constraints-unavailable");
                for (int bound : parameter.type().upperBounds())
                    if (context.modeledTypeOps.assignable(
                                    argument, new Use(shape.types(), bound, variables), 0)
                            == Match.NO)
                        return new Blocked("compiler-binary-bound-incompatible-with-modeled-input");
            }
            Match match = context.modeledTypeOps.assignable(argument, parameter, 0);
            if (variadic
                    && i >= parameters.size() - 1
                    && (i >= parameters.size() || match == Match.NO)) {
                if (parameter.type().kind() != JavaType.Kind.ARRAY) return null;
                match =
                        context.modeledTypeOps.assignable(
                                argument, parameter.child(parameter.type().component()), 0);
            }
            if (match == Match.NO)
                return new Blocked("compiler-binary-target-incompatible-with-modeled-input");
        }
        var symbol =
                new JavaSymbol(
                        constructor ? "CONSTRUCTOR" : "METHOD",
                        owner.name().replace('$', '.'),
                        method.name(),
                        method.descriptor(),
                        shape.executable(),
                        List.of(),
                        variadic);
        return new ExternalSelected(
                JavaTypeNames.binaryCallableId(shape.types(), symbol, owner.name()),
                new Use(shape.types(), executable.returns(), variables),
                receiver.type().name(),
                constructor ? "CONSTRUCTOR" : isStatic ? "STATIC" : "INSTANCE",
                parameters.stream()
                        .map(parameter -> new Use(shape.types(), parameter, Map.copyOf(variables)))
                        .toList());
    }

    boolean externalPolyInvocation(JavaNode site, ExternalSelected selected) {
        if (!context.modeledInference.siteArgument(site)
                || !site.trees("getTypeArguments").isEmpty()) return false;
        if (context.modeledInference.genericInvocation(site) != null) return true;
        Boolean cached = context.externalPolyReturns.get(selected.target());
        if (cached != null) return cached;
        int separator = selected.target().indexOf('#');
        if (separator < 0 || context.binaries == null) return false;
        var owner = context.binaries.find(selected.target().substring(0, separator));
        if (owner.isEmpty()) return false;
        boolean poly = false;
        String name =
                selected.target()
                        .substring(separator + 1, selected.target().indexOf('(', separator));
        for (var method : owner.get().methods()) {
            if (++context.work > 100_000) return false;
            if (!method.name().equals(name)
                    || method.signature() == null
                    || (method.access() & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0)
                continue;
            try {
                var shape = JavaBinaryTypes.method(owner.get(), method);
                var symbol =
                        new JavaSymbol(
                                "METHOD",
                                owner.get().name().replace('$', '.'),
                                method.name(),
                                method.descriptor(),
                                shape.executable(),
                                List.of(),
                                (method.access() & Opcodes.ACC_VARARGS) != 0);
                if (!JavaTypeNames.binaryCallableId(shape.types(), symbol, owner.get().name())
                        .equals(selected.target())) continue;
                var executable = shape.types().get(shape.executable());
                poly =
                        context.modeledInference.mentionsFormal(
                                new Use(shape.types(), executable.returns()),
                                Set.copyOf(shape.methodParameterTypes()),
                                0);
                break;
            } catch (IllegalArgumentException incomplete) {
                return false;
            }
        }
        if (context.externalPolyReturns.size() >= 4096)
            context.externalPolyReturns.remove(
                    context.externalPolyReturns.keySet().iterator().next());
        context.externalPolyReturns.put(selected.target(), poly);
        return poly;
    }
}
