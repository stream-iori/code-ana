package com.anatomist.framework;

@FunctionalInterface
public interface ResourceSelector {
    boolean matches(ProjectResource resource);

    static ResourceSelector kind(String kind) {
        return resource -> resource != null && kind.equals(resource.kind());
    }
}
