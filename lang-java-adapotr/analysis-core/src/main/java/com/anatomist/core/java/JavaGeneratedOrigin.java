package com.anatomist.core.java;

import java.util.Objects;

/** Provenance of a modeled or processor-generated declaration, independent of syntax objects. */
public record JavaGeneratedOrigin(
        String producerId,
        String generator,
        String generatorMode,
        String generatedFrom,
        String confidence,
        boolean bodyAvailable) {
    public JavaGeneratedOrigin {
        Objects.requireNonNull(producerId);
        Objects.requireNonNull(generator);
        Objects.requireNonNull(generatorMode);
        Objects.requireNonNull(generatedFrom);
        Objects.requireNonNull(confidence);
    }
}
