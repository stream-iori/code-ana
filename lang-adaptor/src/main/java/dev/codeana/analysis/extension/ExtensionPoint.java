package dev.codeana.analysis.extension;

import java.util.*;

/** Build-time registration only; no class loading from a target project's classpath. */
public interface ExtensionPoint {
    String id();
    default String version() { return "1"; }
    default String producer() { return id(); }
    default Set<String> requires() { return Set.of(); }
    default boolean enabled(Map<String, Object> configuration) { return true; }
    default Map<String, Object> configuration(Map<String, Object> configuration) { return Map.of(); }
    default Map<String, Integer> facets() { return Map.of(); }
    default boolean supportsBodyOnlyReuse() { return false; }
    /** Legacy/custom project callbacks keep the complete model unless explicitly narrowed. */
    default boolean projectRequiresFullModel() { return true; }
    /** Resource-only builtins may skip callbacks when no captured resources exist. */
    default boolean requiresCapturedResources() { return false; }
    /** A strict rule is a gate failure, rather than a recoverable callback failure. */
    final class GateFailure extends RuntimeException {
        public GateFailure(String message) { super(message); }
    }
}
