package dev.codeana.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.Map;

/** Test-only access to task buffers; executes the real bridge task under an actual small heap. */
public final class NativeTaskProbe {
    public static void main(String[] args) throws Exception {
        long id = NativeBridge.create(null);
        var tasks = NativeBridge.class.getDeclaredField("TASKS"); tasks.setAccessible(true);
        Object task = ((Map<?, ?>) tasks.get(null)).get(id);
        var input = task.getClass().getDeclaredField("input"); input.setAccessible(true);
        ((ByteArrayOutputStream) input.get(task)).write(Files.readAllBytes(Path.of(args[0])));
        if (NativeBridge.seal(null, id) != 0 || NativeBridge.start(null, id) != 0) throw new AssertionError("TASK_START");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        int state;
        while ((state = NativeBridge.poll(null, id)) == 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("TASK_TERMINAL_TIMEOUT");
            Thread.sleep(10);
        }
        var output = task.getClass().getDeclaredField("errorOutput"); output.setAccessible(true);
        String response = new String((byte[]) output.get(task), java.nio.charset.StandardCharsets.UTF_8);
        if (NativeBridge.close(null, id) != 0) throw new AssertionError("TASK_CLEANUP");
        if (state != -1 || !response.contains("NATIVE_HEAP_EXHAUSTED")) throw new AssertionError("EXPECTED_REAL_HEAP_FAILURE: "+state+" "+response);
        System.out.println(response);
    }
}
