package com.anatomist.framework;

/** Common contract for deterministic, compile-time registered extensions. */
public interface ExtensionPoint {
    String id();

    default String version() {
        return "1";
    }

    default String producerId() {
        return id();
    }

    /** Stable configuration material that changes the facts emitted by this extension. */
    default String fingerprintMaterial() {
        return "";
    }
}
