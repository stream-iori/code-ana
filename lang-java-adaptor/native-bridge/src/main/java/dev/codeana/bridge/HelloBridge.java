package dev.codeana.bridge;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;

public final class HelloBridge {
    private HelloBridge() {}

    @CEntryPoint(name = "codeana_java_hello")
    public static int hello(@SuppressWarnings("unused") IsolateThread thread) {
        return 25;
    }
}
