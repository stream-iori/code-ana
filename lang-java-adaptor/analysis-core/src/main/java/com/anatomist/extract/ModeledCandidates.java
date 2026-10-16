package com.anatomist.extract;

import static com.anatomist.extract.JavaModeledCallRecovery.*;

import com.anatomist.core.asmsolver.JavaBinaryTypes;
import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;

import java.util.*;

/** Task-local ModeledCandidates; shared state remains owned by JavaModeledCallRecovery. */
final class ModeledCandidates {
    private final JavaModeledCallRecovery context;

    ModeledCandidates(JavaModeledCallRecovery context) {
        this.context = context;
    }

    boolean binaryHasSourceParent(String root) {
        Boolean cached = context.binarySourceParents.get(root);
        if (cached != null) return cached;
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(root);
        boolean found = false;
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || visited.size() >= 256) return true;
            String name = pending.removeFirst().replace('$', '.');
            if (!visited.add(name)) continue;
            if (!(context.model.type(name) instanceof JavaModel.MissingType)) {
                found = true;
                break;
            }
            var binary =
                    context.binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : context.binaries.find(name);
            if (binary.isEmpty()) continue;
            if (binary.get().superclass() != null) pending.add(binary.get().superclass());
            pending.addAll(binary.get().interfaces());
        }
        if (context.binarySourceParents.size() >= 4096)
            context.binarySourceParents.remove(
                    context.binarySourceParents.keySet().iterator().next());
        context.binarySourceParents.put(root, found);
        return found;
    }

    SourceParents sourceParents(Use root, int initialDepth) {
        ArrayDeque<LocatedType> pending = new ArrayDeque<>();
        pending.add(new LocatedType(root, initialDepth));
        List<Owner> owners = new ArrayList<>();
        Map<String, Use> visited = new HashMap<>();
        boolean incomplete = false;
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || visited.size() >= 256)
                return new SourceParents(List.copyOf(owners), true);
            LocatedType located = pending.removeFirst();
            Use current = located.type().resolved();
            Use previous = visited.putIfAbsent(current.name(), current);
            if (previous != null) {
                if (context.modeledTypeOps.same(previous, current, 0) != Match.YES)
                    incomplete = true;
                continue;
            }
            var lookup = context.model.type(current.name());
            if (lookup instanceof JavaModel.FoundType found) {
                owners.add(
                        context.modeledCandidates.owner(
                                found.declaration(), current, located.depth()));
                continue;
            }
            if (lookup instanceof JavaModel.AmbiguousType) {
                incomplete = true;
                continue;
            }
            var binary =
                    context.binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : context.binaries.find(current.name());
            if (binary.isEmpty()) {
                incomplete = true;
                continue;
            }
            try {
                var hierarchy = JavaBinaryTypes.hierarchy(binary.get());
                var variables = ModeledInference.binaryVariables(hierarchy, current);
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

    Owner owner(JavaModel.DeclarationRef declaration, Use type, int depth) {
        JavaUnit source = context.model.unit(declaration.source());
        var declared =
                source.types().get(context.model.declaration(declaration).symbol().declaredType());
        Use actual = type.resolved();
        var arguments = actual.type().arguments();
        Map<String, Use> variables = new LinkedHashMap<>();
        if (actual.type().enclosing() >= 0) {
            if (++context.work > 100_000 || depth >= 64)
                throw new IllegalArgumentException("enclosing type substitution exceeds budget");
            variables.putAll(actual.variables());
            Use enclosing = actual.child(actual.type().enclosing()).resolved();
            if (context.model.type(enclosing.name()) instanceof JavaModel.FoundType found)
                variables.putAll(
                        context.modeledCandidates
                                .owner(found.declaration(), enclosing, depth + 1)
                                .variables());
        }
        for (int i = 0; i < declared.arguments().size(); i++) {
            var variable = source.types().get(declared.arguments().get(i));
            if (variable.kind() != JavaType.Kind.TYPEVAR) continue;
            if (i < arguments.size()) variables.put(variable.name(), actual.argument(i));
            else if (!variable.upperBounds().isEmpty())
                variables.put(
                        variable.name(),
                        ModeledTypeOps.namedType(
                                JavaTypeNames.erased(
                                        source.types(), variable.upperBounds().getFirst())));
        }
        return new Owner(declaration, Map.copyOf(variables), depth);
    }

    BinarySearch binaryCandidates(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots) {
        return context.modeledCandidates.binaryCandidates(
                site, receiver, constructor, arguments, roots, Set.of());
    }

    BinarySearch binaryCandidates(
            JavaNode site,
            Receiver receiver,
            boolean constructor,
            List<Use> arguments,
            List<Use> roots,
            Set<String> sourceOwners) {
        if (context.binaries == null)
            return BinarySearch.failed(new Unavailable("binary-recovery-unavailable"));
        String name = constructor ? "<init>" : JavaModeledCallRecovery.methodName(site);
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.addAll(roots);
        Map<String, Use> visited = new HashMap<>();
        Set<String> signatures = new HashSet<>();
        List<BinaryCandidate> candidates = new ArrayList<>();
        int uncertainPhase = Integer.MAX_VALUE;
        int namedMethods = 0;
        boolean unknown = false;
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || visited.size() >= 256)
                return BinarySearch.failed(new Unavailable("recovery-budget"));
            Use current = pending.removeFirst().resolved();
            String owner = current.name();
            Use previous = visited.putIfAbsent(owner, current);
            if (previous != null) {
                if (context.modeledTypeOps.same(previous, current, 0) != Match.YES) unknown = true;
                continue;
            }
            if (context.model.type(owner) instanceof JavaModel.FoundType) {
                if (!sourceOwners.contains(owner)) unknown = true;
                continue;
            }
            if (context.model.type(owner) instanceof JavaModel.AmbiguousType) {
                unknown = true;
                continue;
            }
            var found = context.binaries.find(owner);
            if (found.isEmpty()) {
                unknown = true;
                continue;
            }
            var type = found.get();
            for (var method : type.methods()) {
                if (++context.work > 100_000)
                    return BinarySearch.failed(new Unavailable("recovery-budget"));
                if (!method.name().equals(name)
                        || (method.access() & Opcodes.ACC_PUBLIC) == 0
                        || (method.access() & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0)
                    continue;
                boolean isStatic = (method.access() & Opcodes.ACC_STATIC) != 0;
                if (!constructor && receiver.typeQualifier() && !isStatic) continue;
                if (isStatic
                        && !owner.equals(receiver.type().name())
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
                if (!ModeledSelection.arity(
                        executable.parameters().size(), arguments.size(), variadic)) continue;
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
                            context.modeledInference.methodArguments(
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
                var match =
                        context.modeledSelection.applicability(arguments, parameterTypes, variadic);
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
                                        receiver.type().name(),
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
                    Map<String, Use> variables =
                            ModeledInference.binaryVariables(hierarchy, current);
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

    Receiver receiver(JavaNode site) {
        String constructor = explicitConstructorName(site);
        if (constructor != null) {
            var enclosing = context.modeledCandidates.enclosingType(site);
            if (enclosing == null) return null;
            var owner = context.model.declaration(enclosing);
            int type =
                    constructor.equals("super")
                            ? owner.superclass()
                            : owner.symbol().declaredType();
            return type < 0
                    ? null
                    : new Receiver(
                            new Use(context.model.unit(enclosing.source()).types(), type), false);
        }
        if (site.kind().equals("MEMBER_REFERENCE")) {
            var qualifier = site.tree("getQualifierExpression");
            var type = context.modeledTypeOps.readableReceiver(context.expression(qualifier));
            var binding = qualifier == null ? null : context.unit.bindings().get(qualifier.id());
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
            var type = context.expression(site.tree("getIdentifier"));
            return type == null ? null : new Receiver(type, true);
        }
        JavaNode select = site.tree("getMethodSelect");
        if (select == null) return null;
        if (select.kind().equals("MEMBER_SELECT")) {
            JavaNode expression = select.tree("getExpression");
            var type = context.modeledTypeOps.readableReceiver(context.expression(expression));
            var binding = expression == null ? null : context.unit.bindings().get(expression.id());
            boolean qualifier =
                    binding != null
                            && Set.of("IDENTIFIER", "MEMBER_SELECT", "PARAMETERIZED_TYPE")
                                    .contains(expression.kind())
                            && Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                                    .contains(binding.kind());
            return type == null ? null : new Receiver(type, qualifier);
        }
        var type = context.modeledCandidates.enclosingType(site);
        if (type == null) return null;
        var declaration = context.model.declaration(type);
        Use enclosing =
                new Use(
                        context.model.unit(type.source()).types(),
                        declaration.symbol().declaredType());
        String name = JavaModeledCallRecovery.methodName(site);
        if (!context.modeledCandidates.namedCallable(enclosing, name, false)) {
            Receiver lexical = context.modeledCandidates.lexicalReceiver(type, name);
            if (lexical != null) return lexical;
            List<Use> imports = context.modeledCandidates.staticImports(name);
            if (!imports.isEmpty()) {
                Use primary =
                        imports.stream()
                                .filter(
                                        root ->
                                                context.model.type(root.name())
                                                        instanceof JavaModel.FoundType)
                                .findFirst()
                                .orElse(imports.getFirst());
                return new Receiver(primary, true, imports);
            }
        }
        return new Receiver(enclosing, false);
    }

    Receiver lexicalReceiver(JavaModel.DeclarationRef current, String name) {
        JavaNode node = context.model.syntax(current).orElse(null);
        if (node == null) return null;
        boolean staticOnly = false;
        var syntax = context.model.syntax(context.unit.path());
        for (int depth = 0; node != null; depth++, node = syntax.parent(node).orElse(null)) {
            if (++context.work > 100_000 || depth >= 128)
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
                    context.model.declarationsAt(context.unit.path(), node.span()).stream()
                            .filter(
                                    reference ->
                                            JavaModel.matches(
                                                    typeNode,
                                                    context.model.declaration(reference).symbol()))
                            .toList();
            if (declarations.size() != 1) return null;
            var found = declarations.getFirst();
            if (!found.equals(current)) {
                Use outer =
                        new Use(
                                context.model.unit(found.source()).types(),
                                context.model.declaration(found).symbol().declaredType());
                // A nearer declaration shadows farther lexical types even when inaccessible
                // or inapplicable; the normal candidate search checks those constraints.
                if (context.modeledCandidates.namedCallable(outer, name, false))
                    return new Receiver(outer, staticOnly);
            }
            if (Set.of("INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD").contains(node.kind()))
                staticOnly = true;
        }
        return null;
    }

    List<Use> staticImports(String name) {
        List<Use> cached = context.importedRoots.get(name);
        if (cached != null) return cached;
        Set<String> owners = new LinkedHashSet<>();
        Set<String> singles = new LinkedHashSet<>();
        for (JavaNode imported : context.unit.tree().trees("getImports")) {
            if (!imported.text("isStatic").equals("true")) continue;
            String qualified =
                    context.modeledCandidates.qualifiedName(
                            imported.tree("getQualifiedIdentifier"));
            int dot = qualified.lastIndexOf('.');
            if (dot < 0 || !Set.of(name, "*").contains(qualified.substring(dot + 1))) continue;
            String owner = qualified.substring(0, dot);
            if (qualified.substring(dot + 1).equals(name)) singles.add(owner);
            else if (context.modeledCandidates.namedCallable(
                    ModeledTypeOps.namedType(owner), name, true)) owners.add(owner);
        }
        // A single static import shadows every on-demand import with that name, including
        // overloads that would otherwise be more specific or applicable.
        List<Use> roots =
                (singles.isEmpty() ? owners : singles)
                        .stream().map(JavaModeledCallRecovery::namedType).toList();
        context.importedRoots.put(name, roots);
        return roots;
    }

    String qualifiedName(JavaNode node) {
        List<String> parts = new ArrayList<>();
        for (int depth = 0; node != null && depth < 64; depth++) {
            if (++context.work > 100_000)
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

    boolean namedCallable(Use root, String name, boolean staticOnly) {
        var key = new CallableName(root.name(), name, staticOnly);
        Boolean cached = context.callableNames.get(key);
        if (cached != null) return cached;
        boolean present = context.modeledCandidates.findNamedCallable(root, name, staticOnly);
        context.callableNames.put(key, present);
        return present;
    }

    boolean findNamedCallable(Use root, String name, boolean staticOnly) {
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(root);
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || visited.size() >= 256)
                throw new IllegalArgumentException("callable name lookup exceeds budget");
            Use current = pending.removeFirst();
            if (!visited.add(current.name())) continue;
            var lookup = context.model.type(current.name());
            if (lookup instanceof JavaModel.FoundType source) {
                for (var member : context.model.members(source.declaration())) {
                    var symbol = context.model.declaration(member).symbol();
                    if (symbol.kind().equals("METHOD")
                            && symbol.name().equals(name)
                            && (!staticOnly || symbol.modifiers().contains("static"))
                            && (current == root || !symbol.modifiers().contains("private")))
                        return true;
                }
                var declaration = context.model.declaration(source.declaration());
                var types = context.model.unit(source.declaration().source()).types();
                if (declaration.superclass() >= 0)
                    pending.add(new Use(types, declaration.superclass()));
                for (int parent : declaration.interfaces()) pending.add(new Use(types, parent));
            } else if (lookup instanceof JavaModel.AmbiguousType) {
                return true;
            } else if (context.binaries != null) {
                var type = context.binaries.find(current.name());
                if (type.isEmpty()) continue;
                for (var method : type.get().methods())
                    if (method.name().equals(name)
                            && (!staticOnly || (method.access() & Opcodes.ACC_STATIC) != 0)
                            && (current == root || (method.access() & Opcodes.ACC_PRIVATE) == 0))
                        return true;
                if (type.get().superclass() != null)
                    pending.add(ModeledTypeOps.namedType(type.get().superclass()));
                for (String parent : type.get().interfaces())
                    pending.add(ModeledTypeOps.namedType(parent));
            }
        }
        return false;
    }

    JavaModel.DeclarationRef enclosingType(JavaNode node) {
        var syntax = context.model.syntax(context.unit.path());
        for (var parent = syntax.parent(node).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (!Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                    .contains(parent.kind())) continue;
            var typeSyntax = parent;
            var declarations =
                    context.model.declarationsAt(context.unit.path(), parent.span()).stream()
                            .filter(
                                    reference ->
                                            JavaModel.matches(
                                                    typeSyntax,
                                                    context.model.declaration(reference).symbol()))
                            .toList();
            if (declarations.size() == 1) return declarations.getFirst();
            return null;
        }
        return null;
    }

    boolean accessible(
            JavaDeclaration owner,
            JavaDeclaration member,
            JavaNode site,
            Receiver receiver,
            int inheritedDepth) {
        boolean constructor = member.symbol().kind().equals("CONSTRUCTOR");
        var modifiers = member.symbol().modifiers();
        if (!constructor && receiver.typeQualifier() && !modifiers.contains("static")) return false;
        if (inheritedDepth > 0
                && owner.symbol().kind().equals("INTERFACE")
                && modifiers.contains("static")) return false;
        var enclosing = context.modeledCandidates.enclosingType(site);
        if (enclosing == null) return false;
        var caller = context.model.declaration(enclosing);
        JavaDeclaration accessibleType = owner;
        if (inheritedDepth > 0
                && context.model.type(receiver.type().name()) instanceof JavaModel.FoundType type)
            accessibleType = context.model.declaration(type.declaration());
        if (!context.modeledCandidates.typeAccessible(accessibleType, caller, 0)) return false;
        if (!constructor
                && !modifiers.contains("static")
                && context.modeledCandidates.staticContext(site)) return false;
        if (modifiers.contains("public")) return true;
        if (modifiers.contains("private"))
            return inheritedDepth == 0
                    && ModeledCandidates.nest(owner.binaryName())
                            .equals(ModeledCandidates.nest(caller.binaryName()));
        if (ModeledCandidates.packageName(owner.binaryName())
                .equals(ModeledCandidates.packageName(caller.binaryName()))) {
            if (!constructor && inheritedDepth > 0 && !modifiers.contains("protected"))
                return context.modeledCandidates.inheritsPackageMember(
                        receiver.type(),
                        owner.canonicalName(),
                        ModeledCandidates.packageName(owner.binaryName()),
                        0);
            return true;
        }
        if (!modifiers.contains("protected")
                || constructor && !"super".equals(explicitConstructorName(site))) return false;
        if (context.modeledTypeOps.sourceHierarchy(
                        ModeledTypeOps.namedType(caller.canonicalName()),
                        ModeledTypeOps.namedType(owner.canonicalName()),
                        0)
                != Match.YES) return false;
        return constructor
                || modifiers.contains("static")
                || context.modeledTypeOps.sourceHierarchy(
                                receiver.type(),
                                ModeledTypeOps.namedType(caller.canonicalName()),
                                0)
                        == Match.YES;
    }

    boolean inheritsPackageMember(Use receiver, String owner, String ownerPackage, int depth) {
        if (++context.work > 100_000
                || depth >= 64
                || !ModeledCandidates.packageName(receiver.name()).equals(ownerPackage))
            return false;
        if (receiver.name().equals(owner)) return true;
        var lookup = context.model.type(receiver.name());
        if (lookup instanceof JavaModel.FoundType found) {
            var declaration = context.model.declaration(found.declaration());
            if (declaration.superclass() < 0) return false;
            var source = context.model.unit(found.declaration().source());
            return context.modeledCandidates.inheritsPackageMember(
                    new Use(
                            source.types(),
                            declaration.superclass(),
                            context.modeledCandidates
                                    .owner(found.declaration(), receiver, depth)
                                    .variables()),
                    owner,
                    ownerPackage,
                    depth + 1);
        }
        var binary =
                context.binaries == null
                        ? Optional.<JavaBinaryType>empty()
                        : context.binaries.find(receiver.name());
        if (binary.isEmpty()) return false;
        try {
            var hierarchy = JavaBinaryTypes.hierarchy(binary.get());
            return hierarchy.superclass() >= 0
                    && context.modeledCandidates.inheritsPackageMember(
                            new Use(
                                    hierarchy.types(),
                                    hierarchy.superclass(),
                                    ModeledInference.binaryVariables(hierarchy, receiver)),
                            owner,
                            ownerPackage,
                            depth + 1);
        } catch (IllegalArgumentException unavailable) {
            return false;
        }
    }

    boolean typeAccessible(JavaDeclaration owner, JavaDeclaration caller, int depth) {
        if (++context.work > 100_000 || depth >= 64) return false;
        boolean sameNest =
                ModeledCandidates.nest(owner.binaryName())
                        .equals(ModeledCandidates.nest(caller.binaryName()));
        boolean samePackage =
                ModeledCandidates.packageName(owner.binaryName())
                        .equals(ModeledCandidates.packageName(caller.binaryName()));
        var modifiers = owner.symbol().modifiers();
        if (modifiers.contains("private") && !sameNest) return false;
        if (!modifiers.contains("public") && !sameNest && !samePackage) {
            if (!modifiers.contains("protected")) return false;
            var parent = context.model.type(owner.symbol().owner());
            if (!(parent instanceof JavaModel.FoundType found)
                    || context.modeledTypeOps.sourceHierarchy(
                                    ModeledTypeOps.namedType(caller.canonicalName()),
                                    ModeledTypeOps.namedType(
                                            context.model
                                                    .declaration(found.declaration())
                                                    .canonicalName()),
                                    0)
                            != Match.YES) return false;
        }
        if (owner.symbol().owner().isEmpty()) return true;
        var parent = context.model.type(owner.symbol().owner());
        return !(parent instanceof JavaModel.FoundType found)
                || context.modeledCandidates.typeAccessible(
                        context.model.declaration(found.declaration()), caller, depth + 1);
    }

    boolean staticContext(JavaNode site) {
        if (site.kind().equals("MEMBER_REFERENCE")) return false;
        var syntax = context.model.syntax(context.unit.path());
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

    static String packageName(String binary) {
        int dot = binary.lastIndexOf('.');
        return dot < 0 ? "" : binary.substring(0, dot);
    }

    static int visibility(List<String> modifiers) {
        if (modifiers.contains("public")) return 3;
        if (modifiers.contains("protected")) return 2;
        return modifiers.contains("private") ? 0 : 1;
    }

    static String nest(String binary) {
        int nested = binary.indexOf('$');
        return nested < 0 ? binary : binary.substring(0, nested);
    }
}
