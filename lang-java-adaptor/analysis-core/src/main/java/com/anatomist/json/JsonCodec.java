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
    /** Production writers require an explicit allocation-aware implementation. */
    default Object toTree(T value,com.anatomist.core.AnalysisContext analysis) {
        if(analysis==null)return toTree(value);
        throw new com.anatomist.core.FatalAnalysisFailure("JSON_CODEC_BUDGET_REQUIRED");
    }
    default T fromTree(Object tree,com.anatomist.core.AnalysisContext analysis) {
        if(analysis==null)return fromTree(tree);
        throw new com.anatomist.core.FatalAnalysisFailure("JSON_CODEC_BUDGET_REQUIRED");
    }
}
