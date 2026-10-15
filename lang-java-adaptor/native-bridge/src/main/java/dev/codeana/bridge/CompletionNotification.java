package dev.codeana.bridge;

import java.util.List;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

/** Task-owned nonblocking Unix socket. No heap allocation is needed on terminal OOM. */
@CContext(CompletionNotification.Headers.class)
@CLibrary(value = "codeana_completion", requireStatic = true)
public final class CompletionNotification {
    public static final class Headers implements CContext.Directives {
        public List<String> getHeaderFiles() { return List.of("<unistd.h>", "<fcntl.h>", "<sys/socket.h>"); }
        public List<String> getLibraryPaths() {
            String path = System.getProperty("codeana.completion.library");
            if (path == null) throw new IllegalStateException("COMPLETION_LIBRARY_REQUIRED");
            return List.of(path);
        }
    }
    @CConstant("MSG_DONTWAIT") static native int dontWait();
    @CConstant("MSG_NOSIGNAL") static native int noSignal();
    @CFunction("codeana_dup_completion") static native int duplicate(int fd);
    @CFunction("send") static native long send(int fd, CCharPointer bytes, UnsignedWord size, int flags);
    @CFunction("close") static native int close(int fd);
    static void complete(int fd) {
        if (fd < 0) return;
        try {
            CCharPointer token = StackValue.get(1);
            token.write(0, (byte) 1);
            send(fd, token, WordFactory.unsigned(1), dontWait() | noSignal());
        } finally { close(fd); }
    }
}
