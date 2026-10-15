package com.anatomist.framework;

import com.anatomist.core.NodeKeyFactory;
import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.SymbolFact;
import com.anatomist.model.SymbolRef;
import com.anatomist.model.SymbolResolution;
import com.anatomist.model.TypeRelationFact;

import java.util.Map;
import java.util.Set;

/** Read-only merged view: retained committed facts plus facts staged in the current run. */
public interface ProjectFactView {
    Set<String> knownNodeIds();

    Map<String, BeanRefTarget> beanTargets();

    default java.util.List<SymbolFact> symbolFacts() {
        return knownNodeIds().stream()
                .map(
                        id -> {
                            String symbol =
                                    NodeKeyFactory.isKey(id) ? NodeKeyFactory.symbolId(id) : id;
                            return new SymbolFact(id, symbol, null, null, null, null, null);
                        })
                .toList();
    }

    default java.util.List<TypeRelationFact> typeRelations() {
        return java.util.List.of();
    }

    /** Language-neutral symbol lookup; Java is the only installed frontend today. */
    default SymbolResolution resolve(SymbolRef reference) {
        if (reference == null
                || reference.languageHint() == null
                || !"java".equalsIgnoreCase(reference.languageHint())) {
            return SymbolResolution.of(reference, java.util.List.of());
        }
        java.util.Set<String> owners = candidateOwners(reference.owner());
        java.util.List<SymbolFact> matching =
                symbolFacts().stream()
                        .filter(fact -> fact.symbolId() != null)
                        .filter(fact -> matches(fact.symbolId(), reference, owners))
                        .filter(fact -> staticCompatible(fact, reference.staticRequirement()))
                        .toList();
        java.util.List<SymbolFact> sameModule =
                matching.stream()
                        .filter(fact -> "MAIN".equals(fact.scope()))
                        .filter(fact -> belongsToModule(reference.sourceFile(), fact.module()))
                        .toList();
        if (!sameModule.isEmpty()) matching = sameModule;
        else {
            java.util.List<SymbolFact> main =
                    matching.stream().filter(fact -> "MAIN".equals(fact.scope())).toList();
            if (main.size() == 1) matching = main;
        }
        java.util.List<String> candidates =
                matching.stream()
                        .map(fact -> fact.id() == null ? fact.symbolId() : fact.id())
                        .distinct()
                        .sorted()
                        .toList();
        return SymbolResolution.of(reference, candidates);
    }

    default String returnType(String symbolId) {
        for (SymbolFact fact : symbolFacts()) {
            if (!(java.util.Objects.equals(fact.symbolId(), symbolId)
                            || java.util.Objects.equals(fact.id(), symbolId))
                    || fact.metadata() == null) continue;
            try {
                Object tree = com.anatomist.json.Json.parseTree(fact.metadata());
                if (tree instanceof java.util.Map<?, ?> map && map.get("returnType") != null) {
                    return String.valueOf(map.get("returnType"));
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private static boolean staticCompatible(SymbolFact fact, Boolean required) {
        if (required == null || fact.metadata() == null) return true;
        try {
            Object tree = com.anatomist.json.Json.parseTree(fact.metadata());
            if (tree instanceof java.util.Map<?, ?> map
                    && map.get("isStatic") instanceof Boolean value) {
                return value.equals(required);
            }
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    private static boolean belongsToModule(String sourceFile, String module) {
        if (sourceFile == null || module == null) return false;
        if (".".equals(module)) return !sourceFile.contains("/") || sourceFile.startsWith("src/");
        return sourceFile.equals(module) || sourceFile.startsWith(module + "/");
    }

    private java.util.Set<String> candidateOwners(String owner) {
        if (owner == null) return java.util.Set.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
        out.add(owner);
        pending.add(owner);
        while (!pending.isEmpty() && out.size() <= 256) {
            String current = pending.removeFirst();
            for (TypeRelationFact relation : typeRelations()) {
                if (!current.equals(relation.sourceType())
                        || !("INHERITS".equals(relation.relation())
                                || "IMPLEMENTS".equals(relation.relation()))) continue;
                if (relation.targetType() != null && out.add(relation.targetType())) {
                    pending.addLast(relation.targetType());
                }
            }
        }
        return java.util.Set.copyOf(out);
    }

    private static boolean matches(String id, SymbolRef reference, java.util.Set<String> owners) {
        String member = reference.memberName();
        int hash = id.indexOf('#');
        if (hash < 0 || !owners.contains(id.substring(0, hash)) || member == null) return false;
        int open = id.indexOf('(', hash + 1);
        int close = id.lastIndexOf(')');
        if (open < 0 || close < open) return false;
        if (!id.substring(hash + 1, open).equals(member)) return false;
        java.util.List<String> params = parameters(id.substring(open + 1, close));
        if (reference.arity() != null && params.size() != reference.arity()) return false;
        if (!reference.parameterTypeHints().isEmpty()) {
            if (params.size() != reference.parameterTypeHints().size()) return false;
            for (int i = 0; i < params.size(); i++) {
                String hint = reference.parameterTypeHints().get(i);
                if (hint != null && !hint.isBlank() && !sameType(params.get(i), hint)) return false;
            }
        }
        return true;
    }

    private static java.util.List<String> parameters(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(value.split(",", -1)).map(String::trim).toList();
    }

    private static boolean sameType(String actual, String hint) {
        String left = erase(actual);
        String right = erase(hint);
        return left.equals(right) || simple(left).equals(simple(right));
    }

    private static String erase(String value) {
        String out = value.trim();
        int generic = out.indexOf('<');
        if (generic >= 0) out = out.substring(0, generic);
        return out.replace("...", "[]");
    }

    private static String simple(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }
}
