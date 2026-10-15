package com.anatomist.json;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Type → codec lookup. DTOs register themselves in their static init blocks, so introducing a new
 * serializable type doesn't require touching {@link Json}.
 */
public final class JsonCodecRegistry {

    private static final ConcurrentMap<Class<?>, JsonCodec<?>> CODECS = new ConcurrentHashMap<>();

    private JsonCodecRegistry() {}

    public static <T> void register(Class<T> type, JsonCodec<T> codec) {
        CODECS.put(type, codec);
    }

    @SuppressWarnings("unchecked")
    public static <T> JsonCodec<T> lookup(Class<?> type) {
        return (JsonCodec<T>) CODECS.get(type);
    }
}
