package com.anatomist.core;

public record SourceIdentity(String module, SourceScope scope) {
    public SourceIdentity {
        module = module == null || module.isBlank() ? "." : module.replace('\\', '/');
        scope = scope == null ? SourceScope.MAIN : scope;
    }
}
