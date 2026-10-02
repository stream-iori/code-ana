package dev.codeana.common;

import java.util.*;

/** Internal flat metadata for selecting facts without decoding their attribute graphs. */
public interface FactHeaders {
    Map<String, Object> factHeader(int index);

    default String factKind(int index) { return Json.string(factHeader(index), "kind"); }
    default String factWorld(int index) { return Json.string(factHeader(index), "world"); }

    static String kind(List<?> facts, int index) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        return facts instanceof FactHeaders ? ((FactHeaders) facts).factKind(index)
                : Json.string(project(facts.get(index)), "kind");
    }

    static String world(List<?> facts, int index) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        return facts instanceof FactHeaders ? ((FactHeaders) facts).factWorld(index)
                : Json.string(project(facts.get(index)), "world");
    }

    static long identityHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            boolean paired = Character.isHighSurrogate(current)
                    && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1));
            if (paired) {
                hash ^= current; hash *= 0x100000001b3L;
                hash ^= value.charAt(++index); hash *= 0x100000001b3L;
            } else {
                hash ^= Character.isSurrogate(current) ? '?' : current;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    static String encodedText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) index++;
            else if (Character.isSurrogate(current))
                return new String(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8);
        }
        return value;
    }

    /** Hashes only reject nonmatches; any candidate must still be checked by exact identity. */
    default boolean mightHaveId(int index, Set<Long> wanted) {
        return wanted.contains(identityHash(Json.string(factHeader(index), "id")));
    }

    static boolean mightHaveId(List<?> facts, int index, Set<Long> wanted) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        return facts instanceof FactHeaders ? ((FactHeaders) facts).mightHaveId(index, wanted)
                : wanted.contains(identityHash(Json.string(Json.map(facts.get(index)), "id")));
    }

    static Map<String, Object> project(Object raw) {
        Map<String, Object> fact = Json.map(raw);
        return Collections.unmodifiableMap(
                Json.object(
                        "id",
                        fact.get("id"),
                        "signature",
                        fact.get("signature"),
                        "kind",
                        fact.get("kind"),
                        "source",
                        fact.get("source"),
                        "parent",
                        fact.get("parent"),
                        "target",
                        fact.get("target"),
                        "world",
                        Json.map(fact.get("owner")).get("world"),
                        "generated",
                        Boolean.TRUE.equals(Json.map(fact.get("attributes")).get("generated")),
                        "receiver_type",
                        Json.map(fact.get("attributes")).getOrDefault("receiver_type", "")));
    }

    static Map<String, Object> get(List<?> facts, int index) {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        return facts instanceof FactHeaders
                ? ((FactHeaders) facts).factHeader(index)
                : project(facts.get(index));
    }

    interface Encoded {
        void copyRecord(int index, RecordArchive destination);
    }

    static void copy(List<?> records, int index, RecordArchive destination) {
        if (records instanceof Encoded) ((Encoded) records).copyRecord(index, destination);
        else destination.add(records.get(index));
    }

    abstract class View extends AbstractList<Object> implements FactHeaders, Encoded {
        public void copyRecord(int index, RecordArchive destination) {
            destination.add(get(index));
        }
    }

    /** The first stage is fixed at entry; only this stage's output is retained in memory. */
    static List<Object> appended(final List<Object> core) {
        return joined(core, new ArrayList<Object>(), core.size());
    }

    static List<Object> joined(final List<Object> core, final List<Object> appended) {
        return joined(core, appended, core.size());
    }

    static List<Object> joined(
            final List<Object> core, final List<Object> appended, final int initial) {
        return new View() {
            private void check(int index) {
                if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            }

            @Override
            public int size() {
                return initial + appended.size();
            }

            @Override
            public Object get(int index) {
                check(index);
                return index < initial ? core.get(index) : appended.get(index - initial);
            }

            @Override
            public Map<String, Object> factHeader(int index) {
                check(index);
                return index < initial
                        ? FactHeaders.get(core, index)
                        : FactHeaders.get(appended, index - initial);
            }

            @Override
            public String factKind(int index) {
                check(index);
                return index < initial ? FactHeaders.kind(core, index)
                        : FactHeaders.kind(appended, index - initial);
            }

            @Override
            public String factWorld(int index) {
                check(index);
                return index < initial ? FactHeaders.world(core, index)
                        : FactHeaders.world(appended, index - initial);
            }

            @Override
            public boolean mightHaveId(int index, Set<Long> wanted) {
                check(index);
                return index < initial ? FactHeaders.mightHaveId(core, index, wanted)
                        : FactHeaders.mightHaveId(appended, index - initial, wanted);
            }

            @Override
            public void copyRecord(int index, RecordArchive destination) {
                check(index);
                if (index < initial) FactHeaders.copy(core, index, destination);
                else FactHeaders.copy(appended, index - initial, destination);
            }

            @Override
            public boolean add(Object value) {
                return appended.add(value);
            }
        };
    }
}
