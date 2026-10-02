package dev.codeana.analysis.extension;

import dev.codeana.common.FactHeaders;
import dev.codeana.common.Json;

import java.util.*;
import java.util.function.Consumer;

/**
 * Task-scoped execution over frozen inputs, in model -> Java -> call -> resource -> project order.
 */
public final class ExtensionRunner {
    private final Map<String, Object> configuration;
    private final ExtensionRegistry registry;
    private final List<ExtensionPoint> enabled;
    private final List<ExtensionPoint> projectEnabled;
    private final List<Object> pending = new ArrayList<>();
    private final ExtensionReport report = new ExtensionReport();
    private final Consumer<String> progress;

    public ExtensionRunner(Map<String, Object> configuration, ExtensionRegistry registry) {
        this(configuration, registry, ignored -> {});
    }

    public ExtensionRunner(Map<String, Object> configuration, ExtensionRegistry registry, Consumer<String> progress) {
        this.configuration = configuration;
        this.registry = registry;
        this.progress = Objects.requireNonNull(progress);
        enabled = registry.enabled(configuration);
        boolean resources = !Json.list(configuration.getOrDefault("resources", List.of())).isEmpty();
        projectEnabled = enabled.stream()
                .filter(e -> (e instanceof ProjectResourceProvider || e instanceof ProjectResourceAnalyzer)
                        && (resources || !e.requiresCapturedResources())).toList();
    }

    public boolean hasProjectStages() {
        return !projectEnabled.isEmpty();
    }

    public Map<String, Object> projectModel(Map<String, Object> model) {
        if (projectEnabled.stream().anyMatch(ExtensionPoint::projectRequiresFullModel)) return model;
        Map<String, Object> headers = new LinkedHashMap<>(model);
        for (String section : List.of("observations", "syntax", "diagnostics"))
            headers.put(section, List.of());
        return headers;
    }

    public void augment(Map<String, Object> model) throws Exception {
        for (ExtensionPoint e : enabled)
            if (e instanceof JavaModelExtension extension) {
                progress.accept("model." + e.id());
                ExtensionContext context =
                        new ExtensionContext(configuration, model, List.of(), List.of());
                if (extension instanceof JavaDeclarationModelExtension perDeclaration) {
                    for (Object raw : Json.list(context.model().get("declarations"))) {
                        Map<String, Object> declaration = Json.map(raw);
                        ExtensionExecution.run(
                                e,
                                "model",
                                context,
                                report,
                                pending,
                                Json.string(declaration, "source"),
                                Json.string(declaration, "signature"),
                                () -> {
                                    if (!perDeclaration.acceptsDeclaration(declaration)) return;
                                    ModelOverlay overlay = new ModelOverlay();
                                    perDeclaration.augmentDeclaration(
                                            context, declaration, overlay);
                                    overlay.commit(
                                            model,
                                            e.producer(),
                                            configuration.containsKey("model_record_limit")
                                                    ? Json.number(
                                                            configuration, "model_record_limit")
                                                    : 100000);
                                });
                    }
                    continue;
                }
                ExtensionExecution.run(
                        e,
                        "model",
                        context,
                        report,
                        pending,
                        "",
                        () -> {
                            ModelOverlay overlay = new ModelOverlay();
                            extension.augment(context, overlay);
                            overlay.commit(
                                    model,
                                    e.producer(),
                                    configuration.containsKey("model_record_limit")
                                            ? Json.number(configuration, "model_record_limit")
                                            : 100000);
                        });
            }
    }

    public List<Object> analyze(Map<String, Object> model, List<Object> core) throws Exception {
        List<Object> output = analyzeJava(model, core);
        output.addAll(analyzeProject(model, output));
        return output;
    }

    public List<Object> analyzeJava(Map<String, Object> model, List<Object> core) throws Exception {
        // Baseline-backed views must remain lazy. Retain only this stage's new output.
        List<Object> output = FactHeaders.appended(core);
        output.addAll(pending);
        ExtensionContext frozenInputs = null;
        for (ExtensionPoint e : enabled)
            if (e instanceof JavaModelAnalyzer analyzer) {
                progress.accept("java." + e.id());
                if (frozenInputs == null)
                    frozenInputs = new ExtensionContext(configuration, model, List.of(), List.of());
                ExtensionContext context =
                        frozenInputs.withFacts(output);
                if (analyzer instanceof JavaDeclarationAnalyzer perDeclaration) {
                    FactSink.ValidationIndex identities = new FactSink.ValidationIndex(output);
                    for (Object raw : Json.list(context.model().get("declarations"))) {
                        Map<String, Object> declaration = Json.map(raw);
                        int previous = output.size();
                        ExtensionExecution.run(
                                e,
                                "java",
                                context,
                                report,
                                output,
                                Json.string(declaration, "source"),
                                Json.string(declaration, "signature"),
                                () -> {
                                    if (!perDeclaration.acceptsDeclaration(declaration)) return;
                                    FactSink sink = new FactSink();
                                    perDeclaration.analyzeDeclaration(context, declaration, sink);
                                    List<Object> verified = sink.validate(e, context, identities);
                                    output.addAll(verified);
                                });
                        identities.added(output.subList(previous, output.size()));
                    }
                    continue;
                }
                ExtensionExecution.run(
                        e,
                        "java",
                        context,
                        report,
                        output,
                        "",
                        () -> {
                            FactSink sink = new FactSink();
                            analyzer.analyze(context, sink);
                            output.addAll(sink.validate(e, context, output));
                        });
            }
        for (ExtensionPoint e : enabled)
            if (e instanceof JavaCallSiteEvidenceProvider observer) {
                progress.accept("call." + e.id());
                if (frozenInputs == null)
                    frozenInputs = new ExtensionContext(configuration, model, List.of(), List.of());
                ExtensionContext context =
                        frozenInputs.withFacts(output);
                FactSink.ValidationIndex identities = new FactSink.ValidationIndex(output);
                for (int index = 0; index < context.facts().size(); index++) {
                    if (!"call_site".equals(context.factKind(index))) continue;
                    Map<String, Object> header = context.factHeader(index);
                    final int position = index;
                    int previous = output.size();
                    ExtensionExecution.run(
                            e,
                            "call",
                            context,
                            report,
                            output,
                            Json.string(header, "source"),
                            Json.string(header, "id"),
                            () -> {
                                if (!observer.acceptsCallSite(context, header)) return;
                                Map<String, Object> call;
                                try {
                                    call = Json.map(context.facts().get(position));
                                } catch (java.util.concurrent.CancellationException cancelled) {
                                    throw cancelled;
                                } catch (RuntimeException malformed) {
                                    ExtensionPoint.GateFailure failure = new ExtensionPoint.GateFailure("EXTENSION_CALL_INPUT_CORRUPT");
                                    failure.initCause(malformed);
                                    throw failure;
                                }
                                Optional<JavaCallSiteEvidenceProvider.Evidence> evidence =
                                        observer.observe(context, call);
                                if (evidence.isEmpty()) return;
                                var value = evidence.get();
                                Map<String, Object> a = new TreeMap<>(value.attributes());
                                a.put("schema_version", value.schemaVersion());
                                Map<String, Object> owner =
                                        new TreeMap<>(Json.map(call.get("owner")));
                                owner.put("producer", e.producer());
                                Map<String, Object> fact =
                                        Json.object(
                                                "id",
                                                Json.key(
                                                        "extension-call",
                                                        e.id(),
                                                        Json.string(call, "id"),
                                                        value.namespace()),
                                                "kind",
                                                "facet",
                                                "owner",
                                                owner,
                                                "source",
                                                call.get("source"),
                                                "span",
                                                call.get("span"),
                                                "name",
                                                "",
                                                "signature",
                                                "",
                                                "parent",
                                                call.get("id"),
                                                "target",
                                                "",
                                                "relation",
                                                value.namespace(),
                                                "status",
                                                "modeled",
                                                "origin",
                                                e.producer(),
                                                "evidence_complete",
                                                false,
                                                "declaration_resolved",
                                                false,
                                                "attributes",
                                                a);
                                FactSink sink = new FactSink();
                                sink.add(fact);
                                List<Object> verified = sink.validate(e, context, identities);
                                output.addAll(verified);
                            });
                    identities.added(output.subList(previous, output.size()));
                }
            }
        return output;
    }

    /** Called only after every compiler context's Java and call stages are staged. */
    public List<Object> analyzeProject(Map<String, Object> model, List<Object> staged)
            throws Exception {
        List<Object> output = new ArrayList<>();
        List<Object> visible = FactHeaders.joined(staged, output);
        List<ProjectResource> inventory = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (ExtensionPoint e : projectEnabled)
            if (e instanceof ProjectResourceProvider provider) {
                progress.accept("resource." + e.id());
                ExtensionContext context =
                        new ExtensionContext(configuration, model, visible, inventory);
                ExtensionExecution.run(
                        e,
                        "resource",
                        context,
                        report,
                        output,
                        "",
                        () -> {
                            List<ProjectResource> candidate =
                                    List.copyOf(provider.discover(context));
                            if (candidate.size() > 10000)
                                throw new IllegalArgumentException("EXTENSION_RESOURCE_BUDGET");
                            List<ProjectResource> commit = new ArrayList<>();
                            Set<String> keys = new HashSet<>();
                            for (ProjectResource resource : candidate) {
                                String key =
                                        Json.write(
                                                List.of(
                                                        resource.kind(),
                                                        resource.world(),
                                                        resource.path()));
                                if (!provider.kinds().contains(resource.kind())
                                        || !keys.add(key)
                                        || !Json.list(configuration.get("resources"))
                                                .contains(resource.captured()))
                                    throw new IllegalArgumentException(
                                            "EXTENSION_UNCAPTURED_OR_DUPLICATE_RESOURCE");
                                if (!selected.contains(key)) commit.add(resource);
                            }
                            inventory.addAll(commit);
                            selected.addAll(keys);
                        });
            }
        inventory.sort(
                Comparator.comparing(ProjectResource::kind)
                        .thenComparing(ProjectResource::world)
                        .thenComparing(ProjectResource::path));
        for (ExtensionPoint e : projectEnabled)
            if (e instanceof ProjectResourceAnalyzer analyzer) {
                progress.accept("project." + e.id());
                ExtensionContext context =
                        new ExtensionContext(configuration, model, visible, inventory);
                ExtensionExecution.run(
                        e,
                        "project",
                        context,
                        report,
                        output,
                        "",
                        () -> {
                            ResourceSelector selector =
                                    Objects.requireNonNull(
                                            analyzer.selector(), "EXTENSION_RESOURCE_SELECTOR");
                            List<ProjectResource> resources = new ArrayList<>();
                            for (ProjectResource resource : inventory) {
                                if (Thread.currentThread().isInterrupted())
                                    throw new java.util.concurrent.CancellationException(
                                            "CANCELLED");
                                if (analyzer.resourceKinds().contains(resource.kind())
                                        && selector.matches(resource)) resources.add(resource);
                            }
                            FactSink sink = new FactSink();
                            progress.accept("project." + e.id() + ".analyze");
                            analyzer.analyzeResources(context, List.copyOf(resources), sink);
                            progress.accept("project." + e.id() + ".validate");
                            output.addAll(sink.validate(e, context, visible));
                        });
            }
        return output;
    }

    public Map<String, Object> report() {
        Map<String, Object> result = report.summary();
        result.put("fingerprint", registry.fingerprint(configuration));
        result.put("extensions", registry.manifest(configuration));
        return result;
    }
}
