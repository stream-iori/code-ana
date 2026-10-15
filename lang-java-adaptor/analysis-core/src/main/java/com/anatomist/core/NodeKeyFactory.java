package com.anatomist.core;

/** Builds and parses the storage identity: provider::module::scope::symbol_id. */
public final class NodeKeyFactory {

    private static final String SEP = "::";
    public static final String DEFAULT_PROVIDER = "java-core";

    private NodeKeyFactory() {}

    public static String key(SourceIdentity identity, String symbolId) {
        return key(DEFAULT_PROVIDER, identity, symbolId);
    }

    public static String key(String providerId, SourceIdentity identity, String symbolId) {
        if (symbolId == null) return null;
        String provider =
                providerId == null || providerId.isBlank() ? DEFAULT_PROVIDER : providerId;
        return escape(provider)
                + SEP
                + escape(identity.module())
                + SEP
                + identity.scope().name()
                + SEP
                + symbolId;
    }

    public static boolean isKey(String value) {
        if (value == null) return false;
        Parts parts = parts(value);
        if (parts == null) return false;
        try {
            SourceScope.valueOf(parts.scope());
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static String symbolId(String key) {
        Parts parts = parts(key);
        return parts == null ? key : parts.symbolId();
    }

    public static SourceIdentity identity(String key) {
        Parts parts = parts(key);
        if (parts == null) return new SourceIdentity(".", SourceScope.MAIN);
        return new SourceIdentity(unescape(parts.module()), SourceScope.valueOf(parts.scope()));
    }

    public static String providerId(String key) {
        Parts parts = parts(key);
        return parts == null ? DEFAULT_PROVIDER : unescape(parts.providerId());
    }

    private static Parts parts(String value) {
        if (value == null) return null;
        int first = value.indexOf(SEP);
        int second = first < 0 ? -1 : value.indexOf(SEP, first + SEP.length());
        if (first < 0 || second < 0) return null;
        int third = value.indexOf(SEP, second + SEP.length());
        if (third < 0) {
            // Read legacy v22 keys so compatibility diagnostics and tests can explain them.
            return new Parts(
                    DEFAULT_PROVIDER,
                    value.substring(0, first),
                    value.substring(first + SEP.length(), second),
                    value.substring(second + SEP.length()));
        }
        return new Parts(
                value.substring(0, first),
                value.substring(first + SEP.length(), second),
                value.substring(second + SEP.length(), third),
                value.substring(third + SEP.length()));
    }

    private static String escape(String module) {
        return module.replace("%", "%25").replace(":", "%3A");
    }

    private static String unescape(String module) {
        return module.replace("%3A", ":").replace("%25", "%");
    }

    private record Parts(String providerId, String module, String scope, String symbolId) {}
}
