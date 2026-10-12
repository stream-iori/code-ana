package com.anatomist.core.asmsolver;

public final class FqnUtil {

    private FqnUtil() {}

    public static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        int dollar = fqn.lastIndexOf('$');
        int idx = Math.max(dot, dollar);
        return idx < 0 ? fqn : fqn.substring(idx + 1);
    }

    public static String packageName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    public static String className(String fqn) {
        String pkg = packageName(fqn);
        return pkg.isEmpty() ? fqn : fqn.substring(pkg.length() + 1);
    }
}
