package com.anatomist.provider;

import java.util.Set;

/**
 * Query-side semantic boundary for operations that cannot be implemented from generic facts alone.
 */
public interface ProviderSemanticAdapter {
    Set<String> operations();

    default boolean supports(String operation) {
        return operation != null && operations().contains(operation);
    }
}
