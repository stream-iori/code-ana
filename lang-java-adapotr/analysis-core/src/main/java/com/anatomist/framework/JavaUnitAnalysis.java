package com.anatomist.framework;

import com.anatomist.core.SourceIdentity;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Borrowed context for one unit callback; the framework must not retain model or binary readers.
 */
public record JavaUnitAnalysis(
        JavaFrameworkContext project,
        JavaModel model,
        JavaBinaryRepository binaries,
        Path source,
        SourceIdentity identity) {
    public JavaUnitAnalysis {
        Objects.requireNonNull(project);
        Objects.requireNonNull(model);
        source = Objects.requireNonNull(source).toAbsolutePath().normalize();
        Objects.requireNonNull(identity);
    }

    public JavaUnit unit() {
        return model.unit(source);
    }

    public JavaIdentities identities() {
        return new JavaIdentities(model);
    }

    public String sourceFile() {
        return project.projectRoot().relativize(source).toString().replace('\\', '/');
    }
}
