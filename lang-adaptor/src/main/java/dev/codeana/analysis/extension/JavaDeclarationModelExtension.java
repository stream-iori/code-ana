package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.Map;

/** Opt-in per-declaration transactions over one immutable compiler model snapshot. */
public interface JavaDeclarationModelExtension extends JavaModelExtension {
    default boolean acceptsDeclaration(Map<String, Object> declaration) { return true; }
    void augmentDeclaration(ExtensionContext context, Map<String, Object> declaration, ModelOverlay overlay) throws Exception;
    @Override default void augment(ExtensionContext context, ModelOverlay overlay) throws Exception {
        for (Object raw : Json.list(context.model().get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            if (acceptsDeclaration(declaration)) augmentDeclaration(context, declaration, overlay);
        }
    }
}
