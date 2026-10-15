package com.anatomist.core;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Late-binds edges that SymbolSolver classified as external but whose rendered target FQN exactly
 * matches a node emitted by this index.
 */
public final class EdgeTargetBinder {

    private EdgeTargetBinder() {}

    public static int bindExternalTargets(ExtractionResult result) {
        Set<String> knownNodeIds = new HashSet<>();
        for (Node n : result.nodes) {
            if (n.id != null) knownNodeIds.add(n.id);
        }
        return bindExternalTargets(result, knownNodeIds);
    }

    public static int bindExternalTargets(ExtractionResult result, Set<String> knownNodeIds) {
        if (result == null) return 0;
        return bindExternalTargets(result, prepare(knownNodeIds));
    }

    /** Stores ambiguity explicitly, including ambiguity introduced by local nodes. */
    public static final class Catalogue {
        private final Set<String> known;
        private final Map<String, String> symbols = new HashMap<>(), arities = new HashMap<>();
        private Catalogue(Set<String> ids) {
            known = ids == null ? Set.of() : Set.copyOf(ids);
            for (String id : known) index(id, symbols, arities);
        }
        public boolean contains(String id) { return known.contains(id); }
    }

    public static Catalogue prepare(Set<String> ids) { return new Catalogue(ids); }

    private static void merge(Map<String, String> index, String key, String id) {
        if (key != null) index.merge(key, id, (old, added) -> old.equals(added) ? old : "");
    }

    private static void index(String id, Map<String, String> symbols, Map<String, String> arities) {
        String symbol = NodeKeyFactory.symbolId(id);
        merge(symbols, symbol, id);
        merge(arities, methodArityKey(symbol), id);
    }

    private static String unique(Map<String, String> base, Map<String, String> local, String key) {
        String a = base.get(key), b = local.get(key);
        if (a != null && a.isEmpty() || b != null && b.isEmpty()) return null;
        if (a != null && b != null && !a.equals(b)) return null;
        return a != null ? a : b;
    }

    public static int bindExternalTargets(ExtractionResult result, Catalogue catalogue) {
        if (result == null) return 0;
        Set<String> local = new HashSet<>();
        Map<String, String> symbols = new HashMap<>(), arities = new HashMap<>();
        for (Node n : result.nodes) if (n.id != null && local.add(n.id)) index(n.id, symbols, arities);
        int changed = 0;
        for (Edge e : result.edges) {
            if (!e.isExternal || e.externalTargetFqn == null) continue;
            String target =
                    (catalogue.contains(e.externalTargetFqn) || local.contains(e.externalTargetFqn))
                            ? e.externalTargetFqn
                            : unique(catalogue.symbols, symbols, e.externalTargetFqn);
            if (target == null
                    && e.externalTargetFqn.indexOf('$') >= 0
                    && e.metadata != null
                    && e.metadata.contains("\"via\":\"reflection\"")) {
                target = unique(catalogue.symbols, symbols, e.externalTargetFqn.replace('$', '.'));
            }
            if (target == null) {
                target = unique(catalogue.arities, arities, methodArityKey(e.externalTargetFqn));
            }
            if (target == null) continue;
            e.targetId = target;
            e.externalTargetFqn = null;
            e.isExternal = false;
            e.resolution = null;
            changed++;
        }
        return changed;
    }

    private static String methodArityKey(String methodId) {
        if (methodId == null) return null;
        int open = methodId.indexOf('(');
        int hash = methodId.lastIndexOf('#', open >= 0 ? open : methodId.length());
        int close = methodId.lastIndexOf(')');
        if (hash < 0 || open < hash || close < open) return null;
        String ownerAndName = methodId.substring(0, open);
        String params = methodId.substring(open + 1, close).trim();
        int arity = params.isEmpty() ? 0 : params.split(",", -1).length;
        return ownerAndName + "/" + arity;
    }
}
