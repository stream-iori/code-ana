package com.anatomist.core.javac;

import com.anatomist.core.*;
import com.anatomist.core.process.*;
import com.anatomist.json.Json;

import org.w3c.dom.Element;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Effective Maven reactor models and mediated, ordered MAIN/TEST dependency inputs. */
public record JavacMavenInputs(
        List<Module> modules,
        Map<Path, String> modelInputs,
        String fingerprint,
        boolean resolvedClasspath) {
    public sealed interface Dependency permits ReactorDependency, ExternalDependency {
        String coordinate();
    }

    public record ReactorDependency(String coordinate, String module) implements Dependency {}

    public record ExternalDependency(
            String coordinate, String classifier, String extension, Path archive)
            implements Dependency {}

    public record Module(
            String name,
            Path directory,
            String coordinate,
            int release,
            List<Path> mainRoots,
            List<Path> testRoots,
            List<Dependency> mainDependencies,
            List<Dependency> testDependencies,
            String modelHash,
            List<Path> generatedMainRoots,
            List<Path> generatedTestRoots,
            List<Path> outputRoots,
            List<Path> mainResources,
            List<Path> testResources) {
        public Module(
                String name,
                Path directory,
                String coordinate,
                int release,
                List<Path> mainRoots,
                List<Path> testRoots,
                List<Dependency> mainDependencies,
                List<Dependency> testDependencies,
                String modelHash) {
            this(
                    name,
                    directory,
                    coordinate,
                    release,
                    mainRoots,
                    testRoots,
                    mainDependencies,
                    testDependencies,
                    modelHash,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        public Module {
            generatedMainRoots = List.copyOf(generatedMainRoots);
            generatedTestRoots = List.copyOf(generatedTestRoots);
            outputRoots = List.copyOf(outputRoots);
            mainResources = List.copyOf(mainResources);
            testResources = List.copyOf(testResources);
            mainRoots = List.copyOf(mainRoots);
            testRoots = List.copyOf(testRoots);
            mainDependencies = List.copyOf(mainDependencies);
            testDependencies = List.copyOf(testDependencies);
        }

        public List<Dependency> dependencies(SourceScope scope) {
            return scope == SourceScope.TEST ? testDependencies : mainDependencies;
        }

        public List<Path> classpath(SourceScope scope) {
            return dependencies(scope).stream()
                    .filter(ExternalDependency.class::isInstance)
                    .map(ExternalDependency.class::cast)
                    .map(ExternalDependency::archive)
                    .toList();
        }
    }

    public JavacMavenInputs {
        modules = List.copyOf(modules);
        modelInputs = Collections.unmodifiableMap(new LinkedHashMap<>(modelInputs));
    }

    public Module module(String name) {
        return modules.stream()
                .filter(module -> module.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Maven module is outside effective reactor: " + name));
    }

    /** Source labels are publication identities; compiler ownership follows the effective model. */
    public Module owningModule(Path source) {
        Module selected = null;
        int selectedDepth = -1;
        boolean ambiguous = false;
        for (Module module : modules) {
            int depth =
                    java.util.stream.Stream.concat(
                                    module.mainRoots().stream(), module.testRoots().stream())
                            .filter(source::startsWith)
                            .mapToInt(Path::getNameCount)
                            .max()
                            .orElse(-1);
            if (depth > selectedDepth) {
                selected = module;
                selectedDepth = depth;
                ambiguous = false;
            } else if (depth >= 0
                    && depth == selectedDepth
                    && selected != null
                    && !selected.name().equals(module.name())) ambiguous = true;
        }
        if (ambiguous)
            throw new IllegalArgumentException("ambiguous effective Maven source owner: " + source);
        if (selected != null) return selected;
        return modules.stream()
                .filter(module -> source.startsWith(module.directory()))
                .max(Comparator.comparingInt(module -> module.directory().getNameCount()))
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "source root is outside effective reactor: " + source));
    }

    public void verify() throws IOException {
        for (var entry : modelInputs.entrySet())
            if (!entry.getValue().equals(ContentHash.sha256(entry.getKey())))
                throw new IllegalArgumentException(
                        "effective Maven model input changed: " + entry.getKey());
    }

    public static String workerFingerprint() {
        // Input resolution is owned by Rust; its captured manifest includes exporter identity.
        return "rust-inputs-manifest-v3";
    }

    /** No lifecycle/plugin goal is executed. Reactor artifacts are resolution placeholders only. */
    public static JavacMavenInputs resolve(
            Path project,
            JavacToolchain toolchain,
            Path cache,
            boolean resolveClasspath,
            boolean offline) {
        return resolve(project, toolchain, cache, resolveClasspath, offline, "mvn");
    }

    public static JavacMavenInputs resolve(
            Path project,
            JavacToolchain toolchain,
            Path cache,
            boolean resolveClasspath,
            boolean offline,
            boolean includeTests) {
        return resolve(
                project, toolchain, cache, resolveClasspath, offline, "mvn", includeTests, null);
    }

    static JavacMavenInputs resolve(
            Path supplied,
            JavacToolchain toolchain,
            Path cache,
            boolean resolveClasspath,
            boolean offline,
            String maven) {
        return resolve(supplied, toolchain, cache, resolveClasspath, offline, maven, true, null);
    }

    public static JavacMavenInputs resolve(
            Path project,
            JavacToolchain toolchain,
            Path cache,
            boolean offline,
            boolean includeTests,
            Set<String> selectedModules) {
        return resolve(
                project, toolchain, cache, true, offline, "mvn", includeTests, selectedModules);
    }

    public record Selection(Set<String> modules, boolean tests) {
        public Selection {
            modules = Set.copyOf(modules);
        }
    }

    public static JavacMavenInputs resolveSelected(
            Path project,
            JavacToolchain toolchain,
            Path cache,
            boolean includeTests,
            java.util.function.Function<JavacMavenInputs, Selection> selection,
            IndexTimings timings) {
        return resolveSelected(project, toolchain, cache, includeTests, selection, timings, "mvn");
    }

    static JavacMavenInputs resolveSelected(
            Path project,
            JavacToolchain toolchain,
            Path cache,
            boolean includeTests,
            java.util.function.Function<JavacMavenInputs, Selection> selection,
            IndexTimings timings,
            String maven) {
        return resolve(
                project,
                toolchain,
                cache,
                true,
                false,
                maven,
                includeTests,
                null,
                Objects.requireNonNull(selection),
                timings);
    }

    private static JavacMavenInputs resolve(
            Path supplied,
            JavacToolchain toolchain,
            Path cache,
            boolean resolveClasspath,
            boolean offline,
            String maven,
            boolean includeTests,
            Set<String> selectedModules) {
        return resolve(
                supplied,
                toolchain,
                cache,
                resolveClasspath,
                offline,
                maven,
                includeTests,
                selectedModules,
                null,
                null);
    }

    private static JavacMavenInputs resolve(
            Path supplied,
            JavacToolchain toolchain,
            Path cache,
            boolean resolveClasspath,
            boolean offline,
            String maven,
            boolean includeTests,
            Set<String> selectedModules,
            java.util.function.Function<JavacMavenInputs, Selection> select,
            IndexTimings timings) {
        Path project = supplied.toAbsolutePath().normalize();
        try {
            Files.createDirectories(cache);
            try (var directory =
                    new JavacCompilationCache.Directory(
                            Files.createTempDirectory(cache, ".maven-inputs-"))) {
                Path worker = directory.path().resolve("extension.jar");
                try (InputStream input =
                        JavacMavenInputs.class.getResourceAsStream(
                                "/META-INF/anatomist/maven-input-worker.jar")) {
                    if (input == null)
                        throw new IOException("packaged Maven input worker is missing");
                    Files.copy(input, worker);
                }
                String nonce = UUID.randomUUID().toString();
                List<String> command =
                        new ArrayList<>(List.of(maven, "-q", "--no-transfer-progress"));
                if (offline) command.add("--offline");
                if (select != null) command.add("-Danatomist.inputs.interactive=true");
                if (selectedModules != null) {
                    Path selection = directory.path().resolve("selected-modules.txt");
                    Path realProject = project.toRealPath();
                    Files.write(
                            selection,
                            selectedModules.stream()
                                    .sorted()
                                    .map(name -> realProject.resolve(name).normalize().toString())
                                    .toList());
                    command.add("-Danatomist.inputs.modules=" + selection);
                }
                command.addAll(
                        List.of(
                                "-Dmaven.ext.class.path=" + worker,
                                "-Danatomist.inputs.output=" + directory.path(),
                                "-Danatomist.inputs.project=" + project,
                                "-Danatomist.inputs.nonce=" + nonce,
                                "-Danatomist.inputs.resolve=" + resolveClasspath,
                                "-Danatomist.inputs.tests=" + includeTests,
                                "validate"));
                Map<String, String> environment =
                        Map.of(
                                "JAVA_HOME",
                                toolchain.home().toString(),
                                "PATH",
                                toolchain.home().resolve("bin")
                                        + File.pathSeparator
                                        + System.getenv().getOrDefault("PATH", ""),
                                "MAVEN_OPTS",
                                "-Xmx512m -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US",
                                "MAVEN_DEBUG_OPTS",
                                "",
                                "_JAVA_OPTIONS",
                                "",
                                "JAVA_TOOL_OPTIONS",
                                "",
                                "JDK_JAVA_OPTIONS",
                                "");
                var tail = new ProcessRunner.Tail(ProcessRunner.SMALL_OUTPUT);
                var selection = new java.util.concurrent.CompletableFuture<Selection>();
                long started = System.nanoTime();
                if (timings != null) timings.increment("maven_processes");
                var result =
                        ProcessRunner.run(
                                new ProcessRunner.Spec(
                                        command,
                                        project,
                                        environment,
                                        Duration.ofMinutes(5),
                                        true,
                                        SnapshotProcessReceipts.lifecycle()),
                                select == null
                                        ? null
                                        : output -> {
                                            Selection chosen;
                                            try {
                                                chosen = selection.get();
                                            } catch (
                                                    java.util.concurrent.ExecutionException
                                                            failure) {
                                                if (failure.getCause() instanceof Exception cause)
                                                    throw cause;
                                                if (failure.getCause() instanceof Error cause)
                                                    throw cause;
                                                throw failure;
                                            }
                                            DataOutputStream data = new DataOutputStream(output);
                                            data.writeInt(0x414d5331);
                                            data.writeUTF(nonce);
                                            data.writeBoolean(chosen.tests());
                                            data.writeInt(chosen.modules().size());
                                            Path realProject = project.toRealPath();
                                            for (String module :
                                                    chosen.modules().stream().sorted().toList())
                                                data.writeUTF(
                                                        realProject
                                                                .resolve(module)
                                                                .normalize()
                                                                .toString());
                                            data.flush();
                                        },
                                input -> {
                                    long dependenciesStarted = 0;
                                    try (var buffered = new BufferedInputStream(input, 65536)) {
                                        String line;
                                        while ((line =
                                                        ProcessRunner.line(
                                                                buffered,
                                                                ProcessRunner.SMALL_OUTPUT))
                                                != null) {
                                            tail.write(
                                                    (line + "\n")
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8));
                                            if (select != null
                                                    && line.replaceAll("\\u001B\\[[;\\d]*m", "")
                                                            .equals(
                                                                    "ANATOMIST_MAVEN_INPUTS_READY:"
                                                                            + nonce)) {
                                                if (selection.isDone())
                                                    throw new IOException(
                                                            "duplicate Maven metadata response");
                                                JavacMavenInputs metadata =
                                                        verified(
                                                                project,
                                                                directory.path(),
                                                                "metadata",
                                                                nonce,
                                                                false);
                                                if (timings != null)
                                                    timings.stop("maven_metadata", started);
                                                Selection chosen = select.apply(metadata);
                                                dependenciesStarted = System.nanoTime();
                                                selection.complete(chosen);
                                            }
                                        }
                                        if (select != null && !selection.isDone())
                                            throw new IOException(
                                                    "missing Maven metadata response");
                                        if (timings != null && dependenciesStarted != 0)
                                            timings.stop("maven_dependencies", dependenciesStarted);
                                        return ProcessRunner.Read.complete(null);
                                    } catch (IOException | RuntimeException | Error failure) {
                                        selection.completeExceptionally(failure);
                                        throw failure;
                                    }
                                });
                try {
                    result.requireValue();
                } catch (ProcessRunner.Failure failure) {
                    throw new IllegalStateException(
                            "JAVAC_MAVEN_INPUTS_FAILED: "
                                    + failure.getMessage()
                                    + (failure.getCause() == null
                                            ? ""
                                            : "\n" + failureDetails(failure.getCause().toString()))
                                    + "\n"
                                    + failureDetails(tail.text()),
                            failure);
                }
                if (!(result.outcome() instanceof ProcessRunner.Completed)
                        || result.exitCode() != 1
                        || !tail.text().contains("ANATOMIST_MAVEN_INPUTS_COMPLETE"))
                    throw new IllegalArgumentException(
                            "JAVAC_MAVEN_INPUTS_FAILED: "
                                    + ProcessRunner.exitMessage(result, "effective Maven inputs")
                                    + "\n"
                                    + failureDetails(tail.text()));
                return verified(project, directory.path(), "inputs", nonce, resolveClasspath);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "JAVAC_MAVEN_INPUTS_FAILED: " + failure.getMessage(), failure);
        }
    }

    private static JavacMavenInputs verified(
            Path project, Path directory, String name, String nonce, boolean resolved)
            throws IOException {
        Path response = directory.resolve(name + ".xml"),
                checksum = directory.resolve(name + ".sha256");
        if (!Files.isRegularFile(response)
                || Files.size(response) > 32L * 1024 * 1024
                || !Files.isRegularFile(checksum)
                || Files.size(checksum) != 64
                || !ContentHash.sha256(response).equals(Files.readString(checksum)))
            throw new IOException("Maven input response checksum or size is invalid");
        JavacMavenInputs inputs = read(project, response, nonce, resolved);
        inputs.verify();
        return inputs;
    }

    private static String failureDetails(String output) {
        String sanitized =
                output.replaceAll("\\u001B\\[[;\\d]*m", "")
                        .replaceAll("(?i)(https?|file)://\\S+", "<repository>");
        return sanitized.substring(Math.max(0, sanitized.length() - 4096));
    }

    static JavacMavenInputs read(Path project, Path response, String nonce, boolean resolved)
            throws IOException {
        try {
            if (Files.size(response) > 32L * 1024 * 1024)
                throw new IOException("Maven input response exceeds byte budget");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Effective models are escaped text, not DTD entities. Their size is bounded by
            // the entire response budget; JDK 25's default 100 KB also rejects ordinary models.
            factory.setAttribute("jdk.xml.maxGeneralEntitySizeLimit", "33554432");
            factory.setAttribute("jdk.xml.totalEntitySizeLimit", "33554432");
            Element document;
            try (var input = Files.newInputStream(response)) {
                document = factory.newDocumentBuilder().parse(input).getDocumentElement();
            }
            if (!document.getTagName().equals("maven-inputs")
                    || !document.getAttribute("format").equals("maven-inputs-v1")
                    || !document.getAttribute("nonce").equals(nonce)
                    || !document.getAttribute("resolved").equals(Boolean.toString(resolved)))
                throw new IOException("invalid Maven input receipt");
            Path realProject = project.toRealPath();
            List<Module> modules = new ArrayList<>();
            Map<Path, String> files = new LinkedHashMap<>();
            Map<String, String> names = new LinkedHashMap<>();
            List<Element> elements = children(document, "module");
            if (elements.isEmpty() || elements.size() > 10_000)
                throw new IOException("invalid Maven module count");
            for (Element element : elements) {
                Path directory =
                        Path.of(element.getAttribute("directory")).toAbsolutePath().normalize();
                if (!directory.startsWith(realProject))
                    throw new IOException("reactor module escapes project: " + directory);
                String name =
                        directory.equals(realProject)
                                ? "."
                                : realProject.relativize(directory).toString().replace('\\', '/');
                if (names.putIfAbsent(directory.toString(), name) != null)
                    throw new IOException("duplicate Maven module directory");
            }
            Set<String> coordinates = new HashSet<>();
            for (Element element : elements) {
                String coordinate = element.getAttribute("coordinate");
                if (coordinate.isBlank() || !coordinates.add(coordinate))
                    throw new IOException("invalid or duplicate Maven coordinate");
                Path directory = Path.of(element.getAttribute("directory"));
                String name = names.get(directory.toString());
                List<Element> models = children(element, "effective-model");
                if (models.size() != 1) throw new IOException("missing effective Maven model");
                for (Element file : children(element, "model-input")) {
                    Path path = Path.of(file.getAttribute("path"));
                    String hash = file.getAttribute("sha256");
                    if (!path.isAbsolute() || !hash.matches("[a-f0-9]{64}"))
                        throw new IOException("invalid Maven model provenance");
                    String prior = files.putIfAbsent(path, hash);
                    if (prior != null && !prior.equals(hash))
                        throw new IOException("Maven parent model changed during resolution");
                }
                modules.add(
                        new Module(
                                name,
                                projectPath(project, realProject, directory),
                                coordinate,
                                release(element.getAttribute("release")),
                                roots(element, "main-root", project, realProject),
                                roots(element, "test-root", project, realProject),
                                dependencies(element, "main-dependencies", names),
                                dependencies(element, "test-dependencies", names),
                                ContentHash.sha256(models.getFirst().getTextContent()),
                                roots(element, "generated-main-root", project, realProject),
                                roots(element, "generated-test-root", project, realProject),
                                roots(element, "output-root", project, realProject),
                                roots(element, "main-resource", project, realProject),
                                roots(element, "test-resource", project, realProject)));
            }
            modules.sort(Comparator.comparing(Module::name));
            var inputs =
                    new JavacMavenInputs(modules, files, fingerprint(modules, resolved), resolved);
            for (Module module : modules)
                for (Dependency dependency : module.testDependencies()) {
                    if (dependency instanceof ReactorDependency reactor
                            && !inputs.module(reactor.module())
                                    .coordinate()
                                    .equals(reactor.coordinate()))
                        throw new IOException("reactor dependency coordinate mismatch");
                }
            return inputs;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new IOException("invalid effective Maven input", invalid);
        }
    }

    private static int release(String value) throws IOException {
        if (value.isEmpty()) return 0;
        String version = value.startsWith("1.") ? value.substring(2) : value;
        if (!version.matches("[0-9]+"))
            throw new IOException("unresolved Maven compiler release: " + value);
        return Integer.parseInt(version);
    }

    private static List<Path> roots(Element module, String kind, Path project, Path realProject) {
        return children(module, kind).stream()
                .map(
                        root ->
                                projectPath(
                                        project,
                                        realProject,
                                        Path.of(root.getTextContent())
                                                .toAbsolutePath()
                                                .normalize()))
                .toList();
    }

    private static Path projectPath(Path project, Path realProject, Path path) {
        return path.startsWith(realProject)
                ? project.resolve(realProject.relativize(path)).normalize()
                : path;
    }

    private static List<Dependency> dependencies(
            Element module, String scope, Map<String, String> names) throws IOException {
        List<Element> sections = children(module, scope);
        if (sections.size() != 1) throw new IOException("invalid scoped Maven dependencies");
        List<Dependency> dependencies = new ArrayList<>();
        for (var child = sections.getFirst().getFirstChild();
                child != null;
                child = child.getNextSibling()) {
            if (!(child instanceof Element dependency)) continue;
            String coordinate = dependency.getAttribute("coordinate");
            if (coordinate.isBlank()) throw new IOException("missing Maven dependency coordinate");
            if (dependency.getTagName().equals("reactor")) {
                String name = names.get(dependency.getAttribute("directory"));
                if (name == null) throw new IOException("Maven dependency is outside reactor");
                dependencies.add(new ReactorDependency(coordinate, name));
            } else if (dependency.getTagName().equals("external")) {
                Path path = Path.of(dependency.getAttribute("path"));
                if (!path.isAbsolute() || !Files.isRegularFile(path))
                    throw new IOException("unresolved Maven classpath artifact");
                dependencies.add(
                        new ExternalDependency(
                                coordinate,
                                dependency.getAttribute("classifier"),
                                dependency.getAttribute("extension"),
                                path));
            } else throw new IOException("unknown Maven dependency kind");
            if (dependencies.size() > 100_000)
                throw new IOException("Maven dependency count exceeds budget");
        }
        return List.copyOf(dependencies);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element element && element.getTagName().equals(name))
                children.add(element);
        return children;
    }

    private static String fingerprint(List<Module> modules, boolean resolved) {
        List<Object> values = new ArrayList<>();
        for (Module module : modules) {
            values.add(
                    List.of(
                            module.name(),
                            module.coordinate(),
                            module.release(),
                            module.modelHash(),
                            module.mainRoots().stream().map(Path::toString).toList(),
                            module.testRoots().stream().map(Path::toString).toList(),
                            module.mainDependencies().stream()
                                    .map(JavacMavenInputs::dependencyKey)
                                    .toList(),
                            module.testDependencies().stream()
                                    .map(JavacMavenInputs::dependencyKey)
                                    .toList()));
        }
        return ContentHash.sha256(
                "maven-inputs-v1|resolved=" + resolved + Json.writeCompact(values));
    }

    private static Object dependencyKey(Dependency dependency) {
        return switch (dependency) {
            case ReactorDependency reactor ->
                    List.of("reactor", reactor.coordinate(), reactor.module());
            case ExternalDependency external ->
                    List.of(
                            "external",
                            external.coordinate(),
                            external.classifier(),
                            external.extension(),
                            external.archive().toString());
        };
    }
}
