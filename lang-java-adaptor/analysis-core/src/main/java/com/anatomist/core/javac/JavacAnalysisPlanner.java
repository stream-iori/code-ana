package com.anatomist.core.javac;

import com.anatomist.core.SourceScope;

import java.util.*;

/** Logical tasks only: compiler inputs are bound after prerequisites have completed. */
final class JavacAnalysisPlanner {
    record Task(long id, int ordinal, JavacContexts.Context context, Set<Long> prerequisites) {
        Task {
            prerequisites = Set.copyOf(prerequisites);
        }
    }

    private final List<Task> tasks;
    private final Set<Long> completed = new HashSet<>();

    JavacAnalysisPlanner(List<JavacContexts.Context> contexts) {
        Map<String, Long> identities = new LinkedHashMap<>();
        for (var context : contexts) {
            String key = key(context.module(), context.scope());
            if (identities.putIfAbsent(key, (long) identities.size() + 1) != null)
                throw new IllegalArgumentException("duplicate javac task: " + key);
        }
        List<Task> planned = new ArrayList<>();
        Set<java.nio.file.Path> published = new HashSet<>();
        for (var context : contexts) {
            Set<Long> dependencies = new LinkedHashSet<>();
            for (String module : new TreeSet<>(context.dependencyModules())) {
                Long id = identities.get(key(module, SourceScope.MAIN));
                if (id != null) dependencies.add(id);
            }
            if (context.scope() == SourceScope.TEST) {
                Long id = identities.get(key(context.module(), SourceScope.MAIN));
                if (id != null) dependencies.add(id);
            }
            for (var path : context.publish())
                if (!published.add(path))
                    throw new IllegalArgumentException("duplicate javac publication: " + path);
            planned.add(
                    new Task(
                            identities.get(key(context.module(), context.scope())),
                            planned.size(),
                            context,
                            dependencies));
        }
        tasks = List.copyOf(planned);
        Set<Long> visited = new HashSet<>();
        while (visited.size() < tasks.size()) {
            int before = visited.size();
            for (Task task : tasks)
                if (visited.containsAll(task.prerequisites())) visited.add(task.id());
            if (visited.size() == before)
                throw new IllegalArgumentException(
                        "JAVAC_MODULE_CYCLE: "
                                + tasks.stream()
                                        .filter(task -> !visited.contains(task.id()))
                                        .map(
                                                task ->
                                                        key(
                                                                task.context().module(),
                                                                task.context().scope()))
                                        .toList());
        }
    }

    List<Task> ready() {
        return tasks.stream()
                .filter(
                        task ->
                                !completed.contains(task.id())
                                        && completed.containsAll(task.prerequisites()))
                .toList();
    }

    void completed(Task task) {
        if (!tasks.contains(task)
                || !completed.containsAll(task.prerequisites())
                || !completed.add(task.id()))
            throw new IllegalStateException("invalid javac task completion: " + task.id());
    }

    boolean finished() {
        return completed.size() == tasks.size();
    }

    private static String key(String module, SourceScope scope) {
        return module + "@" + scope;
    }
}
