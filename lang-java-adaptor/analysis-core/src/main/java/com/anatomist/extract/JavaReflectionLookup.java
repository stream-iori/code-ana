package com.anatomist.extract;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.*;

/** Exact erased reflection signatures, without class loading or a parser symbol solver. */
final class JavaReflectionLookup {
    record Target(
            String id, String owner, String member, List<String> parameters, boolean internal) {
        Target {
            parameters = List.copyOf(parameters);
        }
    }

    private final JavaModel model;
    private final JavaIdentities identities;
    private final JavaBinaryRepository binaries;

    JavaReflectionLookup(JavaModel model, JavaBinaryRepository binaries) {
        this.model = model;
        this.identities = new JavaIdentities(model);
        this.binaries = binaries;
    }

    String canonical(String name) {
        return canonical(name, 0);
    }

    private String canonical(String name, int depth) {
        if (depth > 128) return name;
        if (model.type(name) instanceof JavaModel.FoundType found)
            return identities.declaration(found.declaration());
        if (binaries != null) {
            var binary = binaries.find(name);
            if (binary.isPresent()) {
                for (var inner : binary.get().innerTypes())
                    if (inner.name().equals(binary.get().name())
                            && inner.outerName() != null
                            && inner.simpleName() != null)
                        return canonical(inner.outerName(), depth + 1) + "." + inner.simpleName();
                return binary.get().name();
            }
        }
        return name;
    }

    boolean internal(String name) {
        return model.type(name) instanceof JavaModel.FoundType;
    }

    Target find(
            String owner,
            String member,
            List<String> parameters,
            boolean declared,
            boolean constructor) {
        ArrayDeque<List<String>> levels = new ArrayDeque<>();
        levels.add(List.of(owner));
        Set<String> visited = new HashSet<>();
        int members = 0;
        while (!levels.isEmpty()) {
            Map<String, Target> matches = new LinkedHashMap<>();
            List<String> parents = new ArrayList<>();
            for (String name : levels.removeFirst()) {
                if (!visited.add(name)) continue;
                if (visited.size() > 256) return null;
                var lookup = model.type(name);
                if (lookup instanceof JavaModel.AmbiguousType) return null;
                if (lookup instanceof JavaModel.FoundType found) {
                    var unit = model.unit(found.declaration().source());
                    var type = model.declaration(found.declaration());
                    for (var reference : model.members(found.declaration())) {
                        if (++members > 100000) return null;
                        var symbol = model.declaration(reference).symbol();
                        if (!symbol.kind().equals(constructor ? "CONSTRUCTOR" : "METHOD")) continue;
                        if (!constructor && !symbol.name().equals(member)) continue;
                        if (!declared && !symbol.modifiers().contains("public")) continue;
                        if (!declared
                                && visited.size() > 1
                                && type.symbol().kind().equals("INTERFACE")
                                && symbol.modifiers().contains("static")) continue;
                        var executable = unit.types().get(symbol.declaredType());
                        var actual =
                                executable.parameters().stream()
                                        .map(value -> identities.erased(reference.source(), value))
                                        .toList();
                        if (!actual.equals(parameters)) continue;
                        String id = identities.declaration(reference);
                        matches.put(
                                id,
                                new Target(
                                        id,
                                        canonical(owner),
                                        constructor ? null : member,
                                        parameters,
                                        true));
                    }
                    if (type.superclass() >= 0)
                        parents.add(JavaTypeNames.erased(unit.types(), type.superclass()));
                    for (int implemented : type.interfaces())
                        parents.add(JavaTypeNames.erased(unit.types(), implemented));
                } else if (binaries != null && binaries.find(name).isPresent()) {
                    var binary = binaries.find(name).orElseThrow();
                    String targetOwner = canonical(binary.name());
                    for (var method : binary.methods()) {
                        if (++members > 100000) return null;
                        if (!method.name().equals(constructor ? "<init>" : member)) continue;
                        if (!declared && (method.access() & Opcodes.ACC_PUBLIC) == 0) continue;
                        if (!declared
                                && visited.size() > 1
                                && (binary.access() & Opcodes.ACC_INTERFACE) != 0
                                && (method.access() & Opcodes.ACC_STATIC) != 0) continue;
                        var actual =
                                Arrays.stream(Type.getArgumentTypes(method.descriptor()))
                                        .map(this::typeName)
                                        .toList();
                        if (!actual.equals(parameters)) continue;
                        String methodName =
                                constructor
                                        ? targetOwner.substring(targetOwner.lastIndexOf('.') + 1)
                                        : member;
                        String id =
                                binary.name()
                                        + "#"
                                        + methodName
                                        + "("
                                        + String.join(
                                                ",",
                                                Arrays.stream(
                                                                Type.getArgumentTypes(
                                                                        method.descriptor()))
                                                        .map(Type::getClassName)
                                                        .toList())
                                        + ")";
                        matches.put(
                                id,
                                new Target(
                                        id,
                                        canonical(owner),
                                        constructor ? null : member,
                                        parameters,
                                        false));
                    }
                    if (binary.superclass() != null) parents.add(binary.superclass());
                    parents.addAll(binary.interfaces());
                } else return null;
            }
            if (!matches.isEmpty())
                return matches.size() == 1 ? matches.values().iterator().next() : null;
            if (declared || constructor) return null;
            if (!parents.isEmpty()) levels.add(parents);
        }
        return null;
    }

    private String typeName(Type type) {
        if (type.getSort() == Type.ARRAY)
            return typeName(type.getElementType()) + "[]".repeat(type.getDimensions());
        return type.getSort() == Type.OBJECT ? canonical(type.getClassName()) : type.getClassName();
    }
}
