package dev.codeana.adapter;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.framework.lombok.*;
import com.anatomist.framework.sofa.SofaFrameworkPlugin;
import com.anatomist.framework.spring.SpringFrameworkPlugin;
import com.anatomist.model.*;
import dev.codeana.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Task lifetime at the native boundary. All semantics belong to the migrated core. */
public final class AnalysisSession implements AutoCloseable {
    public static final String IDENTITY = "anatomist-dff2e395-codeana-v5";
    private final Map<String, Object> request;
    private final AnalysisLimits limits;
    private final Path taskRoot;
    private FactArchive baseline, output;
    private volatile Thread executing;
    private volatile boolean cancelled;
    private final AnalysisObserver observer;

    public AnalysisSession(Map<String, Object> request) throws IOException {
        this(request, AnalysisObserver.NONE);
    }

    public AnalysisSession(Map<String, Object> request, AnalysisObserver observer)
            throws IOException {
        this.observer = Objects.requireNonNull(observer);
        limits = new AnalysisLimits(request);
        this.request = new LinkedHashMap<>(request);
        this.request.put("diagnostic_scratch", Json.string(request, "scratch"));
        taskRoot =
                Files.createTempDirectory(
                        Path.of(Json.string(request, "scratch")), "anatomist-task-");
        this.request.put("scratch", taskRoot.toString());
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
        executing = Thread.currentThread();
        check();
        if (Json.number(request, "version") != 8)
            throw new IllegalArgumentException("ABI_REQUEST_VERSION");
        observer.phase("capture", "", true);
        CapturedProject project = new CapturedProject(request);
        observer.phase("capture", "", false);
        Map<String, Object> archive =
                request.get("baseline_archive") instanceof Map<?, ?>
                        ? Json.map(request.get("baseline_archive"))
                        : null;
        if (archive != null) {
            baseline =
                    FactArchive.open(
                            Path.of(Json.string(archive, "path")),
                            limits.bytes,
                            limits.facts,
                            Json.string(archive, "sha256"));
            if (baseline.size() != Json.number(archive, "count")
                    || baseline.bytes() != Json.number(archive, "bytes"))
                throw new IOException("BASELINE_ARCHIVE_IDENTITY");
        }
        boolean full = !Boolean.FALSE.equals(request.get("full"));
        Map<String, String> previous = new HashMap<>();
        if (baseline != null)
            for (Object row : baseline) {
                Map<String, Object> f = Json.map(row), a = Json.map(f.get("attributes"));
                if ("provenance".equals(f.get("kind")) && IDENTITY.equals(a.get("adapter")))
                    previous.put(
                            Json.string(Json.map(f.get("owner")), "world"),
                            Json.string(a, "context_digest"));
            }
        Set<String> dirty = new HashSet<>(), active = new HashSet<>();
        for (var c : project.contexts) {
            active.add(c.world());
            c.resources().forEach(resource -> active.add(resource.world()));
            if (full || !c.digest().equals(previous.get(c.world()))) dirty.add(c.world());
        }
        // XML and cross-file framework resolution consume the merged project declaration view.
        if (!dirty.isEmpty())
            for (var c : project.contexts) if (!c.resources().isEmpty()) dirty.add(c.world());
        Set<String> replaced = new HashSet<>(dirty);
        for (var c : project.contexts)
            if (dirty.contains(c.world()))
                c.resources().forEach(resource -> replaced.add(resource.world()));
        output =
                new FactArchive(
                        Path.of(Json.string(request, "scratch")).resolve("anatomist-facts.caf"),
                        limits.bytes,
                        limits.facts,
                        !project.deferFactDedup);
        FactProjection projection = new FactProjection(project, output);
        ProjectView view = new ProjectView();
        if (!full && baseline != null)
            for (Object row : baseline) {
                Map<String, Object> f = Json.map(row), owner = Json.map(f.get("owner"));
                String world = Json.string(owner, "world");
                if (replaced.contains(world) || !active.contains(world))
                    projection.retainOwner(owner);
                else {
                    view.retain(f);
                    projection.retain(f);
                }
            }
        if (dirty.isEmpty())
            return Json.object(
                    "version",
                    8,
                    "mode",
                    full ? "full" : "delta",
                    "replacements",
                    projection.owners(),
                    "facts",
                    output);
        Path home = Path.of(Json.string(request, "java_home"));
        Path cache =
                request.get("cache_root") instanceof String p
                        ? Path.of(p)
                        : Files.createDirectory(
                                Path.of(Json.string(request, "scratch"))
                                        .resolve("anatomist-cache"));
        // Rust owns this task copy; only complete signature entries are committed after publish
        // Ack.
        cache = Files.createDirectories(cache);
        int threads =
                request.get("parallelism") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        int heap =
                request.get("compiler_heap_bytes") instanceof Number n && n.longValue() > 0
                        ? (int) (n.longValue() / (1 << 20))
                        : 512;
        JavacOptions options =
                new JavacOptions(home, true, true, false, false, cache, threads, heap);
        var toolchain = JavacToolchain.resolve(options, 8, null);
        var backend =
                new JavacBackend(project.root, toolchain, List.of(), project.roots(), options);
        var timings = observer.timings();
        backend.setTimings(timings);
        Map<String, JavacBackend.ModelBudget> modelBudgets = new HashMap<>();
        for (var c : project.contexts)
            modelBudgets.put(
                    c.module() + "@" + c.scope(),
                    new JavacBackend.ModelBudget(
                            c.options().get("model_archive_bytes") instanceof Number n
                                    ? n.longValue()
                                    : 64L << 20,
                            c.options().get("model_record_limit") instanceof Number n
                                    ? n.longValue()
                                    : 100000));
        backend.setModelBudgets(modelBudgets);
        backend.setSourceSignatures(!Boolean.FALSE.equals(request.get("signature_cache")));
        backend.setBatchTimeout(
                java.time.Duration.ofSeconds(
                        request.get("compiler_timeout_seconds") instanceof Number n
                                ? n.longValue()
                                : 120));
        backend.setCapturedInputs(
                project.compilerContexts(dirty), project.sourcePlan(dirty), project.moduleInputs());
        Map<String, CapturedProject.Context> contexts = new HashMap<>();
        Map<String, JavaFrameworkContext> frameworks = new HashMap<>();
        Map<String, JavaModelAnalyzerRegistry> registries = new HashMap<>();
        for (var c : project.contexts) {
            String key = c.module() + "@" + c.scope();
            contexts.put(key, c);
            ProjectConfig config = new ProjectConfig();
            config.setJavaVersion(8);
            config.setLombokMode("model".equals(c.options().get("lombok")) ? "ast" : "off");
            config.setLombokStrict(Boolean.TRUE.equals(c.options().get("lombok_strict")));
            config.setSpringXml(Boolean.TRUE.equals(c.options().get("spring_xml")));
            JavaFrameworkContext f =
                    new JavaFrameworkContext(
                            project.root,
                            c.sources().stream().map(CapturedProject.Source::file).toList(),
                            config,
                            config.springXml());
            frameworks.put(key, f);
            registries.put(key, registry(project, c, f));
        }
        backend.setDeclarationOverlay(
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
                });
        Set<String> partial = new HashSet<>();
        observer.phase("backend_total", "", true);
        try {
            backend.analyzeFiles(
                    project.sourcePlan(dirty).indexedFiles(),
                    analysis -> {
                        check();
                        observer.model(analysis);
                        String key = analysis.module() + "@" + analysis.scope();
                        var c = contexts.get(key);
                        observer.phase("inventory", key, true);
                        projection.context(c);
                        projection.inventory(analysis).forEach(view::symbol);
                        observer.phase("inventory", key, false);
                        for (var diagnostic : analysis.diagnostics())
                            if (diagnostic.path() != null && diagnostic.kind().equals("ERROR"))
                                partial.add(project.source(diagnostic.path()).path());
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
                                                observer.phase("unit_projection", key, true);
                                                view.add(unit.facts());
                                                projection.unit(unit);
                                                observer.phase("unit_projection", key, false);
                                                for (var diagnostic : unit.diagnostics())
                                                    if (diagnostic.sourceFile() != null)
                                                        partial.add(diagnostic.sourceFile());
                                            });
                        } finally {
                            projection.closeContext();
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
        for (var c : project.contexts)
            if (dirty.contains(c.world())) {
                check();
                projection.context(c);
                for (var source : c.sources())
                    if (c.publish().contains(source.file())) {
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
                            partial.add(source.path());
                        }
                        projection.coverage(source, partial.contains(source.path()));
                    }
                if (!c.resources().isEmpty()) {
                    String key = c.module() + "@" + c.scope();
                    observer.phase("resources", key, true);
                    var report = new ExtensionReport();
                    var result = new ExtractionResult();
                    List<ProjectResource> inventory =
                            c.resources().stream()
                                    .filter(
                                            s ->
                                                    s.path().endsWith(".xml")
                                                            && SpringBeanParser.isSpringBeansFile(
                                                                    s.file()))
                                    .map(s -> new ProjectResource(s.file(), s.path(), "spring-xml"))
                                    .toList();
                    new ProjectAnalysisRunner()
                            .run(
                                    registries.get(key),
                                    frameworks.get(key),
                                    inventory,
                                    view.snapshot(c),
                                    result,
                                    report);
                    projection.extraction(result);
                    observer.extraction(result);
                    report.diagnostics().forEach(projection::diagnostic);
                    for (var source : c.resources())
                        projection.coverage(
                                source,
                                report.diagnostics().stream()
                                        .anyMatch(d -> source.path().equals(d.sourceFile())));
                    observer.phase("resources", key, false);
                }
                projection.provenance(IDENTITY);
            }
        project.verify();
        observer.projectionStatistics(projection.statistics());
        check();
        return Json.object(
                "version",
                8,
                "mode",
                full ? "full" : "delta",
                "replacements",
                full ? List.of() : projection.owners(),
                "facts",
                output);
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
        List<JavaModelExtension> declarations = new ArrayList<>();
        List<JavaModelAnalyzer> analyzers = new ArrayList<>();
        List<ProjectResourceProvider> providers = new ArrayList<>();
        List<ProjectResourceAnalyzer> resources = new ArrayList<>();
        List<JavaCallSiteEvidenceProvider> evidence = new ArrayList<>();
        if ("model".equals(c.options().get("lombok"))) {
            declarations.add(
                    new LombokJavaModelExtension(root, f.config().lombokStrict(), configurations));
            evidence.add(new LombokJavaCallEvidence(root, configurations));
        }
        List<JavaFrameworkPlugin> plugins = new ArrayList<>();
        if (Boolean.TRUE.equals(c.options().get("spring"))
                || Boolean.TRUE.equals(c.options().get("spring_xml"))
                || Boolean.TRUE.equals(c.options().get("sofa")))
            plugins.add(new SpringFrameworkPlugin());
        if (Boolean.TRUE.equals(c.options().get("sofa"))) plugins.add(new SofaFrameworkPlugin());
        for (var plugin : plugins) {
            var r = plugin.detachedAnalyzers(f);
            declarations.addAll(r.declarations());
            analyzers.addAll(r.analyzers());
            providers.addAll(r.resourceProviders());
            resources.addAll(r.resourceAnalyzers());
            evidence.addAll(r.callSiteEvidenceProviders());
        }
        return new JavaModelAnalyzerRegistry(
                declarations, analyzers, providers, resources, List.of(IDENTITY), evidence);
    }

    private static final class ProjectView {
        final Map<String, SymbolFact> symbols = new LinkedHashMap<>();
        final Set<TypeRelationFact> relations = new LinkedHashSet<>();
        final Map<String, BeanRefTarget> beans = new LinkedHashMap<>();
        final Set<String> ambiguousBeans = new HashSet<>();
        final Map<String, String> rawIds = new HashMap<>();
        final List<Map<String, Object>> retainedRelations = new ArrayList<>();

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
                symbol(
                        new SymbolFact(
                                n.id, n.id, n.kind, n.module, n.scope, n.sourceFile, n.metadata));
            }
            for (Edge e : result.edges)
                if (Set.of("INHERITS", "IMPLEMENTS").contains(e.relation))
                    relations.add(
                            new TypeRelationFact(
                                    e.sourceId,
                                    e.isExternal ? e.externalTargetFqn : e.targetId,
                                    e.relation));
        }

        void retain(Map<String, Object> fact) {
            Map<String, Object> a = Json.map(fact.get("attributes"));
            if (Set.of("extends", "implements")
                    .contains(Objects.toString(fact.get("relation"), ""))) {
                retainedRelations.add(fact);
                return;
            }
            if (!"declaration".equals(fact.get("kind"))
                    && !"java.bean".equals(fact.get("relation"))) return;
            String id = Objects.toString(a.get("upstream_id"), Json.string(fact, "signature"));
            rawIds.put(Json.string(fact, "id"), id);
            if ("java.bean".equals(fact.get("relation"))) a = new TreeMap<>(a);
            if ("java.bean".equals(fact.get("relation"))) {
                a.put("bean_name", Json.string(fact, "name"));
                a.put("upstream_kind", "BEAN");
            }
            symbol(
                    new SymbolFact(
                            id,
                            id,
                            Objects.toString(
                                    a.get("upstream_kind"), Objects.toString(a.get("kind"), "")),
                            Objects.toString(a.get("module"), ""),
                            Objects.toString(a.get("scope"), ""),
                            Json.string(fact, "source"),
                            Json.write(a)));
        }

        ProjectFactView snapshot(CapturedProject.Context context) {
            Set<String> visible = new HashSet<>();
            for (var source : context.sources())
                visible.add(source.module() + "\n" + source.scope() + "\n" + source.path());
            for (var source : context.resources())
                visible.add(source.module() + "\n" + source.scope() + "\n" + source.path());
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
                if (!name.isEmpty()) bean(name, symbol.id(), metadata);
            }
            Set<String> known = new HashSet<>(catalogue.keySet());
            catalogue.values().forEach(s -> known.add(s.symbolId()));
            List<TypeRelationFact> hierarchy = new ArrayList<>();
            for (var relation : relations)
                if (known.contains(relation.sourceType())) hierarchy.add(relation);
            for (var fact : retainedRelations) {
                String source = rawIds.get(Json.string(fact, "parent"));
                String target = rawIds.get(Json.string(fact, "target"));
                if (source != null && target != null && known.contains(source))
                    hierarchy.add(
                            new TypeRelationFact(
                                    source,
                                    target,
                                    "extends".equals(fact.get("relation"))
                                            ? "INHERITS"
                                            : "IMPLEMENTS"));
            }
            return new DefaultProjectFactView(
                    known, beans, List.copyOf(catalogue.values()), List.copyOf(hierarchy));
        }
    }

    public void close() throws IOException {
        IOException failure = null;
        for (var archive : new FactArchive[] {output, baseline})
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
