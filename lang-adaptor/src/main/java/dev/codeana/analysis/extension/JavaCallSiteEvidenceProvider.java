package dev.codeana.analysis.extension;

import java.util.*;

/** Evidence on an existing call only. An observer cannot create a declaration or target. */
public interface JavaCallSiteEvidenceProvider extends ExtensionPoint {
    /** Immutable flat metadata; default observers still receive the complete call. */
    default boolean acceptsCallSite(ExtensionContext context, Map<String, Object> header) throws Exception {
        return true;
    }
    Optional<Evidence> observe(ExtensionContext context, Map<String, Object> call) throws Exception;
    record Evidence(String namespace, int schemaVersion, Map<String, Object> attributes) {
        public Evidence { attributes = ExtensionContext.freezeMap(attributes); }
    }
}
