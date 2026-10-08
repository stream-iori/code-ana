package com.anatomist.core.asmsolver;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Abstracts the source of .class bytes for the AsmTypeSolver, so unit tests can inject in-memory
 * mock classes via ASM ClassWriter while production reads from on-disk jars.
 */
public interface ClassFileSource extends AutoCloseable {

    /** Return the raw .class bytes for {@code fqn} if present, else empty. */
    Optional<byte[]> find(String fqn);

    /**
     * Enumerate every FQN this source can resolve (used for diagnostics and for the AsmTypeSolver
     * to know whether to defer to a sibling).
     */
    Set<String> knownClasses();

    /** Manifest value from the exact classpath artifact that supplies this binary name. */
    default Optional<String> artifactAttribute(String binaryName, String name) {
        return Optional.empty();
    }

    /** Ordered providers for one diagnostic type; ordinary resolution need not enumerate them. */
    default List<Path> providers(String binaryName) {
        return List.of();
    }

    /** Unreadable entries encountered while explaining one diagnostic type. */
    default List<Path> providerFailures(String binaryName) {
        return List.of();
    }

    @Override
    default void close() {}
}
