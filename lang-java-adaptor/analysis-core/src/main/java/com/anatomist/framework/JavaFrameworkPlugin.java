package com.anatomist.framework;

import java.util.List;

/** A Java framework adapter linked into the application at build time. */
public interface JavaFrameworkPlugin {
    String id();

    String version();

    default List<String> requires() {
        return List.of();
    }

    default JavaModelAnalyzerRegistry detachedAnalyzers(JavaFrameworkContext context) {
        throw new UnsupportedOperationException("plugin has no detached implementation: " + id());
    }
}
