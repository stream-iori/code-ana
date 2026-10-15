package com.anatomist.core.asmsolver;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test-only in-memory source. Production paths use {@link JarClassFileSource}. */
public final class InMemoryClassFileSource implements ClassFileSource {

    private final Map<String, byte[]> classes;

    public InMemoryClassFileSource(Map<String, byte[]> classes) {
        this.classes = Map.copyOf(classes);
    }

    @Override
    public Optional<byte[]> find(String fqn) {
        byte[] b = classes.get(fqn);
        return b == null ? Optional.empty() : Optional.of(b);
    }

    @Override
    public Set<String> knownClasses() {
        return classes.keySet();
    }
}
