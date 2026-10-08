package com.anatomist.extract;

import com.anatomist.core.ContentHash;
import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.SourceIdentity;
import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.ExtractionResult;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Context-scoped neutral extraction. Each file result can be staged and released immediately. */
public final class JavaModelExtractor {
    public record UnitFacts(
            Path source,
            String contractHash,
            ExtractionResult facts,
            List<IndexDiagnostic> diagnostics) {
        public UnitFacts {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final Path project;
    private final JavaBinaryRepository binaries;
    private final JavaFrameworkContext framework;
    private final List<JavaModelAnalyzer> analyzers;
    private final List<JavaCallSiteEvidenceProvider> evidenceProviders;
    private final com.anatomist.core.IndexTimings timings;

    private record CorePasses(JavaDeclarationFacts declarations, JavaCallFacts calls) {}

    public JavaModelExtractor(Path project, JavaBinaryRepository binaries) {
        this(
                new JavaFrameworkContext(
                        project, List.of(), new com.anatomist.config.ProjectConfig(), false),
                binaries,
                JavaModelAnalyzerRegistry.NONE);
    }

    public JavaModelExtractor(
            JavaFrameworkContext framework,
            JavaBinaryRepository binaries,
            JavaModelAnalyzerRegistry registry) {
        this(framework, binaries, registry, null);
    }

    public JavaModelExtractor(
            JavaFrameworkContext framework,
            JavaBinaryRepository binaries,
            JavaModelAnalyzerRegistry registry,
            com.anatomist.core.IndexTimings timings) {
        this.framework = Objects.requireNonNull(framework);
        this.project = framework.projectRoot();
        this.binaries = binaries;
        analyzers = Objects.requireNonNull(registry).analyzers();
        evidenceProviders = registry.callSiteEvidenceProviders();
        this.timings = timings;
    }

    public void extract(JavaAnalysisContext context, Consumer<UnitFacts> consumer) {
        if (!context.analysisFailure().isEmpty())
            throw new IllegalStateException("JAVAC_ANALYZE_FAILED: " + context.analysisFailure());
        try (var model = new JavaModel(context)) {
            Map<SourceIdentity, CorePasses> corePasses = new HashMap<>();
            var fields = new JavaFieldFacts(model, project, framework.config(), binaries);
            var types = new JavaTypeFacts(model, project, framework.config());
            var annotations = new JavaAnnotationFacts(model, project, binaries);
            var overrides = new JavaOverrideFacts(model, project, binaries);
            var reflection = new JavaReflectionFacts(model, project, binaries);
            Path firstPublished = context.publish().stream().sorted().findFirst().orElse(null);
            for (Path path : context.publish().stream().sorted().toList()) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException(
                            "Java extraction cancelled");
                long started = timings == null ? 0 : timings.start();
                JavaUnit unit = model.unit(path);
                model.source(path);
                var facts = new ExtractionResult();
                var diagnostics = new ArrayList<IndexDiagnostic>();
                diagnostics.addAll(unit.extensions().diagnostics());
                unit.extensions().counters().forEach((key, value) -> facts.stats.put(key, value));
                var identity = context.identity(path);
                var passes =
                        corePasses.computeIfAbsent(
                                identity,
                                source ->
                                        new CorePasses(
                                                new JavaDeclarationFacts(
                                                        model,
                                                        project,
                                                        source.module(),
                                                        source.scope().name()),
                                                new JavaCallFacts(
                                                        model,
                                                        project,
                                                        source.module(),
                                                        source.scope().name(),
                                                        binaries,
                                                        framework.config())));
                passes.declarations().extract(path, facts);
                annotations.extract(path, facts);
                reflection.extract(path, facts);
                for (var gap : overrides.extract(path, facts))
                    diagnostics.add(
                            gap(
                                    context,
                                    model,
                                    path,
                                    gap.span(),
                                    gap.owner(),
                                    "OVERRIDES",
                                    gap.owner(),
                                    gap.reason()));
                for (var gap : types.extract(path, facts))
                    diagnostics.add(
                            gap(
                                    context,
                                    model,
                                    path,
                                    SourceSpan.ABSENT,
                                    gap.owner(),
                                    gap.relation(),
                                    gap.target(),
                                    gap.reason()));
                for (var gap : passes.calls().extract(path, facts))
                    diagnostics.add(
                            gap(
                                    context,
                                    model,
                                    path,
                                    gap.span(),
                                    gap.owner(),
                                    "CALLS",
                                    gap.symbol(),
                                    gap.reason(),
                                    gap.receiverStaticType()));
                for (var gap : fields.extract(path, facts))
                    diagnostics.add(
                            gap(
                                    context,
                                    model,
                                    path,
                                    gap.span(),
                                    gap.owner(),
                                    "FIELD_ACCESS",
                                    model.source(path).slice(gap.span()),
                                    gap.reason()));
                for (JavaDiagnostic diagnostic : context.diagnostics()) {
                    if (diagnostic.path() == null
                            ? !path.equals(firstPublished)
                            : !path.equals(diagnostic.path())) continue;
                    boolean compileFailure =
                            diagnostic.code().equals("JAVAC_LOMBOK_COMPILE_FAILED");
                    diagnostics.add(
                            new IndexDiagnostic(
                                    compileFailure && framework.config().lombokStrict()
                                            ? "error"
                                            : diagnostic.kind().equals("ERROR")
                                                    ? "warning"
                                                    : "info",
                                    compileFailure
                                            ? "LOMBOK_COMPILE_FAILED"
                                            : "JAVAC_" + diagnostic.code(),
                                    compileFailure ? "JAVAC_COMPILE" : "JAVAC_ANALYZE",
                                    sourceFile(path),
                                    identity.module(),
                                    identity.scope().name(),
                                    null,
                                    1,
                                    "L"
                                            + diagnostic.line()
                                            + "C"
                                            + diagnostic.column()
                                            + ": "
                                            + diagnostic.message()));
                }
                var report = new ExtensionReport(timings);
                var analysis = new JavaUnitAnalysis(framework, model, binaries, path, identity);
                JavaCallEvidence.enrich(analysis, facts, evidenceProviders, report);
                for (var analyzer : analyzers)
                    ExtensionExecution.facts(
                            analyzer,
                            ExtensionExecution.Stage.JAVA,
                            new ExtensionExecution.Source(
                                    sourceFile(path), identity.module(), identity.scope().name()),
                            report,
                            facts,
                            isolated -> analyzer.analyze(analysis, isolated, report));
                diagnostics.addAll(report.diagnostics());
                report.counters()
                        .forEach(
                                (key, value) ->
                                        facts.stats.merge(
                                                key,
                                                value,
                                                (before, increment) ->
                                                        ((Number) before).longValue()
                                                                + ((Number) increment)
                                                                        .longValue()));
                attachDeclarationCoordinates(facts);
                var extracted =
                        new UnitFacts(path, JavaContract.fingerprint(unit), facts, diagnostics);
                if (timings != null) timings.stop("java_model_extract", started);
                consumer.accept(extracted);
            }
        }
    }

    private static void attachDeclarationCoordinates(ExtractionResult facts) {
        Map<String, com.anatomist.model.Node> owners = new HashMap<>();
        for (var node : facts.nodes) owners.put(node.id, node);
        for (var edge : facts.edges) {
            // A declaration range locates signature/relationship evidence. It must never
            // replace the original expression range of a source call.
            if ("CALLS".equals(edge.relation)) continue;
            var owner = owners.get(edge.sourceId);
            if (owner == null) continue;
            if (edge.sourceLocation == null) edge.sourceLocation = owner.sourceLocation;
            if (edge.beginLine == null
                    && edge.beginColumn == null
                    && edge.endLine == null
                    && edge.endColumn == null) {
                edge.beginLine = owner.beginLine;
                edge.beginColumn = owner.beginColumn;
                edge.endLine = owner.endLine;
                edge.endColumn = owner.endColumn;
                edge.sourceOrdinal = owner.sourceOrdinal;
            }
        }
    }

    private IndexDiagnostic gap(
            JavaAnalysisContext context,
            JavaModel model,
            Path path,
            SourceSpan span,
            String owner,
            String relation,
            String symbol,
            String reason) {
        return gap(context, model, path, span, owner, relation, symbol, reason, null);
    }

    private IndexDiagnostic gap(
            JavaAnalysisContext context,
            JavaModel model,
            Path path,
            SourceSpan span,
            String owner,
            String relation,
            String symbol,
            String reason,
            String receiver) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("relation", relation);
        details.put("source_owner_id", owner);
        if (!context.classpath().isEmpty()) {
            details.put("classpath_source", context.classpathSource());
            details.put(
                    "classpath_order_sha256",
                    ContentHash.sha256(
                            context.classpathEntries().isEmpty()
                                    ? context.classpath().stream()
                                            .map(
                                                    entry ->
                                                            entry.toAbsolutePath()
                                                                    .normalize()
                                                                    .toString())
                                            .collect(java.util.stream.Collectors.joining("\n"))
                                    : context.classpathEntries().stream()
                                            .map(entry -> entry.path() + "=" + entry.sha256())
                                            .collect(java.util.stream.Collectors.joining("\n"))));
        }
        if (receiver != null
                && receiver.matches("[a-zA-Z_$][a-zA-Z0-9_$.]*")
                && !receiver.startsWith("java.")) {
            if (model.type(receiver) instanceof JavaModel.FoundType found) {
                var members =
                        model.members(found.declaration()).stream()
                                .map(model::declaration)
                                .filter(member -> member.symbol().name().equals(symbol))
                                .limit(8)
                                .map(
                                        member ->
                                                member.symbol().name()
                                                        + member.symbol().descriptor())
                                .toList();
                details.put(
                        "classpath_owner",
                        Map.of(
                                "status",
                                "source",
                                "owner",
                                receiver,
                                "matching_members",
                                members,
                                "cause",
                                members.isEmpty()
                                        ? "source-owner-declares-no-matching-member"
                                        : "source-member-unconfirmed"));
            } else if (binaries != null) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("owner", receiver);
                try {
                    List<Path> providers = binaries.providers(receiver);
                    List<Path> failures = binaries.providerFailures(receiver);
                    evidence.put(
                            "status",
                            failures.isEmpty()
                                    ? providers.isEmpty() ? "class-missing" : "class-present"
                                    : "partial-inspection");
                    evidence.put("providers", providers.stream().map(Path::toString).toList());
                    if (!failures.isEmpty())
                        evidence.put(
                                "unreadable_entries",
                                failures.stream().map(Path::toString).toList());
                    binaries.find(receiver)
                            .ifPresent(
                                    type ->
                                            evidence.put(
                                                    "matching_members",
                                                    type.methods().stream()
                                                            .filter(
                                                                    method ->
                                                                            method.name()
                                                                                    .equals(symbol))
                                                            .limit(8)
                                                            .map(
                                                                    method ->
                                                                            method.name()
                                                                                    + method
                                                                                            .descriptor())
                                                            .toList()));
                    if (providers.size() > 1) evidence.put("duplicate_class", true);
                    if (!providers.isEmpty() && failures.isEmpty())
                        evidence.put(
                                "cause",
                                evidence.get("matching_members") instanceof List<?> members
                                                && members.isEmpty()
                                        ? "selected-class-declares-no-matching-member"
                                        : "binary-member-unconfirmed");
                } catch (java.io.UncheckedIOException failure) {
                    evidence.put("status", "unavailable");
                    evidence.put("reason", failure.getMessage());
                }
                details.put("classpath_owner", evidence);
            }
        }
        String sample = symbol;
        if (span.present()) {
            var begin = model.source(path).position(span.start());
            var end = model.source(path).position(Math.max(span.start(), span.end() - 1));
            details.put("begin_line", begin.line());
            details.put("begin_column", begin.column());
            details.put("end_line", end.line());
            details.put("end_column", end.column());
            sample = "L" + begin.line() + ": " + model.source(path).slice(span);
            if (relation.equals("CALLS")) {
                var sameLine =
                        context.diagnostics().stream()
                                .filter(
                                        diagnostic ->
                                                path.equals(diagnostic.path())
                                                        && diagnostic.kind().equals("ERROR")
                                                        && diagnostic.line() == begin.line())
                                .limit(4)
                                .toList();
                var matching =
                        sameLine.stream()
                                .filter(diagnostic -> diagnostic.message().contains(symbol))
                                .findFirst()
                                .orElse(sameLine.size() == 1 ? sameLine.getFirst() : null);
                if (matching == null) details.put("raw_javac_status", "unmatched");
                else
                    details.put(
                            "raw_javac",
                            Map.of(
                                    "code", matching.code(),
                                    "line", matching.line(),
                                    "column", matching.column(),
                                    "message", matching.message()));
            }
        }
        String phase =
                relation.equals("CALLS")
                        ? "NAVIGATION_CALL_GRAPH"
                        : Set.of("INHERITS", "IMPLEMENTS", "PERMITS", "OVERRIDES")
                                        .contains(relation)
                                ? "NAVIGATION_HIERARCHY"
                                : "NAVIGATION_" + relation;
        String reasonCode =
                reason.startsWith("ambiguous") || reason.endsWith("ambiguous")
                        ? "AMBIGUOUS"
                        : reason.equals("incomplete-evidence")
                                ? "INCOMPLETE_EVIDENCE"
                                : "SYMBOL_UNRESOLVED";
        details.put("compiler_reason", reason);
        return new IndexDiagnostic(
                "warning",
                "NAVIGATION_TARGET_UNRESOLVED",
                phase,
                sourceFile(path),
                context.identity(path).module(),
                context.identity(path).scope().name(),
                symbol,
                1,
                sample,
                "java",
                "java-core",
                null,
                reasonCode,
                Json.writeCompact(details));
    }

    private String sourceFile(Path path) {
        return project.relativize(path).toString().replace('\\', '/');
    }
}
