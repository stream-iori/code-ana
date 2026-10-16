package com.anatomist.core.javac;

import com.anatomist.core.*;
import com.anatomist.core.asmsolver.JavaBinarySources;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.java.JavaClasspathEntry;
import com.anatomist.core.java.JavaDeclaration;
import com.anatomist.core.java.JavaDeclarationOverlay;
import com.anatomist.core.java.JavaDiagnostic;
import com.anatomist.core.java.JavaExtensionData;
import com.anatomist.core.java.JavaSyntax;
import com.anatomist.core.java.JavaUnit;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Target-JDK javac worker backend. Trees are detached before compiler resources are released. */
public final class JavacBackend implements JavaSemanticBackend {
    private final JavacModelConsumer modelConsumer = new JavacModelConsumer(this);
    public static final String MODEL_VERSION = "javac-model-v50";
    final Path projectRoot;
    private final List<SourceRoot> sourceRoots;
    final JavacToolchain toolchain;
    final List<Path> classpath;
    final JavacOptions config;
    private ParserResourceLimits resourceLimits = ParserResourceLimits.DEFAULT;
    IndexTimings timings;
    ScanPolicy policy;
    private String identity;
    private JavaSourceDiscovery sourceDiscovery = new JavaSourceDiscovery();
    JavaSourcePlan sourcePlan;
    private long environmentBuilds;
    private JavacWorkerPool activeWorkers;
    private JavaBinarySources.Session binaryInventories;
    private int completedWorkerLaunches;
    JavacMavenInputs mavenInputs;
    List<JavacContexts.Context> sealedContexts;

    public static JavacBackend captured(
            Path root,
            JavacToolchain toolchain,
            List<SourceRoot> roots,
            JavacOptions options,
            JavacRunConfiguration run) {
        var backend = new JavacBackend(root, toolchain, List.of(), roots, options);
        backend.setTimings(run.timings());
        backend.setModelBudgets(run.modelBudgets());
        backend.setDecodeBudgetBytes(run.decodeBudgetBytes());
        backend.setSourceSignatures(run.sourceSignatures());
        backend.setBatchTimeout(run.batchTimeout());
        backend.setDeclarationOverlay(run.overlay());
        backend.setCapturedInputs(run.contexts(), run.sourcePlan(), run.moduleInputs());
        return backend;
    }

    private void requireIdle() {
        if (activeWorkers != null) throw new IllegalStateException("analysis already running");
    }

    /** Captured by the Rust coordinator. No discovery or Maven execution is allowed here. */
    public record CapturedContext(
            String module,
            SourceScope scope,
            List<Path> sources,
            Set<Path> publish,
            Set<String> dependencyModules) {
        public CapturedContext {
            sources = List.copyOf(sources);
            publish = Set.copyOf(publish);
            dependencyModules = Set.copyOf(dependencyModules);
            if (!sources.containsAll(publish))
                throw new IllegalArgumentException("publication outside captured input");
        }
    }

    public void setCapturedInputs(
            List<CapturedContext> contexts, JavaSourcePlan plan, JavacMavenInputs inputs) {
        if (activeWorkers != null) throw new IllegalStateException("analysis already running");
        if (toolchain.release() != 8 || config.lombokCompile())
            throw new IllegalArgumentException("CODEANA_JAVA8_STATIC_ONLY");
        Set<Path> captured = Set.copyOf(plan.visibleFiles());
        if (contexts.stream().anyMatch(c -> !captured.containsAll(c.sources())))
            throw new IllegalArgumentException("context outside captured inventory");
        mavenInputs = Objects.requireNonNull(inputs);
        sourcePlan = Objects.requireNonNull(plan);
        sealedContexts =
                contexts.stream()
                        .map(
                                c ->
                                        new JavacContexts.Context(
                                                c.module(),
                                                c.scope(),
                                                c.sources(),
                                                c.publish(),
                                                c.dependencyModules()))
                        .toList();
        identity = null;
    }

    public record ModelBudget(long bytes, long records) {
        public ModelBudget {
            if (bytes < 1 || records < 1)
                throw new IllegalArgumentException("JAVA_MODEL_BUDGET_RANGE");
        }
    }

    Map<String, ModelBudget> modelBudgets = Map.of();

    public void setModelBudgets(Map<String, ModelBudget> budgets) {
        if (activeWorkers != null) throw new IllegalStateException("analysis already running");
        modelBudgets = Map.copyOf(budgets);
    }

    long decodeBudgetBytes = JavacDecodeBudget.DEFAULT_BYTES;

    /** Resource admission only; does not alter the compiler model or semantic fingerprint. */
    public void setDecodeBudgetBytes(long bytes) {
        if (activeWorkers != null || bytes < (64L << 10) || bytes > (2L << 30))
            throw new IllegalArgumentException("JAVA_MODEL_DECODE_BYTES_RANGE");
        decodeBudgetBytes = bytes;
    }

    private Duration batchTimeout = Duration.ofMinutes(10);

    public void setBatchTimeout(Duration timeout) {
        if (activeWorkers != null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("invalid active worker timeout");
        batchTimeout = timeout;
    }

    String classpathSource = "explicit-or-detected";
    private final List<JavacBatchMetrics> batchEvidence = new ArrayList<>();

    public List<JavacBatchMetrics> batchEvidence() {
        return List.copyOf(batchEvidence);
    }

    final Map<Integer, Map<String, Object>> diagnosticsEvidence = new TreeMap<>();

    public List<Map<String, Object>> diagnosticEvidence() {
        return List.copyOf(diagnosticsEvidence.values());
    }

    private final List<JavacBatchProtocol.Execution> executions = new ArrayList<>();

    public List<Map<String, Object>> executionEvidence() {
        return executions.stream().map(JavacWorkerSession::evidence).toList();
    }

    public Map<String, Object> schedulingEvidence() {
        return Map.of(
                "analysis_threads",
                config.analysisThreads(),
                "effective_threads",
                config.analysisThreads(),
                "worker_heap_mb",
                config.workerHeapMb(),
                "batch_width",
                config.analysisThreads(),
                "batch_timeout_seconds",
                batchTimeout.toSeconds(),
                "exclusive_reason",
                "");
    }

    final Map<Integer, Map<String, Object>> classpathEvidence = new TreeMap<>();

    public List<Map<String, Object>> taskClasspathEvidence() {
        return List.copyOf(classpathEvidence.values());
    }

    JavaDeclarationOverlay modelExtensions = JavaDeclarationOverlay.NONE;

    public void setDeclarationOverlay(JavaDeclarationOverlay overlay) {
        if (activeWorkers != null)
            throw new IllegalStateException("cannot change extensions during javac session");
        Objects.requireNonNull(overlay);
        if (!modelExtensions.fingerprint().equals(overlay.fingerprint())) identity = null;
        modelExtensions = overlay;
    }

    public void setMavenInputs(JavacMavenInputs inputs) {
        if (activeWorkers != null)
            throw new IllegalStateException("cannot change inputs during javac session");
        Set<String> selected = new HashSet<>();
        if (policy != null) {
            var identities = SourceIdentityResolver.fromRoots(projectRoot, sourceRoots);
            for (Path file :
                    new ProjectScanner(Set.of(), policy, sourceDiscovery)
                            .scanSourceRoots(sourceRoots)) {
                var owner = inputs.owningModule(file);
                selected.add(owner.name());
                for (var dependency : owner.dependencies(identities.compilationScope(file)))
                    if (dependency instanceof JavacMavenInputs.ReactorDependency reactor)
                        selected.add(reactor.module());
            }
        }
        for (var module : inputs.modules())
            if ((policy == null || selected.contains(module.name()))
                    && module.release() != 0
                    && module.release() != toolchain.release())
                throw new IllegalArgumentException(
                        "JAVAC_MIXED_RELEASE_UNSUPPORTED: "
                                + module.name()
                                + " targets "
                                + module.release()
                                + " but selected JDK is "
                                + toolchain.release());
        sourceRoots.forEach(root -> inputs.owningModule(root.path()));
        mavenInputs = inputs;
        sourcePlan = null;
        identity = null;
    }

    public void setClasspathSource(String source) {
        if (activeWorkers != null)
            throw new IllegalStateException("cannot change classpath source during javac session");
        classpathSource = Objects.requireNonNull(source);
    }

    List<JavaClasspathEntry> classpathEntries(
            JavacContexts.Context context,
            JavacSignaturePlan signatures,
            List<Path> compilerClasspath)
            throws IOException {
        Map<Path, String> coordinates = new HashMap<>();
        if (mavenInputs != null && mavenInputs.resolvedClasspath())
            for (var dependency :
                    mavenInputs.module(context.module()).dependencies(context.scope()))
                if (dependency instanceof JavacMavenInputs.ExternalDependency external)
                    coordinates.put(external.archive(), external.coordinate());
        var declared =
                signatures.classpathEvidence(context).stream()
                        .map(
                                item -> {
                                    Path path = Path.of(item.path());
                                    return new JavaClasspathEntry(
                                            path,
                                            coordinates.getOrDefault(path, ""),
                                            item.sha256(),
                                            sealedContexts == null
                                                    ? path.toAbsolutePath().normalize().toString()
                                                    : "capture-sha256:" + item.sha256());
                                })
                        .toList();
        if (sealedContexts == null) return declared;
        Map<Path, JavaClasspathEntry> known = new HashMap<>();
        declared.forEach(entry -> known.put(entry.path(), entry));
        List<JavaClasspathEntry> effective = new ArrayList<>();
        for (Path value : compilerClasspath) {
            Path path = value.toAbsolutePath().normalize();
            var entry = known.get(path);
            if (entry != null) {
                effective.add(entry);
                continue;
            }
            // Generated NoCode archives are real compiler inputs too. Describe their
            // bytes and order without using the disposable task/cache location as identity.
            String digest;
            if (Files.isDirectory(path)) {
                String contents =
                        JavacSignatureInputs.files(List.of(path)).stream()
                                .filter(item -> !"directory".equals(item.sha256()))
                                .map(
                                        item ->
                                                path.relativize(Path.of(item.path()))
                                                                .toString()
                                                                .replace('\\', '/')
                                                        + "="
                                                        + item.sha256())
                                .sorted()
                                .collect(java.util.stream.Collectors.joining("\n"));
                digest = ContentHash.sha256(contents);
            } else digest = ContentHash.sha256(path);
            effective.add(new JavaClasspathEntry(path, "", digest, "capture-sha256:" + digest));
        }
        return List.copyOf(effective);
    }

    /**
     * A failed module cannot supply class files or confirmed calls. A declaration-only source with
     * no local compiler errors can still carry processor-created signatures that were absent from
     * the clean fallback task. Keep that evidence separate from successful compilation.
     */
    static void restorePartialProcessorDeclarations(
            List<JavaUnit> attempted, JavacProtocol.Result failed, List<JavaUnit> fallback) {
        if (!failed.failure().isEmpty()) return;
        if (failed.diagnostics().stream()
                .anyMatch(d -> d.kind().equals("ERROR") && d.path() == null)) return;
        Set<Path> errors = new HashSet<>();
        for (JavaDiagnostic diagnostic : failed.diagnostics())
            if (diagnostic.kind().equals("ERROR") && diagnostic.path() != null)
                errors.add(diagnostic.path().toAbsolutePath().normalize());
        Map<Path, JavaUnit> candidates = new HashMap<>();
        for (JavaUnit unit : attempted)
            if (unit.hasProcessorEvidence() && !errors.contains(unit.path()))
                candidates.put(unit.path(), unit);
        for (int index = 0; index < fallback.size(); index++) {
            JavaUnit clean = fallback.get(index);
            JavaUnit candidate = candidates.get(clean.path());
            if (candidate == null
                    || !candidate.sourceHash().equals(clean.sourceHash())
                    || !declarationOnlySource(candidate)
                    || !hasNewLombokMember(candidate, clean)) continue;
            fallback.set(
                    index,
                    new JavaUnit(
                            candidate.path(),
                            candidate.tree(),
                            candidate.processing(),
                            candidate.bindings(),
                            candidate.types(),
                            candidate.declarations(),
                            candidate.declarationInventory(),
                            candidate.sourceHash(),
                            new JavaExtensionData(
                                    Map.of(),
                                    Map.of(),
                                    List.of(),
                                    Map.of("lombok_processor_partial", 1L))));
        }
    }

    static boolean declarationOnlySource(JavaUnit unit) {
        for (var node : new JavaSyntax(unit.tree()).nodes())
            if (Set.of("METHOD_INVOCATION", "NEW_CLASS", "MEMBER_REFERENCE", "LAMBDA_EXPRESSION")
                    .contains(node.kind())) return false;
        return true;
    }

    private static boolean hasNewLombokMember(JavaUnit candidate, JavaUnit clean) {
        Set<String> prior = new HashSet<>();
        clean.declarationInventory().forEach(type -> memberKeys(type, prior));
        for (JavaDeclaration type : candidate.declarationInventory()) {
            if (!hasLombokAnnotation(type)) continue;
            Set<String> current = new HashSet<>();
            memberKeys(type, current);
            if (current.stream().anyMatch(key -> !prior.contains(key))) return true;
        }
        return false;
    }

    private static boolean hasLombokAnnotation(JavaDeclaration declaration) {
        if (declaration.annotations().stream()
                .anyMatch(annotation -> annotation.descriptor().startsWith("Llombok/")))
            return true;
        return declaration.members().stream().anyMatch(JavacBackend::hasLombokAnnotation);
    }

    private static void memberKeys(JavaDeclaration declaration, Set<String> keys) {
        if (declaration.origin() != JavaDeclaration.Origin.SOURCE)
            keys.add(
                    declaration.symbol().owner()
                            + "#"
                            + declaration.symbol().kind()
                            + ":"
                            + declaration.symbol().name()
                            + declaration.symbol().descriptor());
        declaration.members().forEach(member -> memberKeys(member, keys));
    }

    /** Caller-owned metadata scope using the same module dependency order as its compiler task. */
    public com.anatomist.core.asmsolver.JavaBinaryRepository openBinaryRepository(
            JavaAnalysisContext context) {
        var entries =
                mavenInputs != null && mavenInputs.resolvedClasspath()
                        ? mavenInputs.module(context.module()).classpath(context.scope())
                        : classpath;
        return openBinaries(entries);
    }

    com.anatomist.core.asmsolver.JavaBinaryRepository openBinaries(List<Path> entries) {
        return binaryInventories == null
                ? JavaBinarySources.open(
                        entries, toolchain.home(), toolchain.release(), effectiveAsmCacheMaxBytes())
                : binaryInventories.open(entries, effectiveAsmCacheMaxBytes());
    }

    private final class BinaryScope implements AutoCloseable {
        private final JavaBinarySources.Session inventories;

        private BinaryScope() {
            if (binaryInventories != null)
                throw new IllegalStateException("binary session already active");
            inventories =
                    new JavaBinarySources.Session(
                            classpath, toolchain.home(), toolchain.release(), timings);
            binaryInventories = inventories;
        }

        @Override
        public void close() {
            try {
                inventories.close();
            } finally {
                binaryInventories = null;
            }
        }
    }

    private boolean sourceSignatures =
            Boolean.parseBoolean(System.getProperty("anatomist.javac.sourceSignatures", "true"));
    private SignatureStats signatureStats = new SignatureStats(0, 0, 0);

    record SignatureStats(
            int archivesUsed,
            int fallbackModules,
            long sourceAppearances,
            Map<String, List<String>> fallbackReasons,
            int catalogueEvictions,
            int catalogueReloads,
            int restoredModules) {
        SignatureStats(int archivesUsed, int fallbackModules, long sourceAppearances) {
            this(archivesUsed, fallbackModules, sourceAppearances, Map.of(), 0, 0, 0);
        }
    }

    public void setSourceSignatures(boolean enabled) {
        requireIdle();
        sourceSignatures = enabled;
    }

    SignatureStats signatureStats() {
        return signatureStats;
    }

    @Override
    public com.anatomist.core.java.JavaAnalysisStats analysisStats() {
        return new com.anatomist.core.java.JavaAnalysisStats(
                signatureStats.archivesUsed(),
                signatureStats.fallbackModules(),
                signatureStats.sourceAppearances(),
                workerLaunches(),
                signatureStats.fallbackReasons(),
                signatureStats.catalogueEvictions(),
                signatureStats.catalogueReloads(),
                signatureStats.restoredModules());
    }

    int workerLaunches() {
        return completedWorkerLaunches
                + (activeWorkers == null
                        ? 0
                        : activeWorkers.workers.stream()
                                .mapToInt(JavacWorkerSession::launches)
                                .sum());
    }

    private final class WorkerScope implements AutoCloseable {
        private boolean closed;

        WorkerScope() {
            if (activeWorkers != null)
                throw new IllegalStateException("javac parsing session already active");
            try {
                String workerPath =
                        worker(
                                        sealedContexts == null
                                                ? config.cacheDirectory()
                                                : config.cacheDirectory().resolve(".runtime"))
                                .toString();
                List<String> command =
                        new ArrayList<>(
                                List.of(
                                        toolchain.home().resolve("bin/java").toString(),
                                        "-Xmx" + config.workerHeapMb() + "m"));
                if (toolchain.release() == 8)
                    workerPath += File.pathSeparator + toolchain.home().resolve("lib/tools.jar");
                else command.addAll(List.of("--add-modules", "jdk.compiler"));
                command.addAll(List.of("-cp", workerPath, "com.anatomist.worker.JavacWorker", "1"));
                activeWorkers =
                        new JavacWorkerPool(
                                command,
                                projectRoot,
                                batchTimeout,
                                config.analysisThreads(),
                                timings);
            } catch (IOException failure) {
                throw new IllegalStateException("failed to prepare javac worker", failure);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            var pool = activeWorkers;
            try {
                pool.close();
            } finally {
                for (var worker : pool.workers) {
                    executions.addAll(worker.executions());
                    batchEvidence.addAll(worker.batches());
                    completedWorkerLaunches += worker.launches();
                }
                activeWorkers = null;
            }
        }
    }

    private final List<JavacDeclarationCache> declarationCaches = new ArrayList<>();

    private JavacDeclarationCache declarationCache(int slot) {
        while (declarationCaches.size() <= slot)
            declarationCaches.add(
                    new JavacDeclarationCache(
                            JavacDeclarationCache.DEFAULT_BYTES / config.analysisThreads()));
        return declarationCaches.get(slot);
    }

    private boolean dependencyDeclarations =
            Boolean.parseBoolean(
                    System.getProperty("anatomist.javac.dependencyDeclarations", "true"));

    void setDependencyDeclarations(boolean enabled) {
        requireIdle();
        dependencyDeclarations = enabled;
        identity = null;
    }

    long declarationCacheHits() {
        return declarationCaches.stream().mapToLong(JavacDeclarationCache::hits).sum();
    }

    private boolean declarationViews() {
        return dependencyDeclarations;
    }

    public JavacBackend(
            JavacToolchain toolchain, List<Path> classpath, List<Path> roots, JavacOptions config) {
        this(
                roots.isEmpty() ? Path.of(".").toAbsolutePath() : roots.getFirst(),
                toolchain,
                classpath,
                roots.stream().map(root -> new SourceRoot(root, ".", SourceScope.MAIN)).toList(),
                config);
    }

    public JavacBackend(
            Path projectRoot,
            JavacToolchain toolchain,
            List<Path> classpath,
            List<SourceRoot> sourceRoots,
            JavacOptions config) {
        this.projectRoot = projectRoot;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.toolchain = toolchain;
        Path project = projectRoot.toAbsolutePath().normalize();
        this.classpath =
                classpath.stream()
                        .map(entry -> entry.toAbsolutePath().normalize())
                        .filter(
                                entry -> {
                                    Path absolute = entry.toAbsolutePath().normalize();
                                    if (!absolute.startsWith(project)) return true;
                                    String relative =
                                            project.relativize(absolute)
                                                    .toString()
                                                    .replace('\\', '/');
                                    return !relative.matches(
                                            "(?:.*/)?(?:target/(?:test-)?classes|build/classes(?:/.*)?)");
                                })
                        .toList();
        this.config = config;
    }

    @Override
    public void setTimings(IndexTimings value) {
        requireIdle();
        timings = value;
        sourceDiscovery.setTimings(value);
    }

    @Override
    public void setSourcePolicy(ScanPolicy value) {
        requireIdle();
        if (policy != null
                && value != null
                && policy.canonical(sourceRoots, List.of())
                        .equals(value.canonical(sourceRoots, List.of()))) return;
        policy = value;
        sourcePlan = null;
        identity = null;
    }

    @Override
    public void setResourceLimits(ParserResourceLimits value) {
        requireIdle();
        resourceLimits = value == null ? ParserResourceLimits.DEFAULT : value;
    }

    @Override
    public int effectiveSourceCacheSize() {
        return ParserCacheBudgets.resolve(resourceLimits).sourceEntries();
    }

    @Override
    public long effectiveCombinedCacheSize() {
        return ParserCacheBudgets.resolve(resourceLimits).combinedEntries();
    }

    @Override
    public long effectiveAsmCacheMaxBytes() {
        return ParserCacheBudgets.resolve(resourceLimits).asmBytes();
    }

    @Override
    public String environmentFingerprint() {
        if (identity == null) {
            long started = System.nanoTime();
            environmentBuilds++;
            try {
                Path worker =
                        worker(
                                sealedContexts == null
                                        ? config.cacheDirectory()
                                        : config.cacheDirectory().resolve(".runtime"));
                String material =
                        MODEL_VERSION
                                + "|contract="
                                + com.anatomist.core.java.JavaContract.VERSION
                                + "|signatures="
                                + JavacSignatureCache.VERSION
                                + "|maven="
                                + (mavenInputs == null ? "direct-pom" : mavenInputs.fingerprint())
                                + "|maven-worker="
                                + (sealedContexts != null
                                        ? "rust-captured-v1"
                                        : mavenInputs == null
                                                ? ""
                                                : JavacMavenInputs.workerFingerprint())
                                + "|support="
                                + supportFingerprint()
                                + "|declarations="
                                + declarationViews()
                                + "|model-extensions="
                                + modelExtensions.fingerprint()
                                + "|toolchain|"
                                + toolchain.home()
                                + "|"
                                + toolchain.fingerprint()
                                + "|"
                                + ContentHash.sha256(worker)
                                + "|compile="
                                + config.lombokCompile()
                                + "|aux="
                                + (config.lombokCompile()
                                        ? JavacCompilationCache.auxiliaryFingerprint(
                                                lombokConfigurationRoots(), classpath)
                                        : "");
                identity = ContentHash.sha256(material);
            } catch (IOException failure) {
                throw new IllegalStateException("failed to identify javac worker", failure);
            } finally {
                if (timings != null) timings.stop("parser_environment_fingerprint", started);
            }
        }
        return identity;
    }

    public void setSourceDiscovery(JavaSourceDiscovery discovery) {
        requireIdle();
        sourceDiscovery = Objects.requireNonNull(discovery);
        sourcePlan = null;
        identity = null;
    }

    public Map<String, Long> workCounts() {
        Map<String, Long> counts = new LinkedHashMap<>(sourceDiscovery.counts());
        counts.put("environment_fingerprint_builds", environmentBuilds);
        counts.put(
                "model_bytes",
                batchEvidence.stream().mapToLong(JavacBatchMetrics::resultBytes).sum());
        counts.put(
                "transport_bytes",
                batchEvidence.stream().mapToLong(JavacBatchMetrics::transportBytes).sum());
        counts.put("result_copy_bytes", 0L);
        return Map.copyOf(counts);
    }

    JavaSourcePlan sourcePlan() {
        if (sourcePlan == null) {
            var selected =
                    new ProjectScanner(Set.of(), policy, sourceDiscovery)
                            .scanSourceRoots(sourceRoots);
            var roots = visibleRoots(selected);
            var visible =
                    new ProjectScanner(
                                    Set.of(),
                                    policy == null ? null : policy.support(),
                                    sourceDiscovery)
                            .scanSourceRoots(roots);
            var resolver = SourceIdentityResolver.fromRoots(projectRoot, compilationRoots());
            Map<Path, SourceIdentity> identities = new LinkedHashMap<>();
            visible.forEach(path -> identities.put(path, resolver.resolve(path.toString())));
            sourcePlan = new JavaSourcePlan(selected, visible, roots, identities);
        }
        return sourcePlan;
    }

    private List<Path> lombokConfigurationRoots() {
        return sourcePlan().visibleFiles().stream().map(Path::getParent).distinct().toList();
    }

    private String supportFingerprint() throws IOException {
        var selected = new HashSet<>(sourcePlan().indexedFiles());
        StringBuilder material = new StringBuilder();
        for (Path source : sourcePlan().visibleFiles())
            if (!selected.contains(source))
                material.append(
                                sealedContexts == null
                                        ? source
                                        : projectRoot
                                                .toAbsolutePath()
                                                .normalize()
                                                .relativize(source.toAbsolutePath().normalize()))
                        .append('=')
                        .append(ContentHash.sha256(source))
                        .append('\n');
        return ContentHash.sha256(material.toString());
    }

    @Override
    public Map<String, String> environmentMetadata() {
        return Map.of(
                "parser_environment_hash",
                environmentFingerprint(),
                "parser_backend",
                "javac",
                "javac_execution",
                "toolchain",
                "javac_home",
                toolchain.home().toString(),
                "javac_release",
                Integer.toString(toolchain.release()),
                "javac_toolchain_fingerprint",
                toolchain.fingerprint(),
                "javac_worker_fingerprint",
                workerFingerprint(),
                "java_model_version",
                MODEL_VERSION,
                "maven_input_worker_fingerprint",
                sealedContexts != null
                        ? "rust-captured-v1"
                        : mavenInputs == null ? "" : JavacMavenInputs.workerFingerprint());
    }

    public static String workerFingerprint() {
        try (InputStream input =
                JavacBackend.class.getResourceAsStream("/META-INF/anatomist/javac-worker.jar")) {
            if (input == null) throw new IOException("packaged javac worker is missing");
            return HexFormat.of().formatHex(sha256(input.readAllBytes()));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Direct semantic entrypoint: no compatibility AST or symbol solver is constructed. */
    public ParseInventory analyzeFiles(
            List<Path> supplied, java.util.function.Consumer<JavaAnalysisContext> consumer) {
        classpathEvidence.clear();
        executions.clear();
        batchEvidence.clear();
        diagnosticsEvidence.clear();
        modelExtensions.verifyInputs();
        List<Path> targets =
                supplied.stream()
                        .filter(Objects::nonNull)
                        .map(path -> path.toAbsolutePath().normalize())
                        .filter(path -> policy == null || !policy.textOnly(path))
                        .distinct()
                        .toList();
        // No consumer publication means there is no model to produce. Auxiliary
        // contexts are prerequisites of a requested target, never standalone work.
        if (targets.isEmpty()) return ParseInventory.complete(0);
        List<SourceRoot> visibleRoots =
                sealedContexts == null ? visibleRoots(targets) : sourceRoots;
        LinkedHashSet<Path> visible =
                new LinkedHashSet<>(
                        sourcePlan().visibleFiles().stream()
                                .filter(
                                        path ->
                                                visibleRoots.stream()
                                                        .anyMatch(
                                                                root ->
                                                                        path.startsWith(
                                                                                root.path())))
                                .toList());
        visible.addAll(targets);
        List<Path> input = visible.stream().filter(Files::isRegularFile).sorted().toList();

        long preparationStarted = timings == null ? 0 : timings.start();
        try (var ignored = new WorkerScope();
                var ignoredBinaryScope = new BinaryScope();
                var signatures =
                        new JavacSignaturePlan(
                                projectRoot,
                                sealedContexts == null ? visibleRoots(targets) : sourceRoots,
                                config.cacheDirectory(),
                                environmentFingerprint(),
                                toolchain.release(),
                                classpath,
                                sourceSignatures,
                                mavenInputs)) {
            signatures.setTimings(timings);
            if (timings != null) timings.stop("javac_signature_prepare", preparationStarted);
            long planStarted = timings == null ? 0 : timings.start();
            var contexts =
                    sealedContexts == null
                            ? signatures.contexts(input, targets)
                            : signatures.capturedContexts(sealedContexts);
            if (timings != null) timings.stop("javac_signature_plan", planStarted);
            var inventory = new AnalysisInventory();
            JavacAnalysisCoordinator.run(
                    new JavacAnalysisPlanner(contexts),
                    signatures,
                    config.analysisThreads(),
                    batch -> analyzeBatch(batch, signatures, consumer, inventory));
            long verificationStarted = timings == null ? 0 : timings.start();
            modelExtensions.verifyInputs();
            signatures.verifyInputs();
            if (timings != null) timings.stop("javac_signature_verify", verificationStarted);
            signatureStats =
                    new SignatureStats(
                            signatures.archivesUsed(),
                            signatures.fallbackModules(),
                            signatures.sourceAppearances(),
                            signatures.fallbackReasons(),
                            signatures.catalogueEvictions(),
                            signatures.catalogueReloads(),
                            signatures.restoredModules());
            return new ParseInventory(
                    targets.size(), targets.size(), inventory.parsed, inventory.failures);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "JAVAC_SIGNATURE_FAILED: " + failure.getMessage(), failure);
        } finally {
            declarationCaches.forEach(JavacDeclarationCache::clear);
        }
    }

    List<SourceRoot> compilationRoots() {
        Map<Path, SourceRoot> roots = new LinkedHashMap<>();
        sourceRoots.forEach(root -> roots.put(root.path(), root));
        if (mavenInputs != null) {
            for (var module : mavenInputs.modules()) {
                module.mainRoots()
                        .forEach(
                                path ->
                                        roots.putIfAbsent(
                                                path,
                                                new SourceRoot(
                                                        path, module.name(), SourceScope.MAIN)));
                module.generatedMainRoots()
                        .forEach(
                                path ->
                                        roots.putIfAbsent(
                                                path,
                                                new SourceRoot(
                                                        path,
                                                        module.name(),
                                                        SourceScope.GENERATED)));
            }
        } else {
            for (var root : sourceRoots) {
                String path = root.path().toString().replace('\\', '/');
                if (root.compilationScope() == SourceScope.TEST
                        && path.endsWith("/src/test/java")) {
                    Path main =
                            Path.of(path.substring(0, path.length() - "/src/test/java".length()))
                                    .resolve("src/main/java");
                    roots.putIfAbsent(main, new SourceRoot(main, root.module(), SourceScope.MAIN));
                }
            }
        }
        return List.copyOf(roots.values());
    }

    private List<SourceRoot> visibleRoots(List<Path> targets) {
        if (mavenInputs == null) return compilationRoots();
        var identities = SourceIdentityResolver.fromRoots(projectRoot, sourceRoots);
        Set<String> modules = new LinkedHashSet<>();
        Set<String> tests = new LinkedHashSet<>();
        for (Path target : targets) {
            String module = mavenInputs.owningModule(target).name();
            modules.add(module);
            SourceScope scope = identities.compilationScope(target);
            if (scope == SourceScope.TEST) tests.add(module);
            for (var dependency : mavenInputs.module(module).dependencies(scope))
                if (dependency instanceof JavacMavenInputs.ReactorDependency reactor)
                    modules.add(reactor.module());
        }
        return compilationRoots().stream()
                .filter(root -> modules.contains(mavenInputs.owningModule(root.path()).name()))
                .filter(
                        root ->
                                root.compilationScope() != SourceScope.TEST
                                        || tests.contains(
                                                mavenInputs.owningModule(root.path()).name()))
                .toList();
    }

    private void analyzeBatch(
            List<JavacAnalysisPlanner.Task> tasks,
            JavacSignaturePlan signatures,
            java.util.function.Consumer<JavaAnalysisContext> consumer,
            AnalysisInventory inventory)
            throws IOException {
        int offset = 0;
        while (offset < tasks.size()) {
            try (var attempts = new AttemptBatch()) {
                for (int slot = 0; offset + slot < tasks.size(); slot++) {
                    var task = tasks.get(offset + slot);
                    if (slot > 0 && !signatures.canAdmit(task.context())) break;
                    attempts.values.add(prepareAttempt(task, slot, 0, signatures, false));
                }
                activeWorkers.execute(attempts.values);
                for (int index = 0; index < attempts.values.size(); index++) {
                    var task = tasks.get(offset + index);
                    boolean consumed;
                    try (var attempt = attempts.values.get(index)) {
                        consumed = consumeAttempt(task, attempt, signatures, consumer, inventory);
                    }
                    if (!consumed) {
                        try (var retry = prepareAttempt(task, index, 1, signatures, true)) {
                            if (!retry.cached())
                                retry.received(
                                        activeWorkers
                                                .workers
                                                .get(index)
                                                .executeBatch(List.of(retry.request()))
                                                .getFirst());
                            if (!consumeAttempt(task, retry, signatures, consumer, inventory))
                                throw new IllegalStateException("source fallback still conflicts");
                        }
                    }
                }
                offset += attempts.values.size();
            }
        }
    }

    private JavacAnalysisAttempt prepareAttempt(
            JavacAnalysisPlanner.Task task,
            int lane,
            int attemptId,
            JavacSignaturePlan signatures,
            boolean fullSource)
            throws IOException {
        JavacCancellation.check();
        var input =
                fullSource
                        ? signatures.fullSource(task.context())
                        : signatures.input(task.context());
        var catalogues = signatures.pin(input);
        var cache = declarationCache(lane);
        JavacDeclarationCache.Lease declarations = null;
        JavacCompilationCache.Directory directory = null;
        try {
            declarations =
                    cache.lease(
                            input.context().sources(),
                            task.context().publish(),
                            declarationViews(),
                            environmentFingerprint());
            directory =
                    new JavacCompilationCache.Directory(
                            Files.createTempDirectory(config.cacheDirectory(), ".worker-"));
            var lease = declarations;
            var scratch = directory.path();
            var sources = input.context().sources();
            var compilerClasspath = input.classpath();
            Path processor = null;
            Path target = null;
            boolean cached = false;
            if (config.lombokCompile()) {
                try {
                    processor = JavacCompilationCache.processor(compilerClasspath);
                } catch (JavacCompilationCache.ProcessorUnavailable unavailable) {
                    // Ordinary modules without Lombok still use clean compiler analysis.
                }
                if (processor != null) {
                    target =
                            JavacCompilationCache.cached(
                                    config.cacheDirectory(),
                                    environmentFingerprint()
                                            + "|publish="
                                            + input.context().publish().stream().sorted().toList()
                                            + "|declarations="
                                            + declarationViews()
                                            + "|classpath="
                                            + JavacCompilationCache.auxiliaryFingerprint(
                                                    sources.stream()
                                                            .map(Path::getParent)
                                                            .distinct()
                                                            .toList(),
                                                    compilerClasspath),
                                    sources);
                    try {
                        cached = JavacCompilationCache.valid(target);
                    } catch (IOException corrupt) {
                        cached = false;
                    }
                    if (cached && timings != null) timings.addNanos("lombok_compile_cache_hit", 0);
                    if (cached)
                        Files.copy(target.resolve("analysis.bin"), scratch.resolve("received.bin"));
                }
            }
            Path selectedProcessor = processor;
            Path classes =
                    processor == null ? null : Files.createDirectories(scratch.resolve("classes"));
            var request =
                    new JavacBatchProtocol.Request(
                            task.id(),
                            attemptId,
                            0,
                            out ->
                                    JavacProtocol.request(
                                            out,
                                            toolchain,
                                            sources,
                                            compilerClasspath,
                                            selectedProcessor != null,
                                            selectedProcessor,
                                            classes,
                                            scratch,
                                            lease),
                            scratch.resolve("received.bin"));
            var prepared =
                    new JavacAnalysisAttempt(input, declarations, catalogues, directory, request);
            prepared.compilation(target, cached, processor != null);
            return prepared;
        } catch (IOException | RuntimeException | Error failure) {
            for (AutoCloseable resource :
                    new AutoCloseable[] {declarations, catalogues, directory}) {
                if (resource == null) continue;
                try {
                    resource.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private boolean consumeAttempt(
            JavacAnalysisPlanner.Task task,
            JavacAnalysisAttempt attempt,
            JavacSignaturePlan signatures,
            java.util.function.Consumer<JavaAnalysisContext> consumer,
            AnalysisInventory inventory)
            throws IOException {
        return modelConsumer.consumeAttempt(task, attempt, signatures, consumer, inventory);
    }

    private static final class AttemptBatch implements AutoCloseable {
        private final List<JavacAnalysisAttempt> values = new ArrayList<>();

        @Override
        public void close() throws IOException {
            Throwable failure = null;
            for (var attempt : values.reversed()) {
                try {
                    attempt.close();
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    else failure.addSuppressed(cleanup);
                }
            }
            values.clear();
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IOException("javac batch cleanup failed", failure);
        }
    }

    private void processContext(
            JavacAnalysisPlanner.Task task,
            JavacSignaturePlan.Input compilerInput,
            List<JavaUnit> units,
            JavacProtocol.Result response,
            String compileFailure,
            JavacSignaturePlan signatures,
            java.util.function.Consumer<JavaAnalysisContext> consumer,
            AnalysisInventory inventory)
            throws IOException {
        modelConsumer.processContext(
                task,
                compilerInput,
                units,
                response,
                compileFailure,
                signatures,
                consumer,
                inventory);
    }

    static final class AnalysisInventory {
        int parsed;
        final Map<Path, List<String>> failures = new LinkedHashMap<>();
    }

    JavacProtocol.Result execute(
            JavacAnalysisPlanner.Task task,
            JavacSignaturePlan.Input input,
            int attemptId,
            boolean compile,
            Path processor,
            Path destination,
            Path transcript,
            java.util.function.Consumer<JavaUnit> frames) {
        return execute(
                task.id(),
                attemptId,
                input.context(),
                input.classpath(),
                compile,
                processor,
                destination,
                transcript,
                frames);
    }

    JavacProtocol.Result execute(
            long taskId,
            int attemptId,
            JavacContexts.Context context,
            List<Path> compilerClasspath,
            boolean compile,
            Path processor,
            Path destination,
            Path transcript,
            java.util.function.Consumer<JavaUnit> frames) {
        try {
            List<Path> files = context.sources();
            try (var declarations =
                            declarationCache(0)
                                    .lease(
                                            files,
                                            context.publish(),
                                            declarationViews(),
                                            environmentFingerprint());
                    var scratch =
                            new JavacCompilationCache.Directory(
                                    Files.createTempDirectory(
                                            config.cacheDirectory(), ".worker-"))) {
                if (activeWorkers == null)
                    throw new IllegalStateException("javac worker requires a parsing session");
                var response =
                        activeWorkers
                                .workers
                                .getFirst()
                                .execute(
                                        taskId,
                                        attemptId,
                                        out ->
                                                JavacProtocol.request(
                                                        out,
                                                        toolchain,
                                                        files,
                                                        compilerClasspath,
                                                        compile,
                                                        processor,
                                                        destination,
                                                        scratch.path(),
                                                        declarations),
                                        frames,
                                        declarations,
                                        transcript);
                if (timings != null) response.timingsNanos().forEach(timings::addNanos);
                if (!compile || response.compileSucceeded()) declarations.commit();
                return response;
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "JAVAC_WORKER_FAILED: task="
                            + taskId
                            + " module="
                            + context.module()
                            + " scope="
                            + context.scope()
                            + " inputs="
                            + context.sources().size()
                            + " published="
                            + context.publish().size()
                            + ": "
                            + failure.getMessage(),
                    failure);
        }
    }

    private static Path worker(Path cacheDirectory) throws IOException {
        byte[] bytes;
        try (InputStream input =
                JavacBackend.class.getResourceAsStream("/META-INF/anatomist/javac-worker.jar")) {
            if (input == null) throw new IOException("packaged javac worker is missing");
            bytes = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(sha256(bytes));
        Path directory = cacheDirectory.resolve("workers");
        Files.createDirectories(directory);
        Path target = directory.resolve(hash + ".jar");
        if (!Files.isRegularFile(target) || !ContentHash.sha256(target).equals(hash)) {
            Path temp = Files.createTempFile(directory, "worker-", ".part");
            try {
                Files.write(temp, bytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return target;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
