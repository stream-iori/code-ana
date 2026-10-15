package com.anatomist.core.java;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Declaration and type-use annotation values, including annotations with SOURCE retention. */
public record JavaAnnotation(
        String descriptor, Retention retention, Map<String, JavaBinaryType.Value> values) {
    public enum Retention {
        SOURCE,
        CLASS,
        RUNTIME
    }

    public JavaAnnotation {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(retention);
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
