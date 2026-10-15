package dev.codeana.maven;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.*;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.*;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.visitor.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

import javax.xml.stream.*;

/** Read Maven's effective models and mediated dependencies, then stop before any build goal. */
public final class MavenInputsWorker extends AbstractMavenLifecycleParticipant {
    public static final String COMPLETE = "CODEANA_MAVEN_INPUTS_COMPLETE";
    protected ProjectDependenciesResolver resolver;
    private final Map<Path, String> snapshots = new LinkedHashMap<Path, String>();
    private Path projectRoot;
    private final Map<String, DependencyResolutionResult> graphs = new HashMap<String, DependencyResolutionResult>();

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        String project = session.getUserProperties().getProperty("codeana.inputs.project");
        if (project == null) return;
        try {
            projectRoot = Paths.get(project).toRealPath();
            snapshotModel(projectRoot.resolve("pom.xml"), true);
        } catch (Exception failure) {
            throw new MavenExecutionException("CODEANA_MAVEN_INPUT_SNAPSHOT_FAILED", failure);
        }
    }

    private void snapshotModel(Path candidate, boolean followModules) throws Exception {
        if (!Files.isRegularFile(candidate)) return;
        Path path = candidate.toRealPath();
        if (snapshots.containsKey(path)) return;
        if (snapshots.size() >= 10000)
            throw new IOException("Maven model input count exceeds budget");
        snapshots.put(path, hash(path));
        Model model;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            model = new org.apache.maven.model.io.xpp3.MavenXpp3Reader().read(reader);
        }
        List<String> modules = new ArrayList<String>(model.getModules());
        for (org.apache.maven.model.Profile profile : model.getProfiles())
            modules.addAll(profile.getModules());
        for (String module : followModules ? modules : Collections.<String>emptyList()) {
            Path child = path.getParent().resolve(module).normalize();
            snapshotModel(Files.isDirectory(child) ? child.resolve("pom.xml") : child, true);
        }
        if (model.getParent() != null) {
            String relative = model.getParent().getRelativePath();
            if (relative != null && !relative.isEmpty()) {
                Path parent = path.getParent().resolve(relative).normalize();
                snapshotModel(
                        Files.isDirectory(parent) ? parent.resolve("pom.xml") : parent, false);
            }
        }
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String destination = session.getUserProperties().getProperty("codeana.inputs.output");
        if (destination == null) return;
        try {
            Path output = Paths.get(destination).toAbsolutePath().normalize();
            String nonce = session.getUserProperties().getProperty("codeana.inputs.nonce");
            if (nonce == null || !nonce.matches("[a-zA-Z0-9-]{36}"))
                throw new IOException("invalid input request nonce");
            boolean includeTests =
                    Boolean.parseBoolean(
                            session.getUserProperties()
                                    .getProperty("codeana.inputs.tests", "true"));
            boolean resolve =
                    Boolean.parseBoolean(
                            session.getUserProperties()
                                    .getProperty("codeana.inputs.resolve", "true"));
            String selectionFile =
                    session.getUserProperties().getProperty("codeana.inputs.modules");
            Set<String> selected =
                    selectionFile == null
                            ? null
                            : new HashSet<String>(
                                    Files.readAllLines(
                                            Paths.get(selectionFile), StandardCharsets.UTF_8));
            List<MavenProject> projects =
                    new ArrayList<MavenProject>(
                            session.getAllProjects() == null
                                    ? session.getProjects()
                                    : session.getAllProjects());
            if (projects.size() > 10000) throw new IOException("reactor module budget exceeded");
            Map<String, MavenProject> reactor = new LinkedHashMap<String, MavenProject>();
            for (MavenProject project : projects) {
                String coordinate = coordinate(project);
                MavenProject previous = reactor.put(coordinate, project);
                if (previous != null)
                    throw new IOException("duplicate reactor coordinate " + coordinate + ": "
                            + pomPath(previous) + " and " + pomPath(project));
            }
            Path placeholder = output.resolve("index-only-placeholder.jar");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(placeholder))) {
                jar.putNextEntry(new JarEntry("META-INF/codeana-index-only"));
                jar.closeEntry();
            }
            DefaultRepositorySystemSession repository =
                    new DefaultRepositorySystemSession(session.getRepositorySession());
            // Depth-first collection expands long repeated paths before mediation. Bound that
            // work using Resolver's breadth-first collector, without changing Maven mediation.
            repository.setConfigProperty("aether.dependencyCollector.impl", "bf");
            Reactor workspace =
                    new Reactor(
                            reactor,
                            placeholder.toFile(),
                            repository.getWorkspaceReader(),
                            resolve,
                            output);
            if (!resolve)
                repository.setDependencySelector(
                        AndDependencySelector.newInstance(
                                repository.getDependencySelector(),
                                new ReactorSelector(workspace)));
            repository.setWorkspaceReader(workspace);
            boolean interactive =
                    Boolean.parseBoolean(
                            session.getUserProperties()
                                    .getProperty("codeana.inputs.interactive", "false"));
            DataInputStream selection = null;
            Set<String> prerequisites = new HashSet<String>();
            if (interactive) {
                writeResponse(
                        output,
                        "metadata",
                        nonce,
                        projects,
                        reactor,
                        repository,
                        false,
                        includeTests,
                        null,
                        prerequisites, -1);
                System.out.println("CODEANA_MAVEN_INPUTS_READY:" + nonce);
                System.out.flush();
                selection = new DataInputStream(new BufferedInputStream(System.in));
                if (selection.readInt() != 0x414d5331 || !nonce.equals(selection.readUTF()))
                    throw new IOException("invalid Maven selection identity");
                includeTests = selection.readBoolean();
                int count = selection.readInt();
                if (count < 0 || count > 10000)
                    throw new IOException("Maven selection exceeds budget");
                selected = new HashSet<String>();
                Set<String> allowed = new HashSet<String>();
                for (MavenProject project : projects)
                    allowed.add(project.getBasedir().getCanonicalPath());
                for (int i = 0; i < count; i++) {
                    String module = selection.readUTF();
                    if (!allowed.contains(module) || !selected.add(module))
                        throw new IOException("invalid Maven module selection");
                }
            }
            int round = 0;
            try {
                writeResponse(
                        output,
                        "inputs",
                        nonce,
                        projects,
                        reactor,
                        repository,
                        resolve,
                        includeTests,
                        selected,
                        prerequisites, round);
                if (interactive) {
                    Set<String> allowed = new HashSet<String>();
                    for (MavenProject project : projects) allowed.add(project.getBasedir().getCanonicalPath());
                    for (; round <= 256;) {
                        System.out.println("CODEANA_MAVEN_CLASSPATH_READY:" + nonce + ":" + round);
                        System.out.flush();
                        if (selection.readInt() != 0x414d5031 || !nonce.equals(selection.readUTF()))
                            throw new IOException("invalid Maven prerequisite identity");
                        int count = selection.readInt();
                        if (count < 0 || count > 10000) throw new IOException("Maven prerequisite exceeds budget");
                        if (count == 0) break;
                        if (!resolve || round == 256) throw new IOException("Maven prerequisite resolution budget");
                        for (int i = 0; i < count; i++) {
                            String module = selection.readUTF();
                            if (!allowed.contains(module) || selected.contains(module) || !prerequisites.add(module))
                                throw new IOException("invalid Maven prerequisite module");
                        }
                        round++;
                        writeResponse(output, "inputs", nonce, projects, reactor, repository,
                                resolve, includeTests, selected, prerequisites, round);
                    }
                }
            } catch (DependencyInputFailure failure) {
                if (!resolve || !interactive) throw failure;
                writeDependencyFailure(output, nonce, failure, round);
                System.out.println("CODEANA_MAVEN_DEPENDENCY_FAILED:" + nonce + ":" + round);
                System.out.flush();
            }
        } catch (Exception failure) {
            throw new MavenExecutionException(
                    "CODEANA_MAVEN_INPUTS_FAILED: " + failure.getMessage(), failure);
        }
        // Terminate before validate/plugins/lifecycle work, including project-supplied goals.
        // The host accepts only this exit and a verified nonce receipt.
        throw new MavenExecutionException(COMPLETE, session.getTopLevelProject().getFile());
    }

    private void writeResponse(
            Path output,
            String name,
            String nonce,
            List<MavenProject> projects,
            Map<String, MavenProject> reactor,
            DefaultRepositorySystemSession repository,
            boolean resolve,
            boolean includeTests,
            Set<String> selected, Set<String> prerequisites, int round)
            throws Exception {
        Path pending = output.resolve(name + ".xml.part");
        try (OutputStream bytes =
                new BoundedOutput(
                        new BufferedOutputStream(Files.newOutputStream(pending), 64 * 1024))) {
            XMLStreamWriter xml =
                    XMLOutputFactory.newFactory().createXMLStreamWriter(bytes, "UTF-8");
            try {
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("maven-inputs");
                xml.writeAttribute("format", "maven-inputs-v2");
                xml.writeAttribute("nonce", nonce);
                xml.writeAttribute("resolved", Boolean.toString(resolve));
                xml.writeAttribute("round", Integer.toString(round));
                for (MavenProject project : projects) {
                    try {
                        module(
                                xml,
                                project,
                                reactor,
                                repository,
                                resolve
                                        && (selected == null
                                                || selected.contains(
                                                        project.getBasedir().getAbsolutePath())
                                                || prerequisites.contains(project.getBasedir().getAbsolutePath())),
                                includeTests && (selected == null || selected.contains(project.getBasedir().getAbsolutePath())));
                    } catch (DependencyResolutionException failure) {
                        String coordinate = "";
                        if (failure.getResult() != null && !failure.getResult().getUnresolvedDependencies().isEmpty())
                            coordinate = failure.getResult().getUnresolvedDependencies().get(0).getArtifact().toString();
                        throw new DependencyInputFailure(project, coordinate, failure);
                    } catch (MissingExternalArtifact failure) {
                        throw new DependencyInputFailure(project, failure.coordinate, failure);
                    }
                }
                xml.writeEndElement();
                xml.writeEndDocument();
                xml.flush();
            } finally {
                xml.close();
            }
        }
        if (Files.size(pending) > 32L * 1024 * 1024)
            throw new IOException("Maven input response exceeds budget");
        for (Map.Entry<Path, String> snapshot : snapshots.entrySet())
            if (!snapshot.getValue().equals(hash(snapshot.getKey())))
                throw new IOException(
                        "Maven model changed during resolution: " + snapshot.getKey());
        Files.move(pending, output.resolve(name + ".xml"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.write(
                output.resolve(name + ".sha256"),
                hash(output.resolve(name + ".xml")).getBytes(StandardCharsets.US_ASCII));
    }

    // Borrowed from Anatomist e8a940557c9365084cdd5318ed2be3d147697113;
    // code-ana additionally identifies the prerequisite round and metadata snapshot.
    private static final class MissingExternalArtifact extends IOException {
        final String coordinate;
        MissingExternalArtifact(String coordinate) {
            super("unresolved external artifact: " + coordinate);
            this.coordinate = coordinate;
        }
    }

    private static final class DependencyInputFailure extends Exception {
        final MavenProject project;
        final String coordinate;
        DependencyInputFailure(MavenProject project, String coordinate, Exception cause) {
            super(cause.getMessage(), cause);
            this.project = project;
            this.coordinate = coordinate;
        }
    }

    private void writeDependencyFailure(Path output, String nonce, DependencyInputFailure failure, int round)
            throws Exception {
        for (Map.Entry<Path, String> snapshot : snapshots.entrySet())
            if (!snapshot.getValue().equals(hash(snapshot.getKey())))
                throw new IOException("Maven model changed during resolution: " + snapshot.getKey());
        Path pending = output.resolve("dependency-failure.xml.part");
        try (OutputStream bytes = new BoundedOutput(Files.newOutputStream(pending))) {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(bytes, "UTF-8");
            try {
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("maven-dependency-failure");
                xml.writeAttribute("format", "maven-inputs-v2");
                xml.writeAttribute("nonce", nonce);
                xml.writeAttribute("round", Integer.toString(round));
                xml.writeAttribute("metadata-sha256", hash(output.resolve("metadata.xml")));
                xml.writeAttribute("directory", failure.project.getBasedir().getCanonicalPath());
                xml.writeAttribute("pom", pomPath(failure.project));
                xml.writeAttribute("coordinate", failure.coordinate);
                String reason = String.valueOf(failure.getMessage())
                        .replaceAll("\\u001B\\[[;\\d]*m", "")
                        .replaceAll("(?i)(https?|file)://\\S+", "<repository>");
                xml.writeCharacters(reason.substring(0, Math.min(4096, reason.length())));
                xml.writeEndElement();
                xml.writeEndDocument();
            } finally { xml.close(); }
        }
        Path response = output.resolve("dependency-failure.xml");
        Files.move(pending, response, StandardCopyOption.ATOMIC_MOVE);
        Files.write(output.resolve("dependency-failure.sha256"), hash(response).getBytes(StandardCharsets.US_ASCII));
    }

    private void module(
            XMLStreamWriter xml,
            MavenProject project,
            Map<String, MavenProject> reactor,
            DefaultRepositorySystemSession repository,
            boolean resolve,
            boolean includeTests)
            throws Exception {
        xml.writeStartElement("module");
        xml.writeAttribute("directory", project.getBasedir().getCanonicalPath());
        xml.writeAttribute("coordinate", coordinate(project));
        xml.writeAttribute("pom", pomPath(project));
        xml.writeAttribute("release", release(project));
        xml.writeAttribute("resolved", Boolean.toString(resolve));
        for (org.apache.maven.model.Profile profile : project.getActiveProfiles())
            element(xml, "active-profile", profile.getId());
        StringWriter model = new StringWriter();
        project.writeModel(model);
        element(xml, "effective-model", model.toString());
        Set<Path> parents = new LinkedHashSet<Path>();
        for (MavenProject current = project; current != null; current = current.getParent()) {
            if (current.getFile() != null)
                parents.add(current.getFile().toPath().toAbsolutePath().normalize());
            if (parents.size() > 128) throw new IOException("parent model depth exceeds budget");
        }
        for (Path parent : parents) {
            xml.writeEmptyElement("model-input");
            xml.writeAttribute("path", parent.toString());
            String actual = hash(parent);
            if (projectRoot != null
                    && parent.toRealPath().startsWith(projectRoot)
                    && snapshots.containsKey(parent.toRealPath())
                    && !actual.equals(snapshots.get(parent.toRealPath())))
                throw new IOException(
                        "Maven model changed before effective model receipt: " + parent);
            xml.writeAttribute("sha256", actual);
        }
        for (String root : project.getCompileSourceRoots()) element(xml, "main-root", root);
        for (String root : project.getTestCompileSourceRoots()) element(xml, "test-root", root);
        element(
                xml,
                "generated-main-root",
                new File(project.getBuild().getDirectory(), "generated-sources").getAbsolutePath());
        element(
                xml,
                "generated-test-root",
                new File(project.getBuild().getDirectory(), "generated-test-sources")
                        .getAbsolutePath());
        element(xml, "output-root", project.getBuild().getOutputDirectory());
        element(xml, "output-root", project.getBuild().getTestOutputDirectory());
        for (org.apache.maven.model.Resource resource : project.getResources())
            element(xml, "main-resource", resource.getDirectory());
        for (org.apache.maven.model.Resource resource : project.getTestResources())
            element(xml, "test-resource", resource.getDirectory());
        DependencyResolutionResult resolved = resolveGraph(project, repository, resolve, includeTests);
        for (String scope :
                includeTests
                        ? Arrays.asList("compile", "test")
                        : Collections.singletonList("compile")) {
            xml.writeStartElement(
                    scope.equals("compile") ? "main-dependencies" : "test-dependencies");
            PreorderNodeListGenerator visitor = new PreorderNodeListGenerator();
            resolved.getDependencyGraph()
                    .accept(
                            new FilteringDependencyVisitor(
                                    visitor, DependencyFilterUtils.classpathFilter(scope)));
            List<org.eclipse.aether.graph.Dependency> dependencies =
                    visitor.getDependencies(!resolve);
            if (dependencies.size() > 100000)
                throw new IOException("module dependency budget exceeded");
            for (org.eclipse.aether.graph.Dependency dependency : dependencies)
                dependency(xml, dependency.getArtifact(), reactor, resolve);
            xml.writeEndElement();
        }
        if (!includeTests) xml.writeEmptyElement("test-dependencies");
        xml.writeEndElement();
    }

    private DependencyResolutionResult resolveGraph(MavenProject project,
            DefaultRepositorySystemSession repository, boolean resolve, boolean includeTests) throws Exception {
        String key = project.getBasedir().getCanonicalPath() + "|" + resolve + "|" + includeTests;
        DependencyResolutionResult cached = graphs.get(key);
        if (cached != null) return cached;
        if (!includeTests) {
            repository = new DefaultRepositorySystemSession(repository);
            repository.setDependencySelector(
                    AndDependencySelector.newInstance(
                            repository.getDependencySelector(),
                            new org.eclipse.aether.util.graph.selector.ScopeDependencySelector(
                                    "test")));
        }
        if (!resolve) {
            repository = new DefaultRepositorySystemSession(repository);
            repository.setDependencySelector(
                    AndDependencySelector.newInstance(
                            repository.getDependencySelector(),
                            new ReactorSelector((Reactor) repository.getWorkspaceReader())));
        }
        DependencyResolutionResult resolved =
                resolver.resolve(
                        new DefaultDependencyResolutionRequest(project, repository)
                                .setResolutionFilter(
                                        resolve
                                                ? DependencyFilterUtils.classpathFilter(
                                                        includeTests ? "test" : "compile")
                                                : (node, parentsPath) -> false));
        graphs.put(key, resolved);
        return resolved;
    }

    private static void dependency(
            XMLStreamWriter xml,
            Artifact artifact,
            Map<String, MavenProject> reactor,
            boolean resolve)
            throws Exception {
        if ("false".equals(artifact.getProperty("constitutesBuildPath", "true"))) return;
        String coordinate =
                artifact.getGroupId()
                        + ":"
                        + artifact.getArtifactId()
                        + ":"
                        + artifact.getBaseVersion();
        MavenProject target = reactor.get(coordinate);
        if (target == null && !resolve) return;
        xml.writeEmptyElement(target == null ? "external" : "reactor");
        xml.writeAttribute("coordinate", coordinate);
        xml.writeAttribute("classifier", artifact.getClassifier());
        xml.writeAttribute("extension", artifact.getExtension());
        if (target != null) {
            if (!artifact.getClassifier().isEmpty() || !artifact.getExtension().equals("jar"))
                throw new IOException("reactor classifier/type is not supported: " + artifact);
            xml.writeAttribute("directory", target.getBasedir().getCanonicalPath());
        } else {
            if (artifact.getFile() == null || !artifact.getFile().isFile())
                throw new MissingExternalArtifact(artifact.toString());
            xml.writeAttribute("path", artifact.getFile().getCanonicalPath());
        }
    }

    private static String release(MavenProject project) {
        String release = project.getProperties().getProperty("maven.compiler.release", "");
        if (release.isEmpty())
            release = project.getProperties().getProperty("maven.compiler.source", "");
        if (release.isEmpty())
            release = project.getProperties().getProperty("maven.compiler.target", "");
        Xpp3Dom config =
                project.getGoalConfiguration(
                        "org.apache.maven.plugins", "maven-compiler-plugin", null, null);
        if (config != null) {
            Xpp3Dom configured = config.getChild("release");
            if (configured == null) configured = config.getChild("source");
            if (configured == null) configured = config.getChild("target");
            if (configured != null && configured.getValue() != null)
                release = configured.getValue();
        }
        return release;
    }

    private static String coordinate(MavenProject project) {
        return project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();
    }

    private static void element(XMLStreamWriter xml, String name, String value)
            throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private static String pomPath(MavenProject project) throws IOException {
        return project.getFile() == null ? "<unknown POM>" : project.getFile().getCanonicalPath();
    }

    private static String hash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest())
            hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private long remaining = 32L * 1024 * 1024;

        BoundedOutput(OutputStream output) {
            super(output);
        }

        private void consume(int bytes) throws IOException {
            remaining -= bytes;
            if (remaining < 0) throw new IOException("Maven input response exceeds byte budget");
        }

        @Override
        public void write(int value) throws IOException {
            consume(1);
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int start, int length) throws IOException {
            consume(length);
            out.write(bytes, start, length);
        }
    }

    private static final class Reactor implements MavenWorkspaceReader {
        private final Map<String, MavenProject> projects;
        private final File placeholder;
        private final WorkspaceReader previous;
        private final boolean resolve;
        private final Path directory;
        private final WorkspaceRepository repository = new WorkspaceRepository("codeana-inputs");

        Reactor(
                Map<String, MavenProject> projects,
                File placeholder,
                WorkspaceReader previous,
                boolean resolve,
                Path directory) {
            this.projects = projects;
            this.placeholder = placeholder;
            this.previous = previous;
            this.resolve = resolve;
            this.directory = directory;
        }

        private boolean containsArtifact(Artifact artifact) {
            for (MavenProject project : projects.values())
                if (project.getGroupId().equals(artifact.getGroupId())
                        && project.getArtifactId().equals(artifact.getArtifactId())) return true;
            return false;
        }

        private MavenProject project(Artifact artifact) {
            return projects.get(
                    artifact.getGroupId()
                            + ":"
                            + artifact.getArtifactId()
                            + ":"
                            + artifact.getBaseVersion());
        }

        public WorkspaceRepository getRepository() {
            return repository;
        }

        public File findArtifact(Artifact artifact) {
            MavenProject project = project(artifact);
            if (project == null) {
                // Keep alternate versions in Maven's conflict mediation, but never infer source
                // dependencies from a binary selected by an explicitly supplied classpath.
                if (!resolve
                        && containsArtifact(artifact)
                        && artifact.getExtension().equals("pom")) {
                    try {
                        Path descriptor = Files.createTempFile(directory, "descriptor-", ".pom");
                        try (Writer writer =
                                Files.newBufferedWriter(descriptor, StandardCharsets.UTF_8)) {
                            new MavenXpp3Writer().write(writer, emptyModel(artifact));
                        }
                        return descriptor.toFile();
                    } catch (IOException failure) {
                        throw new IllegalStateException(
                                "cannot write source-only descriptor", failure);
                    }
                }
                return previous == null ? null : previous.findArtifact(artifact);
            }
            if (artifact.getExtension().equals("pom")) return project.getFile();
            if (!artifact.getExtension().equals("jar") || !artifact.getClassifier().isEmpty())
                throw new IllegalArgumentException(
                        "reactor classifier/type is not supported: " + artifact);
            return placeholder;
        }

        public List<String> findVersions(Artifact artifact) {
            Set<String> versions = new LinkedHashSet<String>();
            for (MavenProject project : projects.values())
                if (project.getGroupId().equals(artifact.getGroupId())
                        && project.getArtifactId().equals(artifact.getArtifactId()))
                    versions.add(project.getVersion());
            if (previous != null) versions.addAll(previous.findVersions(artifact));
            return new ArrayList<String>(versions);
        }

        public Model findModel(Artifact artifact) {
            MavenProject project = project(artifact);
            if (project == null && !resolve && containsArtifact(artifact))
                return emptyModel(artifact);
            return project == null
                    ? previous instanceof MavenWorkspaceReader
                            ? ((MavenWorkspaceReader) previous).findModel(artifact)
                            : null
                    : project.getModel();
        }

        private static Model emptyModel(Artifact artifact) {
            Model model = new Model();
            model.setModelVersion("4.0.0");
            model.setGroupId(artifact.getGroupId());
            model.setArtifactId(artifact.getArtifactId());
            model.setVersion(artifact.getBaseVersion());
            return model;
        }
    }

    /** Explicit classpath mode collects only declared reactor paths, before descriptor lookup. */
    private static final class ReactorSelector implements DependencySelector {
        private final Reactor reactor;

        ReactorSelector(Reactor reactor) {
            this.reactor = reactor;
        }

        public boolean selectDependency(org.eclipse.aether.graph.Dependency dependency) {
            Artifact artifact = dependency.getArtifact();
            if (!reactor.containsArtifact(artifact)) return false;
            String version = artifact.getVersion();
            if (version.startsWith("[") || version.startsWith("("))
                throw new IllegalArgumentException(
                        "source-only reactor version range is not supported: " + artifact);
            return true;
        }

        public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
            return this;
        }
    }
}
