package com.anatomist.framework;

import com.anatomist.core.java.JavaExtensionData;
import com.anatomist.core.java.JavaNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Observes source use on an existing call site; evidence cannot create a declaration or target. */
public interface JavaCallSiteEvidenceProvider extends ExtensionPoint {
    Optional<Evidence> observe(JavaUnitAnalysis unit, JavaNode call, FallbackCallSite site);

    record FallbackCallSite(String ownerFqn, String callKind, String resolution) {}

    record Evidence(String namespace, Map<String, Object> value, String counter) {
        public Evidence {
            if (namespace == null || namespace.isBlank())
                throw new IllegalArgumentException("evidence namespace must not be blank");
            value =
                    new JavaExtensionData(
                                    Map.of(),
                                    Map.of("evidence", value == null ? Map.of() : value),
                                    List.of(),
                                    Map.of())
                            .declarationMetadata()
                            .get("evidence");
        }
    }
}
