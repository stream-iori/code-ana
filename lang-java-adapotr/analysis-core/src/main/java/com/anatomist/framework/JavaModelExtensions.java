package com.anatomist.framework;

import com.anatomist.core.ContentHash;
import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;

import java.util.*;

/** Deterministic detached overlays; an unsuccessful extension cannot commit a partial inventory. */
public final class JavaModelExtensions implements JavaDeclarationOverlay {
    public static final JavaModelExtensions NONE = new JavaModelExtensions(List.of());
    private final List<JavaModelExtension> extensions;
    private final String fingerprint;

    public JavaModelExtensions(List<JavaModelExtension> extensions) {
        this.extensions = List.copyOf(extensions);
        Set<String> ids = new HashSet<>();
        StringBuilder material = new StringBuilder("java-model-extensions-v1\n");
        for (var extension : this.extensions) {
            if (extension.id() == null || extension.id().isBlank() || !ids.add(extension.id()))
                throw new IllegalArgumentException("invalid or duplicate model extension id");
            // Length framing prevents separators in configuration material from colliding.
            for (String field :
                    List.of(
                            extension.getClass().getName(),
                            extension.id(),
                            extension.version(),
                            extension.producerId(),
                            extension.fingerprintMaterial()))
                material.append(field.length()).append(':').append(field);
            material.append('\n');
        }
        fingerprint = ContentHash.sha256(material.toString());
    }

    public boolean isEmpty() {
        return extensions.isEmpty();
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public void verifyInputs() {
        for (var extension : extensions) extension.verifyInputs();
    }

    public JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries) {
        return augment(context, binaries, null);
    }

    @Override
    public JavaAnalysisContext augment(
            JavaAnalysisContext context,
            JavaBinaryRepository binaries,
            com.anatomist.core.IndexTimings timings) {
        JavaAnalysisContext working = context;
        for (var extension : extensions) {
            long started = timings == null ? 0 : timings.start();
            try {
                JavaAnalysisContext candidate =
                        Objects.requireNonNull(extension.augment(working, binaries));
                validate(working, candidate);
                working = candidate;
            } catch (RuntimeException failure) {
                working = failed(working, extension, failure);
            } finally {
                if (timings != null)
                    timings.stop("extension_model_augment." + extension.id(), started);
            }
        }
        return working;
    }

    private static void validate(JavaAnalysisContext before, JavaAnalysisContext after) {
        if (!before.module().equals(after.module())
                || before.scope() != after.scope()
                || !before.publish().equals(after.publish())
                || !before.diagnostics().equals(after.diagnostics())
                || !before.analysisFailure().equals(after.analysisFailure())
                || !before.sourceIdentities().equals(after.sourceIdentities())
                || before.units().size() != after.units().size())
            throw new IllegalArgumentException("model extension changed analysis ownership");
        for (int i = 0; i < before.units().size(); i++) {
            JavaUnit original = before.units().get(i), replacement = after.units().get(i);
            if (!original.path().equals(replacement.path())
                    || original.tree() != replacement.tree()
                    || original.processing() != replacement.processing()
                    || !original.bindings().equals(replacement.bindings())
                    || original.declarations() != replacement.declarations()
                    || !original.sourceHash().equals(replacement.sourceHash())
                    || replacement.types().entries().size() < original.types().entries().size()
                    || !replacement
                            .types()
                            .entries()
                            .subList(0, original.types().entries().size())
                            .equals(original.types().entries()))
                throw new IllegalArgumentException("model extension changed compiler evidence");
            original.extensions()
                    .declarationMetadata()
                    .forEach(
                            (declaration, namespaces) -> {
                                var contributed =
                                        replacement
                                                .extensions()
                                                .declarationMetadata()
                                                .getOrDefault(declaration, Map.of());
                                namespaces.forEach(
                                        (namespace, value) -> {
                                            if (!Objects.equals(value, contributed.get(namespace)))
                                                throw new IllegalStateException(
                                                        "EXTENSION_METADATA_NAMESPACE_CONFLICT: "
                                                                + namespace);
                                        });
                            });
        }
    }

    private static JavaAnalysisContext failed(
            JavaAnalysisContext context, JavaModelExtension extension, RuntimeException failure) {
        List<JavaUnit> units = new ArrayList<>();
        for (var unit : context.units()) {
            var old = unit.extensions();
            List<IndexDiagnostic> diagnostics = new ArrayList<>(old.diagnostics());
            var identity = context.identity(unit.path());
            diagnostics.add(
                    new IndexDiagnostic(
                            "warning",
                            "JAVA_MODEL_EXTENSION_FAILED",
                            "JAVA_MODEL_EXTENSION",
                            unit.path().toString(),
                            identity.module(),
                            identity.scope().name(),
                            extension.id(),
                            1,
                            failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            units.add(
                    new JavaUnit(
                            unit.path(),
                            unit.tree(),
                            unit.processing(),
                            unit.bindings(),
                            unit.types(),
                            unit.declarations(),
                            unit.declarationInventory().stream()
                                    .map(value -> incomplete(value, extension.id()))
                                    .toList(),
                            unit.sourceHash(),
                            new JavaExtensionData(
                                    old.generated(),
                                    old.declarationMetadata(),
                                    diagnostics,
                                    old.counters())));
        }
        return new JavaAnalysisContext(
                context.module(),
                context.scope(),
                units,
                context.publish(),
                context.diagnostics(),
                context.analysisFailure(),
                context.sourceIdentities(),
                context.classpath(),
                context.classpathSource(),
                context.classpathEntries(),
                context.targetRelease());
    }

    private static JavaDeclaration incomplete(JavaDeclaration value, String extension) {
        List<String> reasons = new ArrayList<>(value.incompleteReasons());
        reasons.add("model-extension-failed:" + extension);
        return new JavaDeclaration(
                value.symbol(),
                value.canonicalName(),
                value.binaryName(),
                value.signature(),
                value.span(),
                value.origin(),
                value.superclass(),
                value.interfaces(),
                value.permitted(),
                value.annotations(),
                value.constant(),
                value.annotationDefault(),
                value.members(),
                reasons,
                value.overrides(),
                value.deprecated());
    }
}
