package com.anatomist.core.asmsolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads class bytes lazily from a single jar. Eagerly indexes the jar's entry table on construction
 * so {@link #knownClasses()} is cheap, but does NOT load class bytes into memory until {@link
 * #find} is called.
 */
public final class JarClassFileSource implements ClassFileSource {

    private final JarFile jar;
    private final Set<String> classNames;

    public JarClassFileSource(Path jarPath) {
        try {
            this.jar = new JarFile(jarPath.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open jar " + jarPath, e);
        }
        Set<String> names = new LinkedHashSet<>();
        for (var it = jar.entries(); it.hasMoreElements(); ) {
            JarEntry e = it.nextElement();
            String name = e.getName();
            if (!name.endsWith(".class")) continue;
            if (name.equals("module-info.class")) continue;
            if (name.endsWith("/module-info.class")) continue;
            if (name.endsWith("/package-info.class")) continue;
            // strip ".class" suffix, swap '/' → '.'
            String fqn = name.substring(0, name.length() - 6).replace('/', '.');
            names.add(fqn);
        }
        this.classNames = Set.copyOf(names);
    }

    @Override
    public Optional<byte[]> find(String fqn) {
        if (!classNames.contains(fqn)) return Optional.empty();
        String entryName = fqn.replace('.', '/') + ".class";
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) return Optional.empty();
        try (InputStream in = jar.getInputStream(entry)) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + entryName + " from jar", e);
        }
    }

    @Override
    public Set<String> knownClasses() {
        return classNames;
    }

    @Override
    public void close() {
        try {
            jar.close();
        } catch (IOException ignore) {
        }
    }
}
