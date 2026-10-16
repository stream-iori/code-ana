package com.anatomist.extract;

import static com.anatomist.extract.JavaModeledCallRecovery.*;

import com.anatomist.core.asmsolver.JavaBinaryTypes;
import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;

import java.util.*;

/** Task-local ModeledTypeOps; shared state remains owned by JavaModeledCallRecovery. */
final class ModeledTypeOps {
    private final JavaModeledCallRecovery context;

    ModeledTypeOps(JavaModeledCallRecovery context) {
        this.context = context;
    }

    Match strictAssignable(Use actual, Use parameter) {
        if (ModeledTypeOps.primitive(actual.resolved().name())
                != ModeledTypeOps.primitive(parameter.resolved().name())) return Match.NO;
        return context.modeledTypeOps.assignable(actual, parameter, 0);
    }

    Use readableReceiver(Use type) {
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

    Use ground(Use type) {
        Use current = type;
        for (int i = 0; i < 64; i++) {
            current = current.resolved();
            if (current.type().kind() != JavaType.Kind.WILDCARD) return current;
            if (current.type().lowerBound() >= 0)
                current = current.child(current.type().lowerBound());
            else if (!current.type().upperBounds().isEmpty())
                current = current.child(current.type().upperBounds().getFirst());
            else return ModeledTypeOps.namedType("java.lang.Object");
        }
        return null;
    }

    Match assignable(Use argument, Use parameter, int depth) {
        if (depth > 64 || ++context.work > 100_000) return Match.UNKNOWN;
        var poly = context.polyNodes.get(argument);
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
                        : context.modeledTypeOps.assignable(value, parameter, depth + 1);
            }
            var descriptor = context.modeledInference.sam(parameter);
            if (descriptor == null) {
                var lookup = context.model.type(parameter.name());
                if (lookup instanceof JavaModel.FoundType found
                        && !context.model
                                .declaration(found.declaration())
                                .symbol()
                                .kind()
                                .equals("INTERFACE")) return Match.NO;
                var binary =
                        context.binaries == null
                                ? Optional.<JavaBinaryType>empty()
                                : context.binaries.find(parameter.name());
                return ModeledTypeOps.primitive(parameter.name())
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
                    Use actualType = context.expression(written);
                    if (actualType == null
                            || context.modeledTypeOps.same(
                                            actualType, descriptor.parameters().get(i), 0)
                                    != Match.YES) return Match.UNKNOWN;
                }
            }
            return context.modeledInference.polyReturnCompatible(
                    poly, parameter, descriptor, depth + 1);
        }
        Use actual = argument.resolved(), expected = parameter.resolved();
        var source = actual.type();
        var target = expected.type();
        if (source.kind() == JavaType.Kind.TYPEVAR
                && target.kind() == JavaType.Kind.TYPEVAR
                && actual.types() == expected.types()
                && actual.reference() == expected.reference())
            return context.modeledTypeOps.sameSubstitutions(actual, expected, depth + 1);
        if ((source.kind() == JavaType.Kind.TYPEVAR || source.kind() == JavaType.Kind.WILDCARD)
                && target.kind() != JavaType.Kind.TYPEVAR
                && target.kind() != JavaType.Kind.ERROR) {
            if (source.kind() == JavaType.Kind.WILDCARD && source.upperBounds().isEmpty())
                return context.modeledTypeOps.assignable(
                        ModeledTypeOps.namedType("java.lang.Object"), expected, depth + 1);
            for (int bound : source.upperBounds())
                if (context.modeledTypeOps.assignable(actual.child(bound), expected, depth + 1)
                        == Match.YES) return Match.YES;
            return Match.UNKNOWN;
        }
        if (source.kind() == JavaType.Kind.ERROR
                || target.kind() == JavaType.Kind.ERROR
                || source.kind() == JavaType.Kind.TYPEVAR
                || target.kind() == JavaType.Kind.TYPEVAR
                || source.kind() == JavaType.Kind.WILDCARD) return Match.UNKNOWN;
        if (target.kind() == JavaType.Kind.WILDCARD)
            return context.modeledTypeOps.contained(actual, expected, depth + 1);
        if (target.kind() == JavaType.Kind.INTERSECTION) {
            Match result = Match.YES;
            for (int bound : target.upperBounds()) {
                Match match =
                        context.modeledTypeOps.assignable(actual, expected.child(bound), depth + 1);
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
            return ModeledTypeOps.primitive(actual.child(source.component()).name())
                            || ModeledTypeOps.primitive(expected.child(target.component()).name())
                    ? context.modeledTypeOps.same(
                            actual.child(source.component()),
                            expected.child(target.component()),
                            depth + 1)
                    : context.modeledTypeOps.assignable(
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
                    && context.modeledTypeOps.genericArity(from) == target.arguments().size())
                return Match.YES;
            if (source.arguments().size() != target.arguments().size()) return Match.UNKNOWN;
            Match result = Match.YES;
            for (int i = 0; i < target.arguments().size(); i++) {
                Use argumentType = actual.child(source.arguments().get(i));
                Use parameterType = expected.child(target.arguments().get(i));
                var comparison =
                        parameterType.resolved().type().kind() == JavaType.Kind.WILDCARD
                                ? context.modeledTypeOps.contained(
                                        argumentType, parameterType, depth + 1)
                                : context.modeledTypeOps.same(
                                        argumentType, parameterType, depth + 1);
                if (comparison == Match.NO) return Match.NO;
                if (comparison == Match.UNKNOWN) result = Match.UNKNOWN;
            }
            return result;
        }
        if (BOXES.getOrDefault(from, "").equals(to) || BOXES.getOrDefault(to, "").equals(from))
            return Match.YES;
        if (to.equals("java.lang.Object") && source.kind() != JavaType.Kind.VOID) return Match.YES;
        if (ModeledTypeOps.primitive(from) && ModeledTypeOps.primitive(to))
            return ModeledTypeOps.widens(from, to) ? Match.YES : Match.NO;
        if (source.kind() == JavaType.Kind.DECLARED && ModeledTypeOps.primitive(to)) {
            for (var box : BOXES.entrySet())
                if (box.getValue().equals(from))
                    return ModeledTypeOps.widens(box.getKey(), to) ? Match.YES : Match.NO;
            return Match.NO;
        }
        if (ModeledTypeOps.primitive(from) && target.kind() == JavaType.Kind.DECLARED)
            return context.modeledTypeOps.sourceHierarchy(
                    ModeledTypeOps.namedType(BOXES.get(from)), expected, depth + 1);
        if (source.kind() == JavaType.Kind.ARRAY && target.kind() == JavaType.Kind.DECLARED)
            return Set.of("java.lang.Cloneable", "java.io.Serializable").contains(to)
                    ? Match.YES
                    : Match.NO;
        if (source.kind() == JavaType.Kind.DECLARED && target.kind() == JavaType.Kind.DECLARED)
            return context.modeledTypeOps.sourceHierarchy(actual, expected, depth + 1);
        return Match.UNKNOWN;
    }

    static Use namedType(String name) {
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

    static Use scalarType(JavaType.Kind kind) {
        String name = kind.name().toLowerCase(Locale.ROOT);
        return new Use(
                new JavaTypes(
                        List.of(
                                new JavaType(
                                        kind, name, name, -1, List.of(), List.of(), -1, -1,
                                        List.of(), -1, List.of()))),
                0);
    }

    static boolean numeric(Use value) {
        if (value == null) return false;
        String name = value.name();
        for (var box : BOXES.entrySet())
            if (!box.getKey().equals("boolean")
                    && (name.equals(box.getKey()) || name.equals(box.getValue()))) return true;
        return false;
    }

    int genericArity(String name) {
        if (++context.work > 100_000) return -1;
        var source = context.model.type(name);
        if (source instanceof JavaModel.FoundType found) {
            var declaration = context.model.declaration(found.declaration());
            return context.model
                    .unit(found.declaration().source())
                    .types()
                    .get(declaration.symbol().declaredType())
                    .arguments()
                    .size();
        }
        if (!(source instanceof JavaModel.MissingType) || context.binaries == null) return -1;
        var binary = context.binaries.find(name);
        if (binary.isEmpty()) return -1;
        try {
            return JavaBinaryTypes.hierarchy(binary.get()).parameters().size();
        } catch (IllegalArgumentException unavailable) {
            return -1;
        }
    }

    static boolean booleanValue(Use value) {
        return value != null && Set.of("boolean", "java.lang.Boolean").contains(value.name());
    }

    Match contained(Use actual, Use expected, int depth) {
        if (++context.work > 100_000 || depth >= 64) return Match.UNKNOWN;
        var wildcard = expected.resolved();
        var type = wildcard.type();
        if (type.kind() != JavaType.Kind.WILDCARD)
            return context.modeledTypeOps.same(actual, expected, depth + 1);
        var source = actual.resolved();
        if (source.type().kind() == JavaType.Kind.WILDCARD) {
            if (type.lowerBound() >= 0)
                return source.type().lowerBound() < 0
                        ? Match.NO
                        : context.modeledTypeOps.assignable(
                                wildcard.child(type.lowerBound()),
                                source.child(source.type().lowerBound()),
                                depth + 1);
            Use sourceUpper =
                    source.type().upperBounds().isEmpty()
                            ? ModeledTypeOps.namedType("java.lang.Object")
                            : source.child(source.type().upperBounds().getFirst());
            Match result = Match.YES;
            for (int upper : type.upperBounds()) {
                Match match =
                        context.modeledTypeOps.assignable(
                                sourceUpper, wildcard.child(upper), depth + 1);
                if (match == Match.NO) return Match.NO;
                if (match == Match.UNKNOWN) result = Match.UNKNOWN;
            }
            return result;
        }
        if (type.lowerBound() >= 0)
            return context.modeledTypeOps.assignable(
                    wildcard.child(type.lowerBound()), actual, depth + 1);
        Match result = Match.YES;
        for (int upper : type.upperBounds()) {
            Match match =
                    context.modeledTypeOps.assignable(actual, wildcard.child(upper), depth + 1);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    Match sourceHierarchy(Use actual, Use expected, int depth) {
        TypeProjection projection =
                context.modeledTypeOps.projectType(actual, expected.name(), depth);
        return projection.match() == Match.YES
                ? context.modeledTypeOps.assignable(projection.type(), expected, depth + 1)
                : projection.match();
    }

    TypeProjection projectType(Use actual, String targetName, int depth) {
        var targetLookup = context.model.type(targetName);
        boolean classTarget;
        if (targetLookup instanceof JavaModel.FoundType target) {
            String targetKind = context.model.declaration(target.declaration()).symbol().kind();
            classTarget = Set.of("CLASS", "ENUM", "RECORD").contains(targetKind);
        } else {
            var target =
                    context.binaries == null
                            ? Optional.<JavaBinaryType>empty()
                            : context.binaries.find(targetName);
            if (target.isEmpty()) return new TypeProjection(null, Match.UNKNOWN);
            classTarget = (target.get().access() & Opcodes.ACC_INTERFACE) == 0;
        }
        ArrayDeque<Use> pending = new ArrayDeque<>();
        pending.add(actual);
        Set<String> visited = new HashSet<>();
        boolean unknown = false;
        while (!pending.isEmpty()) {
            if (++context.work > 100_000 || depth > 64 || visited.size() >= 256)
                return new TypeProjection(null, Match.UNKNOWN);
            Use current = pending.removeFirst();
            if (!visited.add(current.name())) continue;
            if (current.name().equals(targetName)) return new TypeProjection(current, Match.YES);
            if (current.name().equals("java.lang.Object")) continue;
            var lookup = context.model.type(current.name());
            if (!(lookup instanceof JavaModel.FoundType found)) {
                var binary =
                        context.binaries == null
                                ? Optional.<JavaBinaryType>empty()
                                : context.binaries.find(current.name());
                if (binary.isEmpty()) {
                    unknown = true;
                    continue;
                }
                try {
                    var hierarchy = JavaBinaryTypes.hierarchy(binary.get());
                    var variables = ModeledInference.binaryVariables(hierarchy, current);
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
            var declaration = context.model.declaration(found.declaration());
            var source = context.model.unit(found.declaration().source());
            var variables =
                    context.modeledCandidates
                            .owner(found.declaration(), current, depth)
                            .variables();
            if (declaration.superclass() >= 0)
                pending.add(new Use(source.types(), declaration.superclass(), variables));
            if (!classTarget)
                for (int parent : declaration.interfaces())
                    pending.add(new Use(source.types(), parent, variables));
        }
        return new TypeProjection(null, unknown ? Match.UNKNOWN : Match.NO);
    }

    Match sameSubstitutions(Use first, Use second, int depth) {
        if (++context.work > 100_000 || depth >= 64) return Match.UNKNOWN;
        if (first.variables() == second.variables()) return Match.YES;
        if (first.variables().size() != second.variables().size()) return Match.UNKNOWN;
        Match result = Match.YES;
        for (var entry : first.variables().entrySet()) {
            Use other = second.variables().get(entry.getKey());
            if (other == null) return Match.UNKNOWN;
            Match match = context.modeledTypeOps.same(entry.getValue(), other, depth + 1);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    Match same(Use first, Use second, int depth) {
        if (depth > 64 || ++context.work > 100_000) return Match.UNKNOWN;
        var left = first.resolved();
        var right = second.resolved();
        if (left.type().kind() == JavaType.Kind.TYPEVAR
                && right.type().kind() == JavaType.Kind.TYPEVAR
                && left.types() == right.types()
                && left.reference() == right.reference())
            return context.modeledTypeOps.sameSubstitutions(left, right, depth + 1);
        if (left.type().kind() == JavaType.Kind.WILDCARD
                && right.type().kind() == JavaType.Kind.WILDCARD) {
            if (left.capture() != null
                    && right.capture() != null
                    && !left.capture().equals(right.capture())) return Match.NO;
            Match forward = context.modeledTypeOps.contained(left, right, depth + 1);
            return forward == Match.YES
                    ? context.modeledTypeOps.contained(right, left, depth + 1)
                    : forward;
        }
        if (Set.of(JavaType.Kind.TYPEVAR, JavaType.Kind.ERROR, JavaType.Kind.WILDCARD)
                        .contains(left.type().kind())
                || Set.of(JavaType.Kind.TYPEVAR, JavaType.Kind.ERROR, JavaType.Kind.WILDCARD)
                        .contains(right.type().kind())) return Match.UNKNOWN;
        if (!left.name().equals(right.name())) return Match.NO;
        if (left.type().arguments().size() != right.type().arguments().size()) return Match.UNKNOWN;
        Match result = Match.YES;
        for (int i = 0; i < left.type().arguments().size(); i++) {
            var match = context.modeledTypeOps.same(left.argument(i), right.argument(i), depth + 1);
            if (match == Match.NO) return Match.NO;
            if (match == Match.UNKNOWN) result = Match.UNKNOWN;
        }
        return result;
    }

    static boolean primitive(String name) {
        return BOXES.containsKey(name);
    }

    static boolean widens(String from, String to) {
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
