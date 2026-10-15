package com.anatomist.config;

import java.nio.file.Path;

/** Shared storage-home policy for CLI and application services. */
public final class StoragePaths {
    private StoragePaths() {}

    public static Path home(String override, String userHome) {
        if (override != null && !override.isEmpty()) return Path.of(override);
        return Path.of(
                userHome == null || userHome.isEmpty()
                        ? System.getProperty("user.home", ".")
                        : userHome,
                ".anatomist");
    }

    public static Path home() {
        return home(System.getenv("ANATOMIST_HOME"), System.getProperty("user.home"));
    }
}
