package com.anatomist.extract;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.asmsolver.JavaBinaryTypes;
import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;

import java.util.*;

/**
 * Bounded source-use recovery for detached modeled members, without pretending javac selected them.
 */
final class JavaModeledCallRecovery {
    sealed interface Result permits Selected, ExternalSelected, Unavailable, Blocked {}

    record Selected(
            JavaModel.DeclarationRef declaration,
            Use returns,
            String receiver,
            String callKind,
            List<Use> parameters)
            implements Result {}

    record Unavailable(String reason) implements Result {}

    record ExternalSelected(
            String target, Use returns, String receiver, String callKind, List<Use> parameters)
            implements Result {}

    record Blocked(String reason) implements Result {}

    private enum Match {
        YES,
        NO,
        UNKNOWN
    }

    private record Capture(int site, String path) {}

    record Use(JavaTypes types, int reference, Map<String, Use> variables, Capture capture) {
        Use(JavaTypes types, int reference, Map<String, Use> variables) {
            this(types, reference, variables, null);
        }

        Use(JavaTypes types, int reference) {
            this(types, reference, Map.of());
        }

        JavaType type() {
            return types.get(reference);
        }

        Use resolved() {
            Use current = this;
            for (int depth = 0; depth < 64; depth++) {
                if (current.type().kind() != JavaType.Kind.TYPEVAR) return current;
                Use next = current.variables.get(current.type().name());
                if (next == null || next == current) return current;
                // The capture of '?' also has the declaration's implicit upper bound. Keep
                // that formal when the wildcard supplies no stronger readable upper bound.
                if (next.type().kind() == JavaType.Kind.WILDCARD
                        && next.type().upperBounds().isEmpty()
                        && !JavaTypeNames.erased(current.types, current.reference)
                                .equals("java.lang.Object")) return current;
                current = next;
            }
            throw new IllegalArgumentException("modeled type substitution exceeds budget");
        }

        String name() {
            Use use = resolved();
            return JavaTypeNames.erased(use.types, use.reference);
        }

        Use child(int child) {
            return new Use(types, child, variables, capture);
        }

        private Use argument(int index) {
            return new Use(
                    types,
                    type().arguments().get(index),
                    variables,
                    capture == null
                            ? null
                            : new Capture(capture.site(), capture.path() + "/" + index));
        }
    }

    private record Receiver(Use type, boolean typeQualifier, List<Use> roots) {
        private Receiver(Use type, boolean typeQualifier) {
            this(type, typeQualifier, List.of(type));
        }
    }

    private record Nearest(int depth, String owner, JavaModel.DeclarationRef declaration) {}

    private record CallableName(String owner, String name, boolean staticOnly) {}

    private final Map<CallableName, Boolean> callableNames = new HashMap<>();
    private final Map<String, List<Use>> importedRoots = new HashMap<>();

    private record Sam(List<Use> parameters, Use returns) {}

    private record TypeProjection(Use type, Match match) {}

    private record Owner(
            JavaModel.DeclarationRef declaration, Map<String, Use> variables, int depth) {}

    private record LocatedType(Use type, int depth) {}

    private record SourceParents(List<Owner> owners, boolean incomplete) {}

    private final Map<String, Boolean> binarySourceParents = new LinkedHashMap<>();

    private record Candidate(
            JavaModel.DeclarationRef declaration,
            Use returns,
            String callKind,
            List<Use> parameters,
            int phase) {}

    private record Applicability(Match match, int phase, List<Use> parameters) {}

    private record BinaryCandidate(
            ExternalSelected selected,
            List<Use> parameters,
            int phase,
            int access,
            String descriptor) {}

    private record BinarySearch(
            List<BinaryCandidate> candidates,
            int uncertainPhase,
            int namedMethods,
            boolean incomplete,
            Result failure) {
        private static BinarySearch failed(Result failure) {
            return new BinarySearch(List.of(), 0, 0, true, failure);
        }
    }

    private record HiddenSource(String owner, String parameters) {}

    private record MixedCandidate(
            Result selected, List<Use> parameters, int phase, int access, String descriptor) {}

    private static final Map<String, String> BOXES =
            Map.of(
                    "boolean",
                    "java.lang.Boolean",
                    "byte",
                    "java.lang.Byte",
                    "short",
                    "java.lang.Short",
                    "int",
                    "java.lang.Integer",
                    "long",
                    "java.lang.Long",
                    "char",
                    "java.lang.Character",
                    "float",
                    "java.lang.Float",
                    "double",
                    "java.lang.Double");
    private final JavaModel model;
    private final JavaUnit unit;
    private final JavaBinaryRepository binaries;
    private final Map<Integer, Result> results = new HashMap<>();
    private final Deque<Map<Integer, Result>> provisionalResults = new ArrayDeque<>();
    private final Set<Integer> active = new HashSet<>();
    private int work;
    private final Map<Use, JavaNode> polyNodes = new IdentityHashMap<>();
    private final Map<Integer, Use> expectedOverrides = new HashMap<>();
    private final Set<Integer> probingReferences = new HashSet<>();

    JavaModeledCallRecovery(JavaModel model, JavaUnit unit) {
        this(model, unit, null);
    }

    JavaModeledCallRecovery(JavaModel model, JavaUnit unit, JavaBinaryRepository binaries) {
        this.model = model;
        this.unit = unit;
        this.binaries = binaries;
    }

    record CallbackSignature(Use functionalType, List<Use> parameters) {
        CallbackSignature {
            parameters = List.copyOf(parameters);
        }
    }

    /** The selected modeled call supplies a target, independently of javac attribution. */
    CallbackSignature callbackSignature(JavaNode site) {
        work = 0;
        if (!site.kind().equals("LAMBDA_EXPRESSION")) return null;
        try {
            Use target = expected(site);
            Sam descriptor = sam(target);
            if (descriptor == null
                    || descriptor.parameters().size() != site.trees("getParameters").size())
                return null;
            return new CallbackSignature(target.resolved(), descriptor.parameters());
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
    }

    private Map<Integer, Result> resultCache() {
        return provisionalResults.isEmpty() ? results : provisionalResults.peek();
    }

    enum InputEvidence {
        PRESENT,
        ABSENT,
        INCOMPLETE
    }

    private static final class EvidenceBudgetExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        EvidenceBudgetExceeded() {
            super("modeled input evidence exceeds budget");
        }
    }

    /** A new source call owns a fresh budget; recursive resolution shares it. */
    Result resolveSite(JavaNode site) {
        work = 0;
        try {
            return resolve(site);
        } catch (EvidenceBudgetExceeded exhausted) {
            return new Blocked("recovery-budget");
        }
    }

    InputEvidence inputEvidence(JavaNode site) {
        work = 0;
        try {
            return hasModeledInput(site) ? InputEvidence.PRESENT : InputEvidence.ABSENT;
        } catch (EvidenceBudgetExceeded exhausted) {
            return InputEvidence.INCOMPLETE;
        }
    }

    private Result resolve(JavaNode site) {
        if (!model.hasModeledDeclarations()) return new Unavailable("no-modeled-declarations");
        Result old = resultCache().get(site.id());
        if (old != null) return old;
        if (++work > 100_000 || active.size() >= 64 || !active.add(site.id()))
            return new Unavailable("recovery-budget");
        try {
            Result result = select(site);
            resultCache().put(site.id(), result);
            return result;
        } finally {
            active.remove(site.id());
        }
    }

    private Result select(JavaNode site) {
        boolean reference = site.kind().equals("MEMBER_REFERENCE");
        boolean constructor =
                site.kind().equals("NEW_CLASS")
                        || reference && site.text("getMode").equals("NEW")
                        || explicitConstructorName(site) != null;
        if (!constructor && !reference && !site.kind().equals("METHOD_INVOCATION"))
            return new Unavailable("unsupported-recovery-site");
        var receiver = receiver(site);
        if (receiver == null) return new Unavailable("receiver-unavailable");
        String name = constructor ? "<init>" : methodName(site);
        List<Use> argumentTypes = new ArrayList<>();
        if (reference) {
            var sam = sam(expected(site));
            if (sam == null) return new Unavailable("functional-descriptor-unavailable");
            argumentTypes.addAll(sam.parameters());
            if (!constructor && receiver.typeQualifier) {
                var staticSelection =
                        staticReference(
                                receiver.type,
                                name,
                                argumentTypes.size(),
                                argumentTypes.isEmpty() ? null : argumentTypes.getFirst());
                if (staticSelection == Match.UNKNOWN)
                    return new Blocked("ambiguous-static-or-unbound-reference");
                if (staticSelection == Match.NO) {
                    if (argumentTypes.isEmpty()
                            || strictAssignable(argumentTypes.getFirst(), receiver.type)
                                    != Match.YES)
                        return new Unavailable("unbound-reference-receiver-unavailable");
                    receiver = new Receiver(readableReceiver(argumentTypes.removeFirst()), false);
                }
            }
        } else {
            for (var argument : site.trees("getArguments")) {
                var type = expression(argument);
                if (type == null) return new Unavailable("argument-type-unavailable");
                argumentTypes.add(type);
            }
        }
        if (constructor) {
            TypeProjection constructed = constructorType(site, receiver, argumentTypes.isEmpty());
            if (constructed.match() == Match.NO)
                return new Blocked("constructor-type-argument-incompatible");
            if (constructed.match() == Match.UNKNOWN)
                return new Unavailable("constructor-target-type-unavailable");
            receiver = new Receiver(constructed.type(), receiver.typeQualifier());
        }
        var lookup = model.type(receiver.type.name());
        boolean modeledInput = hasModeledInput(site) || expectedOverrides.containsKey(site.id());
        JavaBinding binding = unit.bindings().get(site.id());
        SourceParents reentry =
                !constructor
                                && lookup instanceof JavaModel.MissingType
                                && (modeledInput
                                        || binding == null
                                        || !binding.declarationResolved())
                                && binaryHasSourceParent(receiver.type().name())
                        ? sourceParents(receiver.type(), 0)
                        : new SourceParents(List.of(), false);
        if (!(lookup instanceof JavaModel.FoundType) && reentry.owners().isEmpty())
            return lookup instanceof JavaModel.MissingType && modeledInput
                    ? referenceResult(
                            site,
                            receiver,
                            constructor,
                            binary(site, receiver, constructor, argumentTypes, receiver.roots()))
                    : new Unavailable("receiver-unavailable-or-ambiguous");
        List<Candidate> applicable = new ArrayList<>();
        int uncertainPhase = reentry.incomplete() ? 0 : Integer.MAX_VALUE;
        boolean modeledPotential = modeledInput;
        boolean sourceNamedMember = false;
        List<Use> binaryParents = new ArrayList<>();
        List<HiddenSource> hiddenSources = new ArrayList<>();
        ArrayDeque<Owner> pending = new ArrayDeque<>();
        pending.addAll(reentry.owners());
        for (Use root : receiver.roots()) {
            var rootLookup = model.type(root.name());
            if (rootLookup instanceof JavaModel.FoundType source)
                pending.add(owner(source.declaration(), root, 0));
            else if (rootLookup instanceof JavaModel.AmbiguousType) uncertainPhase = 0;
            else binaryParents.add(root);
        }
        Set<String> visited = new HashSet<>();
        Map<String, Nearest> nearest = new HashMap<>();
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256)
                return new Unavailable("recovery-budget");
            var current = pending.removeFirst();
            var owner = model.declaration(current.declaration);
            String ownerName = owner.canonicalName();
            if (!visited.add(ownerName)) continue;
            JavaUnit declaringUnit = model.unit(current.declaration.source());
            for (var member : model.members(current.declaration)) {
                if (++work > 100_000) return new Unavailable("recovery-budget");
                var declaration = model.declaration(member);
                if (!declaration.symbol().kind().equals(constructor ? "CONSTRUCTOR" : "METHOD")
                        || !declaration.symbol().name().equals(name)) continue;
                sourceNamedMember = true;
                var executable = declaringUnit.types().get(declaration.symbol().declaredType());
                if (executable.kind() != JavaType.Kind.EXECUTABLE) {
                    uncertainPhase = 0;
                    continue;
                }
                boolean variadic = declaration.symbol().variadic();
                if (!arity(executable.parameters().size(), argumentTypes.size(), variadic))
                    continue;
                String signature = name + declaration.symbol().descriptor();
                Nearest closer =
                        nearest.putIfAbsent(
                                signature, new Nearest(current.depth, ownerName, member));
                if (closer != null
                        && closer.depth() < current.depth
                        && sourceHierarchy(namedType(closer.owner()), namedType(ownerName), 0)
                                == Match.YES) {
                    var child = model.declaration(closer.declaration());
                    var parentModifiers = declaration.symbol().modifiers();
                    boolean inherited =
                            !parentModifiers.contains("private")
                                    && (parentModifiers.contains("public")
                                            || parentModifiers.contains("protected")
                                            || packageName(closer.owner())
                                                    .equals(packageName(ownerName)));
                    if (inherited
                            && (parentModifiers.contains("final")
                                    || visibility(child.symbol().modifiers())
                                            < visibility(parentModifiers)
                                    || child.symbol().modifiers().contains("static")
                                            != parentModifiers.contains("static")))
                        return new Blocked("illegal-source-member-shadow");
                    if (inherited) continue;
                }
                if (declaration.origin() == JavaDeclaration.Origin.MODEL) modeledPotential = true;
                if (!accessible(owner, declaration, site, receiver, current.depth)) {
                    hiddenSources.add(
                            new HiddenSource(
                                    declaration.symbol().owner(),
                                    parameterDescriptor(declaration.symbol().descriptor())));
                    continue;
                }
                Map<String, Use> variables = new LinkedHashMap<>(current.variables);
                var formals =
                        declaration.members().stream()
                                .filter(
                                        parameter ->
                                                parameter.symbol().kind().equals("TYPE_PARAMETER"))
                                .toList();
                if (!formals.isEmpty()) {
                    Match constraints =
                            methodArguments(
                                    site,
                                    argumentTypes,
                                    executable.parameters(),
                                    executable.returns(),
                                    declaringUnit.types(),
                                    formals.stream()
                                            .map(formal -> formal.symbol().declaredType())
                                            .toList(),
                                    variables,
                                    variadic);
                    if (constraints != Match.YES) {
                        if (constraints == Match.UNKNOWN) uncertainPhase = 0;
                        continue;
                    }
                }
                var snapshot = Map.copyOf(variables);
                var parameterTypes =
                        executable.parameters().stream()
                                .map(
                                        parameter ->
                                                new Use(declaringUnit.types(), parameter, snapshot))
                                .toList();
                var match = applicability(argumentTypes, parameterTypes, variadic);
                if (match.match() == Match.UNKNOWN)
                    uncertainPhase = Math.min(uncertainPhase, match.phase());
                else if (match.match() == Match.YES)
                    applicable.add(
                            new Candidate(
                                    member,
                                    new Use(declaringUnit.types(), executable.returns(), snapshot),
                                    constructor
                                            ? "CONSTRUCTOR"
                                            : declaration.symbol().modifiers().contains("static")
                                                    ? "STATIC"
                                                    : "INSTANCE",
                                    match.parameters(),
                                    match.phase()));
                if (match.match() != Match.NO
                        && declaration.origin() == JavaDeclaration.Origin.MODEL)
                    modeledPotential = true;
            }
            if (constructor) continue;
            List<Integer> parents = new ArrayList<>(owner.interfaces());
            if (owner.superclass() >= 0) parents.addFirst(owner.superclass());
            else if (!receiver.typeQualifier()
                    && Set.of("INTERFACE", "ANNOTATION_TYPE").contains(owner.symbol().kind()))
                // Interface-typed expressions also expose Object's public instance methods.
                // binaryCandidates filters out protected members such as clone/finalize.
                binaryParents.add(namedType("java.lang.Object"));
            for (int parent : parents) {
                Use type = new Use(declaringUnit.types(), parent, current.variables);
                var parentLookup = model.type(type.name());
                if (parentLookup instanceof JavaModel.FoundType source)
                    pending.add(owner(source.declaration(), type, current.depth + 1));
                else if (parentLookup instanceof JavaModel.AmbiguousType) uncertainPhase = 0;
                else if (parentLookup instanceof JavaModel.MissingType) {
                    binaryParents.add(type);
                    if (binaryHasSourceParent(type.name())) {
                        SourceParents parentsFromBinary = sourceParents(type, current.depth + 1);
                        pending.addAll(parentsFromBinary.owners());
                        if (parentsFromBinary.incomplete()) uncertainPhase = 0;
                    }
                }
            }
        }
        if (!constructor && modeledPotential && !binaryParents.isEmpty() && binaries != null) {
            BinarySearch search =
                    binaryCandidates(site, receiver, false, argumentTypes, binaryParents, visited);
            if (search.failure() != null) return search.failure();
            List<MixedCandidate> candidates = new ArrayList<>();
            for (Candidate candidate : applicable)
                candidates.add(
                        new MixedCandidate(
                                new Selected(
                                        candidate.declaration(),
                                        candidate.returns(),
                                        receiver.type.name(),
                                        candidate.callKind(),
                                        candidate.parameters()),
                                candidate.parameters(),
                                candidate.phase(),
                                0,
                                model.declaration(candidate.declaration()).symbol().descriptor()));
            boolean hiddenBinary = false;
            for (BinaryCandidate candidate : search.candidates()) {
                String ownerName =
                        candidate
                                .selected()
                                .target()
                                .substring(0, candidate.selected().target().indexOf('#'))
                                .replace('$', '.');
                boolean hidden = false;
                for (HiddenSource source : hiddenSources) {
                    if (source.parameters().equals(parameterDescriptor(candidate.descriptor()))
                            && sourceHierarchy(namedType(source.owner()), namedType(ownerName), 0)
                                    == Match.YES) {
                        hidden = true;
                        break;
                    }
                }
                if (hidden) hiddenBinary = true;
                else
                    candidates.add(
                            new MixedCandidate(
                                    candidate.selected(),
                                    candidate.parameters(),
                                    candidate.phase(),
                                    candidate.access(),
                                    candidate.descriptor()));
            }
            if (hiddenBinary && candidates.isEmpty())
                return new Blocked("inaccessible-modeled-shadow");
            int uncertainty =
                    search.incomplete() ? 0 : Math.min(uncertainPhase, search.uncertainPhase());
            if (!sourceNamedMember
                    && candidates.isEmpty()
                    && uncertainty != 0
                    && search.namedMethods() == 1
                    && !site.kind().equals("MEMBER_REFERENCE")) {
                Result confirmed = confirmedBinary(site, receiver, false);
                if (confirmed != null) return referenceResult(site, receiver, false, confirmed);
            }
            return referenceResult(site, receiver, false, selectMixed(candidates, uncertainty));
        }
        if (!modeledPotential) return new Unavailable("non-modeled-recovery-target");
        int phase = applicable.stream().mapToInt(Candidate::phase).min().orElse(Integer.MAX_VALUE);
        if (uncertainPhase != Integer.MAX_VALUE && uncertainPhase <= phase)
            return new Blocked("ambiguous-or-incomplete-overload");
        if (applicable.isEmpty()) return new Blocked("modeled-member-unavailable-or-inaccessible");
        var best = applicable.stream().filter(candidate -> candidate.phase() == phase).toList();
        Candidate candidate = mostSpecific(best);
        if (candidate == null) return new Blocked("ambiguous-or-incomplete-overload");
        if (!modeledInput
                && model.declaration(candidate.declaration).origin()
                        != JavaDeclaration.Origin.MODEL)
            return new Unavailable("non-modeled-recovery-target");
        return referenceResult(
                site,
                receiver,
                constructor,
                new Selected(
                        candidate.declaration,
                        candidate.returns,
                        receiver.type.name(),
                        candidate.callKind,
                        candidate.parameters));
    }

    private TypeProjection constructorType(JavaNode site, Receiver receiver, boolean noArguments) {
        boolean reference = site.kind().equals("MEMBER_REFERENCE");
        JavaNode written =
                reference ? site.tree("getQualifierExpression") : site.tree("getIdentifier");
        if (written == null || !reference && !written.kind().equals("PARAMETERIZED_TYPE"))
            return new TypeProjection(receiver.type(), Match.YES);
        boolean explicit = !written.trees("getTypeArguments").isEmpty();
        Use root;
        List<Formal> formals = new ArrayList<>();
        var lookup = model.type(receiver.type().name());
        if (lookup instanceof JavaModel.FoundType found) {
            JavaUnit source = model.unit(found.declaration().source());
            root =
                    new Use(
                            source.types(),
                            model.declaration(found.declaration()).symbol().declaredType());
            for (int argument : root.type().arguments())
                if (source.types().get(argument).kind() == JavaType.Kind.TYPEVAR)
                    formals.add(new Formal(source.types(), argument));
        } else {
            var binary =
                    binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : binaries.find(receiver.type().name());
            if (binary.isEmpty()) return new TypeProjection(receiver.type(), Match.YES);
            var shape = JavaBinaryTypes.hierarchy(binary.get());
            List<Integer> arguments = new ArrayList<>();
            Map<String, Integer> references = new HashMap<>();
            for (int i = 0; i < shape.types().entries().size(); i++) {
                if (++work > 100_000) return new TypeProjection(null, Match.UNKNOWN);
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
                Sam descriptor = sam(expected(site));
                target = descriptor == null ? null : ground(descriptor.returns());
            } else target = writtenExpected(site);
            if (target == null || target.resolved().type().kind() != JavaType.Kind.DECLARED)
                return new TypeProjection(receiver.type(), Match.YES);
            TypeProjection projection = projectType(root, target.name(), 0);
            if (projection.match() != Match.YES) return projection;
            Match seeded =
                    seedTarget(target, projection.type(), formals, variables, 0, noArguments);
            if (seeded != Match.YES) return new TypeProjection(null, seeded);
        }
        for (Formal formal : formals) {
            Use value = variables.get(formal.name());
            var bounds = formal.types().get(formal.reference()).upperBounds();
            if (value == null && noArguments && bounds.size() == 1) {
                Use upper = new Use(formal.types(), bounds.getFirst(), variables);
                if (!unboundTarget(upper, 0)) {
                    value = upper;
                    variables.put(formal.name(), value);
                }
            }
            if (value == null) return new TypeProjection(null, Match.UNKNOWN);
            for (int bound : bounds) {
                Match match = assignable(value, new Use(formal.types(), bound, variables), 0);
                if (match != Match.YES) return new TypeProjection(null, match);
            }
        }
        return new TypeProjection(
                explicit
                        ? receiver.type()
                        : new Use(root.types(), root.reference(), Map.copyOf(variables)),
                Match.YES);
    }

    private Result referenceResult(
            JavaNode site, Receiver receiver, boolean constructor, Result result) {
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
        if (!site.kind().equals("MEMBER_REFERENCE") || probingReferences.contains(site.id()))
            return result;
        Use returns =
                result instanceof Selected source
                        ? source.returns()
                        : result instanceof ExternalSelected external ? external.returns() : null;
        if (returns == null) return result;
        var descriptor = sam(expected(site));
        if (descriptor == null) return new Unavailable("functional-descriptor-unavailable");
        if (descriptor.returns().resolved().type().kind() != JavaType.Kind.VOID
                && assignable(constructor ? receiver.type : returns, descriptor.returns(), 0)
                        != Match.YES)
            return new Blocked("functional-return-incompatible-or-incomplete");
        return result;
    }

    private static boolean arity(int parameters, int arguments, boolean variadic) {
        return variadic ? parameters > 0 && arguments >= parameters - 1 : arguments == parameters;
    }

    private Applicability applicability(List<Use> actual, List<Use> parameters, boolean variadic) {
        if (actual.size() == parameters.size()) {
            Match strict = arguments(actual, parameters, true);
            if (strict != Match.NO) return new Applicability(strict, 0, parameters);
            Match loose = arguments(actual, parameters, false);
            if (loose != Match.NO) return new Applicability(loose, 1, parameters);
        }
        if (!variadic || !arity(parameters.size(), actual.size(), true))
            return new Applicability(Match.NO, 2, parameters);
        var array = parameters.getLast().resolved();
        if (array.type().kind() != JavaType.Kind.ARRAY)
            return new Applicability(Match.UNKNOWN, 2, parameters);
        List<Use> expanded = new ArrayList<>(parameters.subList(0, parameters.size() - 1));
        while (expanded.size() < actual.size()) expanded.add(array.child(array.type().component()));
        Match match = arguments(actual, expanded, false);
        // Zero trailing arguments still compare component types for most-specific selection.
        if (expanded.size() == parameters.size() - 1)
            expanded.add(array.child(array.type().component()));
        return new Applicability(match, 2, List.copyOf(expanded));
    }

    private Match arguments(List<Use> actual, List<Use> parameters, boolean strict) {
        if (actual.size() != parameters.size()) return Match.NO;
        Match result = Match.YES;
        for (int i = 0; i < actual.size(); i++) {
            Match match =
                    strict
                            ? strictAssignable(actual.get(i), parameters.get(i))
                            : assignable(actual.get(i), parameters.get(i), 0);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    private Match strictAssignable(Use actual, Use parameter) {
        if (primitive(actual.resolved().name()) != primitive(parameter.resolved().name()))
            return Match.NO;
        return assignable(actual, parameter, 0);
    }

    private Candidate mostSpecific(List<Candidate> candidates) {
        if (candidates.size() == 1) return candidates.getFirst();
        Candidate result = null;
        for (Candidate candidate : candidates) {
            boolean specific = true;
            for (Candidate other : candidates) {
                if (candidate == other) continue;
                if (arguments(candidate.parameters(), other.parameters(), true) != Match.YES) {
                    specific = false;
                    break;
                }
                if (arguments(other.parameters(), candidate.parameters(), true) == Match.YES
                        && !overrides(candidate, other)) {
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

    private boolean overrides(Candidate first, Candidate second) {
        var target = model.declaration(second.declaration());
        var declaration = model.declaration(first.declaration());
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
                || visibility(declaration.symbol().modifiers())
                        < visibility(target.symbol().modifiers())
                || declaration.symbol().modifiers().contains("static")
                        != target.symbol().modifiers().contains("static")) return false;
        if (!target.symbol().modifiers().contains("public")
                && !target.symbol().modifiers().contains("protected")
                && !packageName(declaration.symbol().owner())
                        .equals(packageName(target.symbol().owner()))) return false;
        return arguments(first.parameters(), second.parameters(), true) == Match.YES
                && arguments(second.parameters(), first.parameters(), true) == Match.YES
                && sourceHierarchy(
                                namedType(declaration.symbol().owner()),
                                namedType(target.symbol().owner()),
                                0)
                        == Match.YES
                && strictAssignable(first.returns(), second.returns()) == Match.YES;
    }

    record ModeledOverride(JavaModel.DeclarationRef source, String external) {}

    record ModeledOverrides(List<ModeledOverride> targets, List<String> incompleteReasons) {}

    private record OverrideParent(Use type, Set<String> packages) {}

    ModeledOverrides modeledOverrides(JavaModel.DeclarationRef reference) {
        var declaration = model.declaration(reference);
        var symbol = declaration.symbol();
        boolean modeledSource = declaration.origin() == JavaDeclaration.Origin.MODEL;
        if (!symbol.kind().equals("METHOD")
                || symbol.modifiers().contains("static")
                || symbol.modifiers().contains("private"))
            return new ModeledOverrides(List.of(), List.of());
        var ownerReference = model.parent(reference).orElseThrow();
        var ownerDeclaration = model.declaration(ownerReference);
        var sourceTypes = model.unit(reference.source()).types();
        var executable = sourceTypes.get(symbol.declaredType());
        List<Use> parameters =
                executable.parameters().stream()
                        .map(parameter -> new Use(sourceTypes, parameter))
                        .toList();
        Use returns = new Use(sourceTypes, executable.returns());
        var pending = new ArrayDeque<OverrideParent>();
        var packages = Set.of(packageName(ownerDeclaration.binaryName()));
        if (ownerDeclaration.superclass() >= 0)
            pending.add(
                    new OverrideParent(
                            new Use(sourceTypes, ownerDeclaration.superclass()), packages));
        for (int parent : ownerDeclaration.interfaces())
            pending.add(new OverrideParent(new Use(sourceTypes, parent), packages));
        var visited = new HashSet<String>();
        var targets = new ArrayList<ModeledOverride>();
        var reasons = new LinkedHashSet<String>();
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256) {
                reasons.add("modeled-override-budget");
                break;
            }
            var entry = pending.removeFirst();
            Use current = entry.type();
            if (!visited.add(current.name())) continue;
            var parentPackages = new HashSet<>(entry.packages());
            var lookup = model.type(current.name());
            if (lookup instanceof JavaModel.FoundType found) {
                var parent = model.declaration(found.declaration());
                var parentTypes = model.unit(found.declaration().source()).types();
                var variables = owner(found.declaration(), current, 0).variables();
                String parentPackage = packageName(parent.binaryName());
                for (var member : model.members(found.declaration())) {
                    var inherited = model.declaration(member);
                    var inheritedSymbol = inherited.symbol();
                    if (!inheritedSymbol.kind().equals("METHOD")
                            || !inheritedSymbol.name().equals(symbol.name())) continue;
                    var parentMethod = parentTypes.get(inheritedSymbol.declaredType());
                    var parentParameters =
                            parentMethod.parameters().stream()
                                    .map(parameter -> new Use(parentTypes, parameter, variables))
                                    .toList();
                    Use parentReturns = new Use(parentTypes, parentMethod.returns(), variables);
                    if (invalidOverride(
                            symbol.modifiers(),
                            inheritedSymbol.modifiers(),
                            parentPackage,
                            entry.packages(),
                            parameters,
                            returns,
                            parentParameters,
                            parentReturns))
                        return new ModeledOverrides(List.of(), List.of("illegal-modeled-override"));
                    if (!overrideVisible(
                            symbol.modifiers(),
                            inheritedSymbol.modifiers(),
                            parentPackage,
                            entry.packages())) continue;
                    if ((modeledSource || inherited.origin() == JavaDeclaration.Origin.MODEL)
                            && overrideSignature(
                                            parameters, returns, parentParameters, parentReturns)
                                    == Match.YES) targets.add(new ModeledOverride(member, null));
                }
                parentPackages.add(parentPackage);
                if (parent.superclass() >= 0)
                    pending.add(
                            new OverrideParent(
                                    new Use(parentTypes, parent.superclass(), variables),
                                    Set.copyOf(parentPackages)));
                for (int inherited : parent.interfaces())
                    pending.add(
                            new OverrideParent(
                                    new Use(parentTypes, inherited, variables),
                                    Set.copyOf(parentPackages)));
            } else if (lookup instanceof JavaModel.AmbiguousType) {
                reasons.add("modeled-override-owner-ambiguous");
            } else {
                var binary =
                        binaries == null
                                ? Optional.<JavaBinaryType>empty()
                                : binaries.find(current.name());
                if (binary.isEmpty()) {
                    reasons.add("modeled-override-hierarchy-incomplete");
                    continue;
                }
                var parent = binary.get();
                try {
                    var hierarchy = JavaBinaryTypes.hierarchy(parent);
                    var variables = binaryVariables(hierarchy, current);
                    String parentPackage = packageName(parent.name());
                    for (var method : parent.methods()) {
                        if (!method.name().equals(symbol.name())
                                || (method.access() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_BRIDGE))
                                        != 0) continue;
                        var modifiers = new ArrayList<String>();
                        if ((method.access() & Opcodes.ACC_PUBLIC) != 0) modifiers.add("public");
                        else if ((method.access() & Opcodes.ACC_PROTECTED) != 0)
                            modifiers.add("protected");
                        if ((method.access() & Opcodes.ACC_STATIC) != 0) modifiers.add("static");
                        if ((method.access() & Opcodes.ACC_FINAL) != 0) modifiers.add("final");
                        var shape = JavaBinaryTypes.method(parent, method);
                        if (!shape.methodParameters().isEmpty()) continue;
                        var methodVariables = new LinkedHashMap<String, Use>();
                        var actual = current.resolved();
                        for (int i = 0; i < shape.classParameters().size(); i++)
                            if (i < actual.type().arguments().size())
                                methodVariables.put(
                                        shape.classParameters().get(i), actual.argument(i));
                        var parentMethod = shape.types().get(shape.executable());
                        var parentParameters =
                                parentMethod.parameters().stream()
                                        .map(
                                                parameter ->
                                                        new Use(
                                                                shape.types(),
                                                                parameter,
                                                                methodVariables))
                                        .toList();
                        Use parentReturns =
                                new Use(shape.types(), parentMethod.returns(), methodVariables);
                        if (invalidOverride(
                                symbol.modifiers(),
                                modifiers,
                                parentPackage,
                                entry.packages(),
                                parameters,
                                returns,
                                parentParameters,
                                parentReturns))
                            return new ModeledOverrides(
                                    List.of(), List.of("illegal-modeled-override"));
                        if (!overrideVisible(
                                symbol.modifiers(), modifiers, parentPackage, entry.packages()))
                            continue;
                        if (modeledSource
                                && overrideSignature(
                                                parameters,
                                                returns,
                                                parentParameters,
                                                parentReturns)
                                        == Match.YES) {
                            var parentSymbol =
                                    new JavaSymbol(
                                            "METHOD",
                                            parent.name().replace('$', '.'),
                                            method.name(),
                                            method.descriptor(),
                                            shape.executable(),
                                            modifiers,
                                            false);
                            targets.add(
                                    new ModeledOverride(
                                            null,
                                            JavaTypeNames.binaryCallableId(
                                                    shape.types(), parentSymbol, parent.name())));
                        }
                    }
                    parentPackages.add(parentPackage);
                    if (hierarchy.superclass() >= 0)
                        pending.add(
                                new OverrideParent(
                                        new Use(
                                                hierarchy.types(),
                                                hierarchy.superclass(),
                                                variables),
                                        Set.copyOf(parentPackages)));
                    for (int inherited : hierarchy.interfaces())
                        pending.add(
                                new OverrideParent(
                                        new Use(hierarchy.types(), inherited, variables),
                                        Set.copyOf(parentPackages)));
                } catch (IllegalArgumentException incomplete) {
                    reasons.add("modeled-override-hierarchy-incomplete");
                }
            }
        }
        return new ModeledOverrides(List.copyOf(targets), List.copyOf(reasons));
    }

    private static boolean overrideVisible(
            List<String> own, List<String> parent, String parentPackage, Set<String> pathPackages) {
        if (parent.contains("private")
                || parent.contains("static")
                || parent.contains("final")
                || visibility(own) < visibility(parent)) return false;
        return parent.contains("public")
                || parent.contains("protected")
                || pathPackages.stream().allMatch(parentPackage::equals);
    }

    private Match overrideSignature(
            List<Use> own, Use returns, List<Use> parent, Use parentReturns) {
        if (overrideParameters(own, parent) != Match.YES) return Match.UNKNOWN;
        return overrideReturns(returns, parentReturns);
    }

    private Match overrideParameters(List<Use> own, List<Use> parent) {
        if (own.size() != parent.size()) return Match.NO;
        for (int i = 0; i < own.size(); i++) {
            var match = same(own.get(i), parent.get(i), 0);
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

    private Match overrideReturns(Use own, Use parent) {
        if (primitive(own.name())
                || primitive(parent.name())
                || own.name().equals("void")
                || parent.name().equals("void"))
            return own.name().equals(parent.name()) ? Match.YES : Match.NO;
        return strictAssignable(own, parent);
    }

    private boolean invalidOverride(
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
        var parameters = overrideParameters(ownParameters, parentParameters);
        boolean erasureClash =
                parameters == Match.NO && ownParameters.size() == parentParameters.size();
        for (int i = 0; erasureClash && i < ownParameters.size(); i++)
            erasureClash = ownParameters.get(i).name().equals(parentParameters.get(i).name());
        return inherited
                && (erasureClash
                        || parameters == Match.YES
                                && (parent.contains("final")
                                        || parent.contains("static")
                                        || visibility(own) < visibility(parent)
                                        || overrideReturns(ownReturns, parentReturns) == Match.NO));
    }

    private boolean hasModeledInput(JavaNode site) {
        if (!model.hasModeledDeclarations()) return false;
        return modeledInputs(site, 0);
    }

    private boolean modeledInputs(JavaNode site, int depth) {
        if (depth >= 64 || ++work > 100_000) throw new EvidenceBudgetExceeded();
        if (site.kind().equals("MEMBER_REFERENCE")) {
            if (expectedOverrides.containsKey(site.id())
                    || modeledReference(site)
                    || modeledExpression(site.tree("getQualifierExpression"), depth + 1))
                return true;
            var parent = model.syntax(unit.path()).parent(site).orElse(null);
            var result = parent == null ? null : resultCache().get(parent.id());
            if (result instanceof Selected || result instanceof ExternalSelected) return true;
        }
        var select = site.tree("getMethodSelect");
        if (select != null
                && select.kind().equals("MEMBER_SELECT")
                && modeledExpression(select.tree("getExpression"), depth + 1)) return true;
        for (var argument : site.trees("getArguments"))
            if (modeledExpression(argument, depth + 1)) return true;
        return false;
    }

    private boolean modeledExpression(JavaNode expression, int depth) {
        if (expression == null) return false;
        if (++work > 100_000 || depth >= 64) throw new EvidenceBudgetExceeded();
        return switch (expression.kind()) {
            case "METHOD_INVOCATION" ->
                    resolve(expression) instanceof Selected
                            || resolve(expression) instanceof ExternalSelected
                            || modeledInputs(expression, depth + 1);
            case "IDENTIFIER" -> lambdaParameter(expression) != null && modeledLambda(expression);
            case "MEMBER_REFERENCE" -> modeledInputs(expression, depth + 1);
            case "LAMBDA_EXPRESSION" -> {
                provisionalResults.push(new HashMap<>());
                try {
                    JavaNode body = expression.tree("getBody");
                    if (body == null) yield false;
                    if (!body.kind().equals("BLOCK")) yield modeledExpression(body, depth + 1);
                    yield modeledBlock(body, depth + 1);
                } finally {
                    provisionalResults.pop();
                }
            }
            case "PARENTHESIZED" -> modeledExpression(expression.tree("getExpression"), depth + 1);
            case "CONDITIONAL_EXPRESSION" ->
                    modeledExpression(expression.tree("getCondition"), depth + 1)
                            || modeledExpression(expression.tree("getTrueExpression"), depth + 1)
                            || modeledExpression(expression.tree("getFalseExpression"), depth + 1);
            default ->
                    modeledExpression(expression.tree("getLeftOperand"), depth + 1)
                            || modeledExpression(expression.tree("getRightOperand"), depth + 1)
                            || modeledExpression(expression.tree("getExpression"), depth + 1);
        };
    }

    private boolean modeledBlock(JavaNode body, int depth) {
        ArrayDeque<JavaNode> pending = new ArrayDeque<>();
        pending.add(body);
        while (!pending.isEmpty()) {
            if (++work > 100_000 || pending.size() >= 4096 || depth >= 64)
                throw new EvidenceBudgetExceeded();
            JavaNode node = pending.removeFirst();
            if (Set.of("METHOD", "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE")
                    .contains(node.kind())) continue;
            if (Set.of("METHOD_INVOCATION", "MEMBER_REFERENCE", "LAMBDA_EXPRESSION")
                    .contains(node.kind())) {
                if (modeledExpression(node, depth + 1)) return true;
                continue;
            }
            for (Object value : node.fields().values()) {
                if (value instanceof JavaNode child) pending.add(child);
                else if (value instanceof List<?> children)
                    for (Object child : children)
                        if (child instanceof JavaNode tree) pending.add(tree);
            }
        }
        return false;
    }

    private boolean modeledReference(JavaNode site) {
        var confirmed = unit.bindings().get(site.id());
        if (confirmed != null
                && confirmed.declarationResolved()
                && model.type(
                                confirmed.binaryOwner().isEmpty()
                                        ? confirmed.owner()
                                        : confirmed.binaryOwner())
                        instanceof JavaModel.FoundType found
                && model.members(found.declaration()).stream()
                        .anyMatch(
                                member -> {
                                    var declaration = model.declaration(member);
                                    return declaration.origin() != JavaDeclaration.Origin.MODEL
                                            && declaration.symbol().kind().equals(confirmed.kind())
                                            && declaration.symbol().name().equals(confirmed.name())
                                            && declaration
                                                    .symbol()
                                                    .descriptor()
                                                    .equals(confirmed.descriptor());
                                })) return false;
        var receiver = receiver(site);
        if (receiver == null) return false;
        String name = site.text("getMode").equals("NEW") ? "<init>" : methodName(site);
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(receiver.type());
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256) return false;
            var current = pending.removeFirst().resolved();
            if (!visited.add(current.name())) continue;
            if (!(model.type(current.name()) instanceof JavaModel.FoundType found)) continue;
            for (var member : model.members(found.declaration())) {
                var declaration = model.declaration(member);
                if (declaration.origin() == JavaDeclaration.Origin.MODEL
                        && Set.of("METHOD", "CONSTRUCTOR").contains(declaration.symbol().kind())
                        && declaration.symbol().name().equals(name)) return true;
            }
            var declaration = model.declaration(found.declaration());
            var source = model.unit(found.declaration().source());
            var variables = owner(found.declaration(), current, 0).variables();
            if (declaration.superclass() >= 0)
                pending.add(new Use(source.types(), declaration.superclass(), variables));
            for (int parent : declaration.interfaces())
                pending.add(new Use(source.types(), parent, variables));
        }
        return false;
    }

    private boolean binaryHasSourceParent(String root) {
        Boolean cached = binarySourceParents.get(root);
        if (cached != null) return cached;
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(root);
        boolean found = false;
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256) return true;
            String name = pending.removeFirst().replace('$', '.');
            if (!visited.add(name)) continue;
            if (!(model.type(name) instanceof JavaModel.MissingType)) {
                found = true;
                break;
            }
            var binary = binaries == null ? Optional.<JavaBinaryType>empty() : binaries.find(name);
            if (binary.isEmpty()) continue;
            if (binary.get().superclass() != null) pending.add(binary.get().superclass());
            pending.addAll(binary.get().interfaces());
        }
        if (binarySourceParents.size() >= 4096)
            binarySourceParents.remove(binarySourceParents.keySet().iterator().next());
        binarySourceParents.put(root, found);
        return found;
    }

    private SourceParents sourceParents(Use root, int initialDepth) {
        ArrayDeque<LocatedType> pending = new ArrayDeque<>();
        pending.add(new LocatedType(root, initialDepth));
        List<Owner> owners = new ArrayList<>();
        Map<String, Use> visited = new HashMap<>();
        boolean incomplete = false;
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256)
                return new SourceParents(List.copyOf(owners), true);
            LocatedType located = pending.removeFirst();
            Use current = located.type().resolved();
            Use previous = visited.putIfAbsent(current.name(), current);
            if (previous != null) {
                if (same(previous, current, 0) != Match.YES) incomplete = true;
                continue;
            }
            var lookup = model.type(current.name());
            if (lookup instanceof JavaModel.FoundType found) {
                owners.add(owner(found.declaration(), current, located.depth()));
                continue;
            }
            if (lookup instanceof JavaModel.AmbiguousType) {
                incomplete = true;
                continue;
            }
            var binary =
                    binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : binaries.find(current.name());
            if (binary.isEmpty()) {
                incomplete = true;
                continue;
            }
            try {
                var hierarchy = JavaBinaryTypes.hierarchy(binary.get());
                var variables = binaryVariables(hierarchy, current);
                if (hierarchy.superclass() >= 0)
                    pending.add(
                            new LocatedType(
                                    new Use(hierarchy.types(), hierarchy.superclass(), variables),
                                    located.depth() + 1));
                for (int parent : hierarchy.interfaces())
                    pending.add(
                            new LocatedType(
                                    new Use(hierarchy.types(), parent, variables),
                                    located.depth() + 1));
            } catch (IllegalArgumentException unavailable) {
                incomplete = true;
            }
        }
        return new SourceParents(List.copyOf(owners), incomplete);
    }

    private Owner owner(JavaModel.DeclarationRef declaration, Use type, int depth) {
        JavaUnit source = model.unit(declaration.source());
        var declared = source.types().get(model.declaration(declaration).symbol().declaredType());
        Use actual = type.resolved();
        var arguments = actual.type().arguments();
        Map<String, Use> variables = new LinkedHashMap<>();
        if (actual.type().enclosing() >= 0) {
            if (++work > 100_000 || depth >= 64)
                throw new IllegalArgumentException("enclosing type substitution exceeds budget");
            variables.putAll(actual.variables());
            Use enclosing = actual.child(actual.type().enclosing()).resolved();
            if (model.type(enclosing.name()) instanceof JavaModel.FoundType found)
                variables.putAll(owner(found.declaration(), enclosing, depth + 1).variables());
        }
        for (int i = 0; i < declared.arguments().size(); i++) {
            var variable = source.types().get(declared.arguments().get(i));
            if (variable.kind() != JavaType.Kind.TYPEVAR) continue;
            if (i < arguments.size()) variables.put(variable.name(), actual.argument(i));
            else if (!variable.upperBounds().isEmpty())
                variables.put(
                        variable.name(),
                        namedType(
                                JavaTypeNames.erased(
                                        source.types(), variable.upperBounds().getFirst())));
        }
        return new Owner(declaration, Map.copyOf(variables), depth);
    }

    private BinarySearch binaryCandidates(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots) {
        return binaryCandidates(site, receiver, constructor, arguments, roots, Set.of());
    }

    private BinarySearch binaryCandidates(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots,
            Set<String> sourceOwners) {
        if (binaries == null)
            return BinarySearch.failed(new Unavailable("binary-recovery-unavailable"));
        String name = constructor ? "<init>" : methodName(site);
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.addAll(roots);
        Map<String, Use> visited = new HashMap<>();
        Set<String> signatures = new HashSet<>();
        List<BinaryCandidate> candidates = new ArrayList<>();
        int uncertainPhase = Integer.MAX_VALUE;
        int namedMethods = 0;
        boolean unknown = false;
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256)
                return BinarySearch.failed(new Unavailable("recovery-budget"));
            Use current = pending.removeFirst().resolved();
            String owner = current.name();
            Use previous = visited.putIfAbsent(owner, current);
            if (previous != null) {
                if (same(previous, current, 0) != Match.YES) unknown = true;
                continue;
            }
            if (model.type(owner) instanceof JavaModel.FoundType) {
                if (!sourceOwners.contains(owner)) unknown = true;
                continue;
            }
            if (model.type(owner) instanceof JavaModel.AmbiguousType) {
                unknown = true;
                continue;
            }
            var found = binaries.find(owner);
            if (found.isEmpty()) {
                unknown = true;
                continue;
            }
            var type = found.get();
            for (var method : type.methods()) {
                if (++work > 100_000)
                    return BinarySearch.failed(new Unavailable("recovery-budget"));
                if (!method.name().equals(name)
                        || (method.access() & Opcodes.ACC_PUBLIC) == 0
                        || (method.access() & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0)
                    continue;
                boolean isStatic = (method.access() & Opcodes.ACC_STATIC) != 0;
                if (!constructor && receiver.typeQualifier && !isStatic) continue;
                if (isStatic
                        && !owner.equals(receiver.type.name())
                        && (type.access() & Opcodes.ACC_INTERFACE) != 0) continue;
                if (!signatures.add(owner + "#" + method.name() + method.descriptor())) continue;
                JavaBinaryTypes.MethodShape shape;
                try {
                    shape = JavaBinaryTypes.method(type, method);
                } catch (IllegalArgumentException incomplete) {
                    unknown = true;
                    continue;
                }
                var executable = shape.types().get(shape.executable());
                boolean variadic = (method.access() & Opcodes.ACC_VARARGS) != 0;
                if (!arity(executable.parameters().size(), arguments.size(), variadic)) continue;
                namedMethods++;
                Map<String, Use> variables = new LinkedHashMap<>();
                {
                    var actual = current;
                    for (int i = 0; i < shape.classParameters().size(); i++)
                        if (i < actual.type().arguments().size())
                            variables.put(shape.classParameters().get(i), actual.argument(i));
                }
                if (!shape.methodParameters().isEmpty()) {
                    Match constraints =
                            methodArguments(
                                    site,
                                    arguments,
                                    executable.parameters(),
                                    executable.returns(),
                                    shape.types(),
                                    shape.methodParameterTypes(),
                                    variables,
                                    variadic);
                    if (constraints != Match.YES) {
                        if (constraints == Match.UNKNOWN) uncertainPhase = 0;
                        continue;
                    }
                }
                var snapshot = Map.copyOf(variables);
                var parameterTypes =
                        executable.parameters().stream()
                                .map(parameter -> new Use(shape.types(), parameter, snapshot))
                                .toList();
                var match = applicability(arguments, parameterTypes, variadic);
                if (match.match() == Match.UNKNOWN)
                    uncertainPhase = Math.min(uncertainPhase, match.phase());
                if (match.match() != Match.YES) continue;
                var symbol =
                        new JavaSymbol(
                                constructor ? "CONSTRUCTOR" : "METHOD",
                                type.name().replace('$', '.'),
                                name,
                                method.descriptor(),
                                shape.executable(),
                                List.of(),
                                (method.access() & Opcodes.ACC_VARARGS) != 0);
                candidates.add(
                        new BinaryCandidate(
                                new ExternalSelected(
                                        JavaTypeNames.binaryCallableId(
                                                shape.types(), symbol, type.name()),
                                        new Use(shape.types(), executable.returns(), snapshot),
                                        receiver.type.name(),
                                        constructor
                                                ? "CONSTRUCTOR"
                                                : isStatic ? "STATIC" : "INSTANCE",
                                        parameterTypes),
                                match.parameters(),
                                match.phase(),
                                method.access(),
                                method.descriptor()));
            }
            if (!constructor) {
                try {
                    var hierarchy = JavaBinaryTypes.hierarchy(type);
                    Map<String, Use> variables = binaryVariables(hierarchy, current);
                    if (type.superclass() != null && hierarchy.superclass() >= 0)
                        pending.add(new Use(hierarchy.types(), hierarchy.superclass(), variables));
                    for (int parent : hierarchy.interfaces())
                        pending.add(new Use(hierarchy.types(), parent, variables));
                } catch (IllegalArgumentException unavailable) {
                    unknown = true;
                }
            }
        }
        return new BinarySearch(
                List.copyOf(candidates), uncertainPhase, namedMethods, unknown, null);
    }

    private Result binary(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots) {
        BinarySearch search = binaryCandidates(site, receiver, constructor, arguments, roots);
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
            Result confirmed = confirmedBinary(site, receiver, constructor);
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
                if (binaryOverrides(candidate, other)) continue;
                if (arguments(candidate.parameters(), other.parameters(), true) != Match.YES
                        || arguments(other.parameters(), candidate.parameters(), true) == Match.YES
                                && !binaryOverrides(candidate, other)) {
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

    private static String parameterDescriptor(String descriptor) {
        int close = descriptor.indexOf(')');
        return close < 0 ? descriptor : descriptor.substring(0, close + 1);
    }

    private Result selectMixed(List<MixedCandidate> candidates, int uncertainPhase) {
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
                if (candidate == other || mixedOverrides(candidate, other)) continue;
                if (arguments(candidate.parameters(), other.parameters(), true) != Match.YES
                        || arguments(other.parameters(), candidate.parameters(), true)
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

    private boolean mixedOverrides(MixedCandidate first, MixedCandidate second) {
        if (first.selected() instanceof Selected source
                && second.selected() instanceof Selected parent)
            return overrides(
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
            return binaryOverrides(
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
            var declaration = model.declaration(parent.declaration());
            String binaryOwner =
                    binary.target().substring(0, binary.target().indexOf('#')).replace('$', '.');
            if (!declaration.complete()
                    || !declaration.symbol().modifiers().contains("public")
                    || declaration.symbol().modifiers().contains("final")
                    || !binary.callKind().equals(parent.callKind())
                    || arguments(first.parameters(), second.parameters(), true) != Match.YES
                    || arguments(second.parameters(), first.parameters(), true) != Match.YES
                    || sourceHierarchy(
                                    namedType(binaryOwner),
                                    namedType(declaration.symbol().owner()),
                                    0)
                            != Match.YES
                    || strictAssignable(binary.returns(), parent.returns()) != Match.YES)
                return false;
            if (first.descriptor().equals(second.descriptor())) return true;
            var type =
                    binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : binaries.find(binaryOwner);
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
        var declaration = model.declaration(source.declaration());
        String parentOwner =
                parent.target().substring(0, parent.target().indexOf('#')).replace('$', '.');
        return declaration.complete()
                && declaration.symbol().modifiers().contains("public")
                && (second.access() & Opcodes.ACC_FINAL) == 0
                && source.callKind().equals(parent.callKind())
                && arguments(first.parameters(), second.parameters(), true) == Match.YES
                && arguments(second.parameters(), first.parameters(), true) == Match.YES
                && sourceHierarchy(
                                namedType(declaration.symbol().owner()), namedType(parentOwner), 0)
                        == Match.YES
                && strictAssignable(source.returns(), parent.returns()) == Match.YES;
    }

    private boolean binaryOverrides(BinaryCandidate first, BinaryCandidate second) {
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
                    || binaries == null
                    || second.descriptor().isEmpty()
                    || arguments(first.parameters(), second.parameters(), true) != Match.YES
                    || arguments(second.parameters(), first.parameters(), true) != Match.YES)
                return false;
            var type = binaries.find(firstOwner);
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
                && sourceHierarchy(
                                namedType(firstOwner.replace('$', '.')),
                                namedType(secondOwner.replace('$', '.')),
                                0)
                        == Match.YES
                && strictAssignable(first.selected().returns(), second.selected().returns())
                        == Match.YES;
    }

    private Match methodArguments(
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
        Use target = site.kind().equals("METHOD_INVOCATION") ? writtenExpected(site) : null;
        for (Formal formal : formals) variables.remove(formal.name());
        var explicit = site.trees("getTypeArguments");
        if (!explicit.isEmpty()) {
            if (explicit.size() != formals.size()) return Match.NO;
            for (int i = 0; i < formals.size(); i++) {
                Use value = expression(explicit.get(i));
                if (value == null) return Match.UNKNOWN;
                variables.put(formals.get(i).name(), value);
            }
        } else {
            List<Formal> inputFormals = formals;
            Use result = new Use(types, returns, variables);
            if (target != null
                    && result.type().kind() == JavaType.Kind.TYPEVAR
                    && formal(result, formals)
                    && returnTargetSeedable(actual, parameters, types, variables, result, target)
                    && Set.of(JavaType.Kind.DECLARED, JavaType.Kind.ARRAY)
                            .contains(target.resolved().type().kind())
                    && satisfiesBounds(target, result, variables)) {
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
                TypeProjection projection = projectType(result, target.name(), 0);
                Map<String, Use> hints = new LinkedHashMap<>(variables);
                if (projection.match() == Match.YES
                        && seedTarget(
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
                Match constraint = infer(actual.get(i), parameter, inputFormals, variables, 0);
                if (constraint != Match.YES) return constraint;
            }
            if (target != null
                    && formal(result, formals)
                    && !variables.containsKey(result.type().name())
                    && satisfiesBounds(target, result, variables))
                variables.put(result.type().name(), target);
        }
        for (int reference : formalTypes) {
            var type = types.get(reference);
            Use value = variables.get(type.name());
            if (value == null) continue;
            for (int bound : type.upperBounds()) {
                Match match = assignable(value, new Use(types, bound, variables), 0);
                if (match != Match.YES) return match;
            }
        }
        if (target != null && !formals.isEmpty()) {
            Match output = assignable(new Use(types, returns, variables), target, 0);
            if (output != Match.YES && !(output == Match.UNKNOWN && unboundTarget(target, 0)))
                return output;
        }
        return Match.YES;
    }

    private boolean satisfiesBounds(Use target, Use formal, Map<String, Use> variables) {
        Map<String, Use> hints = new LinkedHashMap<>(variables);
        hints.put(formal.type().name(), target);
        for (int bound : formal.type().upperBounds())
            if (assignable(target, new Use(formal.types(), bound, hints), 0) != Match.YES)
                return false;
        return true;
    }

    /**
     * Seed invariant return positions; wildcard and outer inference variables remain constraints.
     */
    private Match seedTarget(
            Use target,
            Use pattern,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth,
            boolean wildcardHints) {
        if (++work > 100_000 || depth >= 64) return Match.UNKNOWN;
        target = target.resolved();
        if (wildcardHints && target.type().kind() == JavaType.Kind.WILDCARD) {
            target = ground(target);
            if (target == null || unboundTarget(target, 0)) return Match.YES;
        }
        if (target.type().kind() == JavaType.Kind.TYPEVAR
                || target.type().kind() == JavaType.Kind.WILDCARD) return Match.YES;
        if (pattern.type().kind() == JavaType.Kind.TYPEVAR && formal(pattern, formals))
            return infer(target, pattern, formals, variables, depth + 1);
        pattern = pattern.resolved();
        if (pattern.type().kind() == JavaType.Kind.TYPEVAR && formal(pattern, formals))
            return infer(target, pattern, formals, variables, depth + 1);
        if (pattern.type().kind() == JavaType.Kind.DECLARED
                && target.type().kind() == JavaType.Kind.DECLARED
                && pattern.name().equals(target.name())
                && pattern.type().arguments().size() == target.type().arguments().size()) {
            for (int i = 0; i < pattern.type().arguments().size(); i++) {
                Match match =
                        seedTarget(
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
            return seedTarget(
                    target.child(target.type().component()),
                    pattern.child(pattern.type().component()),
                    formals,
                    variables,
                    depth + 1,
                    wildcardHints);
        return Match.YES;
    }

    private boolean unboundTarget(Use value, int depth) {
        if (++work > 100_000 || depth >= 64)
            throw new IllegalArgumentException("target type lookup exceeds budget");
        value = value.resolved();
        if (value.type().kind() == JavaType.Kind.TYPEVAR) return true;
        for (int argument : value.type().arguments())
            if (unboundTarget(value.child(argument), depth + 1)) return true;
        if (value.type().component() >= 0
                && unboundTarget(value.child(value.type().component()), depth + 1)) return true;
        if (value.type().lowerBound() >= 0
                && unboundTarget(value.child(value.type().lowerBound()), depth + 1)) return true;
        for (int bound : value.type().upperBounds())
            if (unboundTarget(value.child(bound), depth + 1)) return true;
        return false;
    }

    private record Formal(JavaTypes types, int reference) {
        private String name() {
            return types.get(reference).name();
        }
    }

    private boolean returnTargetSeedable(
            List<Use> actual,
            List<Integer> parameters,
            JavaTypes types,
            Map<String, Use> variables,
            Use formal,
            Use target) {
        for (int i = 0; i < parameters.size(); i++) {
            Use parameter = new Use(types, parameters.get(i), variables);
            if (!nestedFormal(parameter, formal, 0)) continue;
            if (i >= actual.size()) return false;
            JavaNode poly = polyNodes.get(actual.get(i));
            // A poly factory may infer its own arguments from this target. A prebuilt
            // invariant value, or a factory returning another subtype, must infer first.
            if (poly == null
                    || !poly.kind().equals("METHOD_INVOCATION")
                    || !sameNestedErasure(actual.get(i), parameter, formal, target, 0))
                return false;
        }
        return true;
    }

    private boolean sameNestedErasure(
            Use actual, Use parameter, Use formal, Use target, int depth) {
        if (++work > 100_000 || depth >= 64) return false;
        if (parameter.types() == formal.types() && parameter.reference() == formal.reference())
            return actual.resolved().type().kind() == JavaType.Kind.DECLARED
                    && actual.name().equals(target.name());
        if (!containsFormal(
                parameter, List.of(new Formal(formal.types(), formal.reference())), depth + 1))
            return true;
        parameter = parameter.resolved();
        actual = actual.resolved();
        if (parameter.type().kind() == JavaType.Kind.DECLARED) {
            TypeProjection projection = projectType(actual, parameter.name(), depth + 1);
            if (projection.match() != Match.YES) return false;
            actual = projection.type().resolved();
            if (actual.type().arguments().size() != parameter.type().arguments().size())
                return false;
            for (int i = 0; i < parameter.type().arguments().size(); i++)
                if (!sameNestedErasure(
                        actual.argument(i), parameter.argument(i), formal, target, depth + 1))
                    return false;
            return true;
        }
        if (parameter.type().kind() == JavaType.Kind.ARRAY
                && actual.type().kind() == JavaType.Kind.ARRAY)
            return sameNestedErasure(
                    actual.child(actual.type().component()),
                    parameter.child(parameter.type().component()),
                    formal,
                    target,
                    depth + 1);
        if (parameter.type().kind() == JavaType.Kind.WILDCARD) {
            Use value = ground(actual);
            if (value == null) return false;
            if (parameter.type().lowerBound() >= 0)
                return sameNestedErasure(
                        value,
                        parameter.child(parameter.type().lowerBound()),
                        formal,
                        target,
                        depth + 1);
            if (parameter.type().upperBounds().size() == 1)
                return sameNestedErasure(
                        value,
                        parameter.child(parameter.type().upperBounds().getFirst()),
                        formal,
                        target,
                        depth + 1);
        }
        return false;
    }

    private boolean nestedFormal(Use parameter, Use formal, int depth) {
        if (++work > 100_000 || depth >= 64) return true;
        if (parameter.type().kind() == JavaType.Kind.ARRAY)
            return nestedFormal(parameter.child(parameter.type().component()), formal, depth + 1);
        return parameter.type().kind() == JavaType.Kind.DECLARED
                && containsFormal(
                        parameter,
                        List.of(new Formal(formal.types(), formal.reference())),
                        depth + 1);
    }

    private boolean formal(Use use, List<Formal> formals) {
        for (Formal formal : formals) {
            if (++work > 100_000)
                throw new IllegalArgumentException("formal identity lookup exceeds budget");
            if (formal.types() == use.types() && formal.reference() == use.reference()) return true;
        }
        return false;
    }

    private boolean containsFormal(Use value, List<Formal> formals, int depth) {
        if (++work > 100_000 || depth >= 64)
            throw new IllegalArgumentException("formal type lookup exceeds budget");
        if (value.type().kind() == JavaType.Kind.TYPEVAR && formal(value, formals)) return true;
        value = value.resolved();
        if (value.type().kind() == JavaType.Kind.TYPEVAR) return formal(value, formals);
        for (int argument : value.type().arguments())
            if (containsFormal(value.child(argument), formals, depth + 1)) return true;
        if (value.type().enclosing() >= 0
                && containsFormal(value.child(value.type().enclosing()), formals, depth + 1))
            return true;
        if (value.type().component() >= 0
                && containsFormal(value.child(value.type().component()), formals, depth + 1))
            return true;
        if (value.type().lowerBound() >= 0
                && containsFormal(value.child(value.type().lowerBound()), formals, depth + 1))
            return true;
        for (int bound : value.type().upperBounds())
            if (containsFormal(value.child(bound), formals, depth + 1)) return true;
        return false;
    }

    private Match infer(
            Use actual,
            Use parameter,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth) {
        if (++work > 100_000 || depth >= 64) return Match.UNKNOWN;
        if (!containsFormal(parameter, formals, 0)) return assignable(actual, parameter, depth + 1);
        var poly = polyNodes.get(actual);
        if (poly != null) {
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS").contains(poly.kind())) {
                Result result = contextualInvocation(poly, parameter);
                if (result instanceof Blocked) return Match.NO;
                Use value =
                        result instanceof Selected source
                                ? source.returns()
                                : result instanceof ExternalSelected external
                                        ? external.returns()
                                        : null;
                return value == null
                        ? Match.UNKNOWN
                        : infer(value, parameter, formals, variables, depth + 1);
            }
            return inferPoly(poly, parameter, formals, variables, depth + 1);
        }
        var source = actual.resolved();
        if (parameter.type().kind() != JavaType.Kind.TYPEVAR || !formal(parameter, formals))
            parameter = parameter.resolved();
        var target = parameter.type();
        if (source.type().kind() == JavaType.Kind.NULL) return Match.YES;
        if (target.kind() == JavaType.Kind.TYPEVAR && formal(parameter, formals)) {
            if (primitive(source.name())) source = namedType(BOXES.get(source.name()));
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
            return previous == null ? Match.YES : same(previous, source, depth + 1);
        }
        if (target.kind() == JavaType.Kind.ARRAY && source.type().kind() == JavaType.Kind.ARRAY)
            return infer(
                    source.child(source.type().component()),
                    parameter.child(target.component()),
                    formals,
                    variables,
                    depth + 1);
        if (target.kind() == JavaType.Kind.WILDCARD) {
            if (target.lowerBound() >= 0)
                return infer(
                        source,
                        parameter.child(target.lowerBound()),
                        formals,
                        variables,
                        depth + 1);
            if (target.upperBounds().size() == 1)
                return infer(
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
                TypeProjection projection = projectType(source, parameter.name(), depth + 1);
                if (projection.match() != Match.YES) return projection.match();
                source = projection.type();
            }
            if (source.type().arguments().size() != target.arguments().size()) return Match.UNKNOWN;
            for (int i = 0; i < target.arguments().size(); i++) {
                Match match =
                        infer(
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

    private static Map<String, Use> binaryVariables(
            JavaBinaryTypes.ClassShape hierarchy, Use current) {
        Map<String, Use> variables = new LinkedHashMap<>();
        var actual = current.resolved();
        for (int i = 0; i < hierarchy.parameters().size(); i++)
            if (i < actual.type().arguments().size())
                variables.put(hierarchy.parameters().get(i), actual.argument(i));
        return Map.copyOf(variables);
    }

    /** Retain javac's selected declaration after checking its class-file and modeled arguments. */
    private Result confirmedBinary(JavaNode site, Receiver receiver, boolean constructor) {
        var binding = unit.bindings().get(site.id());
        if (binding == null
                || !binding.declarationResolved()
                || !binding.kind().equals(constructor ? "CONSTRUCTOR" : "METHOD")
                || !binding.owner().equals(receiver.type.name())
                || binding.expressionType() < 0) return null;
        var found = binaries.find(binding.owner());
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
        if (!constructor && receiver.typeQualifier && !isStatic) return null;
        JavaBinaryTypes.MethodShape shape;
        try {
            shape = JavaBinaryTypes.method(owner, method);
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
        Map<String, Use> variables = new LinkedHashMap<>();
        var actual = receiver.type.resolved();
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
                Use value = expression(argument);
                if (value == null) break;
                actualArguments.add(value);
            }
            if (actualArguments.size() == arguments.size()) {
                Match constraints =
                        methodArguments(
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
            if (++work > 100_000) return new Unavailable("recovery-budget");
            Use argument = expression(arguments.get(i));
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
                else if (same(previous, argument, 0) != Match.YES)
                    return new Unavailable("binary-generic-constraints-unavailable");
                for (int bound : parameter.type().upperBounds())
                    if (assignable(argument, new Use(shape.types(), bound, variables), 0)
                            == Match.NO)
                        return new Blocked("compiler-binary-bound-incompatible-with-modeled-input");
            }
            Match match = assignable(argument, parameter, 0);
            if (variadic
                    && i >= parameters.size() - 1
                    && (i >= parameters.size() || match == Match.NO)) {
                if (parameter.type().kind() != JavaType.Kind.ARRAY) return null;
                match = assignable(argument, parameter.child(parameter.type().component()), 0);
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
                receiver.type.name(),
                constructor ? "CONSTRUCTOR" : isStatic ? "STATIC" : "INSTANCE",
                parameters.stream()
                        .map(parameter -> new Use(shape.types(), parameter, Map.copyOf(variables)))
                        .toList());
    }

    private Use readableReceiver(Use type) {
        if (type == null) return null;
        Use current = type;
        for (int depth = 0; depth < 64; depth++) {
            current = current.resolved();
            if (current.type().kind() != JavaType.Kind.TYPEVAR
                    && current.type().kind() != JavaType.Kind.WILDCARD) return current;
            if (current.type().upperBounds().size() != 1) return current;
            current = current.child(current.type().upperBounds().getFirst());
        }
        return null;
    }

    static String explicitConstructorName(JavaNode site) {
        if (!site.kind().equals("METHOD_INVOCATION")) return null;
        JavaNode select = site.tree("getMethodSelect");
        if (select == null) return null;
        String name =
                select.kind().equals("MEMBER_SELECT")
                        ? select.text("getIdentifier")
                        : select.text("getName");
        return Set.of("this", "super").contains(name) ? name : null;
    }

    private Receiver receiver(JavaNode site) {
        String constructor = explicitConstructorName(site);
        if (constructor != null) {
            var enclosing = enclosingType(site);
            if (enclosing == null) return null;
            var owner = model.declaration(enclosing);
            int type =
                    constructor.equals("super")
                            ? owner.superclass()
                            : owner.symbol().declaredType();
            return type < 0
                    ? null
                    : new Receiver(new Use(model.unit(enclosing.source()).types(), type), false);
        }
        if (site.kind().equals("MEMBER_REFERENCE")) {
            var qualifier = site.tree("getQualifierExpression");
            var type = readableReceiver(expression(qualifier));
            var binding = qualifier == null ? null : unit.bindings().get(qualifier.id());
            return type == null
                    ? null
                    : new Receiver(
                            type,
                            binding != null
                                    && Set.of(
                                                    "CLASS",
                                                    "INTERFACE",
                                                    "ENUM",
                                                    "ANNOTATION_TYPE",
                                                    "RECORD")
                                            .contains(binding.kind()));
        }
        if (site.kind().equals("NEW_CLASS")) {
            var type = expression(site.tree("getIdentifier"));
            return type == null ? null : new Receiver(type, true);
        }
        JavaNode select = site.tree("getMethodSelect");
        if (select == null) return null;
        if (select.kind().equals("MEMBER_SELECT")) {
            JavaNode expression = select.tree("getExpression");
            var type = readableReceiver(expression(expression));
            var binding = expression == null ? null : unit.bindings().get(expression.id());
            boolean qualifier =
                    binding != null
                            && Set.of("IDENTIFIER", "MEMBER_SELECT", "PARAMETERIZED_TYPE")
                                    .contains(expression.kind())
                            && Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                                    .contains(binding.kind());
            return type == null ? null : new Receiver(type, qualifier);
        }
        var type = enclosingType(site);
        if (type == null) return null;
        var declaration = model.declaration(type);
        Use enclosing =
                new Use(model.unit(type.source()).types(), declaration.symbol().declaredType());
        String name = methodName(site);
        if (!namedCallable(enclosing, name, false)) {
            Receiver lexical = lexicalReceiver(type, name);
            if (lexical != null) return lexical;
            List<Use> imports = staticImports(name);
            if (!imports.isEmpty()) {
                Use primary =
                        imports.stream()
                                .filter(
                                        root ->
                                                model.type(root.name())
                                                        instanceof JavaModel.FoundType)
                                .findFirst()
                                .orElse(imports.getFirst());
                return new Receiver(primary, true, imports);
            }
        }
        return new Receiver(enclosing, false);
    }

    private Receiver lexicalReceiver(JavaModel.DeclarationRef current, String name) {
        JavaNode node = model.syntax(current).orElse(null);
        if (node == null) return null;
        boolean staticOnly = false;
        var syntax = model.syntax(unit.path());
        for (int depth = 0; node != null; depth++, node = syntax.parent(node).orElse(null)) {
            if (++work > 100_000 || depth >= 128)
                throw new IllegalArgumentException("lexical receiver lookup exceeds budget");
            JavaNode modifiers = node.tree("getModifiers");
            if (modifiers != null
                    && modifiers.values("getFlags").stream()
                            .anyMatch(flag -> flag.toString().equalsIgnoreCase("static")))
                staticOnly = true;
            if (node.kind().equals("BLOCK") && node.text("isStatic").equals("true"))
                staticOnly = true;
            if (!Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                    .contains(node.kind())) continue;
            JavaNode typeNode = node;
            var declarations =
                    model.declarationsAt(unit.path(), node.span()).stream()
                            .filter(
                                    reference ->
                                            JavaModel.matches(
                                                    typeNode,
                                                    model.declaration(reference).symbol()))
                            .toList();
            if (declarations.size() != 1) return null;
            var found = declarations.getFirst();
            if (!found.equals(current)) {
                Use outer =
                        new Use(
                                model.unit(found.source()).types(),
                                model.declaration(found).symbol().declaredType());
                // A nearer declaration shadows farther lexical types even when inaccessible
                // or inapplicable; the normal candidate search checks those constraints.
                if (namedCallable(outer, name, false)) return new Receiver(outer, staticOnly);
            }
            if (Set.of("INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD").contains(node.kind()))
                staticOnly = true;
        }
        return null;
    }

    private List<Use> staticImports(String name) {
        List<Use> cached = importedRoots.get(name);
        if (cached != null) return cached;
        Set<String> owners = new LinkedHashSet<>();
        Set<String> singles = new LinkedHashSet<>();
        for (JavaNode imported : unit.tree().trees("getImports")) {
            if (!imported.text("isStatic").equals("true")) continue;
            String qualified = qualifiedName(imported.tree("getQualifiedIdentifier"));
            int dot = qualified.lastIndexOf('.');
            if (dot < 0 || !Set.of(name, "*").contains(qualified.substring(dot + 1))) continue;
            String owner = qualified.substring(0, dot);
            if (qualified.substring(dot + 1).equals(name)) singles.add(owner);
            else if (namedCallable(namedType(owner), name, true)) owners.add(owner);
        }
        // A single static import shadows every on-demand import with that name, including
        // overloads that would otherwise be more specific or applicable.
        List<Use> roots =
                (singles.isEmpty() ? owners : singles)
                        .stream().map(JavaModeledCallRecovery::namedType).toList();
        importedRoots.put(name, roots);
        return roots;
    }

    private String qualifiedName(JavaNode node) {
        List<String> parts = new ArrayList<>();
        for (int depth = 0; node != null && depth < 64; depth++) {
            if (++work > 100_000)
                throw new IllegalArgumentException("static import lookup exceeds budget");
            if (node.kind().equals("IDENTIFIER")) {
                parts.addFirst(node.text("getName"));
                return String.join(".", parts);
            }
            if (!node.kind().equals("MEMBER_SELECT")) return "";
            parts.addFirst(node.text("getIdentifier"));
            node = node.tree("getExpression");
        }
        return "";
    }

    private boolean namedCallable(Use root, String name, boolean staticOnly) {
        var key = new CallableName(root.name(), name, staticOnly);
        Boolean cached = callableNames.get(key);
        if (cached != null) return cached;
        boolean present = findNamedCallable(root, name, staticOnly);
        callableNames.put(key, present);
        return present;
    }

    private boolean findNamedCallable(Use root, String name, boolean staticOnly) {
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(root);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256)
                throw new IllegalArgumentException("callable name lookup exceeds budget");
            Use current = pending.removeFirst();
            if (!visited.add(current.name())) continue;
            var lookup = model.type(current.name());
            if (lookup instanceof JavaModel.FoundType source) {
                for (var member : model.members(source.declaration())) {
                    var symbol = model.declaration(member).symbol();
                    if (symbol.kind().equals("METHOD")
                            && symbol.name().equals(name)
                            && (!staticOnly || symbol.modifiers().contains("static"))
                            && (current == root || !symbol.modifiers().contains("private")))
                        return true;
                }
                var declaration = model.declaration(source.declaration());
                var types = model.unit(source.declaration().source()).types();
                if (declaration.superclass() >= 0)
                    pending.add(new Use(types, declaration.superclass()));
                for (int parent : declaration.interfaces()) pending.add(new Use(types, parent));
            } else if (lookup instanceof JavaModel.AmbiguousType) {
                return true;
            } else if (binaries != null) {
                var type = binaries.find(current.name());
                if (type.isEmpty()) continue;
                for (var method : type.get().methods())
                    if (method.name().equals(name)
                            && (!staticOnly || (method.access() & Opcodes.ACC_STATIC) != 0)
                            && (current == root || (method.access() & Opcodes.ACC_PRIVATE) == 0))
                        return true;
                if (type.get().superclass() != null)
                    pending.add(namedType(type.get().superclass()));
                for (String parent : type.get().interfaces()) pending.add(namedType(parent));
            }
        }
        return false;
    }

    private Use expression(JavaNode expression) {
        Use value = expressionType(expression);
        if (value == null || value.capture() != null) return value;
        Use captured =
                new Use(
                        value.types(),
                        value.reference(),
                        value.variables(),
                        new Capture(expression.id(), ""));
        JavaNode poly = polyNodes.get(value);
        if (poly != null) polyNodes.put(captured, poly);
        return captured;
    }

    private Use expressionType(JavaNode expression) {
        if (expression == null) return null;
        if (Set.of("LAMBDA_EXPRESSION", "MEMBER_REFERENCE").contains(expression.kind())) {
            var type =
                    new JavaType(
                            JavaType.Kind.OTHER,
                            "<poly:" + expression.id() + ">",
                            "",
                            -1,
                            List.of(),
                            List.of(),
                            -1,
                            -1,
                            List.of(),
                            -1,
                            List.of());
            var use = new Use(new JavaTypes(List.of(type)), 0);
            polyNodes.put(use, expression);
            return use;
        }
        if (expression.kind().equals("NEW_CLASS")) {
            JavaNode identifier = expression.tree("getIdentifier");
            if (identifier != null
                    && identifier.kind().equals("PARAMETERIZED_TYPE")
                    && identifier.trees("getTypeArguments").isEmpty()) {
                Use root = expression(identifier);
                if (root != null) {
                    polyNodes.put(root, expression);
                    return root;
                }
            }
            Result constructed = resolve(expression);
            if (constructed instanceof Selected selected) return selected.returns();
            if (constructed instanceof ExternalSelected selected) return selected.returns();
            if (constructed instanceof Blocked) return null;
        }
        if (expression.kind().equals("IDENTIFIER")) {
            Use inferred = lambdaParameter(expression);
            if (inferred != null) return inferred;
        }
        if (expression.kind().equals("METHOD_INVOCATION")) {
            Result recovered = resolve(expression);
            if (recovered instanceof Selected selected) {
                Use returns = selected.returns();
                Set<Integer> formals = new HashSet<>();
                for (var formal : model.declaration(selected.declaration()).members())
                    if (formal.symbol().kind().equals("TYPE_PARAMETER"))
                        formals.add(formal.symbol().declaredType());
                if (siteArgument(expression)
                        && expression.trees("getTypeArguments").isEmpty()
                        && mentionsFormal(returns, formals, 0)) polyNodes.put(returns, expression);
                return returns;
            }
            if (recovered instanceof ExternalSelected selected) {
                Use returns = selected.returns();
                if (externalPolyInvocation(expression, selected))
                    polyNodes.put(returns, expression);
                return returns;
            }
            if (recovered instanceof Blocked) return null;
        }
        if (expression.kind().equals("PLUS")) {
            Use left = readableReceiver(expression(expression.tree("getLeftOperand")));
            Use right = readableReceiver(expression(expression.tree("getRightOperand")));
            if (left != null
                    && right != null
                    && !Set.of(JavaType.Kind.ERROR, JavaType.Kind.NONE, JavaType.Kind.VOID)
                            .contains(left.resolved().type().kind())
                    && !Set.of(JavaType.Kind.ERROR, JavaType.Kind.NONE, JavaType.Kind.VOID)
                            .contains(right.resolved().type().kind())
                    && (left.name().equals("java.lang.String")
                            || right.name().equals("java.lang.String")))
                return namedType("java.lang.String");
        }
        if (Set.of("LESS_THAN", "GREATER_THAN", "LESS_THAN_EQUAL", "GREATER_THAN_EQUAL")
                .contains(expression.kind())) {
            Use left = readableReceiver(expression(expression.tree("getLeftOperand")));
            Use right = readableReceiver(expression(expression.tree("getRightOperand")));
            return numeric(left) && numeric(right) ? scalarType(JavaType.Kind.BOOLEAN) : null;
        }
        if (expression.kind().equals("LOGICAL_COMPLEMENT"))
            return booleanValue(expression(expression.tree("getExpression")))
                    ? scalarType(JavaType.Kind.BOOLEAN)
                    : null;
        if (Set.of("CONDITIONAL_AND", "CONDITIONAL_OR").contains(expression.kind()))
            return booleanValue(expression(expression.tree("getLeftOperand")))
                            && booleanValue(expression(expression.tree("getRightOperand")))
                    ? scalarType(JavaType.Kind.BOOLEAN)
                    : null;
        if (Set.of("EQUAL_TO", "NOT_EQUAL_TO").contains(expression.kind())) {
            Use left = expression(expression.tree("getLeftOperand"));
            Use right = expression(expression.tree("getRightOperand"));
            if (left == null || right == null) return null;
            boolean primitives = primitive(left.name()) || primitive(right.name());
            boolean compatible =
                    primitives
                            ? numeric(left) && numeric(right)
                                    || booleanValue(left) && booleanValue(right)
                            : strictAssignable(left, right) == Match.YES
                                    || strictAssignable(right, left) == Match.YES;
            return compatible ? scalarType(JavaType.Kind.BOOLEAN) : null;
        }
        if (expression.kind().equals("CONDITIONAL_EXPRESSION")) {
            if (!booleanValue(expression(expression.tree("getCondition")))) return null;
            Use left = expression(expression.tree("getTrueExpression"));
            Use right = expression(expression.tree("getFalseExpression"));
            if (left != null && right != null) {
                var leftKind = left.resolved().type().kind();
                var rightKind = right.resolved().type().kind();
                if (!Set.of(JavaType.Kind.ARRAY, JavaType.Kind.VOID).contains(leftKind)
                        && same(left, right, 0) == Match.YES) return left;
                if (leftKind == JavaType.Kind.NULL && rightKind == JavaType.Kind.DECLARED)
                    return right;
                if (rightKind == JavaType.Kind.NULL && leftKind == JavaType.Kind.DECLARED)
                    return left;
            }
            // Numeric promotion, array component capture and unrelated branch LUBs are
            // left to the compiler's binding rather than guessed by this limited recovery.
        }
        var binding = unit.bindings().get(expression.id());
        Use generic = genericInvocation(expression);
        if (generic != null) {
            polyNodes.put(generic, expression);
            return generic;
        }
        if (binding != null && binding.expressionType() >= 0) {
            var type = unit.types().get(binding.expressionType());
            if (type.kind() != JavaType.Kind.ERROR && type.kind() != JavaType.Kind.NONE)
                return new Use(unit.types(), binding.expressionType());
        }
        if (binding != null
                && binding.declaredType() >= 0
                && Set.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION_TYPE")
                        .contains(binding.kind())
                && unit.types().get(binding.declaredType()).kind() == JavaType.Kind.DECLARED)
            return new Use(unit.types(), binding.declaredType());
        if (expression.kind().equals("PARAMETERIZED_TYPE")
                && expression.trees("getTypeArguments").isEmpty())
            return expression(expression.tree("getType"));
        if (expression.kind().equals("PARENTHESIZED"))
            return expression(expression.tree("getExpression"));
        if (expression.kind().equals("METHOD_INVOCATION")) {
            Result result = resolve(expression);
            if (result instanceof Selected selected) return selected.returns;
        }
        return null;
    }

    private final Map<String, Boolean> externalPolyReturns = new LinkedHashMap<>();

    private boolean externalPolyInvocation(JavaNode site, ExternalSelected selected) {
        if (!siteArgument(site) || !site.trees("getTypeArguments").isEmpty()) return false;
        if (genericInvocation(site) != null) return true;
        Boolean cached = externalPolyReturns.get(selected.target());
        if (cached != null) return cached;
        int separator = selected.target().indexOf('#');
        if (separator < 0 || binaries == null) return false;
        var owner = binaries.find(selected.target().substring(0, separator));
        if (owner.isEmpty()) return false;
        boolean poly = false;
        String name =
                selected.target()
                        .substring(separator + 1, selected.target().indexOf('(', separator));
        for (var method : owner.get().methods()) {
            if (++work > 100_000) return false;
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
                        mentionsFormal(
                                new Use(shape.types(), executable.returns()),
                                Set.copyOf(shape.methodParameterTypes()),
                                0);
                break;
            } catch (IllegalArgumentException incomplete) {
                return false;
            }
        }
        if (externalPolyReturns.size() >= 4096)
            externalPolyReturns.remove(externalPolyReturns.keySet().iterator().next());
        externalPolyReturns.put(selected.target(), poly);
        return poly;
    }

    private Use genericInvocation(JavaNode site) {
        if (!site.kind().equals("METHOD_INVOCATION")
                || !site.trees("getTypeArguments").isEmpty()
                || !siteArgument(site)) return null;
        var binding = unit.bindings().get(site.id());
        if (binding == null || !binding.declarationResolved() || binding.declaredType() < 0)
            return null;
        var executable = unit.types().get(binding.declaredType());
        if (executable.kind() != JavaType.Kind.EXECUTABLE || executable.arguments().isEmpty())
            return null;
        Use returns = new Use(unit.types(), executable.returns());
        return mentionsFormal(returns, Set.copyOf(executable.arguments()), 0) ? returns : null;
    }

    private boolean mentionsFormal(Use value, Set<Integer> formals, int depth) {
        if (++work > 100_000 || depth >= 64)
            throw new IllegalArgumentException("return type lookup exceeds budget");
        if (value.type().kind() == JavaType.Kind.TYPEVAR)
            return formals.contains(value.reference());
        for (int argument : value.type().arguments())
            if (mentionsFormal(value.child(argument), formals, depth + 1)) return true;
        if (value.type().component() >= 0
                && mentionsFormal(value.child(value.type().component()), formals, depth + 1))
            return true;
        if (value.type().lowerBound() >= 0
                && mentionsFormal(value.child(value.type().lowerBound()), formals, depth + 1))
            return true;
        for (int bound : value.type().upperBounds())
            if (mentionsFormal(value.child(bound), formals, depth + 1)) return true;
        return false;
    }

    private boolean siteArgument(JavaNode site) {
        var syntax = model.syntax(unit.path());
        var parent = syntax.parent(site).orElse(null);
        while (parent != null && parent.kind().equals("PARENTHESIZED")) {
            site = parent;
            parent = syntax.parent(site).orElse(null);
        }
        return parent != null
                && parent.kind().equals("METHOD_INVOCATION")
                && parent.trees("getArguments").contains(site);
    }

    private Use expected(JavaNode site) {
        var override = expectedOverrides.get(site.id());
        if (override != null) return override;
        Use written = writtenExpected(site);
        if (written != null) return written;
        var binding = unit.bindings().get(site.id());
        return binding == null || binding.expressionType() < 0
                ? null
                : new Use(unit.types(), binding.expressionType());
    }

    private Use writtenExpected(JavaNode site) {
        Use override = expectedOverrides.get(site.id());
        if (override != null) return override;
        var syntax = model.syntax(unit.path());
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
                Result selected = resultCache().get(parent.id());
                if (selected == null && !active.contains(parent.id())) selected = resolve(parent);
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
            if (target != null) return expression(target);
            if (parent.kind().equals("RETURN")) {
                for (JavaNode declaration = syntax.parent(parent).orElse(null);
                        declaration != null;
                        declaration = syntax.parent(declaration).orElse(null)) {
                    if (++work > 100_000) return null;
                    if (declaration.kind().equals("METHOD"))
                        return expression(declaration.tree("getReturnType"));
                    if (declaration.kind().equals("LAMBDA_EXPRESSION")) {
                        Sam descriptor = sam(expected(declaration));
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

    private Result contextualInvocation(JavaNode site, Use parameter) {
        Use previous = expectedOverrides.put(site.id(), parameter);
        provisionalResults.push(new HashMap<>());
        try {
            return select(site);
        } finally {
            provisionalResults.pop();
            if (previous == null) expectedOverrides.remove(site.id());
            else expectedOverrides.put(site.id(), previous);
        }
    }

    private boolean modeledLambda(JavaNode expression) {
        var syntax = model.syntax(unit.path());
        for (var parent = syntax.parent(expression).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (!parent.kind().equals("LAMBDA_EXPRESSION")) continue;
            if (expectedOverrides.containsKey(parent.id())) return true;
            var outer = syntax.parent(parent).orElse(null);
            if (outer == null) return false;
            var result = resultCache().get(outer.id());
            return result instanceof Selected || result instanceof ExternalSelected;
        }
        return false;
    }

    private Use lambdaParameter(JavaNode expression) {
        var binding = unit.bindings().get(expression.id());
        if (binding == null || !binding.kind().equals("PARAMETER")) return null;
        var syntax = model.syntax(unit.path());
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
                var descriptor = sam(expected(parent));
                return descriptor == null || i >= descriptor.parameters().size()
                        ? null
                        : descriptor.parameters().get(i);
            }
        }
        return null;
    }

    private Match inferPoly(
            JavaNode poly,
            Use parameter,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth) {
        var descriptor = sam(parameter);
        if (descriptor == null) return Match.UNKNOWN;
        if (poly.kind().equals("MEMBER_REFERENCE")
                && !poly.text("getMode").equals("NEW")
                && !descriptor.parameters().isEmpty()) {
            Receiver receiver = receiver(poly);
            Use input = descriptor.parameters().getFirst();
            if (receiver != null
                    && receiver.typeQualifier()
                    && staticReference(
                                    receiver.type(),
                                    methodName(poly),
                                    descriptor.parameters().size(),
                                    descriptor.parameters().getFirst())
                            == Match.NO
                    && containsFormal(input, formals, 0)
                    && unboundTarget(input, 0)) {
                Match constraint = infer(receiver.type(), input, formals, variables, depth + 1);
                if (constraint != Match.YES) return constraint;
            }
        }
        Use previous = expectedOverrides.put(poly.id(), parameter);
        provisionalResults.push(new HashMap<>());
        try {
            Use returns;
            if (poly.kind().equals("MEMBER_REFERENCE")) {
                if (!probingReferences.add(poly.id())) return Match.UNKNOWN;
                Result result;
                try {
                    result = select(poly);
                } finally {
                    probingReferences.remove(poly.id());
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
                    ReturnSites sites = returnSites(body);
                    if (!sites.complete()) return Match.UNKNOWN;
                    boolean voidReturn =
                            descriptor.returns().resolved().type().kind() == JavaType.Kind.VOID;
                    if (voidReturn)
                        return sites.nodes().stream()
                                        .allMatch(node -> node.tree("getExpression") == null)
                                ? Match.YES
                                : Match.NO;
                    Match completion = abruptCompletion(body, 0);
                    if (completion != Match.YES)
                        return completion == Match.NO ? Match.NO : Match.UNKNOWN;
                    if (sites.nodes().isEmpty()) return Match.UNKNOWN;
                    for (JavaNode site : sites.nodes()) {
                        JavaNode returned = site.tree("getExpression");
                        if (invalidConditional(returned)) return Match.NO;
                        Use value = expression(returned);
                        if (value == null) return Match.UNKNOWN;
                        Match constraint =
                                infer(value, descriptor.returns(), formals, variables, depth + 1);
                        if (constraint != Match.YES) return constraint;
                    }
                    return Match.YES;
                }
                if (invalidConditional(body)) return Match.NO;
                returns = body == null ? null : expression(body);
            }
            if (returns != null
                    && returns.resolved().type().kind() == JavaType.Kind.VOID
                    && descriptor.returns().resolved().type().kind() != JavaType.Kind.VOID)
                return Match.NO;
            return returns == null
                    ? Match.UNKNOWN
                    : infer(returns, descriptor.returns(), formals, variables, depth + 1);
        } finally {
            provisionalResults.pop();
            if (previous == null) expectedOverrides.remove(poly.id());
            else expectedOverrides.put(poly.id(), previous);
        }
    }

    private boolean invalidConditional(JavaNode node) {
        for (int depth = 0; node != null && node.kind().equals("PARENTHESIZED"); depth++) {
            if (++work > 100_000 || depth >= 64) return false;
            node = node.tree("getExpression");
        }
        if (node == null || !node.kind().equals("CONDITIONAL_EXPRESSION")) return false;
        Use condition = expression(node.tree("getCondition"));
        return condition != null
                && !booleanValue(condition)
                && !Set.of(
                                JavaType.Kind.ERROR,
                                JavaType.Kind.NONE,
                                JavaType.Kind.OTHER,
                                JavaType.Kind.TYPEVAR,
                                JavaType.Kind.WILDCARD)
                        .contains(condition.resolved().type().kind());
    }

    private Use ground(Use type) {
        Use current = type;
        for (int i = 0; i < 64; i++) {
            current = current.resolved();
            if (current.type().kind() != JavaType.Kind.WILDCARD) return current;
            if (current.type().lowerBound() >= 0)
                current = current.child(current.type().lowerBound());
            else if (!current.type().upperBounds().isEmpty())
                current = current.child(current.type().upperBounds().getFirst());
            else return namedType("java.lang.Object");
        }
        return null;
    }

    private Sam sam(Use functional) {
        if (functional == null) return null;
        var root = functional.resolved();
        var lookup = model.type(root.name());
        if (lookup instanceof JavaModel.FoundType found) {
            if (!model.declaration(found.declaration()).symbol().kind().equals("INTERFACE"))
                return null;
        } else if (lookup instanceof JavaModel.MissingType && binaries != null) {
            var type = binaries.find(root.name());
            if (type.isEmpty() || (type.get().access() & Opcodes.ACC_INTERFACE) == 0) return null;
        } else return null;
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(root);
        Set<String> visited = new HashSet<>();
        Map<String, Sam> descriptors = new LinkedHashMap<>();
        Set<String> concrete = new HashSet<>();
        Map<String, Use> declaringOwners = new HashMap<>();
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256) return null;
            Use current = pending.removeFirst().resolved();
            if (!visited.add(current.name()) || current.name().equals("java.lang.Object")) continue;
            var currentLookup = model.type(current.name());
            if (currentLookup instanceof JavaModel.FoundType source) {
                var owner = model.declaration(source.declaration());
                var declaring = model.unit(source.declaration().source());
                var variables = owner(source.declaration(), current, 0).variables();
                for (var member : model.members(source.declaration())) {
                    var method = model.declaration(member);
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
                    if (!samMember(
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
            } else if (currentLookup instanceof JavaModel.MissingType && binaries != null) {
                var found = binaries.find(current.name());
                if (found.isEmpty()) return null;
                var type = found.get();
                try {
                    var hierarchy = JavaBinaryTypes.hierarchy(type);
                    var variables = binaryVariables(hierarchy, current);
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
                        if (!samMember(
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

    private boolean samMember(
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
            Use type = ground(new Use(types, parameter, variables));
            if (type == null) return false;
            parameters.add(type);
        }
        var candidate =
                new Sam(List.copyOf(parameters), new Use(types, executable.returns(), variables));
        String key =
                name + "(" + String.join(",", parameters.stream().map(Use::name).toList()) + ")";
        Use previousOwner = declaringOwners.putIfAbsent(key, declaringOwner);
        if (previousOwner != null && !previousOwner.name().equals(declaringOwner.name())) {
            if (sourceHierarchy(previousOwner, namedType(declaringOwner.name()), 0) == Match.YES)
                return true;
            if (sourceHierarchy(declaringOwner, namedType(previousOwner.name()), 0) == Match.YES) {
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
        if (arguments(previous.parameters(), candidate.parameters(), true) != Match.YES
                || arguments(candidate.parameters(), previous.parameters(), true) != Match.YES)
            return false;
        if (strictAssignable(candidate.returns(), previous.returns()) == Match.YES) {
            descriptors.put(key, candidate);
            return true;
        }
        return strictAssignable(previous.returns(), candidate.returns()) == Match.YES;
    }

    private Match staticReference(Use receiver, String name, int argumentCount, Use firstInput) {
        boolean statics = false, instances = false;
        boolean instancePossible =
                firstInput == null || strictAssignable(firstInput, receiver) != Match.NO;
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(receiver);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256) return Match.UNKNOWN;
            Use current = pending.removeFirst().resolved();
            if (!visited.add(current.name())) continue;
            var lookup = model.type(current.name());
            if (lookup instanceof JavaModel.FoundType found) {
                var declaring = model.unit(found.declaration().source());
                var owner = model.declaration(found.declaration());
                var variables = owner(found.declaration(), current, 0).variables();
                for (var member : model.members(found.declaration())) {
                    var method = model.declaration(member);
                    if (!method.symbol().kind().equals("METHOD")
                            || !method.symbol().name().equals(name)) continue;
                    var executable = declaring.types().get(method.symbol().declaredType());
                    if (executable.kind() != JavaType.Kind.EXECUTABLE) return Match.UNKNOWN;
                    if (method.symbol().modifiers().contains("static")) {
                        if (owner.symbol().kind().equals("INTERFACE")
                                && !current.name().equals(receiver.name())) continue;
                        if (arity(
                                executable.parameters().size(),
                                argumentCount,
                                method.symbol().variadic())) statics = true;
                    } else if (instancePossible
                            && arity(
                                    executable.parameters().size(),
                                    argumentCount - 1,
                                    method.symbol().variadic())) instances = true;
                }
                if (owner.superclass() >= 0)
                    pending.add(new Use(declaring.types(), owner.superclass(), variables));
                for (int parent : owner.interfaces())
                    pending.add(new Use(declaring.types(), parent, variables));
            } else if (lookup instanceof JavaModel.MissingType && binaries != null) {
                var found = binaries.find(current.name());
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
                            if (arity(count, argumentCount, variadic)) statics = true;
                        } else if (instancePossible && arity(count, argumentCount - 1, variadic))
                            instances = true;
                    }
                    var hierarchy = JavaBinaryTypes.hierarchy(type);
                    var variables = binaryVariables(hierarchy, current);
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

    private JavaModel.DeclarationRef enclosingType(JavaNode node) {
        var syntax = model.syntax(unit.path());
        for (var parent = syntax.parent(node).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (!Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                    .contains(parent.kind())) continue;
            var typeSyntax = parent;
            var declarations =
                    model.declarationsAt(unit.path(), parent.span()).stream()
                            .filter(
                                    reference ->
                                            JavaModel.matches(
                                                    typeSyntax,
                                                    model.declaration(reference).symbol()))
                            .toList();
            if (declarations.size() == 1) return declarations.getFirst();
            return null;
        }
        return null;
    }

    private boolean accessible(
            JavaDeclaration owner,
            JavaDeclaration member,
            JavaNode site,
            Receiver receiver,
            int inheritedDepth) {
        boolean constructor = member.symbol().kind().equals("CONSTRUCTOR");
        var modifiers = member.symbol().modifiers();
        if (!constructor && receiver.typeQualifier && !modifiers.contains("static")) return false;
        if (inheritedDepth > 0
                && owner.symbol().kind().equals("INTERFACE")
                && modifiers.contains("static")) return false;
        var enclosing = enclosingType(site);
        if (enclosing == null) return false;
        var caller = model.declaration(enclosing);
        JavaDeclaration accessibleType = owner;
        if (inheritedDepth > 0
                && model.type(receiver.type.name()) instanceof JavaModel.FoundType type)
            accessibleType = model.declaration(type.declaration());
        if (!typeAccessible(accessibleType, caller, 0)) return false;
        if (!constructor && !modifiers.contains("static") && staticContext(site)) return false;
        if (modifiers.contains("public")) return true;
        if (modifiers.contains("private"))
            return inheritedDepth == 0
                    && nest(owner.binaryName()).equals(nest(caller.binaryName()));
        if (packageName(owner.binaryName()).equals(packageName(caller.binaryName()))) {
            if (!constructor && inheritedDepth > 0 && !modifiers.contains("protected"))
                return inheritsPackageMember(
                        receiver.type(), owner.canonicalName(), packageName(owner.binaryName()), 0);
            return true;
        }
        if (!modifiers.contains("protected")
                || constructor && !"super".equals(explicitConstructorName(site))) return false;
        if (sourceHierarchy(namedType(caller.canonicalName()), namedType(owner.canonicalName()), 0)
                != Match.YES) return false;
        return constructor
                || modifiers.contains("static")
                || sourceHierarchy(receiver.type, namedType(caller.canonicalName()), 0)
                        == Match.YES;
    }

    private boolean inheritsPackageMember(
            Use receiver, String owner, String ownerPackage, int depth) {
        if (++work > 100_000 || depth >= 64 || !packageName(receiver.name()).equals(ownerPackage))
            return false;
        if (receiver.name().equals(owner)) return true;
        var lookup = model.type(receiver.name());
        if (lookup instanceof JavaModel.FoundType found) {
            var declaration = model.declaration(found.declaration());
            if (declaration.superclass() < 0) return false;
            var source = model.unit(found.declaration().source());
            return inheritsPackageMember(
                    new Use(
                            source.types(),
                            declaration.superclass(),
                            owner(found.declaration(), receiver, depth).variables()),
                    owner,
                    ownerPackage,
                    depth + 1);
        }
        var binary =
                binaries == null
                        ? Optional.<JavaBinaryType>empty()
                        : binaries.find(receiver.name());
        if (binary.isEmpty()) return false;
        try {
            var hierarchy = JavaBinaryTypes.hierarchy(binary.get());
            return hierarchy.superclass() >= 0
                    && inheritsPackageMember(
                            new Use(
                                    hierarchy.types(),
                                    hierarchy.superclass(),
                                    binaryVariables(hierarchy, receiver)),
                            owner,
                            ownerPackage,
                            depth + 1);
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    private boolean typeAccessible(JavaDeclaration owner, JavaDeclaration caller, int depth) {
        if (++work > 100_000 || depth >= 64) return false;
        boolean sameNest = nest(owner.binaryName()).equals(nest(caller.binaryName()));
        boolean samePackage =
                packageName(owner.binaryName()).equals(packageName(caller.binaryName()));
        var modifiers = owner.symbol().modifiers();
        if (modifiers.contains("private") && !sameNest) return false;
        if (!modifiers.contains("public") && !sameNest && !samePackage) {
            if (!modifiers.contains("protected")) return false;
            var parent = model.type(owner.symbol().owner());
            if (!(parent instanceof JavaModel.FoundType found)
                    || sourceHierarchy(
                                    namedType(caller.canonicalName()),
                                    namedType(
                                            model.declaration(found.declaration()).canonicalName()),
                                    0)
                            != Match.YES) return false;
        }
        if (owner.symbol().owner().isEmpty()) return true;
        var parent = model.type(owner.symbol().owner());
        return !(parent instanceof JavaModel.FoundType found)
                || typeAccessible(model.declaration(found.declaration()), caller, depth + 1);
    }

    private boolean staticContext(JavaNode site) {
        if (site.kind().equals("MEMBER_REFERENCE")) return false;
        var syntax = model.syntax(unit.path());
        JavaNode select = site.tree("getMethodSelect");
        // Explicit instance receivers remain legal in a static method.
        if (select != null && select.kind().equals("MEMBER_SELECT")) return false;
        for (var parent = syntax.parent(site).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (Set.of("CLASS", "INTERFACE", "ENUM", "RECORD").contains(parent.kind()))
                return false;
            if (parent.kind().equals("METHOD") || parent.kind().equals("VARIABLE")) {
                var modifiers = parent.tree("getModifiers");
                if (modifiers != null
                        && modifiers.values("getFlags").stream()
                                .anyMatch(flag -> flag.toString().equalsIgnoreCase("static")))
                    return true;
                if (parent.kind().equals("METHOD")) return false;
            }
            if (parent.kind().equals("BLOCK") && parent.text("isStatic").equals("true"))
                return true;
        }
        return false;
    }

    private Match assignable(Use argument, Use parameter, int depth) {
        if (depth > 64 || ++work > 100_000) return Match.UNKNOWN;
        var poly = polyNodes.get(argument);
        if (poly != null) {
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS").contains(poly.kind())) {
                Result result = contextualInvocation(poly, parameter);
                if (result instanceof Blocked) return Match.NO;
                Use value =
                        result instanceof Selected source
                                ? source.returns()
                                : result instanceof ExternalSelected external
                                        ? external.returns()
                                        : null;
                return value == null ? Match.UNKNOWN : assignable(value, parameter, depth + 1);
            }
            var descriptor = sam(parameter);
            if (descriptor == null) {
                var lookup = model.type(parameter.name());
                if (lookup instanceof JavaModel.FoundType found
                        && !model.declaration(found.declaration())
                                .symbol()
                                .kind()
                                .equals("INTERFACE")) return Match.NO;
                var binary =
                        binaries == null
                                ? Optional.<JavaBinaryType>empty()
                                : binaries.find(parameter.name());
                return primitive(parameter.name())
                                || binary.isPresent()
                                        && (binary.get().access() & Opcodes.ACC_INTERFACE) == 0
                        ? Match.NO
                        : Match.UNKNOWN;
            }
            if (poly.kind().equals("LAMBDA_EXPRESSION")) {
                var parameters = poly.trees("getParameters");
                if (parameters.size() != descriptor.parameters().size()) return Match.NO;
                for (int i = 0; i < parameters.size(); i++) {
                    var written = parameters.get(i).tree("getType");
                    if (written == null
                            || !written.span().present()
                            || written.text("originalSourceTree").equals("false")) continue;
                    Use actualType = expression(written);
                    if (actualType == null
                            || same(actualType, descriptor.parameters().get(i), 0) != Match.YES)
                        return Match.UNKNOWN;
                }
            }
            return polyReturnCompatible(poly, parameter, descriptor, depth + 1);
        }
        Use actual = argument.resolved(), expected = parameter.resolved();
        var source = actual.type();
        var target = expected.type();
        if (source.kind() == JavaType.Kind.TYPEVAR
                && target.kind() == JavaType.Kind.TYPEVAR
                && actual.types() == expected.types()
                && actual.reference() == expected.reference())
            return sameSubstitutions(actual, expected, depth + 1);
        if ((source.kind() == JavaType.Kind.TYPEVAR || source.kind() == JavaType.Kind.WILDCARD)
                && target.kind() != JavaType.Kind.TYPEVAR
                && target.kind() != JavaType.Kind.ERROR) {
            if (source.kind() == JavaType.Kind.WILDCARD && source.upperBounds().isEmpty())
                return assignable(namedType("java.lang.Object"), expected, depth + 1);
            for (int bound : source.upperBounds())
                if (assignable(actual.child(bound), expected, depth + 1) == Match.YES)
                    return Match.YES;
            return Match.UNKNOWN;
        }
        if (source.kind() == JavaType.Kind.ERROR
                || target.kind() == JavaType.Kind.ERROR
                || source.kind() == JavaType.Kind.TYPEVAR
                || target.kind() == JavaType.Kind.TYPEVAR
                || source.kind() == JavaType.Kind.WILDCARD) return Match.UNKNOWN;
        if (target.kind() == JavaType.Kind.WILDCARD) return contained(actual, expected, depth + 1);
        if (target.kind() == JavaType.Kind.INTERSECTION) {
            Match result = Match.YES;
            for (int bound : target.upperBounds()) {
                Match match = assignable(actual, expected.child(bound), depth + 1);
                if (match == Match.NO) return Match.NO;
                if (match == Match.UNKNOWN) result = Match.UNKNOWN;
            }
            return target.upperBounds().isEmpty() ? Match.UNKNOWN : result;
        }
        if (source.kind() == JavaType.Kind.NULL)
            return target.kind() == JavaType.Kind.DECLARED || target.kind() == JavaType.Kind.ARRAY
                    ? Match.YES
                    : Match.NO;
        if (source.kind() == JavaType.Kind.ARRAY && target.kind() == JavaType.Kind.ARRAY)
            return primitive(actual.child(source.component()).name())
                            || primitive(expected.child(target.component()).name())
                    ? same(
                            actual.child(source.component()),
                            expected.child(target.component()),
                            depth + 1)
                    : assignable(
                            actual.child(source.component()),
                            expected.child(target.component()),
                            depth + 1);
        if (source.kind() == JavaType.Kind.DECLARED && target.kind() == JavaType.Kind.ARRAY)
            return Match.NO;
        String from = actual.name(), to = expected.name();
        if (from.equals(to)) {
            if (target.arguments().isEmpty()) return Match.YES;
            if (source.kind() == JavaType.Kind.DECLARED
                    && source.arguments().isEmpty()
                    && target.kind() == JavaType.Kind.DECLARED
                    && genericArity(from) == target.arguments().size()) return Match.YES;
            if (source.arguments().size() != target.arguments().size()) return Match.UNKNOWN;
            Match result = Match.YES;
            for (int i = 0; i < target.arguments().size(); i++) {
                Use argumentType = actual.child(source.arguments().get(i));
                Use parameterType = expected.child(target.arguments().get(i));
                var comparison =
                        parameterType.resolved().type().kind() == JavaType.Kind.WILDCARD
                                ? contained(argumentType, parameterType, depth + 1)
                                : same(argumentType, parameterType, depth + 1);
                if (comparison == Match.NO) return Match.NO;
                if (comparison == Match.UNKNOWN) result = Match.UNKNOWN;
            }
            return result;
        }
        if (BOXES.getOrDefault(from, "").equals(to) || BOXES.getOrDefault(to, "").equals(from))
            return Match.YES;
        if (to.equals("java.lang.Object") && source.kind() != JavaType.Kind.VOID) return Match.YES;
        if (primitive(from) && primitive(to)) return widens(from, to) ? Match.YES : Match.NO;
        if (source.kind() == JavaType.Kind.DECLARED && primitive(to)) {
            for (var box : BOXES.entrySet())
                if (box.getValue().equals(from))
                    return widens(box.getKey(), to) ? Match.YES : Match.NO;
            return Match.NO;
        }
        if (primitive(from) && target.kind() == JavaType.Kind.DECLARED)
            return sourceHierarchy(namedType(BOXES.get(from)), expected, depth + 1);
        if (source.kind() == JavaType.Kind.ARRAY && target.kind() == JavaType.Kind.DECLARED)
            return Set.of("java.lang.Cloneable", "java.io.Serializable").contains(to)
                    ? Match.YES
                    : Match.NO;
        if (source.kind() == JavaType.Kind.DECLARED && target.kind() == JavaType.Kind.DECLARED)
            return sourceHierarchy(actual, expected, depth + 1);
        return Match.UNKNOWN;
    }

    private Match polyReturnCompatible(JavaNode poly, Use parameter, Sam descriptor, int depth) {
        Use previous = expectedOverrides.put(poly.id(), parameter);
        provisionalResults.push(new HashMap<>());
        try {
            if (poly.kind().equals("MEMBER_REFERENCE")) {
                Result selected = select(poly);
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
                if (invalidConditional(body)) return Match.NO;
                Use returns = expression(body);
                return returns == null
                        ? Match.UNKNOWN
                        : assignable(returns, descriptor.returns(), depth + 1);
            }
            Match match = voidReturn ? Match.YES : abruptCompletion(body, 0);
            if (match != Match.YES) return match;
            ReturnSites sites = returnSites(body);
            if (!sites.complete()) return Match.UNKNOWN;
            for (JavaNode node : sites.nodes()) {
                JavaNode value = node.tree("getExpression");
                if (voidReturn != (value == null)) return Match.NO;
                if (!voidReturn) {
                    if (invalidConditional(value)) return Match.NO;
                    Use returns = expression(value);
                    Match compatible =
                            returns == null
                                    ? Match.UNKNOWN
                                    : assignable(returns, descriptor.returns(), depth + 1);
                    if (compatible == Match.NO) return Match.NO;
                    if (compatible == Match.UNKNOWN) match = Match.UNKNOWN;
                }
            }
            return match;
        } finally {
            provisionalResults.pop();
            if (previous == null) expectedOverrides.remove(poly.id());
            else expectedOverrides.put(poly.id(), previous);
        }
    }

    private record ReturnSites(List<JavaNode> nodes, boolean complete) {}

    private ReturnSites returnSites(JavaNode body) {
        List<JavaNode> returns = new ArrayList<>();
        ArrayDeque<JavaNode> pending = new ArrayDeque<>();
        pending.push(body);
        while (!pending.isEmpty()) {
            if (++work > 100_000 || pending.size() > 4096) return new ReturnSites(List.of(), false);
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

    /** Prove only simple terminal paths; complex flow remains incomplete. */
    private Match abruptCompletion(JavaNode statement, int depth) {
        if (statement == null) return Match.NO;
        if (++work > 100_000 || depth >= 64) return Match.UNKNOWN;
        return switch (statement.kind()) {
            case "RETURN", "THROW" -> Match.YES;
            case "BLOCK" -> {
                Match completion = Match.NO;
                for (JavaNode child : statement.trees("getStatements")) {
                    Match next = abruptCompletion(child, depth + 1);
                    if (next == Match.YES) {
                        completion = Match.YES;
                        break;
                    }
                    if (next == Match.UNKNOWN) completion = Match.UNKNOWN;
                }
                yield completion;
            }
            case "IF" -> {
                Match thenPart = abruptCompletion(statement.tree("getThenStatement"), depth + 1);
                Match elsePart = abruptCompletion(statement.tree("getElseStatement"), depth + 1);
                yield thenPart == Match.YES && elsePart == Match.YES
                        ? Match.YES
                        : thenPart == Match.UNKNOWN || elsePart == Match.UNKNOWN
                                ? Match.UNKNOWN
                                : Match.NO;
            }
            case "TRY" -> {
                Match cleanup = abruptCompletion(statement.tree("getFinallyBlock"), depth + 1);
                if (cleanup == Match.YES || cleanup == Match.UNKNOWN) yield cleanup;
                Match body = abruptCompletion(statement.tree("getBlock"), depth + 1);
                for (JavaNode handler : statement.trees("getCatches")) {
                    Match branch = abruptCompletion(handler.tree("getBlock"), depth + 1);
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

    private static Use namedType(String name) {
        var type =
                new JavaType(
                        JavaType.Kind.DECLARED,
                        name,
                        name,
                        -1,
                        List.of(),
                        List.of(),
                        -1,
                        -1,
                        List.of(),
                        -1,
                        List.of());
        return new Use(new JavaTypes(List.of(type)), 0);
    }

    private static Use scalarType(JavaType.Kind kind) {
        String name = kind.name().toLowerCase(Locale.ROOT);
        return new Use(
                new JavaTypes(
                        List.of(
                                new JavaType(
                                        kind, name, name, -1, List.of(), List.of(), -1, -1,
                                        List.of(), -1, List.of()))),
                0);
    }

    private static boolean numeric(Use value) {
        if (value == null) return false;
        String name = value.name();
        for (var box : BOXES.entrySet())
            if (!box.getKey().equals("boolean")
                    && (name.equals(box.getKey()) || name.equals(box.getValue()))) return true;
        return false;
    }

    private int genericArity(String name) {
        if (++work > 100_000) return -1;
        var source = model.type(name);
        if (source instanceof JavaModel.FoundType found) {
            var declaration = model.declaration(found.declaration());
            return model.unit(found.declaration().source())
                    .types()
                    .get(declaration.symbol().declaredType())
                    .arguments()
                    .size();
        }
        if (!(source instanceof JavaModel.MissingType) || binaries == null) return -1;
        var binary = binaries.find(name);
        if (binary.isEmpty()) return -1;
        try {
            return JavaBinaryTypes.hierarchy(binary.get()).parameters().size();
        } catch (IllegalArgumentException unavailable) {
            return -1;
        }
    }

    private static boolean booleanValue(Use value) {
        return value != null && Set.of("boolean", "java.lang.Boolean").contains(value.name());
    }

    private Match contained(Use actual, Use expected, int depth) {
        if (++work > 100_000 || depth >= 64) return Match.UNKNOWN;
        var wildcard = expected.resolved();
        var type = wildcard.type();
        if (type.kind() != JavaType.Kind.WILDCARD) return same(actual, expected, depth + 1);
        var source = actual.resolved();
        if (source.type().kind() == JavaType.Kind.WILDCARD) {
            if (type.lowerBound() >= 0)
                return source.type().lowerBound() < 0
                        ? Match.NO
                        : assignable(
                                wildcard.child(type.lowerBound()),
                                source.child(source.type().lowerBound()),
                                depth + 1);
            Use sourceUpper =
                    source.type().upperBounds().isEmpty()
                            ? namedType("java.lang.Object")
                            : source.child(source.type().upperBounds().getFirst());
            Match result = Match.YES;
            for (int upper : type.upperBounds()) {
                Match match = assignable(sourceUpper, wildcard.child(upper), depth + 1);
                if (match == Match.NO) return Match.NO;
                if (match == Match.UNKNOWN) result = Match.UNKNOWN;
            }
            return result;
        }
        if (type.lowerBound() >= 0)
            return assignable(wildcard.child(type.lowerBound()), actual, depth + 1);
        Match result = Match.YES;
        for (int upper : type.upperBounds()) {
            Match match = assignable(actual, wildcard.child(upper), depth + 1);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    private Match sourceHierarchy(Use actual, Use expected, int depth) {
        TypeProjection projection = projectType(actual, expected.name(), depth);
        return projection.match() == Match.YES
                ? assignable(projection.type(), expected, depth + 1)
                : projection.match();
    }

    private TypeProjection projectType(Use actual, String targetName, int depth) {
        var targetLookup = model.type(targetName);
        boolean classTarget;
        if (targetLookup instanceof JavaModel.FoundType target) {
            String targetKind = model.declaration(target.declaration()).symbol().kind();
            classTarget = Set.of("CLASS", "ENUM", "RECORD").contains(targetKind);
        } else {
            var target =
                    binaries == null ? Optional.<JavaBinaryType>empty() : binaries.find(targetName);
            if (target.isEmpty()) return new TypeProjection(null, Match.UNKNOWN);
            classTarget = (target.get().access() & Opcodes.ACC_INTERFACE) == 0;
        }
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(actual);
        Set<String> visited = new HashSet<>();
        boolean unknown = false;
        while (!pending.isEmpty()) {
            if (++work > 100_000 || depth > 64 || visited.size() >= 256)
                return new TypeProjection(null, Match.UNKNOWN);
            Use current = pending.removeFirst();
            if (!visited.add(current.name())) continue;
            if (current.name().equals(targetName)) return new TypeProjection(current, Match.YES);
            if (current.name().equals("java.lang.Object")) continue;
            var lookup = model.type(current.name());
            if (!(lookup instanceof JavaModel.FoundType found)) {
                var binary =
                        binaries == null
                                ? Optional.<JavaBinaryType>empty()
                                : binaries.find(current.name());
                if (binary.isEmpty()) {
                    unknown = true;
                    continue;
                }
                try {
                    var hierarchy = JavaBinaryTypes.hierarchy(binary.get());
                    var variables = binaryVariables(hierarchy, current);
                    if (binary.get().superclass() != null && hierarchy.superclass() >= 0)
                        pending.add(new Use(hierarchy.types(), hierarchy.superclass(), variables));
                    if (!classTarget)
                        for (int parent : hierarchy.interfaces())
                            pending.add(new Use(hierarchy.types(), parent, variables));
                } catch (IllegalArgumentException unavailable) {
                    unknown = true;
                }
                continue;
            }
            var declaration = model.declaration(found.declaration());
            var source = model.unit(found.declaration().source());
            var variables = owner(found.declaration(), current, depth).variables();
            if (declaration.superclass() >= 0)
                pending.add(new Use(source.types(), declaration.superclass(), variables));
            if (!classTarget)
                for (int parent : declaration.interfaces())
                    pending.add(new Use(source.types(), parent, variables));
        }
        return new TypeProjection(null, unknown ? Match.UNKNOWN : Match.NO);
    }

    private Match sameSubstitutions(Use first, Use second, int depth) {
        if (++work > 100_000 || depth >= 64) return Match.UNKNOWN;
        if (first.variables() == second.variables()) return Match.YES;
        if (first.variables().size() != second.variables().size()) return Match.UNKNOWN;
        Match result = Match.YES;
        for (var entry : first.variables().entrySet()) {
            Use other = second.variables().get(entry.getKey());
            if (other == null) return Match.UNKNOWN;
            Match match = same(entry.getValue(), other, depth + 1);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    private Match same(Use first, Use second, int depth) {
        if (depth > 64 || ++work > 100_000) return Match.UNKNOWN;
        var left = first.resolved();
        var right = second.resolved();
        if (left.type().kind() == JavaType.Kind.TYPEVAR
                && right.type().kind() == JavaType.Kind.TYPEVAR
                && left.types() == right.types()
                && left.reference() == right.reference())
            return sameSubstitutions(left, right, depth + 1);
        if (left.type().kind() == JavaType.Kind.WILDCARD
                && right.type().kind() == JavaType.Kind.WILDCARD) {
            if (left.capture() != null
                    && right.capture() != null
                    && !left.capture().equals(right.capture())) return Match.NO;
            Match forward = contained(left, right, depth + 1);
            return forward == Match.YES ? contained(right, left, depth + 1) : forward;
        }
        if (Set.of(JavaType.Kind.TYPEVAR, JavaType.Kind.ERROR, JavaType.Kind.WILDCARD)
                        .contains(left.type().kind())
                || Set.of(JavaType.Kind.TYPEVAR, JavaType.Kind.ERROR, JavaType.Kind.WILDCARD)
                        .contains(right.type().kind())) return Match.UNKNOWN;
        if (!left.name().equals(right.name())) return Match.NO;
        if (left.type().arguments().size() != right.type().arguments().size()) return Match.UNKNOWN;
        Match result = Match.YES;
        for (int i = 0; i < left.type().arguments().size(); i++) {
            var match = same(left.argument(i), right.argument(i), depth + 1);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    private static String methodName(JavaNode site) {
        if (site.kind().equals("MEMBER_REFERENCE")) return site.text("getName");
        var select = site.tree("getMethodSelect");
        return select.kind().equals("MEMBER_SELECT")
                ? select.text("getIdentifier")
                : select.text("getName");
    }

    private static String packageName(String binary) {
        int dot = binary.lastIndexOf('.');
        return dot < 0 ? "" : binary.substring(0, dot);
    }

    private static int visibility(List<String> modifiers) {
        if (modifiers.contains("public")) return 3;
        if (modifiers.contains("protected")) return 2;
        return modifiers.contains("private") ? 0 : 1;
    }

    private static String nest(String binary) {
        int nested = binary.indexOf('$');
        return nested < 0 ? binary : binary.substring(0, nested);
    }

    private static boolean primitive(String name) {
        return BOXES.containsKey(name);
    }

    private static boolean widens(String from, String to) {
        return switch (from) {
            case "byte" -> Set.of("short", "int", "long", "float", "double").contains(to);
            case "short", "char" -> Set.of("int", "long", "float", "double").contains(to);
            case "int" -> Set.of("long", "float", "double").contains(to);
            case "long" -> Set.of("float", "double").contains(to);
            case "float" -> to.equals("double");
            default -> false;
        };
    }
}
