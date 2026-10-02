package dev.codeana.analysis;

import dev.codeana.analysis.extension.*;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.RecordArchive;

import java.util.*;
import java.util.concurrent.*;

/**
 * Conservative cold incremental planner. A context digest covers all semantic inputs, including
 * unchanged upstream sources. Thus additions and failed lookups invalidate their consumers too.
 */
public final class JavaAnalysis {
    private JavaAnalysis() {}

    private static final class ContextResult {
        final List<Object> facts;
        RecordArchive coreArchive;
        final Set<String> analyzedSources;
        final boolean reduced;
        final int compilerSources;
        Map<String, Object> extensionReport = Map.of();
        ExtensionRunner extensions;
        CompilerSession.StagedModel projectModel;
        boolean prerequisite;

        ContextResult(
                List<Object> facts,
                Set<String> analyzedSources,
                boolean reduced,
                int compilerSources) {
            this.facts = facts;
            this.analyzedSources = analyzedSources;
            this.reduced = reduced;
            this.compilerSources = compilerSources;
        }
    }

    private static String ownerKey(Map<String, Object> owner) {
        return Json.write(
                List.of(
                        owner.get("world"),
                        owner.get("producer"),
                        owner.get("kind"),
                        owner.get("id")));
    }

    /** Index record positions; materialize only the owner being compared. */
    private static final class OwnerGroups {
        private final List<Object> records;
        private final Map<String, List<Integer>> offsets = new TreeMap<>();

        OwnerGroups(List<Object> records, Set<String> candidates) {
            this.records = records;
            for (int i = 0; i < records.size(); i++) {
                Map<String, Object> fact = Json.map(records.get(i));
                String key = ownerKey(Json.map(fact.get("owner")));
                if (candidates.contains(key))
                    offsets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
            }
        }

        List<Map<String, Object>> get(String key) {
            List<Integer> positions = offsets.get(key);
            if (positions == null) return null;
            List<Map<String, Object>> group = new ArrayList<>(positions.size());
            for (int position : positions) group.add(Json.map(records.get(position)));
            group.sort(Comparator.comparing(f -> Json.string(f, "id")));
            return group;
        }
    }

    private static ContextResult metadata(ContextResult result) {
        ContextResult metadata =
                new ContextResult(
                        List.of(), result.analyzedSources, result.reduced, result.compilerSources);
        metadata.extensionReport = result.extensionReport;
        metadata.extensions = result.extensions;
        metadata.projectModel = result.projectModel;
        metadata.prerequisite = result.prerequisite;
        return metadata;
    }

    private static boolean sameGroup(
            List<Map<String, Object>> previous, List<Map<String, Object>> current) {
        if (previous == null || current == null) return previous == current;
        if (previous.size() != current.size()) return false;
        for (int i = 0; i < previous.size(); i++) {
            if (!equivalent(previous.get(i), current.get(i))) return false;
        }
        return true;
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number && right instanceof Number)
            return new java.math.BigDecimal(left.toString())
                            .compareTo(new java.math.BigDecimal(right.toString()))
                    == 0;
        if (left instanceof Map && right instanceof Map) {
            Map<?, ?> a = (Map<?, ?>) left, b = (Map<?, ?>) right;
            if (!a.keySet().equals(b.keySet())) return false;
            for (Object key : a.keySet()) if (!equivalent(a.get(key), b.get(key))) return false;
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> a = (List<?>) left, b = (List<?>) right;
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equivalent(a.get(i), b.get(i))) return false;
            return true;
        }
        return Objects.equals(left, right);
    }

    public static Map<String, Object> run(Map<String, Object> request, CompilerSession session)
            throws Exception {
        return run(request, session, BuiltinExtensions.REGISTRY);
    }

    public static Map<String, Object> run(
            Map<String, Object> request, CompilerSession session, ExtensionRegistry registry)
            throws Exception {
        boolean full = !Boolean.FALSE.equals(request.get("full"));
        List<Object> baseline =
                request.containsKey("baseline") ? Json.list(request.get("baseline")) : List.of();
        List<Object> contexts = new ArrayList<>();
        ContextInputs inputView = new ContextInputs(request);
        for (Object raw : Json.list(request.get("contexts"))) {
            Map<String, Object> context = inputView.resolve(Json.map(raw));
            ModelArchive.byteLimit(context);
            context.put("fact_limit", session.factLimit());
            context.put("target_java_home", session.javaHome().toString());
            context.put("extension_fingerprint", registry.fingerprint(context));
            contexts.add(context);
        }
        Map<String, Object> extensionInputs = new TreeMap<>();
        for (Object raw : contexts) {
            Map<String, Object> ctx = Json.map(raw);
            extensionInputs.put(
                    Json.string(ctx, "world"),
                    List.of(ctx.get("digest"), ctx.get("extension_fingerprint")));
        }
        for (Object raw : contexts) {
            Map<String, Object> ctx = Json.map(raw), visible = new TreeMap<>();
            String ownWorld = Json.string(ctx, "world");
            visible.put(ownWorld, ctx.get("extension_fingerprint"));
            for (Object source : Json.list(ctx.get("sources"))) {
                String world = Json.string(Json.map(source), "world");
                if (!world.equals(ownWorld) && extensionInputs.containsKey(world))
                    visible.put(world, extensionInputs.get(world));
            }
            ctx.put(
                    "extension_fingerprint",
                    Json.sha(
                            Json.write(visible).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        Set<String> active = new HashSet<>(), dirty = new HashSet<>();
        for (Object raw : contexts) {
            Map<String, Object> ctx = Json.map(raw);
            String world = Json.string(ctx, "world");
            active.add(world);
            boolean unchanged = !full;
            boolean seenContext = false;
            Set<String> current = new TreeSet<>(), previous = new TreeSet<>();
            for (Object source : Json.list(ctx.get("sources")))
                if (Boolean.TRUE.equals(Json.map(source).get("publish")))
                    current.add(Json.string(Json.map(source), "path"));
            for (Object old : baseline) {
                Map<String, Object> f = Json.map(old);
                if (f.get("kind").equals("coverage")
                        && Json.map(f.get("owner")).get("world").equals(world)
                        && Json.map(f.get("owner"))
                                .get("producer")
                                .equals(FactExtractor.PRODUCER)) {
                    previous.add(Json.string(f, "source"));
                }
                if (f.get("kind").equals("provenance")
                        && Json.string(f, "source").isEmpty()
                        && Json.map(f.get("owner")).get("world").equals(world)
                        && Json.map(f.get("owner")).get("producer").equals(FactExtractor.PRODUCER)
                        && Objects.equals(
                                Json.map(f.get("attributes")).get("context"), ctx.get("context"))) {
                    seenContext = true;
                    if (!Objects.equals(
                            Json.map(f.get("attributes")).get("context_digest"), ctx.get("digest")))
                        unchanged = false;
                    if (!Objects.equals(
                            Json.map(f.get("attributes")).get("extension_fingerprint"),
                            ctx.get("extension_fingerprint"))) unchanged = false;
                }
            }
            if (!seenContext || !current.equals(previous)) unchanged = false;
            if (!unchanged) dirty.add(world);
        }
        for (Object old : baseline) {
            Map<String, Object> owner = Json.map(Json.map(old).get("owner"));
            if (!active.contains(Json.string(owner, "world"))
                    && Json.string(owner, "producer").equals(FactExtractor.PRODUCER))
                dirty.add(Json.string(owner, "world"));
        }
        List<Map<String, Object>> work = new ArrayList<>();
        for (Object raw : contexts) {
            session.check();
            Map<String, Object> ctx = Json.map(raw);
            if (dirty.contains(Json.string(ctx, "world"))) work.add(ctx);
        }
        int parallelism =
                request.containsKey("parallelism") ? (int) Json.number(request, "parallelism") : 1;
        if (parallelism < 1 || parallelism > 8)
            throw new IllegalArgumentException("PARALLELISM_BUDGET");
        RecordArchive facts = session.newFactArchive();
        Map<String, ContextResult> analyzed = new HashMap<>();
        SignatureCache cache = new SignatureCache(request);
        ModuleSignatures modules = new ModuleSignatures(request, contexts, session);
        if (parallelism == 1) work = modules.order(work);
        try {
            if (parallelism == 1 || work.size() < 2) {
                for (Map<String, Object> ctx : work) {
                    ContextResult result =
                            analyzeContext(ctx, session, baseline, cache, full, registry, modules);
                    analyzed.put(Json.string(ctx, "world"), metadata(result));
                    try {
                        session.mark(Json.string(ctx, "context"), "task_accumulate");
                        facts.addAll(result.facts);
                    } finally {
                        session.releaseFactArchive(result.coreArchive);
                    }
                }
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(parallelism);
                List<Future<ContextResult>> results = new ArrayList<>();
                try {
                    // At most parallelism completed models can wait for consumption.
                    for (List<Map<String, Object>> batch : modules.batches(work, parallelism)) {
                        results.clear();
                        for (Map<String, Object> ctx : batch) {
                            results.add(
                                    pool.submit(
                                            () ->
                                                    analyzeContext(
                                                            ctx, session, baseline, cache, full,
                                                            registry, modules)));
                        }
                        for (int i = 0; i < results.size(); i++) {
                            ContextResult result = results.get(i).get();
                            analyzed.put(Json.string(batch.get(i), "world"), metadata(result));
                            try {
                                facts.addAll(result.facts);
                            } finally {
                                session.releaseFactArchive(result.coreArchive);
                            }
                            results.set(i, null);
                        }
                    }
                } catch (Exception e) {
                    for (Future<?> result : results) if (result != null) result.cancel(true);
                    session.cancel();
                    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new IllegalStateException(cause);
                } finally {
                    pool.shutdownNow();
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("PARALLEL_COMPILER_CLEANUP_TIMEOUT");
                }
            }
            // Project/resource stages see all successful Java/call outputs, including unchanged
            // baseline worlds. Context models are reloaded one at a time from task-owned archives.
            if (analyzed.values().stream()
                    .anyMatch(
                            result ->
                                    !result.prerequisite && result.extensions.hasProjectStages())) {
                session.mark("global", "project_stage_prepare");
                RecordArchive staged = session.newFactArchive();
                for (Object raw : baseline) {
                    Map<String, Object> fact = Json.map(raw),
                            attrs = Json.map(fact.get("attributes"));
                    if (!dirty.contains(Json.string(Json.map(fact.get("owner")), "world"))
                            && work.stream()
                                    .noneMatch(
                                            ctx ->
                                                    Objects.equals(
                                                            ctx.get("context"),
                                                            attrs.get("context")))) staged.add(raw);
                }
                staged.addAll(facts);
                ProjectFacts projectFacts = new ProjectFacts(staged);
                for (Map<String, Object> ctx : work) {
                    session.check();
                    ContextResult result = analyzed.get(Json.string(ctx, "world"));
                    if (result.prerequisite || !result.extensions.hasProjectStages()) continue;
                    session.mark(Json.string(ctx, "context"), "project_extensions");
                    try (ModelArchive.OpenModel model = result.projectModel.open()) {
                        List<Object> project =
                                result.extensions.analyzeProject(
                                        model.model(), projectFacts.visible(ctx));
                        facts.addAll(project);
                        staged.addAll(project);
                    }
                    result.extensionReport = result.extensions.report();
                }
                staged.seal();
            }
            session.mark("global", "result_finalize");
            facts.seal();
            if (facts.size() > session.factLimit()) throw new IllegalStateException("FACT_BUDGET");
            Map<String, Object> replacements = new TreeMap<>();
            if (!full) {
                // A captured source change requires every old owner to be replaced, even if an
                // extension emits identical facts. Hash equality is independent of semantic
                // equality.
                Map<String, Object> sourceHashes = new HashMap<>();
                for (Object raw : contexts) {
                    for (Object input : Json.list(Json.map(raw).get("sources"))) {
                        Map<String, Object> source = Json.map(input);
                        if (Boolean.TRUE.equals(source.get("publish")))
                            sourceHashes.put(Json.string(source, "path"), source.get("hash"));
                    }
                }
                Set<String> changedSources = new HashSet<>();
                for (Object raw : baseline) {
                    Map<String, Object> fact = Json.map(raw);
                    if ("coverage".equals(fact.get("kind"))
                            && FactExtractor.PRODUCER.equals(
                                    Json.map(fact.get("owner")).get("producer"))
                            && !Objects.equals(
                                    Json.map(fact.get("attributes")).get("source_hash"),
                                    sourceHashes.get(Json.string(fact, "source"))))
                        changedSources.add(Json.string(fact, "source"));
                }
                Map<String, Object> candidates = new TreeMap<>();
                Set<String> requiredReplacements = new HashSet<>();
                for (Object old : baseline) {
                    Map<String, Object> oldFact = Json.map(old);
                    Map<String, Object> owner = Json.map(oldFact.get("owner"));
                    Map<String, Object> attrs = Json.map(Json.map(old).get("attributes"));
                    boolean affected = dirty.contains(Json.string(owner, "world"));
                    if (attrs.containsKey("context")) {
                        Map<String, Object> ownerContext =
                                contexts.stream()
                                        .map(Json::map)
                                        .filter(
                                                ctx ->
                                                        Objects.equals(
                                                                ctx.get("context"),
                                                                attrs.get("context")))
                                        .findFirst()
                                        .orElse(null);
                        affected |=
                                ownerContext == null
                                        || dirty.contains(Json.string(ownerContext, "world"));
                    }
                    ContextResult contextResult = analyzed.get(Json.string(owner, "world"));
                    if (affected
                            && contextResult != null
                            && contextResult.reduced
                            && "source".equals(owner.get("kind"))
                            && !contextResult.analyzedSources.contains(
                                    Json.string(owner, "id"))) affected = false;
                    if (changedSources.contains(Json.string(oldFact, "source"))) {
                        affected = true;
                        requiredReplacements.add(ownerKey(owner));
                    }
                    // Module NoCode only reduces javac inputs; project resource stages still
                    // rerun. Resource owners must also be replaced when captured bytes change
                    // without changing semantic output. Resource producers do not all retain
                    // byte hashes, so conservatively replace each affected resource owner.
                    if (affected && "resource".equals(owner.get("kind")))
                        requiredReplacements.add(ownerKey(owner));
                    if (affected) candidates.put(ownerKey(owner), owner);
                }
                for (Object raw : facts) {
                    Map<String, Object> owner = Json.map(Json.map(raw).get("owner"));
                    candidates.put(ownerKey(owner), owner);
                }
                OwnerGroups oldGroups = new OwnerGroups(baseline, candidates.keySet());
                OwnerGroups newGroups = new OwnerGroups(facts, candidates.keySet());
                RecordArchive changed = session.newFactArchive();
                for (Map.Entry<String, Object> candidate : candidates.entrySet()) {
                    String key = candidate.getKey();
                    List<Map<String, Object>> current = newGroups.get(key);
                    if (!requiredReplacements.contains(key)
                            && sameGroup(oldGroups.get(key), current)) continue;
                    replacements.put(key, candidate.getValue());
                    if (current != null) changed.addAll(current);
                }
                changed.seal();
                facts = changed;
            }
            return Json.object(
                    "version",
                    5,
                    "mode",
                    full ? "full" : "delta",
                    "facts",
                    facts,
                    "replacements",
                    new ArrayList<>(replacements.values()),
                    "analyzed_contexts",
                    dirty.size(),
                    "compiler_sources",
                    analyzed.values().stream().mapToInt(r -> r.compilerSources).sum(),
                    "reduced_contexts",
                    analyzed.values().stream().filter(r -> r.reduced).count(),
                    "compiler_peak",
                    session.peakProcesses(),
                    "module_signature_report",
                    modules.report(),
                    "signature_cache_write_failures",
                    cache.writeFailures(),
                    "signature_cache_hits",
                    cache.hits(),
                    "signature_cache_evictions",
                    cache.evictions(),
                    "compiler_launches",
                    session.launches(),
                    "compiler_requests",
                    session.requests(),
                    "extension_reports",
                    new TreeMap<>(
                            analyzed.entrySet().stream()
                                    .collect(
                                            java.util.stream.Collectors.toMap(
                                                    Map.Entry::getKey,
                                                    e -> e.getValue().extensionReport))));
        } finally {
            modules.close();
        }
    }

    private static ContextResult analyzeContext(
            Map<String, Object> ctx,
            CompilerSession session,
            List<Object> baseline,
            SignatureCache cache,
            boolean full,
            ExtensionRegistry registry,
            ModuleSignatures modules)
            throws Exception {
        session.check();
        if (!full
                && cache.enabled()
                && "off".equals(ctx.get("lombok"))
                && Json.list(ctx.get("resources")).isEmpty()
                && registry.supportsBodyOnlyReuse(ctx)
                && baseline.stream()
                        .map(Json::map)
                        .filter(f -> "provenance".equals(f.get("kind")))
                        .filter(
                                f ->
                                        "".equals(f.get("source"))
                                                && FactExtractor.PRODUCER.equals(
                                                        Json.map(f.get("owner")).get("producer")))
                        .filter(
                                f ->
                                        Objects.equals(
                                                Json.map(f.get("owner")).get("world"),
                                                ctx.get("world")))
                        .allMatch(
                                f ->
                                        Objects.equals(
                                                Json.map(f.get("attributes"))
                                                        .get("extension_fingerprint"),
                                                ctx.get("extension_fingerprint")))) {
            ContextResult reduced = analyzeReduced(ctx, session, baseline, cache, registry);
            if (reduced != null) return reduced;
        }
        session.mark(Json.string(ctx, "context"), "module_prepare");
        try (ModuleSignatures.Lease input = modules.prepare(ctx)) {
            Map<String, Object> model = session.analyze(input.compiler);
            int sourceCount = Json.list(input.compiler.get("sources")).size();
            boolean moduleReduced = input.reduced();
            if (input.reduced()
                    && (!Json.list(model.get("diagnostics")).isEmpty() || !input.restore(model))) {
                session.releaseModel(model);
                model = session.analyze(ctx);
                sourceCount = Json.list(ctx.get("sources")).size();
                moduleReduced = false;
            }
            RecordArchive coreArchive = null;
            boolean retained = false;
            try {
                session.check();
                // NoCode describes the original compiler input. Reapply detached overlays after
                // restoring this snapshot so generated methods never become javac-confirmed.
                List<Object> compilerDeclarations =
                        modules.enabled()
                                ? Json.list(ExtensionContext.freeze(model.get("declarations")))
                                : List.of();
                session.mark(Json.string(ctx, "context"), "binary_enrich");
                BinaryIndex.enrich(ctx, model, session.javaHome());
                session.check();
                ExtensionRunner extensions = new ExtensionRunner(ctx, registry,
                        phase -> session.mark(Json.string(ctx, "context"), phase));
                session.mark(Json.string(ctx, "context"), "model_extensions");
                extensions.augment(model);
                LombokModel.recover(model, ctx);
                if (Boolean.TRUE.equals(ctx.get("prerequisite"))) {
                    session.mark(Json.string(ctx, "context"), "file_cache");
                    cache.publish(ctx, model);
                    session.mark(Json.string(ctx, "context"), "module_cache");
                    modules.publish(ctx, model, compilerDeclarations);
                    ContextResult result =
                            new ContextResult(List.of(), Set.of(), moduleReduced, sourceCount);
                    result.prerequisite = true;
                    result.extensions = extensions;
                    result.extensionReport = extensions.report();
                    return result;
                }
                session.mark(Json.string(ctx, "context"), "call_recovery");
                CallRecovery.recover(model, ctx, session.javaHome());
                SupplementalTypes.derive(ctx, model, session);
                session.mark(Json.string(ctx, "context"), "reflection");
                ReflectionModel.analyze(ctx, model, session.javaHome());
                session.mark(Json.string(ctx, "context"), "fact_extract");
                coreArchive = session.newFactArchive();
                List<Object> core =
                        new FactExtractor(ctx, Json.string(ctx, "digest"), coreArchive)
                                .extract(model);
                session.mark(Json.string(ctx, "context"), "java_extensions");
                List<Object> facts = extensions.analyzeJava(model, core);
                session.mark(Json.string(ctx, "context"), "file_cache");
                cache.publish(ctx, model);
                session.mark(Json.string(ctx, "context"), "module_cache");
                modules.publish(ctx, model, compilerDeclarations);
                Set<String> published = new HashSet<>();
                for (Object raw : Json.list(ctx.get("sources"))) {
                    Map<String, Object> source = Json.map(raw);
                    if (Boolean.TRUE.equals(source.get("publish")))
                        published.add(Json.string(source, "path"));
                }
                ContextResult result =
                        new ContextResult(facts, published, moduleReduced, sourceCount);
                result.extensionReport = extensions.report();
                result.extensions = extensions;
                if (extensions.hasProjectStages())
                    result.projectModel = session.stageModel(extensions.projectModel(model), ctx);
                result.coreArchive = coreArchive;
                retained = true;
                return result;
            } finally {
                try {
                    session.releaseModel(model);
                } finally {
                    if (!retained) session.releaseFactArchive(coreArchive);
                }
            }
        }
    }

    private static ContextResult analyzeReduced(
            Map<String, Object> ctx,
            CompilerSession session,
            List<Object> baseline,
            SignatureCache cache,
            ExtensionRegistry registry)
            throws Exception {
        try (SignatureCache.Pins pins = cache.pin(session)) {
            return analyzeReduced(ctx, session, baseline, cache, registry, pins);
        }
    }

    private static ContextResult analyzeReduced(
            Map<String, Object> ctx,
            CompilerSession session,
            List<Object> baseline,
            SignatureCache cache,
            ExtensionRegistry registry,
            SignatureCache.Pins pins)
            throws Exception {
        String world = Json.string(ctx, "world"), context = Json.string(ctx, "context");
        // Record positions only. A file's catalogue comparison decodes its own declarations,
        // rather than scanning and decoding the complete baseline once per omitted file.
        Map<String, List<Integer>> declarationPositions = new HashMap<>();
        Map<String, String> oldPublished = new TreeMap<>(), oldSemantic = new TreeMap<>();
        for (int position = 0; position < baseline.size(); position++) {
            if ((position & 255) == 0) session.check();
            Map<String, Object> fact = Json.map(baseline.get(position)),
                    owner = Json.map(fact.get("owner"));
            if (!world.equals(owner.get("world"))
                    || !FactExtractor.PRODUCER.equals(owner.get("producer"))) continue;
            if ("declaration".equals(fact.get("kind")))
                declarationPositions
                        .computeIfAbsent(Json.string(fact, "source"), ignored -> new ArrayList<>())
                        .add(position);
            if ("coverage".equals(fact.get("kind")))
                oldPublished.put(
                        Json.string(fact, "source"),
                        Json.string(Json.map(fact.get("attributes")), "source_hash"));
            if ("provenance".equals(fact.get("kind"))
                    && "context".equals(owner.get("kind"))
                    && context.equals(Json.map(fact.get("attributes")).get("context"))) {
                Object sources = Json.map(fact.get("attributes")).get("semantic_sources");
                if (sources instanceof List)
                    for (Object pair : Json.list(sources)) {
                        List<Object> item = Json.list(pair);
                        if (item.size() != 2) return null;
                        oldSemantic.put((String) item.get(0), (String) item.get(1));
                    }
            }
            if ("diagnostic".equals(fact.get("kind")) && "context".equals(owner.get("kind")))
                return null;
        }
        Map<String, String> currentPublished = new TreeMap<>(), currentSemantic = new TreeMap<>();
        Map<String, Map<String, Object>> inputs = new TreeMap<>();
        for (Object raw : Json.list(ctx.get("sources"))) {
            Map<String, Object> source = Json.map(raw);
            String path = Json.string(source, "path"), hash = Json.string(source, "hash");
            currentSemantic.put(path, hash);
            inputs.put(path, source);
            if (Boolean.TRUE.equals(source.get("publish"))) currentPublished.put(path, hash);
        }
        if (oldSemantic.isEmpty()
                || !oldSemantic.keySet().equals(currentSemantic.keySet())
                || !oldPublished.keySet().equals(currentPublished.keySet())) return null;
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> item : currentPublished.entrySet())
            if (!item.getValue().equals(oldPublished.get(item.getKey())))
                changed.add(item.getKey());
        if (changed.size() != 1) return null;
        String changedPath = changed.get(0);
        for (Map.Entry<String, String> item : currentSemantic.entrySet())
            if (!item.getKey().equals(changedPath)
                    && !item.getValue().equals(oldSemantic.get(item.getKey()))) return null;
        List<Object> compilerSources = new ArrayList<>(), classpath = new ArrayList<>();
        Set<String> omitted = new HashSet<>();
        Map<String, Map<String, Object>> sourceModels = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> item : inputs.entrySet()) {
            if (item.getKey().equals(changedPath)) compilerSources.add(item.getValue());
            else {
                SignatureCache.Input input = pins.acquire(ctx, item.getValue());
                if (input == null) return null;
                Map<String, Object> catalogue = input.catalogue();
                List<Object> fileDeclarations =
                        new AbstractList<>() {
                            private final List<Integer> positions =
                                    declarationPositions.getOrDefault(item.getKey(), List.of());

                            @Override
                            public int size() {
                                return positions.size();
                            }

                            @Override
                            public Object get(int index) {
                                return baseline.get(positions.get(index));
                            }
                        };
                if (!catalogueMatchesBaseline(catalogue, fileDeclarations, item.getKey()))
                    return null;
                for (String section : List.of("declarations", "external"))
                    for (Object raw : Json.list(catalogue.get(section))) {
                        Map<String, Object> declaration = Json.map(raw);
                        sourceModels.put(Json.string(declaration, "signature"), declaration);
                    }
                classpath.add(input.archive().toString());
                omitted.add(item.getKey());
            }
        }
        classpath.addAll(Json.list(ctx.get("classpath")));
        Map<String, Object> compilerContext = new LinkedHashMap<>(ctx);
        compilerContext.put("sources", compilerSources);
        compilerContext.put("classpath", classpath);
        Map<String, Object> model;
        try {
            model = session.analyze(compilerContext);
        } catch (java.io.IOException invalidSignature) {
            session.check();
            return null;
        }
        RecordArchive coreArchive = null;
        try {
            session.check();
            if (!Json.list(model.get("diagnostics")).isEmpty()) return null;
            BinaryIndex.enrich(compilerContext, model, session.javaHome());
            ReflectionModel.analyze(compilerContext, model, session.javaHome());
            // NoCode is only a compiler input. Published declaration evidence still comes
            // from the verified source catalogue, including source-only type annotations.
            for (Object raw : Json.list(model.get("external"))) {
                Map<String, Object> external = Json.map(raw);
                Map<String, Object> source = sourceModels.get(Json.string(external, "signature"));
                if (source != null) external.put("attributes", source.get("attributes"));
            }
            for (Object raw : Json.list(model.get("observations"))) {
                Map<String, Object> observation = Json.map(raw);
                Map<String, Object> source = sourceModels.get(observation.get("target"));
                if (source != null && "call".equals(observation.get("kind"))) {
                    Map<String, Object> attributes = Json.map(observation.get("attributes"));
                    Map<String, Object> declaration = Json.map(source.get("attributes"));
                    attributes.put("declared_type", declaration.get("declared_type"));
                    attributes.put("declared_type_model", declaration.get("type_model"));
                }
            }
            Map<String, Object> publicationContext = new LinkedHashMap<>(ctx);
            List<Object> publicationSources = new ArrayList<>(), known = new ArrayList<>();
            for (Object raw : Json.list(ctx.get("sources"))) {
                Map<String, Object> source = new LinkedHashMap<>(Json.map(raw));
                if (omitted.contains(source.get("path"))) source.put("publish", false);
                publicationSources.add(source);
            }
            for (Object raw : baseline) {
                Map<String, Object> fact = Json.map(raw);
                if ("declaration".equals(fact.get("kind")) && omitted.contains(fact.get("source")))
                    known.add(fact.get("signature"));
            }
            publicationContext.put("sources", publicationSources);
            publicationContext.put("known_signatures", known);
            coreArchive = session.newFactArchive();
            List<Object> core =
                    new FactExtractor(publicationContext, Json.string(ctx, "digest"), coreArchive)
                            .extract(model);
            if (!equivalent(
                            declarationView(baseline, changedPath),
                            declarationView(core, changedPath))
                    || !equivalent(
                            annotationView(baseline, changedPath),
                            annotationView(core, changedPath))) return null;
            Set<String> oldExternal = new HashSet<>();
            for (Object raw : baseline) {
                Map<String, Object> fact = Json.map(raw), owner = Json.map(fact.get("owner"));
                if ("external_symbol".equals(fact.get("kind")) && world.equals(owner.get("world")))
                    oldExternal.add(Json.string(fact, "id"));
            }
            Set<String> candidateExternal = new HashSet<>(oldExternal);
            for (Object raw : core) {
                Map<String, Object> fact = Json.map(raw);
                if ("external_symbol".equals(fact.get("kind")))
                    candidateExternal.add(Json.string(fact, "id"));
            }
            if (!externalReferences(baseline, changedPath, candidateExternal)
                    .equals(externalReferences(core, changedPath, candidateExternal))) return null;
            Map<String, Object> byId = new TreeMap<>();
            for (Object fact : core) byId.put(Json.string(Json.map(fact), "id"), fact);
            for (int position = 0; position < baseline.size(); position++) {
                if ((position & 255) == 0) session.check();
                Map<String, Object> fact = Json.map(baseline.get(position));
                if (oldExternal.contains(fact.get("id")))
                    byId.putIfAbsent(Json.string(fact, "id"), position);
                if (omitted.contains(fact.get("source"))
                        && world.equals(Json.map(fact.get("owner")).get("world")))
                    byId.putIfAbsent(Json.string(fact, "id"), position);
            }
            // Framework registrations carry source spans and references to unchanged declarations.
            // Recompute the changed source against the complete staged view, retaining omitted
            // owners.
            ExtensionRunner extensions = new ExtensionRunner(publicationContext, registry,
                    phase -> session.mark(Json.string(publicationContext, "context"), phase));
            List<Object> positions = new ArrayList<>(byId.values());
            List<Object> retained =
                    new AbstractList<>() {
                        @Override
                        public int size() {
                            return positions.size();
                        }

                        @Override
                        public Object get(int index) {
                            Object value = positions.get(index);
                            return value instanceof Integer position
                                    ? baseline.get(position)
                                    : value;
                        }
                    };
            List<Object> enriched = extensions.analyzeJava(model, retained);
            cache.publish(compilerContext, model);
            ContextResult result =
                    new ContextResult(enriched, Set.of(changedPath), true, compilerSources.size());
            result.extensionReport = extensions.report();
            result.extensions = extensions;
            if (extensions.hasProjectStages()) result.projectModel = session.stageModel(extensions.projectModel(model), ctx);
            return result;
        } finally {
            try {
                session.releaseModel(model);
            } finally {
                session.releaseFactArchive(coreArchive);
            }
        }
    }

    private static List<Object> declarationView(List<Object> facts, String source) {
        List<Object> view = new ArrayList<>();
        for (Object raw : facts) {
            Map<String, Object> fact = Json.map(raw);
            if ("declaration".equals(fact.get("kind")) && source.equals(fact.get("source")))
                view.add(
                        List.of(
                                fact.get("signature"),
                                fact.get("name"),
                                fact.get("parent"),
                                fact.get("attributes")));
        }
        view.sort(Comparator.comparing(item -> (String) Json.list(item).get(0)));
        return view;
    }

    private static boolean catalogueMatchesBaseline(
            Map<String, Object> catalogue, List<Object> baseline, String path) {
        Map<String, Map<String, Object>> previous = new HashMap<>();
        for (Object raw : baseline) {
            Map<String, Object> fact = Json.map(raw);
            if ("declaration".equals(fact.get("kind")) && path.equals(fact.get("source")))
                previous.put(Json.string(fact, "signature"), fact);
        }
        List<Object> declarations = Json.list(catalogue.get("declarations"));
        if (previous.size() != declarations.size()) return false;
        for (Object raw : declarations) {
            Map<String, Object> declaration = Json.map(raw);
            Map<String, Object> old = previous.get(Json.string(declaration, "signature"));
            if (old == null || !Objects.equals(old.get("name"), declaration.get("name")))
                return false;
            Map<String, Object> attributes = new TreeMap<>(Json.map(old.get("attributes")));
            if (!Objects.equals(attributes.remove("generated"), declaration.get("generated")))
                return false;
            attributes.remove("source_available");
            attributes.remove("logical_key");
            if (!equivalent(attributes, declaration.get("attributes"))) return false;
        }
        return true;
    }

    private static Set<String> externalReferences(
            List<Object> facts, String source, Set<String> external) {
        Set<String> references = new TreeSet<>();
        for (Object raw : facts) {
            Map<String, Object> fact = Json.map(raw);
            if (!source.equals(fact.get("source"))) continue;
            for (String field : List.of("parent", "target"))
                if (external.contains(fact.get(field))) references.add((String) fact.get(field));
        }
        return references;
    }

    private static List<Object> annotationView(List<Object> facts, String source) {
        List<Object> view = new ArrayList<>();
        for (Object raw : facts) {
            Map<String, Object> fact = Json.map(raw);
            if (source.equals(fact.get("source")) && "java.annotation".equals(fact.get("relation")))
                view.add(List.of(fact.get("name"), fact.get("parent"), fact.get("attributes")));
        }
        view.sort(Comparator.comparing(Json::write));
        return view;
    }
}
