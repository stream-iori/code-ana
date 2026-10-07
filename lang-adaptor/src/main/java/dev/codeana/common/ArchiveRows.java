package dev.codeana.common;

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.function.Predicate;

/** Mutable detached rows. Position handles remain small; only written rows stay resident. */
public final class ArchiveRows extends AbstractList<Object> {
    private final RecordArchive records;
    private final List<Object> types;
    private int[] positions;
    private int count;
    private final List<Object> appended = new ArrayList<Object>();
    private final Map<Integer, State> edited = new HashMap<Integer, State>();
    private final Map<Integer, StateRef> borrowed = new HashMap<Integer, StateRef>();
    private final ReferenceQueue<State> collected = new ReferenceQueue<State>();
    private final Map<Integer, State> decoded =
            new LinkedHashMap<Integer, State>(64, .75f, true) {
                protected boolean removeEldestEntry(Map.Entry<Integer, State> entry) {
                    return size() > 64;
                }
            };

    private final class State {
        final int position;
        final Object value;

        State(int position, Object value) {
            this.position = position;
            this.value = value;
        }

        void touch() {
            check();
            edited.put(position, this);
            decoded.remove(position);
        }
    }

    private static final class StateRef extends WeakReference<ArchiveRows.State> {
        final int position;

        StateRef(ArchiveRows.State state, ReferenceQueue<ArchiveRows.State> queue) {
            super(state, queue);
            position = state.position;
        }
    }

    ArchiveRows(RecordArchive records, List<Object> types, int[] positions) {
        this.records = records;
        this.types = types;
        this.positions = positions;
        count = positions.length;
    }

    private void check() {
        if (records.isClosed()) throw new IllegalStateException("MODEL_ROWS_CLOSED");
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
    }

    public int size() {
        check();
        return count;
    }

    private void index(int index) {
        check();
        if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
    }

    private State state(int position) {
        check();
        StateRef dead;
        while ((dead = (StateRef) collected.poll()) != null)
            if (borrowed.get(dead.position) == dead) borrowed.remove(dead.position);
        State value = edited.get(position);
        if (value != null) return value;
        value = decoded.get(position);
        if (value != null) return value;
        StateRef reference = borrowed.get(position);
        value = reference == null ? null : reference.get();
        if (value == null) {
            try {
                value =
                        new State(
                                position,
                                ModelArchive.unpack(
                                        Json.map(records.get(position)).get("value"), types, 0));
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
            borrowed.put(position, new StateRef(value, collected));
        }
        decoded.put(position, value);
        return value;
    }

    private Object wrap(Object value, State state) {
        if (value instanceof ModelArchive.ImmutableType || TypeGraph.isView(value)) return value;
        if (value instanceof Map) return new NestedMap(state, Json.map(value));
        if (value instanceof List) return new NestedList(state, Json.list(value));
        return value;
    }

    private Object share(Object value) {
        // Pin an aliased original row too: its existing handles must see writes through this alias.
        if (value instanceof RootMap) {
            State state = ((RootMap) value).state();
            state.touch();
            return state.value;
        }
        if (value instanceof NestedMap) {
            NestedMap map = (NestedMap) value;
            map.state.touch();
            return map.value;
        }
        if (value instanceof NestedList) {
            NestedList list = (NestedList) value;
            list.state.touch();
            return list.value;
        }
        return value;
    }

    public Object get(int index) {
        index(index);
        int position = positions[index];
        if (position < 0) return appended.get(-position - 1);
        State value = state(position);
        return value.value instanceof Map ? new RootMap(position) : wrap(value.value, value);
    }

    public ModelRowLease borrow(int index) {
        index(index);int position=positions[index];
        if(position<0) {
            Object row=appended.get(-position-1);
            return new ModelRowLease(row,row,Long.MAX_VALUE);
        }
        State value=state(position);
        Object row=value.value instanceof Map?new RootMap(position):wrap(value.value,value);
        return new ModelRowLease(row,value,records.recordBytes(position));
    }

    public void add(int index, Object value) {
        check();
        if (index < 0 || index > count) throw new IndexOutOfBoundsException();
        if (count == positions.length) positions = Arrays.copyOf(positions, Math.max(8, count * 2));
        System.arraycopy(positions, index, positions, index + 1, count - index);
        positions[index] = -appended.size() - 1;
        appended.add(share(value));
        count++;
        modCount++;
    }

    public Object set(int index, Object value) {
        index(index);
        Object old = get(index);
        positions[index] = -appended.size() - 1;
        appended.add(share(value));
        return old;
    }

    public Object remove(int index) {
        index(index);
        Object old = get(index);
        System.arraycopy(positions, index + 1, positions, index, count - index - 1);
        count--;
        modCount++;
        return old;
    }

    public void clear() {
        check();
        count = 0;
        modCount++;
    }

    public boolean removeIf(Predicate<? super Object> predicate) {
        check();
        Objects.requireNonNull(predicate);
        int[] keep = new int[count];
        int retained = 0;
        for (int i = 0; i < count; i++)
            if (!predicate.test(get(i))) keep[retained++] = positions[i];
        if (retained == count) return false;
        positions = Arrays.copyOf(keep, retained);
        count = retained;
        modCount++;
        return true;
    }

    /** Callback-entry rows and length; later commits cannot alter the snapshot. */
    public List<Object> snapshot() {
        check();
        final int[] selected = Arrays.copyOf(positions, count);
        final Map<Integer, Integer> frozen = new HashMap<Integer, Integer>();
        ModelArchive.RowSpool spool = null;
        try {
            // Changed rows cannot be borrowed: callers may retain plain mutable aliases.
            // Freeze them on disk rather than keeping a second decoded context on the heap.
            for (int position : selected) {
                if (frozen.containsKey(position)) continue;
                if (position >= 0 && !edited.containsKey(position)) continue;
                Object value = position < 0 ? appended.get(-position - 1) : edited.get(position).value;
                if (spool == null)
                    spool = new ModelArchive.RowSpool(records.path().getParent(), "snapshot", records.byteLimit());
                frozen.put(position, spool.size());
                spool.add(Json.object("snapshot", value));
            }
            if (spool != null) {
                spool.seal();
                records.own(spool);
            }
            return new Snapshot(records, types, selected, frozen, spool);
        } catch (IOException | RuntimeException | Error failed) {
            if (spool != null) try { spool.close(); } catch (IOException cleanup) { failed.addSuppressed(cleanup); }
            if (failed instanceof IOException) throw new UncheckedIOException((IOException) failed);
            if (failed instanceof RuntimeException) throw (RuntimeException) failed;
            throw (Error) failed;
        }
    }

    public static final class Snapshot extends AbstractList<Object> {
        private final RecordArchive records;
        private final List<Object> types;
        private final int[] positions;
        private final Map<Integer, Integer> frozen;
        private final ModelArchive.RowSpool spool;

        Snapshot(
                RecordArchive records,
                List<Object> types,
                int[] positions,
                Map<Integer, Integer> frozen,
                ModelArchive.RowSpool spool) {
            this.records = records;
            this.types = types;
            this.positions = positions;
            this.frozen = frozen;
            this.spool = spool;
        }

        public int size() {
            if (records.isClosed()) throw new IllegalStateException("MODEL_ROWS_CLOSED");
            return positions.length;
        }

        public Object get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            int position = positions[index];
            if (frozen.containsKey(position))
                return ModelArchive.immutable(Json.map(spool.get(frozen.get(position))).get("snapshot"), 0);
            try {
                return ModelArchive.immutable(
                        ModelArchive.unpack(Json.map(records.get(position)).get("value"), types, 0),
                        0);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }

        public ModelRowLease borrow(int index) {
            if(index<0 || index>=size())throw new IndexOutOfBoundsException();
            int position=positions[index];
            if(frozen.containsKey(position)) {
                ModelRowLease lease=spool.borrow(frozen.get(position));
                Object row=ModelArchive.immutable(Json.map(lease.row).get("snapshot"),0);
                return new ModelRowLease(row,lease,lease.bytes);
            }
            Object row=get(index);
            return new ModelRowLease(row,row,records.recordBytes(position));
        }
    }

    private abstract class TrackedMap extends AbstractMap<String, Object> {
        abstract State state();

        abstract Map<String, Object> value();

        public Object get(Object key) {
            State state = state();
            return wrap(value().get(key), state);
        }

        public boolean containsKey(Object key) {
            check();
            return value().containsKey(key);
        }

        public int size() {
            check();
            return value().size();
        }

        public Object put(String key, Object value) {
            State state = state();
            state.touch();
            return value().put(key, share(value));
        }

        public Object remove(Object key) {
            State state = state();
            state.touch();
            return value().remove(key);
        }

        public void clear() {
            State state = state();
            state.touch();
            value().clear();
        }

        public Set<Map.Entry<String, Object>> entrySet() {
            return new AbstractSet<Map.Entry<String, Object>>() {
                public int size() {
                    return TrackedMap.this.size();
                }

                public Iterator<Map.Entry<String, Object>> iterator() {
                    final State state = state();
                    final Iterator<Map.Entry<String, Object>> rows = value().entrySet().iterator();
                    return new Iterator<Map.Entry<String, Object>>() {
                        public boolean hasNext() {
                            check();
                            return rows.hasNext();
                        }

                        public Map.Entry<String, Object> next() {
                            final Map.Entry<String, Object> entry = rows.next();
                            return new Map.Entry<String, Object>() {
                                public String getKey() {
                                    check();
                                    return entry.getKey();
                                }

                                public Object getValue() {
                                    check();
                                    return wrap(entry.getValue(), state);
                                }

                                public Object setValue(Object value) {
                                    state.touch();
                                    return entry.setValue(share(value));
                                }

                                public int hashCode() {
                                    return Objects.hashCode(getKey())
                                            ^ Objects.hashCode(getValue());
                                }

                                public boolean equals(Object other) {
                                    return other instanceof Map.Entry
                                            && Objects.equals(
                                                    getKey(), ((Map.Entry<?, ?>) other).getKey())
                                            && Objects.equals(
                                                    getValue(),
                                                    ((Map.Entry<?, ?>) other).getValue());
                                }
                            };
                        }

                        public void remove() {
                            state.touch();
                            rows.remove();
                        }
                    };
                }
            };
        }
    }

    /** Root handles never retain a decoded row; semantic indexes can keep them cheaply. */
    private final class RootMap extends TrackedMap {
        final int position;

        RootMap(int position) {
            this.position = position;
        }

        State state() {
            return ArchiveRows.this.state(position);
        }

        Map<String, Object> value() {
            return Json.map(state().value);
        }
    }

    /**
     * A borrowed nested container preserves ordinary Java Map/List alias semantics across eviction.
     */
    private final class NestedMap extends TrackedMap {
        final State state;
        final Map<String, Object> value;

        NestedMap(State state, Map<String, Object> value) {
            this.state = state;
            this.value = value;
        }

        State state() {
            check();
            return state;
        }

        Map<String, Object> value() {
            check();
            return value;
        }
    }

    private final class NestedList extends AbstractList<Object> {
        final State state;
        final List<Object> value;

        NestedList(State state, List<Object> value) {
            this.state = state;
            this.value = value;
        }

        public int size() {
            check();
            return value.size();
        }

        public Object get(int index) {
            check();
            return wrap(value.get(index), state);
        }

        public Object set(int index, Object replacement) {
            state.touch();
            return value.set(index, share(replacement));
        }

        public void add(int index, Object replacement) {
            state.touch();
            value.add(index, share(replacement));
            modCount++;
        }

        public Object remove(int index) {
            state.touch();
            Object old = value.remove(index);
            modCount++;
            return old;
        }

        public void clear() {
            state.touch();
            value.clear();
            modCount++;
        }
    }
}
