package dev.codeana.adapter;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.framework.lombok.*;
import com.anatomist.framework.sofa.SofaFrameworkPlugin;
import com.anatomist.model.*;

import dev.codeana.common.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Task lifetime at the native boundary. All semantics belong to the migrated core. */
public final class AnalysisSession implements AutoCloseable {
    public static final String IDENTITY = "anatomist-4a903272-codeana-graph2-maven-fallback1";
    private final AnalysisRequest request;
    private final AnalysisLimits limits;
    private final Path taskRoot;
    private GraphArchive output;
    private volatile Thread executing;
    private volatile boolean cancelled;
    private final AnalysisObserver observer;

    public AnalysisSession(Map<String, Object> request) throws IOException {
        this(request, AnalysisObserver.NONE);
    }

    public AnalysisSession(Map<String, Object> request, AnalysisObserver observer)
            throws IOException {
        this.observer = Objects.requireNonNull(observer);
        var input = new LinkedHashMap<>(request);
        input.put("diagnostic_scratch", Json.string(request, "scratch"));
        taskRoot =
                Files.createTempDirectory(
                        Path.of(Json.string(request, "scratch")), "anatomist-task-");
        input.put("scratch", taskRoot.toString());
        try {
            this.request = AnalysisRequest.parse(input);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(taskRoot);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        limits = this.request.limits();
    }

    public void cancel() {
        cancelled = true;
        Thread t = executing;
        if (t != null) t.interrupt();
    }

    public void check() {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("CANCELLED");
    }

    public Map<String, Object> analyze() throws Exception {
        try (var recording = AllocationRecording.open(request.diagnosticScratch())) {
            return analyzeCaptured();
        }
    }

    private Map<String, Object> analyzeCaptured() throws Exception {
        executing = Thread.currentThread();
        check();
        if (request.version() != 10) throw new IllegalArgumentException("ABI_REQUEST_VERSION");
        var plan = prepareAnalysis();
        extractJava(plan);
        extractResources(plan);
        return sealAnalysis(plan).wire();
    }

    private record Plan(
            CapturedProject project,
            Set<String> dirty,
            AnatomistRows projection,
            ProjectView view,
            JavacBackend backend,
            IndexTimings timings,
            Map<String, JavaFrameworkContext> frameworks,
            Map<String, JavaModelAnalyzerRegistry> registries) {}

    private Plan prepareAnalysis() throws Exception {
        observer.phase("capture", "", true);
        CapturedProject project = new CapturedProject(request);
        observer.phase("capture", "", false);
        boolean full = request.full();
        var summary = request.baseline().read();
        var previous = summary.contexts();
        Set<String> changedSources = new HashSet<>(summary.changedSources());
        if (full) changedSources.addAll(project.sources.keySet());
        if (!full) project.publishedSources(changedSources);

        Set<String> dirty = new HashSet<>();
        for (var c : project.contexts)
            if (full || !c.digest().equals(previous.get(c.world()))) dirty.add(c.world());
        if (!dirty.isEmpty())
            for (var c : project.contexts) if (!c.resources().isEmpty()) dirty.add(c.world());
        output =
                new GraphArchive(
                        request.scratch().resolve("anatomist-rows.bin"),
                        limits.bytes,
                        limits.facts);
        AnatomistRows projection = new AnatomistRows(project, output);
        projection.row(
                dev.codeana.graph.GraphRecords.Table.PROJECT_META,
                "analysis_mode",
                project.dependencyMode.equals("resolved") ? "semantic" : "source_only");
        projection.row(
                dev.codeana.graph.GraphRecords.Table.PROJECT_META,
                "classpath_status",
                project.dependencyMode.equals("resolved") ? "available" : "unavailable");
        projection.row(
                dev.codeana.graph.GraphRecords.Table.PROJECT_META,
                "dependency_mode",
                project.dependencyMode);
        if (project.dependencyMode.equals("source_only_fallback")) {
            var failure = project.dependencyFailure;
            String sample =
                    "Maven external dependency resolution failed; continuing source analysis"
                            + " without external classpath. Type and call relationships may be"
                            + " incomplete. Module: "
                            + failure.module()
                            + "; POM: "
                            + failure.pom()
                            + "; dependency: "
                            + failure.coordinate()
                            + "; "
                            + failure.reason();
            for (var c : project.contexts)
                projection.diagnostic(
                        new IndexDiagnostic(
                                "warning",
                                "CLASSPATH_UNAVAILABLE",
                                "CLASSPATH",
                                null,
                                c.module(),
                                c.scope().name(),
                                failure.coordinate(),
                                1,
                                sample));
        }
        ProjectView view = new ProjectView(project);
        for (var c : project.contexts) {
            if (dirty.contains(c.world())) {
                for (var source : c.sources())
                    if (c.publish().contains(source.file()) && project.publishes(source.file()))
                        projection.replace(source);
                for (var source : c.resources()) projection.replace(source);
            }
            projection.row(
                    dev.codeana.graph.GraphRecords.Table.PROJECT_META,
                    "codeana.context." + c.world(),
                    c.digest());
        }
        for (var old : summary.files())
            if (!project.sources.containsKey(old.path()))
                projection.replaceDeleted(old.path(), old.world());
        for (var symbol : summary.symbols())
            if (!changedSources.contains(symbol.sourceFile())) view.symbol(symbol);
        for (var relation : summary.relations())
            if (!changedSources.contains(relation.sourceFile()))
                view.relations.add(relation.fact());
        Path home = request.javaHome();
        Path cache =
                request.cacheRoot() != null
                        ? request.cacheRoot()
                        : Files.createDirectory(request.scratch().resolve("anatomist-cache"));
        cache = Files.createDirectories(cache);
        int threads = request.parallelism();
        int heap = request.compilerHeapMb();
        JavacOptions options =
                new JavacOptions(home, true, true, false, false, cache, threads, heap);
        var toolchain = JavacToolchain.resolve(options, 8, null);
        var timings = observer.timings();
        Map<String, JavacBackend.ModelBudget> modelBudgets = new HashMap<>();
        for (var c : project.contexts)
            modelBudgets.put(
                    c.module() + "@" + c.scope(),
                    new JavacBackend.ModelBudget(
                            c.options().modelArchiveBytes(), c.options().modelRecordLimit()));
        var decodeBudgets =
                project.contexts.stream()
                        .map(c -> c.options().modelDecodeBytes())
                        .distinct()
                        .toList();
        if (decodeBudgets.size() > 1)
            throw new IllegalArgumentException("JAVA_MODEL_DECODE_BUDGET_CONFLICT");
        Map<String, JavaFrameworkContext> frameworks = new HashMap<>();
        Map<String, JavaModelAnalyzerRegistry> registries = new HashMap<>();
        for (var c : project.contexts) {
            String key = c.module() + "@" + c.scope();
            ProjectConfig config = new ProjectConfig();
            config.setJavaVersion(8);
            config.setLombokMode("model".equals(c.options().lombok()) ? "ast" : "off");
            config.setLombokStrict(c.options().lombokStrict());
            config.setSpringXml(c.options().springXml());
            if (c.options().externalPolicyConfigured())
                config.setExternalExcludePatterns(c.options().externalExcludePatterns());
            JavaFrameworkContext f =
                    new JavaFrameworkContext(
                            project.root,
                            c.sources().stream().map(CapturedProject.Source::file).toList(),
                            config,
                            config.springXml());
            frameworks.put(key, f);
            registries.put(key, registry(project, c, f));
        }
        var overlay =
                new JavaDeclarationOverlay() {
                    public String fingerprint() {
                        return Json.key(
                                "overlays",
                                registries.entrySet().stream()
                                        .sorted(Map.Entry.comparingByKey())
                                        .map(e -> e.getKey() + e.getValue().fingerprint())
                                        .toArray(String[]::new));
                    }

                    public void verifyInputs() {
                        registries.values().forEach(r -> r.overlays().verifyInputs());
                    }

                    public JavaAnalysisContext augment(
                            JavaAnalysisContext c,
                            com.anatomist.core.asmsolver.JavaBinaryRepository b) {
                        return registries
                                .get(c.module() + "@" + c.scope())
                                .overlays()
                                .augment(c, b);
                    }
                };
        var run =
                new JavacRunConfiguration(
                        modelBudgets,
                        decodeBudgets.isEmpty() ? 512L << 20 : decodeBudgets.getFirst(),
                        request.signatureCache(),
                        request.compilerTimeout(),
                        timings,
                        overlay,
                        project.compilerContexts(dirty),
                        project.sourcePlan(dirty),
                        project.moduleInputs());
        var backend = JavacBackend.captured(project.root, toolchain, project.roots(), options, run);
        return new Plan(
                project,
                Set.copyOf(dirty),
                projection,
                view,
                backend,
                timings,
                frameworks,
                registries);
    }

    private void extractJava(Plan plan) {
        var project = plan.project();
        var dirty = plan.dirty();
        var projection = plan.projection();
        var view = plan.view();
        var backend = plan.backend();
        var timings = plan.timings();
        var frameworks = plan.frameworks();
        var registries = plan.registries();
        observer.phase("backend_total", "", true);
        try {
            var sourcePlan = project.sourcePlan(dirty);
            var compilerTargets = sourcePlan.indexedFiles();
            // A resource-only consumer still needs the Java catalogue used by
            // copied XML resolvers. Captured publish flags remain unchanged.
            if (compilerTargets.isEmpty()
                    && project.contexts.stream()
                            .anyMatch(
                                    c ->
                                            dirty.contains(c.world())
                                                    && !c.resources().isEmpty()
                                                    && (c.options().springXml()
                                                            || c.options().sofa())))
                compilerTargets = sourcePlan.visibleFiles();
            backend.analyzeFiles(
                    compilerTargets,
                    analysis -> {
                        check();
                        observer.model(analysis);
                        String key = analysis.module() + "@" + analysis.scope();
                        observer.phase("extraction_total", key, true);
                        try (var binaries = backend.openBinaryRepository(analysis)) {
                            new JavaModelExtractor(
                                            frameworks.get(key),
                                            binaries,
                                            registries.get(key),
                                            timings)
                                    .extract(
                                            analysis,
                                            unit -> {
                                                observer.extraction(unit.facts());
                                                view.add(unit.facts());
                                                projection.unit(unit);
                                            });
                        } finally {
                            projection.flush();
                            observer.phase("extraction_total", key, false);
                        }
                    });
        } finally {
            observer.projectionStatistics(projection.statistics());
            observer.compilerStatistics(
                    Json.object(
                            "analysis",
                            backend.analysisStats().metadata(),
                            "batches",
                            backend.batchEvidence().stream().map(b -> b.toMap()).toList()));
            observer.phase("backend_total", "", false);
        }
    }

    private void extractResources(Plan plan) {
        var project = plan.project();
        var dirty = plan.dirty();
        var projection = plan.projection();
        var view = plan.view();
        var frameworks = plan.frameworks();
        var registries = plan.registries();
        // Register resource declarations before resolving references in any consumer context.
        // The same upstream resource analyzers build both the catalogue and published facts.
        for (var c : project.contexts) {
            if (!dirty.contains(c.world()) || c.resources().isEmpty()) continue;
            check();
            var declarations = new ExtractionResult();
            new ProjectAnalysisRunner()
                    .run(
                            registries.get(c.module() + "@" + c.scope()),
                            frameworks.get(c.module() + "@" + c.scope()),
                            resourceInventory(c),
                            view.snapshot(c, true),
                            declarations,
                            new ExtensionReport());
            for (var node : declarations.nodes) {
                node.module = c.module();
                node.scope = c.scope().name();
            }
            view.add(declarations);
        }
        for (var c : project.contexts)
            if (dirty.contains(c.world())) {
                check();
                for (var source : c.sources())
                    if (c.publish().contains(source.file()) && project.publishes(source.file())) {
                        if (!source.validEncoding()) {
                            projection.diagnostic(
                                    new IndexDiagnostic(
                                            "warning",
                                            "SOURCE_ENCODING_INVALID",
                                            "JAVAC_PARSE",
                                            source.path(),
                                            source.module(),
                                            source.scope(),
                                            null,
                                            1,
                                            "Original captured bytes are not valid UTF-8"));
                        }
                    }
                if (!c.resources().isEmpty()) {
                    String key = c.module() + "@" + c.scope();
                    observer.phase("resources", key, true);
                    var report = new ExtensionReport();
                    var result = new ExtractionResult();
                    List<ProjectResource> inventory = resourceInventory(c);
                    var resourceView = view.snapshot(c, true);
                    new ProjectAnalysisRunner()
                            .run(
                                    registries.get(key),
                                    frameworks.get(key),
                                    inventory,
                                    resourceView,
                                    result,
                                    report);
                    observer.extraction(result);
                    projection.extraction(result, c);
                    report.diagnostics().forEach(projection::diagnostic);
                    observer.phase("resources", key, false);
                }
                projection.provenance(IDENTITY);
            }
    }

    private AnalysisResult sealAnalysis(Plan plan) throws IOException {
        var projection = plan.projection();
        var project = plan.project();
        boolean full = request.full();
        projection.finish();
        project.verify();
        observer.projectionStatistics(projection.statistics());
        check();
        return new AnalysisResult(
                full ? "full" : "delta", full ? List.of() : projection.owners(), output);
    }

    private static List<ProjectResource> resourceInventory(CapturedProject.Context context) {
        return context.resources().stream()
                .filter(
                        s ->
                                s.path().endsWith(".xml")
                                        && SpringBeanParser.isSpringBeansFile(s.file()))
                .map(s -> new ProjectResource(s.file(), s.path(), "spring-xml"))
                .toList();
    }

    private static JavaModelAnalyzerRegistry registry(
            CapturedProject project, CapturedProject.Context c, JavaFrameworkContext f) {
        Path root = project.root;
        Map<Path, String> configurations = new TreeMap<>();
        for (var resource : c.resources())
            if (resource.path().equals("lombok.config")
                    || resource.path().endsWith("/lombok.config")) {
                try {
                    configurations.put(resource.file(), Files.readString(resource.file()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        List<JavaFrameworkPlugin> plugins = new ArrayList<>();
        if (c.options().spring() || c.options().springXml() || c.options().sofa())
            plugins.add(new CapturedSpringPlugin(c.options().spring(), c.options().springXml()));
        if (c.options().sofa()) plugins.add(new SofaFrameworkPlugin());
        return new com.anatomist.application.FrameworkPlugins(plugins)
                .prepareDetached(f, configurations);
    }

    private static final class ProjectView {
        private final CapturedProject project;
        private final Map<CapturedProject.Context, Set<String>> visibility =
                new IdentityHashMap<>();
        private final Set<String> allSources = new HashSet<>();

        ProjectView(CapturedProject project) {
            this.project = project;
            for (var candidate : project.contexts) {
                for (var source : candidate.sources())
                    allSources.add(source.module() + "\n" + source.scope() + "\n" + source.path());
                for (var source : candidate.resources())
                    allSources.add(
                            candidate.module() + "\n" + candidate.scope() + "\n" + source.path());
            }
            for (var context : project.contexts) {
                Set<String> visible = new HashSet<>();
                for (var source : context.sources())
                    visible.add(source.module() + "\n" + source.scope() + "\n" + source.path());
                for (var candidate : project.contexts) {
                    boolean own =
                            candidate.module().equals(context.module())
                                    && (candidate.scope() == context.scope()
                                            || candidate.scope() == SourceScope.MAIN);
                    boolean dependency =
                            context.dependencies().contains(candidate.module())
                                    && candidate.scope() == SourceScope.MAIN;
                    if (own || dependency)
                        for (var source : candidate.resources())
                            visible.add(
                                    candidate.module()
                                            + "\n"
                                            + candidate.scope()
                                            + "\n"
                                            + source.path());
                }
                visibility.put(context, Set.copyOf(visible));
            }
        }

        final Map<String, SymbolFact> symbols = new LinkedHashMap<>();
        final Set<TypeRelationFact> relations = new LinkedHashSet<>();
        final Map<String, BeanRefTarget> beans = new LinkedHashMap<>();
        final Set<String> ambiguousBeans = new HashSet<>();

        void symbol(SymbolFact symbol) {
            String key =
                    symbol.module()
                            + "\n"
                            + symbol.scope()
                            + "\n"
                            + symbol.sourceFile()
                            + "\n"
                            + symbol.symbolId();
            // Keep the same last-observation order as the previous snapshot catalogue.
            symbols.remove(key);
            symbols.put(key, symbol);
        }

        void bean(String name, String id, Map<String, Object> metadata) {
            String type =
                    Objects.toString(
                            metadata.get("productClass"),
                            Objects.toString(
                                    metadata.get("className"),
                                    Objects.toString(metadata.get("returnType"), "")));
            String key = "bean:" + name;
            if (ambiguousBeans.contains(key)) return;
            BeanRefTarget target = new BeanRefTarget(id, type.isEmpty() ? null : type);
            BeanRefTarget previous = beans.putIfAbsent(key, target);
            if (previous != null && !previous.equals(target)) {
                beans.remove(key);
                ambiguousBeans.add(key);
            }
            for (Object alias : CapturedProject.list(metadata.get("aliases"))) {
                String aliasKey = "bean:" + alias;
                if (ambiguousBeans.contains(aliasKey)) continue;
                previous = beans.putIfAbsent(aliasKey, target);
                if (previous != null && !previous.equals(target)) {
                    beans.remove(aliasKey);
                    ambiguousBeans.add(aliasKey);
                }
            }
        }

        void add(ExtractionResult result) {
            for (Node n : result.nodes) {
                String metadata = n.metadata;
                if ("BEAN".equals(n.kind)) {
                    var fields =
                            new TreeMap<>(
                                    Json.map(
                                            Json.parse(
                                                    metadata.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8))));
                    fields.put("bean_name", n.label);
                    metadata = Json.write(fields);
                }
                symbol(
                        new SymbolFact(
                                n.id, n.id, n.kind, n.module, n.scope, n.sourceFile, metadata));
            }
            for (Edge e : result.edges)
                if (Set.of("INHERITS", "IMPLEMENTS").contains(e.relation))
                    relations.add(
                            new TypeRelationFact(
                                    NodeKeyFactory.symbolId(e.sourceId),
                                    e.isExternal
                                            ? e.externalTargetFqn
                                            : NodeKeyFactory.symbolId(e.targetId),
                                    e.relation));
        }

        ProjectFactView snapshot(CapturedProject.Context context) {
            return snapshot(context, false);
        }

        ProjectFactView snapshot(CapturedProject.Context context, boolean projectResources) {
            Set<String> beanVisible = Objects.requireNonNull(visibility.get(context));
            Set<String> visible = projectResources ? allSources : beanVisible;
            beans.clear();
            ambiguousBeans.clear();
            Map<String, SymbolFact> catalogue = new LinkedHashMap<>();
            for (var symbol : symbols.values()) {
                if (symbol.module() == null
                        || symbol.scope() == null
                        || !visible.contains(
                                symbol.module()
                                        + "\n"
                                        + symbol.scope()
                                        + "\n"
                                        + symbol.sourceFile())) continue;
                String key =
                        NodeKeyFactory.key(
                                new SourceIdentity(
                                        symbol.module(), SourceScope.valueOf(symbol.scope())),
                                symbol.symbolId());
                catalogue.put(
                        key,
                        new SymbolFact(
                                key,
                                symbol.symbolId(),
                                symbol.kind(),
                                symbol.module(),
                                symbol.scope(),
                                symbol.sourceFile(),
                                symbol.metadata()));
            }
            for (var symbol : catalogue.values()) {
                if (!"BEAN".equals(symbol.kind())) continue;
                // XML callable lookup uses the merged project declarations. Bean
                // names retain the consumer's module/MAIN/TEST resource visibility.
                if (!beanVisible.contains(
                        symbol.module() + "\n" + symbol.scope() + "\n" + symbol.sourceFile()))
                    continue;
                Map<String, Object> metadata =
                        Json.map(
                                Json.parse(
                                        symbol.metadata()
                                                .getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)));
                String name =
                        Objects.toString(
                                metadata.get("bean_name"),
                                Objects.toString(metadata.get("beanName"), ""));
                if (name.isEmpty() && symbol.symbolId().startsWith("bean:"))
                    name = symbol.symbolId().substring(5);
                // Project resource analyzers compare raw bean IDs with the
                // same inventory they parse. A module-qualified catalogue ID
                // makes that one bean look like two conflicting registrations.
                if (!name.isEmpty()) bean(name, symbol.symbolId(), metadata);
            }
            Set<String> known = new HashSet<>(catalogue.keySet());
            catalogue.values().forEach(s -> known.add(s.symbolId()));
            List<TypeRelationFact> hierarchy = new ArrayList<>();
            for (var relation : relations)
                if (known.contains(relation.sourceType())) hierarchy.add(relation);
            return new DefaultProjectFactView(
                    known, beans, List.copyOf(catalogue.values()), List.copyOf(hierarchy));
        }
    }

    public void close() throws IOException {
        IOException failure = null;
        for (var archive : new GraphArchive[] {output})
            if (archive != null)
                try {
                    archive.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
        try (var files = Files.walk(taskRoot)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }
}
