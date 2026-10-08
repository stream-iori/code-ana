package com.anatomist.core.java;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached syntax at the worker boundary. Fields preserve null and source traversal order. */
public record JavaNode(int id, String kind, long start, long end, Map<String, Object> fields) {
    public JavaNode {
        if (id < 0) throw new IllegalArgumentException("negative syntax identity");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public SourceSpan span() {
        return start < 0 || end < start ? SourceSpan.ABSENT : new SourceSpan(start, end);
    }

    public String text(String key) {
        Object value = fields.get(key);
        return value == null ? "" : value.toString();
    }

    public JavaNode tree(String key) {
        return fields.get(key) instanceof JavaNode value ? value : null;
    }

    public List<JavaNode> trees(String key) {
        return values(key).stream()
                .filter(JavaNode.class::isInstance)
                .map(JavaNode.class::cast)
                .toList();
    }

    public List<?> values(String key) {
        return fields.get(key) instanceof List<?> list ? list : List.of();
    }
}
