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
    final ModeledTypeOps modeledTypeOps = new ModeledTypeOps(this);
    final ModeledCandidates modeledCandidates = new ModeledCandidates(this);
    final ModeledInference modeledInference = new ModeledInference(this);
    final ModeledSelection modeledSelection = new ModeledSelection(this);

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

    enum Match {
        YES,
        NO,
        UNKNOWN
    }

    record Capture(int site, String path) {}

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

        Use argument(int index) {
            return new Use(
                    types,
                    type().arguments().get(index),
                    variables,
                    capture == null
                            ? null
                            : new Capture(capture.site(), capture.path() + "/" + index));
        }
    }

    record Receiver(Use type, boolean typeQualifier, List<Use> roots) {
        Receiver(Use type, boolean typeQualifier) {
            this(type, typeQualifier, List.of(type));
        }
    }

    record Nearest(int depth, String owner, JavaModel.DeclarationRef declaration) {}

    record CallableName(String owner, String name, boolean staticOnly) {}

    final Map<CallableName, Boolean> callableNames = new HashMap<>();
    final Map<String, List<Use>> importedRoots = new HashMap<>();

    record Sam(List<Use> parameters, Use returns) {}

    record TypeProjection(Use type, Match match) {}

    record Owner(JavaModel.DeclarationRef declaration, Map<String, Use> variables, int depth) {}

    record LocatedType(Use type, int depth) {}

    record SourceParents(List<Owner> owners, boolean incomplete) {}

    final Map<String, Boolean> binarySourceParents = new LinkedHashMap<>();

    record Candidate(
            JavaModel.DeclarationRef declaration,
            Use returns,
            String callKind,
            List<Use> parameters,
            int phase) {}

    record Applicability(Match match, int phase, List<Use> parameters) {}

    record BinaryCandidate(
            ExternalSelected selected,
            List<Use> parameters,
            int phase,
            int access,
            String descriptor) {}

    record BinarySearch(
            List<BinaryCandidate> candidates,
            int uncertainPhase,
            int namedMethods,
            boolean incomplete,
            Result failure) {
        static BinarySearch failed(Result failure) {
            return new BinarySearch(List.of(), 0, 0, true, failure);
        }
    }

    record HiddenSource(String owner, String parameters) {}

    record MixedCandidate(
            Result selected, List<Use> parameters, int phase, int access, String descriptor) {}

    static final Map<String, String> BOXES =
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
    final JavaModel model;
    final JavaUnit unit;
    final JavaBinaryRepository binaries;
    final Map<Integer, Result> results = new HashMap<>();
    final Deque<Map<Integer, Result>> provisionalResults = new ArrayDeque<>();
    final Set<Integer> active = new HashSet<>();
    int work;
    final Map<Use, JavaNode> polyNodes = new IdentityHashMap<>();
    final Map<Integer, Use> expectedOverrides = new HashMap<>();
    final Set<Integer> probingReferences = new HashSet<>();

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

    Map<Integer, Result> resultCache() {
        return provisionalResults.isEmpty() ? results : provisionalResults.peek();
    }

    enum InputEvidence {
        PRESENT,
        ABSENT,
        INCOMPLETE
    }

    static final class EvidenceBudgetExceeded extends IllegalArgumentException {
        static final long serialVersionUID = 1L;

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

    Result resolve(JavaNode site) {
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

    Result select(JavaNode site) {
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

    TypeProjection constructorType(JavaNode site, Receiver receiver, boolean noArguments) {
        return modeledSelection.constructorType(site, receiver, noArguments);
    }

    Result referenceResult(JavaNode site, Receiver receiver, boolean constructor, Result result) {
        return modeledSelection.referenceResult(site, receiver, constructor, result);
    }

    static boolean arity(int parameters, int arguments, boolean variadic) {
        return ModeledSelection.arity(parameters, arguments, variadic);
    }

    Applicability applicability(List<Use> actual, List<Use> parameters, boolean variadic) {
        return modeledSelection.applicability(actual, parameters, variadic);
    }

    Match arguments(List<Use> actual, List<Use> parameters, boolean strict) {
        return modeledSelection.arguments(actual, parameters, strict);
    }

    Match strictAssignable(Use actual, Use parameter) {
        return modeledTypeOps.strictAssignable(actual, parameter);
    }

    Candidate mostSpecific(List<Candidate> candidates) {
        return modeledSelection.mostSpecific(candidates);
    }

    boolean overrides(Candidate first, Candidate second) {
        return modeledSelection.overrides(first, second);
    }

    record ModeledOverride(JavaModel.DeclarationRef source, String external) {}

    record ModeledOverrides(List<ModeledOverride> targets, List<String> incompleteReasons) {}

    record OverrideParent(Use type, Set<String> packages) {}

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

    static boolean overrideVisible(
            List<String> own, List<String> parent, String parentPackage, Set<String> pathPackages) {
        return ModeledSelection.overrideVisible(own, parent, parentPackage, pathPackages);
    }

    Match overrideSignature(List<Use> own, Use returns, List<Use> parent, Use parentReturns) {
        return modeledSelection.overrideSignature(own, returns, parent, parentReturns);
    }

    Match overrideParameters(List<Use> own, List<Use> parent) {
        return modeledSelection.overrideParameters(own, parent);
    }

    Match overrideReturns(Use own, Use parent) {
        return modeledSelection.overrideReturns(own, parent);
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
        return modeledSelection.invalidOverride(
                own,
                parent,
                parentPackage,
                pathPackages,
                ownParameters,
                ownReturns,
                parentParameters,
                parentReturns);
    }

    boolean hasModeledInput(JavaNode site) {
        if (!model.hasModeledDeclarations()) return false;
        return modeledInputs(site, 0);
    }

    boolean modeledInputs(JavaNode site, int depth) {
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

    boolean modeledExpression(JavaNode expression, int depth) {
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

    boolean modeledBlock(JavaNode body, int depth) {
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

    boolean modeledReference(JavaNode site) {
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

    boolean binaryHasSourceParent(String root) {
        return modeledCandidates.binaryHasSourceParent(root);
    }

    SourceParents sourceParents(Use root, int initialDepth) {
        return modeledCandidates.sourceParents(root, initialDepth);
    }

    Owner owner(JavaModel.DeclarationRef declaration, Use type, int depth) {
        return modeledCandidates.owner(declaration, type, depth);
    }

    BinarySearch binaryCandidates(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots) {
        return modeledCandidates.binaryCandidates(site, receiver, constructor, arguments, roots);
    }

    BinarySearch binaryCandidates(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots,
            Set<String> sourceOwners) {
        return modeledCandidates.binaryCandidates(
                site, receiver, constructor, arguments, roots, sourceOwners);
    }

    Result binary(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots) {
        return modeledSelection.binary(site, receiver, constructor, arguments, roots);
    }

    static String parameterDescriptor(String descriptor) {
        int close = descriptor.indexOf(')');
        return close < 0 ? descriptor : descriptor.substring(0, close + 1);
    }

    Result selectMixed(List<MixedCandidate> candidates, int uncertainPhase) {
        return modeledSelection.selectMixed(candidates, uncertainPhase);
    }

    boolean mixedOverrides(MixedCandidate first, MixedCandidate second) {
        return modeledSelection.mixedOverrides(first, second);
    }

    boolean binaryOverrides(BinaryCandidate first, BinaryCandidate second) {
        return modeledSelection.binaryOverrides(first, second);
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
        return modeledInference.methodArguments(
                site, actual, parameters, returns, types, formalTypes, variables, variadic);
    }

    boolean satisfiesBounds(Use target, Use formal, Map<String, Use> variables) {
        return modeledInference.satisfiesBounds(target, formal, variables);
    }

    /**
     * Seed invariant return positions; wildcard and outer inference variables remain constraints.
     */
    Match seedTarget(
            Use target,
            Use pattern,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth,
            boolean wildcardHints) {
        return modeledInference.seedTarget(
                target, pattern, formals, variables, depth, wildcardHints);
    }

    boolean unboundTarget(Use value, int depth) {
        return modeledInference.unboundTarget(value, depth);
    }

    record Formal(JavaTypes types, int reference) {
        String name() {
            return types.get(reference).name();
        }
    }

    boolean returnTargetSeedable(
            List<Use> actual,
            List<Integer> parameters,
            JavaTypes types,
            Map<String, Use> variables,
            Use formal,
            Use target) {
        return modeledInference.returnTargetSeedable(
                actual, parameters, types, variables, formal, target);
    }

    boolean sameNestedErasure(Use actual, Use parameter, Use formal, Use target, int depth) {
        return modeledInference.sameNestedErasure(actual, parameter, formal, target, depth);
    }

    boolean nestedFormal(Use parameter, Use formal, int depth) {
        return modeledInference.nestedFormal(parameter, formal, depth);
    }

    boolean formal(Use use, List<Formal> formals) {
        return modeledInference.formal(use, formals);
    }

    boolean containsFormal(Use value, List<Formal> formals, int depth) {
        return modeledInference.containsFormal(value, formals, depth);
    }

    Match infer(
            Use actual,
            Use parameter,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth) {
        return modeledInference.infer(actual, parameter, formals, variables, depth);
    }

    static Map<String, Use> binaryVariables(JavaBinaryTypes.ClassShape hierarchy, Use current) {
        return ModeledInference.binaryVariables(hierarchy, current);
    }

    /** Retain javac's selected declaration after checking its class-file and modeled arguments. */
    Result confirmedBinary(JavaNode site, Receiver receiver, boolean constructor) {
        return modeledSelection.confirmedBinary(site, receiver, constructor);
    }

    Use readableReceiver(Use type) {
        return modeledTypeOps.readableReceiver(type);
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

    Receiver receiver(JavaNode site) {
        return modeledCandidates.receiver(site);
    }

    Receiver lexicalReceiver(JavaModel.DeclarationRef current, String name) {
        return modeledCandidates.lexicalReceiver(current, name);
    }

    List<Use> staticImports(String name) {
        return modeledCandidates.staticImports(name);
    }

    String qualifiedName(JavaNode node) {
        return modeledCandidates.qualifiedName(node);
    }

    boolean namedCallable(Use root, String name, boolean staticOnly) {
        return modeledCandidates.namedCallable(root, name, staticOnly);
    }

    boolean findNamedCallable(Use root, String name, boolean staticOnly) {
        return modeledCandidates.findNamedCallable(root, name, staticOnly);
    }

    Use expression(JavaNode expression) {
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

    Use expressionType(JavaNode expression) {
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

    final Map<String, Boolean> externalPolyReturns = new LinkedHashMap<>();

    boolean externalPolyInvocation(JavaNode site, ExternalSelected selected) {
        return modeledSelection.externalPolyInvocation(site, selected);
    }

    Use genericInvocation(JavaNode site) {
        return modeledInference.genericInvocation(site);
    }

    boolean mentionsFormal(Use value, Set<Integer> formals, int depth) {
        return modeledInference.mentionsFormal(value, formals, depth);
    }

    boolean siteArgument(JavaNode site) {
        return modeledInference.siteArgument(site);
    }

    Use expected(JavaNode site) {
        return modeledInference.expected(site);
    }

    Use writtenExpected(JavaNode site) {
        return modeledInference.writtenExpected(site);
    }

    Result contextualInvocation(JavaNode site, Use parameter) {
        return modeledInference.contextualInvocation(site, parameter);
    }

    boolean modeledLambda(JavaNode expression) {
        return modeledInference.modeledLambda(expression);
    }

    Use lambdaParameter(JavaNode expression) {
        return modeledInference.lambdaParameter(expression);
    }

    Match inferPoly(
            JavaNode poly,
            Use parameter,
            List<Formal> formals,
            Map<String, Use> variables,
            int depth) {
        return modeledInference.inferPoly(poly, parameter, formals, variables, depth);
    }

    boolean invalidConditional(JavaNode node) {
        return modeledInference.invalidConditional(node);
    }

    Use ground(Use type) {
        return modeledTypeOps.ground(type);
    }

    Sam sam(Use functional) {
        return modeledInference.sam(functional);
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
        return modeledInference.samMember(
                descriptors,
                concrete,
                declaringOwners,
                declaringOwner,
                defaultMethod,
                name,
                descriptor,
                types,
                executable,
                variables);
    }

    Match staticReference(Use receiver, String name, int argumentCount, Use firstInput) {
        return modeledInference.staticReference(receiver, name, argumentCount, firstInput);
    }

    JavaModel.DeclarationRef enclosingType(JavaNode node) {
        return modeledCandidates.enclosingType(node);
    }

    boolean accessible(
            JavaDeclaration owner,
            JavaDeclaration member,
            JavaNode site,
            Receiver receiver,
            int inheritedDepth) {
        return modeledCandidates.accessible(owner, member, site, receiver, inheritedDepth);
    }

    boolean inheritsPackageMember(Use receiver, String owner, String ownerPackage, int depth) {
        return modeledCandidates.inheritsPackageMember(receiver, owner, ownerPackage, depth);
    }

    boolean typeAccessible(JavaDeclaration owner, JavaDeclaration caller, int depth) {
        return modeledCandidates.typeAccessible(owner, caller, depth);
    }

    boolean staticContext(JavaNode site) {
        return modeledCandidates.staticContext(site);
    }

    Match assignable(Use argument, Use parameter, int depth) {
        return modeledTypeOps.assignable(argument, parameter, depth);
    }

    Match polyReturnCompatible(JavaNode poly, Use parameter, Sam descriptor, int depth) {
        return modeledInference.polyReturnCompatible(poly, parameter, descriptor, depth);
    }

    record ReturnSites(List<JavaNode> nodes, boolean complete) {}

    ReturnSites returnSites(JavaNode body) {
        return modeledInference.returnSites(body);
    }

    /** Prove only simple terminal paths; complex flow remains incomplete. */
    Match abruptCompletion(JavaNode statement, int depth) {
        return modeledInference.abruptCompletion(statement, depth);
    }

    static Use namedType(String name) {
        return ModeledTypeOps.namedType(name);
    }

    static Use scalarType(JavaType.Kind kind) {
        return ModeledTypeOps.scalarType(kind);
    }

    static boolean numeric(Use value) {
        return ModeledTypeOps.numeric(value);
    }

    int genericArity(String name) {
        return modeledTypeOps.genericArity(name);
    }

    static boolean booleanValue(Use value) {
        return ModeledTypeOps.booleanValue(value);
    }

    Match contained(Use actual, Use expected, int depth) {
        return modeledTypeOps.contained(actual, expected, depth);
    }

    Match sourceHierarchy(Use actual, Use expected, int depth) {
        return modeledTypeOps.sourceHierarchy(actual, expected, depth);
    }

    TypeProjection projectType(Use actual, String targetName, int depth) {
        return modeledTypeOps.projectType(actual, targetName, depth);
    }

    Match sameSubstitutions(Use first, Use second, int depth) {
        return modeledTypeOps.sameSubstitutions(first, second, depth);
    }

    Match same(Use first, Use second, int depth) {
        return modeledTypeOps.same(first, second, depth);
    }

    static String methodName(JavaNode site) {
        if (site.kind().equals("MEMBER_REFERENCE")) return site.text("getName");
        var select = site.tree("getMethodSelect");
        return select.kind().equals("MEMBER_SELECT")
                ? select.text("getIdentifier")
                : select.text("getName");
    }

    static String packageName(String binary) {
        return ModeledCandidates.packageName(binary);
    }

    static int visibility(List<String> modifiers) {
        return ModeledCandidates.visibility(modifiers);
    }

    static String nest(String binary) {
        return ModeledCandidates.nest(binary);
    }

    static boolean primitive(String name) {
        return ModeledTypeOps.primitive(name);
    }

    static boolean widens(String from, String to) {
        return ModeledTypeOps.widens(from, to);
    }
}
