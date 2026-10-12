package com.anatomist.core.asmsolver;

import com.anatomist.core.IndexTimings;

import java.nio.file.Path;
import java.util.*;

/** Lazily opens the ordered dependency classpath and the selected platform in one owned scope. */
public final class JavaBinarySources {
    private JavaBinarySources() {}

    public static JavaBinaryRepository open(
            List<Path> classpath, Path javaHome, int release, long cacheBytes) {
        return new JavaBinaryRepository(
                new Source(List.copyOf(classpath), javaHome, release), cacheBytes);
    }

    /** One bounded inventory owner per analysis; every repository owns its borrowed handles. */
    public static final class Session implements AutoCloseable {
        private final List<Path> classpath;
        private final Path home;
        private final int release;
        private final IndexTimings timings;
        private ClasspathClassFileSource dependencies;
        private JdkClassFileSource platform;
        private boolean closed;

        public Session(List<Path> classpath, Path home, int release, IndexTimings timings) {
            this.classpath = List.copyOf(classpath);
            this.home = Objects.requireNonNull(home);
            this.release = release;
            this.timings = timings;
        }

        public JavaBinaryRepository open(List<Path> entries, long cacheBytes) {
            if (closed) throw new IllegalStateException("binary inventory session is closed");
            return new JavaBinaryRepository(
                    new Source(List.copyOf(entries), home, release, this), cacheBytes);
        }

        private ClasspathClassFileSource dependencies() {
            if (closed) throw new IllegalStateException("binary inventory session is closed");
            if (dependencies == null) {
                long started = timings == null ? 0 : timings.start();
                dependencies = new ClasspathClassFileSource(classpath, release);
                if (timings != null) timings.stop("javac_binary_dependency_inventory", started);
            }
            return dependencies.fork();
        }

        private JdkClassFileSource platform() {
            if (closed) throw new IllegalStateException("binary inventory session is closed");
            if (platform == null) {
                long started = timings == null ? 0 : timings.start();
                platform = new JdkClassFileSource(home, release);
                if (timings != null) timings.stop("javac_binary_platform_inventory", started);
            }
            return platform.fork();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (dependencies != null) dependencies.close();
            } finally {
                if (platform != null) platform.close();
            }
        }
    }

    private static final class Source implements ClassFileSource {
        private final List<Path> classpath;
        private final Path home;
        private final int release;
        private final Session session;
        private ClasspathClassFileSource dependencies;
        private ClasspathClassFileSource sharedDependencies;
        private JdkClassFileSource platform;
        private boolean closed;

        private Source(List<Path> classpath, Path home, int release) {
            this(classpath, home, release, null);
        }

        private Source(List<Path> classpath, Path home, int release, Session session) {
            this.classpath = classpath;
            this.home = Objects.requireNonNull(home);
            this.release = release;
            this.session = session;
        }

        private void dependencies() {
            if (dependencies != null) return;
            int prefix = session == null ? -1 : classpath.size() - session.classpath.size();
            if (prefix >= 0
                    && classpath.subList(prefix, classpath.size()).equals(session.classpath)) {
                dependencies = new ClasspathClassFileSource(classpath.subList(0, prefix), release);
                sharedDependencies = session.dependencies();
            } else dependencies = new ClasspathClassFileSource(classpath, release);
        }

        private void platform() {
            if (platform == null)
                platform =
                        session == null
                                ? new JdkClassFileSource(home, release)
                                : session.platform();
        }

        @Override
        public Optional<byte[]> find(String name) {
            checkOpen();
            dependencies();
            var found = dependencies.find(name);
            if (found.isEmpty() && sharedDependencies != null)
                found = sharedDependencies.find(name);
            if (found.isPresent()) return found;
            platform();
            return platform.find(name);
        }

        @Override
        public Optional<String> artifactAttribute(String binaryName, String name) {
            checkOpen();
            dependencies();
            if (sharedDependencies != null && dependencies.find(binaryName).isEmpty())
                return sharedDependencies.artifactAttribute(binaryName, name);
            return dependencies.artifactAttribute(binaryName, name);
        }

        @Override
        public List<Path> providers(String binaryName) {
            checkOpen();
            dependencies();
            List<Path> found = new ArrayList<>(dependencies.providers(binaryName));
            if (sharedDependencies != null) found.addAll(sharedDependencies.providers(binaryName));
            return List.copyOf(found);
        }

        @Override
        public List<Path> providerFailures(String binaryName) {
            checkOpen();
            dependencies();
            List<Path> found = new ArrayList<>(dependencies.providerFailures(binaryName));
            if (sharedDependencies != null)
                found.addAll(sharedDependencies.providerFailures(binaryName));
            return List.copyOf(found);
        }

        @Override
        public Set<String> knownClasses() {
            checkOpen();
            dependencies();
            platform();
            Set<String> names = new LinkedHashSet<>(dependencies.knownClasses());
            if (sharedDependencies != null) names.addAll(sharedDependencies.knownClasses());
            names.addAll(platform.knownClasses());
            return Collections.unmodifiableSet(names);
        }

        private void checkOpen() {
            if (closed) throw new IllegalStateException("binary sources are closed");
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (dependencies != null) dependencies.close();
            } finally {
                try {
                    if (sharedDependencies != null) sharedDependencies.close();
                } finally {
                    if (platform != null) platform.close();
                }
            }
        }
    }
}
