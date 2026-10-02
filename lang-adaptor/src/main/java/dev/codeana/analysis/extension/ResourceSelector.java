package dev.codeana.analysis.extension;

/** Selects only from the sealed provider inventory. Predicate failures belong to the analyzer transaction. */
@FunctionalInterface
public interface ResourceSelector {
    boolean matches(ProjectResource resource);
    static ResourceSelector kind(String kind) {
        java.util.Objects.requireNonNull(kind);
        return resource -> resource != null && kind.equals(resource.kind());
    }
    default ResourceSelector and(ResourceSelector other) {
        java.util.Objects.requireNonNull(other);
        return resource -> matches(resource) && other.matches(resource);
    }
}
