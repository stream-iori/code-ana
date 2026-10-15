package com.anatomist.core.javac;

import java.util.concurrent.CancellationException;

final class JavacCancellation {
    private JavacCancellation() {}

    static void check() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("JAVAC_CANCELLED");
    }
}
