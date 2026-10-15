package com.anatomist.framework.spring;

import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.util.*;

/** Lifecycle and event registrations, with finite source member lookup and no invocation. */
public final class SpringJavaModelHandlers implements JavaModelAnalyzer {
    private static final Map<String, String> ROLES =
            Map.of(
                    "javax.annotation.PostConstruct",
                    "init",
                    "jakarta.annotation.PostConstruct",
                    "init",
                    "javax.annotation.PreDestroy",
                    "destroy",
                    "jakarta.annotation.PreDestroy",
                    "destroy",
                    "org.springframework.context.event.EventListener",
                    "event",
                    "org.springframework.transaction.event.TransactionalEventListener",
                    "event");

    public String id() {
        return "spring-handlers";
    }

    public String version() {
        return "detached-2";
    }

    public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {
        analyze(unit, result, null);
    }

    @Override
    public void analyze(JavaUnitAnalysis unit, ExtractionResult result, ExtensionReport report) {
        var annotations = new JavaModelAnnotations(unit);
        var progress = new SpringDeclarationExecution.Progress();
        int visited = 0;
        int annotated = 0;
        for (var method : unit.model().declarations(unit.source())) {
            visited++;
            if (!unit.model().declaration(method).symbol().kind().equals("METHOD")
                    || !annotations.mayHaveAnnotations(method)) continue;
            annotated++;
            annotations.beginDeclaration();
            SpringDeclarationExecution.run(
                    unit,
                    id(),
                    method,
                    report,
                    result,
                    isolated -> analyzeMethod(unit, isolated, annotations, method),
                    progress);
        }
        if (report != null) {
            report.increment("spring_handlers_declarations", visited);
            report.increment("spring_handlers_candidates", annotated);
            report.increment("spring_handlers_completed", progress.completed());
            report.increment("spring_handlers_skipped", progress.skipped());
            var counters = annotations.counters();
            report.increment("spring_handlers_first_calls", counters.firstCalls());
            report.increment("spring_handlers_meta_path_visits", counters.metaPathVisits());
            report.increment("spring_handlers_meta_cache_hits", counters.cacheHits());
        }
    }

    private static void analyzeMethod(
            JavaUnitAnalysis unit,
            ExtractionResult result,
            JavaModelAnnotations annotations,
            JavaModel.DeclarationRef method) {
        factory(unit, result, annotations, method);
        var match = annotations.first(method, ROLES.keySet()).orElse(null);
        if (match == null) return;
        String role = ROLES.get(match.rootFqn()), target = unit.identities().declaration(method);
        Map<String, Object> metadata = new LinkedHashMap<>(match.evidence());
        metadata.put("role", role);
        metadata.put("mechanism", "spring.annotation." + role);
        var raw = annotations.raw(method, match.directFqn());
        metadata.put("attributes", raw);
        metadata.put(
                "parameterTypes",
                SpringDetachedSupport.parameters(unit, method).stream()
                        .map(
                                parameter -> {
                                    JavaNode syntax = unit.model().syntax(parameter).orElse(null),
                                            type = syntax == null ? null : syntax.tree("getType");
                                    return type != null && type.span().present()
                                            ? unit.model()
                                                    .source(parameter.source())
                                                    .slice(type.span())
                                            : SpringDetachedSupport.display(
                                                    unit.model().unit(parameter.source()).types(),
                                                    unit.model()
                                                            .declaration(parameter)
                                                            .symbol()
                                                            .declaredType());
                                })
                        .toList());
        metadata.put("condition_status", "unknown");
        if (match.rootFqn().endsWith("TransactionalEventListener")) {
            metadata.put("transactionPhase", raw.getOrDefault("phase", "AFTER_COMMIT"));
            metadata.put("delivery_status", "runtime-dependent");
        }
        SourceSpan span = annotations.span(method, match.directFqn());
        var source = unit.model().source(method.source());
        int line = span.present() ? source.position(span.start()).line() : 1;
        int end =
                span.present()
                        ? source.position(Math.max(span.start(), span.end() - 1)).line()
                        : line;
        var node =
                registration(unit, result, null, line, end, target + ":" + role, target, metadata);
        var edge =
                SpringDetachedSupport.edge(
                        unit, node.id, target, GraphConstants.Relation.HANDLES, line);
        edge.beginLine = null;
        edge.metadata = Json.writeCompact(metadata);
        result.edges.add(edge);
    }

    private static void factory(
            JavaUnitAnalysis unit,
            ExtractionResult result,
            JavaModelAnnotations annotations,
            JavaModel.DeclarationRef factory) {
        var match =
                annotations
                        .first(factory, Set.of("org.springframework.context.annotation.Bean"))
                        .orElse(null);
        if (match == null) return;
        var types = unit.model().unit(factory.source()).types();
        var executable = types.get(unit.model().declaration(factory).symbol().declaredType());
        if (executable.kind() != JavaType.Kind.EXECUTABLE || executable.returns() < 0) return;
        String owner = unit.identities().declaration(factory),
                product = SpringDetachedSupport.display(types, executable.returns());
        for (String role : List.of("init", "destroy")) {
            String member = match.string(role + "Method");
            if (member == null || member.isBlank() || member.equals("(inferred)")) continue;
            Map<String, Object> metadata = new LinkedHashMap<>(match.evidence());
            metadata.put("role", role);
            metadata.put("mechanism", "spring.bean." + role);
            metadata.put("productType", product);
            metadata.put("configuredMethod", member);
            int line = Math.max(1, SpringDetachedSupport.line(unit, factory));
            var node =
                    registration(
                            unit, result, owner, line, line, owner + ":" + role, member, metadata);
            var targets =
                    lifecycle(unit, JavaTypeNames.erased(types, executable.returns()), member);
            var edge =
                    SpringDetachedSupport.edge(
                            unit, node.id, null, GraphConstants.Relation.HANDLES, line);
            edge.beginLine = null;
            if (targets.size() == 1)
                edge.targetId = unit.identities().declaration(targets.getFirst());
            else {
                edge.isExternal = true;
                edge.externalTargetFqn = product + "#" + member + "()";
                metadata.put("resolutionStatus", "unresolved");
            }
            edge.metadata = Json.writeCompact(metadata);
            result.edges.add(edge);
        }
    }

    private record Owner(String name, int depth) {}

    private static List<JavaModel.DeclarationRef> lifecycle(
            JavaUnitAnalysis unit, String product, String member) {
        ArrayDeque<Owner> pending = new ArrayDeque<>();
        pending.add(new Owner(product, 0));
        Set<String> visited = new HashSet<>();
        List<JavaModel.DeclarationRef> found = new ArrayList<>();
        int nearest = Integer.MAX_VALUE, work = 0;
        while (!pending.isEmpty()) {
            if (++work > 100_000 || visited.size() >= 256) return List.of();
            var owner = pending.removeFirst();
            if (owner.depth() > nearest || !visited.add(owner.name())) continue;
            var lookup = unit.model().type(owner.name());
            if (!(lookup instanceof JavaModel.FoundType type)) return List.of();
            var declaration = unit.model().declaration(type.declaration());
            var types = unit.model().unit(type.declaration().source()).types();
            for (var candidate : unit.model().members(type.declaration())) {
                if (++work > 100_000) return List.of();
                var method = unit.model().declaration(candidate);
                if (!method.symbol().kind().equals("METHOD")
                        || !method.symbol().name().equals(member)
                        || method.symbol().modifiers().contains("static")
                        || owner.depth() > 0 && method.symbol().modifiers().contains("private"))
                    continue;
                var executable = types.get(method.symbol().declaredType());
                if (executable.kind() == JavaType.Kind.EXECUTABLE
                        && executable.parameters().isEmpty()) {
                    found.add(candidate);
                    nearest = owner.depth();
                }
            }
            if (owner.depth() >= nearest) continue;
            List<Integer> parents = new ArrayList<>(declaration.interfaces());
            if (declaration.superclass() >= 0) parents.addFirst(declaration.superclass());
            for (int parent : parents) {
                String name = JavaTypeNames.erased(types, parent);
                if (!name.equals("java.lang.Object"))
                    pending.add(new Owner(name, owner.depth() + 1));
            }
        }
        return List.copyOf(found);
    }

    private static Node registration(
            JavaUnitAnalysis unit,
            ExtractionResult result,
            String owner,
            int line,
            int end,
            String key,
            String label,
            Map<String, Object> metadata) {
        var node =
                FrameworkRegistration.add(
                        result, owner, unit.sourceFile(), line, end, key, label, metadata);
        node.module = unit.identity().module();
        node.scope = unit.identity().scope().name();
        return node;
    }
}
