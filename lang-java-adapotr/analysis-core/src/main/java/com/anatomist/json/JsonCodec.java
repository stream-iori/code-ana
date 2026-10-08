package com.anatomist.json;

/** Convert a domain object to/from a tree (Map/List/primitive) for the JSON layer. */
public interface JsonCodec<T> {
    /**
     * Render {@code value} as a tree. Implementations should skip null fields for
     * {@code @JsonInclude(NON_NULL)} parity by emitting a {@link java.util.LinkedHashMap} that
     * omits null-valued keys.
     */
    Object toTree(T value);

    /** Reconstruct an instance from a tree previously produced by {@link Json#parseTree}. */
    T fromTree(Object tree);
}
