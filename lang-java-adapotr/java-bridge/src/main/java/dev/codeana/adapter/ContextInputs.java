package dev.codeana.adapter;

import dev.codeana.common.Json;
import java.math.BigDecimal;
import java.util.*;

/** Task-local shared input descriptions. Contexts materialize one source row on demand. */
public final class ContextInputs {
    private final boolean compact;
    private final List<Object> inputs, classpath;
    private int references;

    public ContextInputs(Map<String, Object> request) {
        compact = Boolean.TRUE.equals(request.get("compact_inputs"));
        inputs = list(request.get("input_table"));
        classpath = list(request.get("classpath_table"));
        if (!compact && (!inputs.isEmpty() || !classpath.isEmpty())) fail("JAVA_INPUT_TABLE_STATE");
        if (inputs.size() > 10000 || classpath.size() > 10000) fail("JAVA_INPUT_TABLE_BUDGET");
        Set<String> paths = new HashSet<>();
        for (Object raw : inputs) {
            Map<String, Object> row = Json.map(raw), unit = Json.map(row.get("unit"));
            if (!paths.add(Json.string(unit, "path"))
                    || Json.string(unit, "path").isEmpty()
                    || Json.string(row, "input").isEmpty()
                    || Boolean.TRUE.equals(row.get("publish"))) fail("JAVA_INPUT_TABLE_IDENTITY");
        }
        for (Object value : classpath)
            if (!(value instanceof String s) || s.isEmpty()) fail("JAVA_CLASSPATH_TABLE_IDENTITY");
    }

    private static List<Object> list(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }

    private static void fail(String reason) {
        throw new IllegalArgumentException(reason);
    }

    private static int slot(Object value, int length) {
        if (!(value instanceof Number)) fail("JAVA_INPUT_INDEX");
        int index;
        try {
            index = new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("JAVA_INPUT_INDEX", invalid);
        }
        if (index < 0 || index >= length) fail("JAVA_INPUT_INDEX");
        return index;
    }

    private List<Object> indices(
            Map<String, Object> context, String field, int length, boolean unique) {
        if (!(context.get(field) instanceof List)) fail("JAVA_INPUT_TABLE_STATE");
        List<Object> values = Json.list(context.get(field));
        references += values.size();
        if (values.size() > 10000 || references > 1000000) fail("JAVA_INDEX_BUDGET");
        Set<Integer> seen = unique ? new HashSet<>() : null;
        for (Object value : values) {
            int index = slot(value, length);
            if (seen != null && !seen.add(index)) fail("JAVA_DUPLICATE_INPUT_INDEX");
        }
        return values;
    }

    public Map<String, Object> resolve(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>(raw);
        if (raw.containsKey("type_graph") && !(raw.get("type_graph") instanceof Boolean))
            fail("JAVA_TYPE_GRAPH_MODE");
        result.put("type_graph", !Boolean.FALSE.equals(raw.get("type_graph")));
        if (!compact) {
            for (String field :
                    List.of(
                            "source_indices",
                            "publish_indices",
                            "classpath_indices",
                            "resource_indices"))
                if (!list(raw.get(field)).isEmpty()) fail("JAVA_INPUT_TABLE_STATE");
            for (Object value : list(raw.get("sources"))) {
                Map<String, Object> source = Json.map(value);
                if (Boolean.TRUE.equals(source.get("publish"))
                        && (Boolean.TRUE.equals(source.get("auxiliary"))
                                || Boolean.TRUE.equals(raw.get("prerequisite"))))
                    fail("JAVA_AUXILIARY_PUBLICATION");
            }
            if (Boolean.TRUE.equals(raw.get("prerequisite")) && !"MAIN".equals(raw.get("scope")))
                fail("JAVA_PREREQUISITE_SCOPE");
            return result;
        }
        for (String field : List.of("sources", "resources", "classpath"))
            if (!list(raw.get(field)).isEmpty()) fail("JAVA_INPUT_TABLE_STATE");
        List<Object> sources = indices(raw, "source_indices", inputs.size(), true);
        List<Object> publications = indices(raw, "publish_indices", inputs.size(), true);
        if (Boolean.TRUE.equals(raw.get("prerequisite"))
                && (!publications.isEmpty() || !"MAIN".equals(raw.get("scope"))))
            fail("JAVA_AUXILIARY_PUBLICATION");
        List<Object> resources = indices(raw, "resource_indices", inputs.size(), true);
        List<Object> jars = indices(raw, "classpath_indices", classpath.size(), false);
        Set<Integer> selected = new HashSet<>(), published = new HashSet<>();
        for (Object value : sources) {
            int index = slot(value, inputs.size());
            selected.add(index);
            if (!"java".equals(Json.map(Json.map(inputs.get(index)).get("unit")).get("language")))
                fail("JAVA_SOURCE_INDEX_IDENTITY");
        }
        for (Object value : publications) {
            int index = slot(value, inputs.size());
            published.add(index);
            Map<String, Object> unit = Json.map(Json.map(inputs.get(index)).get("unit"));
            if (Boolean.TRUE.equals(unit.get("auxiliary"))
                    || !selected.contains(index)
                    || !Objects.equals(unit.get("world"), raw.get("world"))
                    || !Objects.equals(unit.get("scope"), raw.get("scope")))
                fail("JAVA_PUBLICATION_INDEX");
        }
        for (Object value : resources)
            if ("java"
                    .equals(
                            Json.map(Json.map(inputs.get(slot(value, inputs.size()))).get("unit"))
                                    .get("language"))) fail("JAVA_RESOURCE_INDEX_IDENTITY");
        result.put(
                "sources",
                new AbstractList<Object>() {
                    public int size() {
                        return sources.size();
                    }

                    public Object get(int position) {
                        int index = slot(sources.get(position), inputs.size());
                        Map<String, Object> row = Json.map(inputs.get(index));
                        Map<String, Object> source = new LinkedHashMap<>(Json.map(row.get("unit")));
                        source.put("input", row.get("input"));
                        source.put("publish", published.contains(index));
                        return source;
                    }
                });
        result.put(
                "resources",
                new AbstractList<Object>() {
                    public int size() {
                        return resources.size();
                    }

                    public Object get(int position) {
                        Map<String, Object> row =
                                Json.map(inputs.get(slot(resources.get(position), inputs.size())));
                        return Json.object("unit", row.get("unit"), "input", row.get("input"));
                    }
                });
        result.put(
                "classpath",
                new AbstractList<Object>() {
                    public int size() {
                        return jars.size();
                    }

                    public Object get(int position) {
                        return classpath.get(slot(jars.get(position), classpath.size()));
                    }
                });
        return result;
    }
}
