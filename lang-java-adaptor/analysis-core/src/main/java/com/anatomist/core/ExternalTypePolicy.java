package com.anatomist.core;

/** Decides whether an external type should be omitted from the indexed graph. */
@FunctionalInterface
public interface ExternalTypePolicy {
    ExternalTypePolicy DEFAULT =
            prefixPolicy(
                    "java.lang.",
                    "java.io.",
                    "java.net.",
                    "java.nio.",
                    "java.math.",
                    "sun.",
                    "com.sun.",
                    "jdk.");

    boolean isExternalExcluded(String qualifiedName);

    /** Explicit exclusions also apply to a type written at an exact source position. */
    default boolean isSourceSpelledExcluded(String qualifiedName) {
        return isExternalExcluded(qualifiedName);
    }

    static ExternalTypePolicy prefixPolicy(String... prefixes) {
        var snapshot = prefixes == null ? new String[0] : prefixes.clone();
        return qualifiedName -> {
            if (qualifiedName == null) return true;
            for (String prefix : snapshot) {
                if (qualifiedName.startsWith(prefix)) return true;
            }
            return false;
        };
    }
}
