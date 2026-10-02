package dev.codeana.analysis;

import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;

import java.util.*;

/** Task-local world index over staged records, with primitive positions and lazy row decoding. */
final class ProjectFacts {
    private static final class Positions {
        int[] values = new int[64];
        int size;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }
    }

    private final List<Object> records;
    private final Map<String, Positions> worlds = new HashMap<>();
    private int indexed;

    ProjectFacts(List<Object> records) {
        this.records = records;
    }

    List<Object> visible(Map<String, Object> context) {
        for (; indexed < records.size(); indexed++) {
            String world = FactHeaders.world(records, indexed);
            worlds.computeIfAbsent(world, ignored -> new Positions()).add(indexed);
        }
        Set<String> allowed = new HashSet<>();
        allowed.add(Json.string(context, "world"));
        for (Object raw : Json.list(context.get("sources")))
            allowed.add(Json.string(Json.map(raw), "world"));
        // Resource worlds are shared by inventory, but only this context owns their callbacks.
        for (Object raw : Json.list(context.get("resources")))
            allowed.add(Json.string(Json.map(Json.map(raw).get("unit")), "world"));
        int count =
                allowed.stream()
                        .map(worlds::get)
                        .filter(Objects::nonNull)
                        .mapToInt(p -> p.size)
                        .sum();
        int[] selected = new int[count];
        int next = 0;
        for (String world : allowed) {
            Positions positions = worlds.get(world);
            if (positions == null) continue;
            System.arraycopy(positions.values, 0, selected, next, positions.size);
            next += positions.size;
        }
        Arrays.sort(selected);
        return new FactHeaders.View() {
            @Override
            public int size() {
                return selected.length;
            }

            @Override
            public Object get(int index) {
                return records.get(selected[Objects.checkIndex(index, selected.length)]);
            }

            @Override
            public Map<String, Object> factHeader(int index) {
                return FactHeaders.get(
                        records, selected[Objects.checkIndex(index, selected.length)]);
            }

            @Override
            public String factKind(int index) {
                return FactHeaders.kind(records, selected[Objects.checkIndex(index, selected.length)]);
            }

            @Override
            public String factWorld(int index) {
                return FactHeaders.world(records, selected[Objects.checkIndex(index, selected.length)]);
            }

            @Override
            public boolean mightHaveId(int index, Set<Long> wanted) {
                return FactHeaders.mightHaveId(records, selected[Objects.checkIndex(index, selected.length)], wanted);
            }

            @Override
            public void copyRecord(int index, dev.codeana.common.RecordArchive destination) {
                FactHeaders.copy(records, selected[Objects.checkIndex(index, selected.length)], destination);
            }
        };
    }
}
