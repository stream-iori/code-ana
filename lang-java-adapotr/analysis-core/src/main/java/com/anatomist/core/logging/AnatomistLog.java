package com.anatomist.core.logging;

import java.lang.System.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/** File logging facade. The application installs bounded asynchronous appenders at run start. */
public final class AnatomistLog {
    public static final long MAX_BYTES = 1L << 30;
    private static final Logger WARN = System.getLogger("com.anatomist.warn");
    private static final Logger DEBUG = System.getLogger("com.anatomist.debug");
    private static volatile boolean debugEnabled;
    private static volatile String runId;
    private static volatile boolean warnWriteFailed;
    private static volatile boolean debugWriteFailed;
    private static volatile Consumer<String> warningOutput = ignored -> {};
    private static volatile ConfigurationHook configurationHook = (repo, home, debug) -> {};

    @FunctionalInterface
    public interface ConfigurationHook {
        void configure(Path repo, Path home, boolean debug);
    }

    private AnatomistLog() {}

    public static void install(ConfigurationHook hook, Consumer<String> warnings) {
        configurationHook = hook;
        warningOutput = warnings;
    }

    public static void configure(Path repoDir, Path home) {
        configure(repoDir, home, false);
    }

    public static void configure(Path repoDir, Path home, boolean debug) {
        configure(repoDir, home, debug, null);
    }

    public static void configure(Path repoDir, Path home, boolean debug, String id) {
        beginRun(id);
        debugEnabled = debug;
        configurationHook.configure(repoDir, home, debug);
    }

    public static void beginRun(String id) {
        runId = id;
        warnWriteFailed = false;
        debugWriteFailed = false;
        debugEnabled = false;
    }

    public static void writeFailed(String sink, Throwable failure) {
        boolean first;
        if ("debug".equals(sink)) {
            first = !debugWriteFailed;
            debugWriteFailed = true;
        } else {
            first = !warnWriteFailed;
            warnWriteFailed = true;
        }
        if (first)
            warningOutput.accept(
                    "WARN: log_write_failed sink="
                            + sink
                            + " cause="
                            + failure.getClass().getSimpleName());
    }

    public static Map<String, Object> writeStatus() {
        return Map.of(
                "warn_write_failed",
                warnWriteFailed,
                "debug_write_failed",
                debugWriteFailed,
                "mode",
                "async_appender",
                "queue_size",
                1024,
                "blocking",
                true);
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void warn(String message) {
        WARN.log(System.Logger.Level.WARNING, prefix(message));
    }

    public static void diagnostic(String summary, String detail) {
        WARN.log(System.Logger.Level.WARNING, prefix(summary + (detail.isEmpty() ? "" : "\n" + detail)));
    }

    /** A CLI event without a file-log side effect. */
    public static void emit(String message) {
        warningOutput.accept(message);
    }

    /** A user-visible warning rendered by the CLI and retained in warn.log. */
    public static void notice(String message) {
        warningOutput.accept(message);
        WARN.log(System.Logger.Level.WARNING, prefix(message));
    }

    public static void debug(String message) {
        if (debugEnabled) DEBUG.log(System.Logger.Level.DEBUG, prefix(message));
    }

    private static String prefix(String message) {
        return runId == null ? message : "run_id=" + runId + " " + message;
    }
}
