package dev.codeana.analysis.extension;

/** Adds declaration headers with a source origin; cannot change javac's trees or bindings. */
public interface JavaModelExtension extends ExtensionPoint {
    void augment(ExtensionContext context, ModelOverlay overlay) throws Exception;
}
