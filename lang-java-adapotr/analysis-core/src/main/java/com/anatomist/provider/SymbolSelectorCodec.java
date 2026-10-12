package com.anatomist.provider;

/** Provider-owned parsing boundary for exact symbol selectors. */
public interface SymbolSelectorCodec {
    /**
     * Return the provider-local selector in canonical form, or the input when already canonical.
     */
    String canonicalize(String selector, String entityKind);
}
