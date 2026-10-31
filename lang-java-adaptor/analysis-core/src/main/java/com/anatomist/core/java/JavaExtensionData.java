package com.anatomist.core.java;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.AnalysisContext;
import com.anatomist.core.HardResourceLimit;

import java.util.*;

/** Extension boundary: declaration IDs own generated provenance and serialized capability data. */
public record JavaExtensionData(
        Map<String, JavaGeneratedOrigin> generated,
        Map<String, Map<String, Object>> declarationMetadata,
        List<IndexDiagnostic> diagnostics,
        Map<String, Long> counters) {
    public static final JavaExtensionData EMPTY =
            new JavaExtensionData(Map.of(), Map.of(), List.of(), Map.of());

    public JavaExtensionData {
        generated = Collections.unmodifiableMap(new LinkedHashMap<>(generated));
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        var budget = new Budget();
        declarationMetadata.forEach((key, value) -> metadata.put(key, freezeMap(value, 0, budget)));
        declarationMetadata = Collections.unmodifiableMap(metadata);
        diagnostics = List.copyOf(diagnostics);
        counters = Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }

    /** Java 25 constructor prologue admits all copies before delegating to the
     * canonical immutable snapshot. No thread-local or default task ledger. */
    public JavaExtensionData(Map<String,JavaGeneratedOrigin> generated,
            Map<String,Map<String,Object>> metadata,List<IndexDiagnostic> diagnostics,
            Map<String,Long> counters,AnalysisContext analysis) {
        admit(generated,metadata,diagnostics,counters,analysis);
        this(generated,metadata,diagnostics,counters);
    }
    public static void admitMetadata(Map<String,Object> value,AnalysisContext resources) {
        admit(Map.of(),Map.of("evidence",value==null?Map.of():value),List.of(),Map.of(),resources);
    }

    private record Pending(Object value,int depth) {}
    private static void admit(Map<String,JavaGeneratedOrigin> generated,
            Map<String,Map<String,Object>> metadata,List<IndexDiagnostic> diagnostics,
            Map<String,Long> counters,AnalysisContext root) {
        if(root==null)return;
        long entries=(long)generated.size()+metadata.size()+diagnostics.size()+counters.size();
        root.charge(1+entries,1+entries,1024L+128L*entries);
        var budget=root.childScope(new AnalysisContext.Limits(root.limits().maxWork(),
                Math.min(100000,root.limits().maxNodes()),root.limits().maxPayloadBytes(),Math.min(65,root.limits().maxDepth())));
        var pending=new ArrayDeque<Pending>();
        java.util.function.BiConsumer<Object,Integer> push=(value,depth)-> {
            budget.depth(depth+1);budget.charge(1,1,256);
            pending.push(new Pending(value,depth));
        };
        for(var map:metadata.values()) {
            root.charge(1,1,128L+64L*map.size());
            for(var entry:map.entrySet())push.accept(entry.getValue(),1);
        }
        while(!pending.isEmpty()) {
            var next=pending.pop();Object value=next.value();
            if(value instanceof Map<?,?> map) {
                budget.charge(map.size(),0,64L*map.size());
                for(var entry:map.entrySet()) {
                    if(!(entry.getKey() instanceof String))throw new IllegalArgumentException("extension metadata key must be text");
                    push.accept(entry.getValue(),next.depth()+1);
                }
            } else if(value instanceof Collection<?> items) {
                budget.charge(items.size(),0,32L*items.size());
                for(Object item:items)push.accept(item,next.depth()+1);
            } else if(!(value==null || value instanceof String || value instanceof Boolean
                    || value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof Float || value instanceof Double))
                throw new IllegalArgumentException("extension metadata value must be JSON data");
        }
    }

    private static Map<String, Object> freezeMap(Map<?, ?> input, int depth, Budget budget) {
        Map<String, Object> copy = new LinkedHashMap<>();
        input.forEach(
                (key, value) -> {
                    if (!(key instanceof String name))
                        throw new IllegalArgumentException("extension metadata key must be text");
                    copy.put(name, freeze(value, depth + 1, budget));
                });
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth, Budget budget) {
        if (depth > 64 || ++budget.visits > 100_000)
            throw new HardResourceLimit("extension metadata exceeds budget");
        if (value instanceof Map<?, ?> map) return freezeMap(map, depth, budget);
        if (value instanceof Collection<?> values) {
            List<Object> copy = new ArrayList<>(values.size());
            for (Object item : values) copy.add(freeze(item, depth + 1, budget));
            return Collections.unmodifiableList(copy);
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double) return value;
        throw new IllegalArgumentException("extension metadata value must be JSON data");
    }

    private static final class Budget {
        private int visits;
    }
}
