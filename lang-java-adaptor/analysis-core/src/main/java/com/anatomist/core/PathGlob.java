package com.anatomist.core;

/** Project-relative, separator-stable glob with bounded dynamic-programming matching. */
public final class PathGlob {
    private final String source;
    private final String[] segments;
    private final boolean literal;
    private final String prefix;

    public PathGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("blank glob");
        this.source = pattern.replace('\\', '/');
        if (source.startsWith("/") || source.endsWith("/") || source.contains("//")) {
            throw new IllegalArgumentException(
                    "glob must be a normalized relative path: " + pattern);
        }
        this.segments = source.split("/");
        literal = source.indexOf('*') < 0 && source.indexOf('?') < 0;
        String candidatePrefix =
                source.endsWith("/**") ? source.substring(0, source.length() - 3) : "";
        prefix =
                !candidatePrefix.isEmpty()
                                && candidatePrefix.indexOf('*') < 0
                                && candidatePrefix.indexOf('?') < 0
                        ? candidatePrefix
                        : null;
    }

    public String source() {
        return source;
    }

    public boolean matches(String candidate) {
        String normalized = candidate == null ? "" : candidate.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (source.equals("**")) return true;
        if (literal) return source.equals(normalized);
        if (prefix != null) return normalized.equals(prefix) || normalized.startsWith(prefix + "/");
        String[] values = normalized.isEmpty() ? new String[0] : normalized.split("/");
        boolean[][] dp = new boolean[segments.length + 1][values.length + 1];
        dp[segments.length][values.length] = true;
        for (int p = segments.length - 1; p >= 0; p--) {
            for (int v = values.length; v >= 0; v--) {
                if ("**".equals(segments[p])) {
                    dp[p][v] = dp[p + 1][v] || (v < values.length && dp[p][v + 1]);
                } else if (v < values.length && segmentMatches(segments[p], values[v])) {
                    dp[p][v] = dp[p + 1][v + 1];
                }
            }
        }
        return dp[0][0];
    }

    private static boolean segmentMatches(String pattern, String value) {
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) return pattern.equals(value);
        boolean[][] dp = new boolean[pattern.length() + 1][value.length() + 1];
        dp[pattern.length()][value.length()] = true;
        for (int p = pattern.length() - 1; p >= 0; p--) {
            for (int v = value.length(); v >= 0; v--) {
                char token = pattern.charAt(p);
                if (token == '*') {
                    dp[p][v] = dp[p + 1][v] || (v < value.length() && dp[p][v + 1]);
                } else if (v < value.length() && (token == '?' || token == value.charAt(v))) {
                    dp[p][v] = dp[p + 1][v + 1];
                }
            }
        }
        return dp[0][0];
    }

    @Override
    public String toString() {
        return source;
    }
}
