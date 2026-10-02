package dev.codeana.common;

import java.util.*;

/** Sorted identity lookup that retains positions and a bounded cache, never the whole archive. */
public final class IndexedRecords extends AbstractMap<String, Map<String, Object>> {
    private final List<Object> rows;
    private final NavigableMap<String, Integer> positions = new TreeMap<>();
    private final Map<Integer, Map<String, Object>> decoded =
            new LinkedHashMap<>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Integer, Map<String, Object>> eldest) {
                    return size() > 64;
                }
            };

    public IndexedRecords(List<Object> rows) {
        this.rows = rows;
    }

    /** Register a row identity; the latest registration replaces the earlier position. */
    public void index(String identity, int position) {
        Objects.checkIndex(position, rows.size());
        positions.put(Objects.requireNonNull(identity), position);
    }

    @Override
    public Map<String, Object> get(Object identity) {
        Integer position = positions.get(identity);
        return position == null
                ? null
                : decoded.computeIfAbsent(position, i -> Json.map(rows.get(i)));
    }

    @Override
    public boolean containsKey(Object identity) {
        return positions.containsKey(identity);
    }

    @Override
    public int size() {
        return positions.size();
    }

    @Override
    public Set<Map.Entry<String, Map<String, Object>>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return positions.size();
            }

            @Override
            public Iterator<Map.Entry<String, Map<String, Object>>> iterator() {
                Iterator<String> identities = positions.navigableKeySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return identities.hasNext();
                    }

                    @Override
                    public Map.Entry<String, Map<String, Object>> next() {
                        String identity = identities.next();
                        return new AbstractMap.SimpleImmutableEntry<>(identity, get(identity));
                    }
                };
            }
        };
    }
}
