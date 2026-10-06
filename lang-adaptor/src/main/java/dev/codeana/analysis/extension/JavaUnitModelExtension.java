package dev.codeana.analysis.extension;

import java.util.Map;

/** A validated replacement model: the caller commits once after the extension succeeds. */
public interface JavaUnitModelExtension extends JavaModelExtension {
    void augmentUnits(ExtensionContext context, Map<String,Object> candidate) throws Exception;
    @Override
    default void augment(ExtensionContext context, ModelOverlay overlay) {
        throw new IllegalStateException("JAVA_UNIT_EXTENSION_REQUIRES_UNIT_RUNNER");
    }
}
