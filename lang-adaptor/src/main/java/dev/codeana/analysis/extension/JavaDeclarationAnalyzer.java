package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.Map;

/** A failing declaration discards only its own pending output. */
public interface JavaDeclarationAnalyzer extends JavaModelAnalyzer {
    default boolean acceptsDeclaration(Map<String, Object> declaration) { return true; }
    void analyzeDeclaration(ExtensionContext context, Map<String, Object> declaration, FactSink facts) throws Exception;
    @Override default void analyze(ExtensionContext context, FactSink facts) throws Exception {
        for (Object raw : Json.list(context.model().get("declarations"))) {
            Map<String, Object> declaration = Json.map(raw);
            if (acceptsDeclaration(declaration)) analyzeDeclaration(context, declaration, facts);
        }
    }
}
