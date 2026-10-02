package dev.codeana.analysis;

import dev.codeana.common.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class SharedCacheNegative {
    static void require(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        List<Object> contexts = Json.list(request.get("contexts"));
        Map<String, Object> context = Json.map(contexts.getFirst());
        Path root = Path.of(Json.string(request, "cache_root")),
                modules = root.resolveSibling(root.getFileName() + "-modules");
        try (CompilerSession session = new CompilerSession(request)) {
            Map<String, Object> model = session.analyze(context);
            Path victim = root.resolveSibling("victim");
            Files.createDirectory(victim);
            Path sentinel = victim.resolve("sentinel");
            Files.writeString(sentinel, "unchanged");
            Files.createSymbolicLink(modules, victim);
            SignatureCache files = new SignatureCache(request);
            files.publish(context, model);
            require(files.writeFailures() == 1, "symlink root not rejected");
            try (ModuleSignatures cache = new ModuleSignatures(request, contexts, session)) {
                cache.publish(context, model);
                require(
                        !Json.map(cache.report().get("module_signature_fallbacks")).isEmpty(),
                        "module symlink not rejected");
            }
            require(Files.readString(sentinel).equals("unchanged"), "symlink target changed");
            try (var all = Files.list(victim)) {
                require(all.count() == 1, "symlink target received cache writes");
            }
            Files.delete(modules);
            Files.createSymbolicLink(SignatureStores.lockPath(root), sentinel);
            files.publish(context, model);
            require(files.writeFailures() == 2, "symlink lock not rejected");
            require(Files.readString(sentinel).equals("unchanged"), "lock symlink overwritten");
            Files.delete(SignatureStores.lockPath(root));
            files.publish(context, model);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            SignatureStores.LOCK.lock();
            Future<?> waiting;
            java.util.concurrent.atomic.AtomicReference<Thread> waiter =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            try {
                waiting =
                        executor.submit(
                                () -> {
                                    waiter.set(Thread.currentThread());
                                    started.countDown();
                                    try (SignatureCache.Pins pins = files.pin(session)) {
                                        pins.acquire(
                                                context,
                                                Json.map(
                                                        Json.list(context.get("sources"))
                                                                .getFirst()));
                                        throw new AssertionError(
                                                "cancelled lock acquisition succeeded");
                                    } catch (java.io.IOException expected) {
                                        require(
                                                expected.getMessage().equals("CANCELLED"),
                                                "wrong cancellation:" + expected);
                                    }
                                });
                require(started.await(5, TimeUnit.SECONDS), "waiter did not start");
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!SignatureStores.LOCK.hasQueuedThread(waiter.get())
                        && System.nanoTime() < end) Thread.sleep(1);
                require(
                        SignatureStores.LOCK.hasQueuedThread(waiter.get()),
                        "waiter did not block on shared lock");
                session.cancel();
                waiting.get(5, TimeUnit.SECONDS);
            } finally {
                SignatureStores.LOCK.unlock();
                executor.shutdownNow();
                require(executor.awaitTermination(5, TimeUnit.SECONDS), "thread leaked");
            }
            require(session.signaturePinBytes() == 0, "cancellation leaked combined reservation");
            session.releaseModel(model);
            System.out.println("accepted symlink roots/lock, cancellation and pin accounting");
        }
    }
}
