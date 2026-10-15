package com.anatomist.framework;

import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.SymbolFact;
import com.anatomist.model.TypeRelationFact;

import java.util.Map;
import java.util.Set;

public record DefaultProjectFactView(
        Set<String> knownNodeIds,
        Map<String, BeanRefTarget> beanTargets,
        java.util.List<SymbolFact> symbolFacts,
        java.util.List<TypeRelationFact> typeRelations)
        implements ProjectFactView {
    public static Map<String, BeanRefTarget> fromBeanClassMap(Map<String, String> beanClassById) {
        Map<String, BeanRefTarget> out = new java.util.LinkedHashMap<>();
        beanClassById.forEach((id, type) -> out.put(id, new BeanRefTarget(id, type)));
        return out;
    }

    public DefaultProjectFactView(
            Set<String> knownNodeIds, Map<String, BeanRefTarget> beanTargets) {
        this(knownNodeIds, beanTargets, null, null);
    }

    public DefaultProjectFactView(
            Set<String> knownNodeIds,
            Map<String, BeanRefTarget> beanTargets,
            java.util.List<SymbolFact> symbolFacts) {
        this(knownNodeIds, beanTargets, symbolFacts, null);
    }

    public DefaultProjectFactView {
        knownNodeIds = knownNodeIds == null ? Set.of() : Set.copyOf(knownNodeIds);
        beanTargets = beanTargets == null ? Map.of() : Map.copyOf(beanTargets);
        symbolFacts =
                symbolFacts == null
                        ? knownNodeIds.stream()
                                .map(
                                        id ->
                                                new SymbolFact(
                                                        id,
                                                        com.anatomist.core.NodeKeyFactory.isKey(id)
                                                                ? com.anatomist.core.NodeKeyFactory
                                                                        .symbolId(id)
                                                                : id,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null))
                                .toList()
                        : java.util.List.copyOf(symbolFacts);
        typeRelations =
                typeRelations == null ? java.util.List.of() : java.util.List.copyOf(typeRelations);
    }
}
