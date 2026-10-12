package com.anatomist.core.javac;

import java.io.IOException;
import java.util.List;

/** Batch barriers keep all shared signature, extension and publication state on the caller. */
final class JavacAnalysisCoordinator {
    @FunctionalInterface
    interface BatchConsumer {
        void accept(List<JavacAnalysisPlanner.Task> tasks) throws IOException;
    }

    private JavacAnalysisCoordinator() {}

    static void run(
            JavacAnalysisPlanner planner,
            JavacSignaturePlan signatures,
            int concurrency,
            BatchConsumer consumer)
            throws IOException {
        while (!planner.finished()) {
            JavacCancellation.check();
            boolean restored;
            do {
                restored = false;
                for (var task : planner.ready()) {
                    JavacCancellation.check();
                    if (signatures.restore(task.context())) {
                        planner.completed(task);
                        restored = true;
                    }
                }
            } while (restored);
            if (planner.finished()) return;
            var ready = planner.ready();
            if (ready.isEmpty())
                throw new IllegalStateException("javac planner has no ready tasks");
            for (int offset = 0; offset < ready.size(); offset += concurrency) {
                var batch = ready.subList(offset, Math.min(ready.size(), offset + concurrency));
                consumer.accept(batch);
                JavacCancellation.check();
                batch.forEach(planner::completed);
            }
        }
    }
}
