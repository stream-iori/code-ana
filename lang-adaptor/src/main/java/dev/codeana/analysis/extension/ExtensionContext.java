package dev.codeana.analysis.extension;

import dev.codeana.analysis.ModelAnnotations;
import dev.codeana.common.Json;
import dev.codeana.common.FactHeaders;
import dev.codeana.common.ModelArchive;

import java.util.*;

/** A task-local, deeply immutable input view. Values contain no compiler objects. */
public final class ExtensionContext {
    private final Map<String, Object> configuration, model;
    private final List<Object> facts;
    private final List<Object> factInputs;
    private final List<ProjectResource> resources;
    private dev.codeana.common.IndexedRecords declarations;

    public ExtensionContext(
            Map<String, Object> configuration,
            Map<String, Object> model,
            List<Object> facts,
            List<ProjectResource> resources) {
        this(configuration, model, facts, resources, false);
    }

    private ExtensionContext(Map<String, Object> configuration, Map<String, Object> model,
            List<Object> facts, List<ProjectResource> resources, boolean frozen) {
        this.configuration = frozen ? configuration : freezeMap(configuration);
        this.model = frozen ? model : freezeMap(model);
        this.factInputs = facts;
        // Fix the view length at callback entry. Decode and freeze one fact on demand,
        // allowing a task archive to remain on disk rather than copying the whole graph.
        this.facts = new FactHeaders.View() {
            private final int length = facts.size();
            @Override
            public int size() { return length; }
            @Override
            public Object get(int index) {
                Objects.checkIndex(index, length);
                return freeze(facts.get(index));
            }
            @Override
            public Map<String, Object> factHeader(int index) {
                Objects.checkIndex(index, length);
                return FactHeaders.get(facts, index);
            }
            @Override
            public boolean mightHaveId(int index, Set<Long> wanted) {
                Objects.checkIndex(index, length);
                return FactHeaders.mightHaveId(facts, index, wanted);
            }
            @Override
            public String factKind(int index) {
                Objects.checkIndex(index, length);
                return FactHeaders.kind(facts, index);
            }
            @Override
            public String factWorld(int index) {
                Objects.checkIndex(index, length);
                return FactHeaders.world(facts, index);
            }
        };
        this.resources = List.copyOf(resources);
    }

    /** Java/call callbacks share a completed model; each sees facts at its own callback entry. */
    ExtensionContext withFacts(List<Object> facts) {
        return new ExtensionContext(configuration, model, facts, resources, true);
    }

    public Map<String, Object> configuration() {
        return configuration;
    }

    public Map<String, Object> model() {
        return model;
    }

    public List<Object> facts() {
        return facts;
    }

    /** Internal index metadata; never exposes a mutable fact or decodes its type graph twice. */
    String factKind(int index) {
        Objects.checkIndex(index, facts.size());
        return FactHeaders.kind(factInputs, index);
    }

    Map<String, Object> factHeader(int index) {
        Objects.checkIndex(index, facts.size());
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
        return dev.codeana.common.FactHeaders.get(factInputs, index);
    }

    public List<ProjectResource> resources() {
        return resources;
    }

    /** Task-local SOURCE/overlay lookup, with a lazy positional index and bounded decoded rows. */
    public Optional<Map<String, Object>> declaration(String signature) {
        if (declarations == null) {
            List<Object> rows = Json.list(model.get("declarations"));
            declarations = new dev.codeana.common.IndexedRecords(rows);
            for (int i = 0; i < rows.size(); i++) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("CANCELLED");
                declarations.index(Json.string(Json.map(rows.get(i)), "signature"), i);
            }
        }
        return Optional.ofNullable(declarations.get(signature));
    }

    public ModelAnnotations annotations() {
        return new ModelAnnotations(model);
    }

    public static Map<String, Object> freezeMap(Map<String, Object> value) {
        return Json.map(freeze(value));
    }

    public static Object freeze(Object value) {
        return copy(value, true, 0, true);
    }

    public static Map<String, Object> mutableMap(Map<String, Object> value) {
        return Json.map(copy(value, false, 0, false));
    }

    /** Pending headers isolate mutable containers while retaining sealed compiler type evidence. */
    static Map<String, Object> pendingMap(Map<String, Object> value) {
        return Json.map(copy(value, false, 0, true));
    }

    private static Object copy(Object value, boolean immutable, int depth, boolean shareTypes) {
        if (depth > Json.MAX_DEPTH) throw new IllegalArgumentException("EXTENSION_VALUE_DEPTH");
        if (immutable && value instanceof dev.codeana.common.ArchiveRows)
            return ((dev.codeana.common.ArchiveRows) value).snapshot();
        if (immutable && value instanceof dev.codeana.common.ArchiveRows.Snapshot) return value;
        // Full mutable copies remain editable; pending overlays share only sealed type nodes.
        if (shareTypes && value instanceof ModelArchive.ImmutableType) return value;
        if (immutable && value instanceof ModelArchive.SyntaxRows) return value;
        if (value instanceof Map) {
            Map<String, Object> result = new TreeMap<>();
            for (var entry : Json.map(value).entrySet())
                result.put(entry.getKey(), copy(entry.getValue(), immutable, depth + 1, shareTypes));
            return immutable ? Collections.unmodifiableMap(result) : result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : Json.list(value)) result.add(copy(item, immutable, depth + 1, shareTypes));
            return immutable ? Collections.unmodifiableList(result) : result;
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) return value;
        throw new IllegalArgumentException("EXTENSION_VALUE_TYPE");
    }
}
