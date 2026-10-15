package com.anatomist.core;

import java.nio.file.Path;

public record SourceRoot(
        Path path, String module, SourceScope scope, SourceScope compilationScope) {
    public SourceRoot(Path path, String module, SourceScope scope) {
        this(
                path,
                module,
                scope,
                scope == SourceScope.TEST
                                || (path.toString().replace('\\', '/') + "/")
                                        .contains("/generated-test-sources/")
                        ? SourceScope.TEST
                        : SourceScope.MAIN);
    }

    public SourceRoot {
        path = path.toAbsolutePath().normalize();
        module = module == null || module.isBlank() ? "." : module.replace('\\', '/');
        scope = scope == null ? SourceScope.MAIN : scope;
        if (compilationScope != SourceScope.MAIN && compilationScope != SourceScope.TEST)
            throw new IllegalArgumentException("compilation scope must be MAIN or TEST");
    }
}
