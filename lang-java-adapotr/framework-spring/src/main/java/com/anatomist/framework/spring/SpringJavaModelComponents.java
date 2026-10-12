package com.anatomist.framework.spring;

import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.util.*;

/** Spring beans and injection declarations from the detached inventory. */
public final class SpringJavaModelComponents implements JavaModelAnalyzer {
    private static final Set<String> COMPONENTS =
            Set.of(
                    "org.springframework.stereotype.Component",
                    "org.springframework.stereotype.Service",
                    "org.springframework.stereotype.Repository",
                    "org.springframework.stereotype.Controller",
                    "org.springframework.web.bind.annotation.RestController",
                    "org.springframework.context.annotation.Configuration");
    private static final Set<String> BEAN = Set.of("org.springframework.context.annotation.Bean");
    private static final Set<String> PRIMARY =
            Set.of("org.springframework.context.annotation.Primary");
    private static final Set<String> QUALIFIER =
            Set.of(
                    "org.springframework.beans.factory.annotation.Qualifier",
                    "jakarta.inject.Named",
                    "javax.inject.Named");
    private static final Set<String> INJECTION =
            Set.of(
                    "org.springframework.beans.factory.annotation.Autowired",
                    "jakarta.annotation.Resource",
                    "javax.annotation.Resource",
                    "jakarta.inject.Inject",
                    "javax.inject.Inject");
    private static final Set<String> NON_BEAN =
            Set.of(
                    "org.springframework.beans.factory.annotation.Value",
                    "com.alipay.sofa.runtime.api.annotation.SofaReference");
    private static final Set<String> CONSTRAINTS =
            Set.of(
                    "org.springframework.context.annotation.Profile",
                    "org.springframework.context.annotation.Conditional",
                    "org.springframework.context.annotation.Scope",
                    "org.springframework.context.annotation.Lazy",
                    "org.springframework.context.annotation.Import",
                    "org.springframework.context.annotation.ImportResource",
                    "org.springframework.context.annotation.ComponentScan");

    public String id() {
        return "spring-components";
    }

    public String version() {
        return "detached-2";
    }

    public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {
        analyze(unit, result, null);
    }

    @Override
    public void analyze(JavaUnitAnalysis unit, ExtractionResult result, ExtensionReport report) {
        new Pass(unit, result, report).run();
    }

    private static final class Pass {
        private final JavaUnitAnalysis unit;
        private final ExtractionResult destination;
        private final ExtensionReport report;
        private ExtractionResult result;
        private final JavaModelAnnotations annotations;
        private final JavaIdentities identities;

        private Pass(JavaUnitAnalysis unit, ExtractionResult result, ExtensionReport report) {
            this.unit = unit;
            destination = result;
            this.report = report;
            annotations = new JavaModelAnnotations(unit);
            identities = unit.identities();
        }

        private void run() {
            var progress = new SpringDeclarationExecution.Progress();
            int visited = 0;
            int annotated = 0;
            for (var reference : unit.model().declarations(unit.source())) {
                visited++;
                String kind = unit.model().declaration(reference).symbol().kind();
                if (!Set.of("CLASS", "INTERFACE", "METHOD", "FIELD", "CONSTRUCTOR").contains(kind))
                    continue;
                if (!annotations.mayHaveAnnotations(reference)) {
                    if (!kind.equals("CONSTRUCTOR")) continue;
                    var owner = unit.model().parent(reference).orElse(null);
                    if (owner == null || !annotations.mayHaveAnnotations(owner)) continue;
                }
                annotated++;
                annotations.beginDeclaration();
                SpringDeclarationExecution.run(
                        unit,
                        "spring-components",
                        reference,
                        report,
                        destination,
                        isolated -> {
                            this.result = isolated;
                            switch (kind) {
                                case "CLASS", "INTERFACE" -> component(reference);
                                case "METHOD" -> {
                                    bean(reference);
                                    methodInjection(reference);
                                }
                                case "FIELD" -> fieldInjection(reference);
                                case "CONSTRUCTOR" -> constructorInjection(reference);
                                default -> {}
                            }
                        },
                        progress);
            }
            if (report != null) {
                report.increment("spring_components_declarations", visited);
                report.increment("spring_components_candidates", annotated);
                report.increment("spring_components_completed", progress.completed());
                report.increment("spring_components_skipped", progress.skipped());
                var counters = annotations.counters();
                report.increment("spring_components_first_calls", counters.firstCalls());
                report.increment("spring_components_meta_path_visits", counters.metaPathVisits());
                report.increment("spring_components_meta_cache_hits", counters.cacheHits());
            }
        }

        private void component(JavaModel.DeclarationRef reference) {
            var match = annotations.first(reference, COMPONENTS).orElse(null);
            if (match == null) return;
            var declaration = unit.model().declaration(reference);
            String name = match.string("value");
            if (name == null || name.isBlank()) name = match.string("name");
            if (name == null || name.isBlank())
                name = SpringDetachedSupport.decapitalize(declaration.symbol().name());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("className", declaration.canonicalName());
            metadata.put("source", "annotation");
            metadata.put("stereotype", match.rootFqn());
            metadata.putAll(match.evidence());
            decorate(reference, metadata);
            emitBean(reference, name, identities.declaration(reference), metadata);
        }

        private void bean(JavaModel.DeclarationRef reference) {
            var match = annotations.first(reference, BEAN).orElse(null);
            if (match == null) return;
            var declaration = unit.model().declaration(reference);
            var names = match.strings("name");
            if (names.isEmpty()) names = match.strings("value");
            String name =
                    names.isEmpty() || names.getFirst().isBlank()
                            ? declaration.symbol().name()
                            : names.getFirst();
            var types = unit.model().unit(reference.source()).types();
            var method = types.get(declaration.symbol().declaredType());
            if (method.kind() != JavaType.Kind.EXECUTABLE) return;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("factoryMethod", identities.declaration(reference));
            metadata.put("returnType", SpringDetachedSupport.display(types, method.returns()));
            metadata.put("source", "bean_method");
            metadata.putAll(match.evidence());
            if (!names.isEmpty()) metadata.put("aliases", names);
            decorate(reference, metadata);
            emitBean(reference, name, identities.declaration(reference), metadata);
        }

        private void emitBean(
                JavaModel.DeclarationRef reference,
                String name,
                String target,
                Map<String, Object> metadata) {
            String id = "bean:" + name;
            int line = SpringDetachedSupport.line(unit, reference);
            result.nodes.add(
                    SpringDetachedSupport.node(
                            unit, id, name, GraphConstants.Kind.BEAN, line, metadata));
            result.edges.add(
                    SpringDetachedSupport.edge(
                            unit, id, target, GraphConstants.Relation.DEFINED_BY, line));
        }

        private void decorate(JavaModel.DeclarationRef reference, Map<String, Object> metadata) {
            annotations
                    .first(reference, PRIMARY)
                    .ifPresent(
                            match -> {
                                metadata.put("primary", true);
                                metadata.put("primaryEvidence", match.evidence());
                            });
            annotations
                    .first(reference, QUALIFIER)
                    .ifPresent(
                            match -> {
                                String value = match.string("value");
                                if (value != null && !value.isBlank())
                                    metadata.put("qualifier", value);
                            });
            if (annotations
                    .first(
                            reference,
                            Set.of("javax.annotation.Priority", "jakarta.annotation.Priority"))
                    .isPresent()) metadata.put("prioritySelectionUnknown", true);
            List<Map<String, Object>> constraints = new ArrayList<>();
            JavaModel.DeclarationRef current = reference;
            while (current != null) {
                for (var annotation : annotations.annotations(current)) {
                    String name = JavaModelAnnotations.name(annotation.descriptor());
                    if (CONSTRAINTS.contains(name))
                        constraints.add(
                                Map.of(
                                        "annotation",
                                        name,
                                        "attributes",
                                        annotations.raw(current, name),
                                        "status",
                                        "unevaluated"));
                }
                current = unit.model().parent(current).orElse(null);
            }
            if (!constraints.isEmpty()) metadata.put("configurationConstraints", constraints);
        }

        private void fieldInjection(JavaModel.DeclarationRef reference) {
            var injection = annotations.first(reference, INJECTION).orElse(null);
            if (injection == null) return;
            var owner = unit.model().parent(reference).orElse(null);
            if (owner == null) return;
            emitInjection(
                    owner,
                    reference,
                    reference,
                    injection,
                    annotations.first(reference, QUALIFIER).orElse(null),
                    false,
                    "field:" + unit.model().declaration(reference).symbol().name());
        }

        private void methodInjection(JavaModel.DeclarationRef reference) {
            var injection = annotations.first(reference, INJECTION).orElse(null);
            if (injection == null && annotations.first(reference, BEAN).isEmpty()) return;
            var owner = unit.model().parent(reference).orElse(null);
            if (owner == null) return;
            var parameters = SpringDetachedSupport.parameters(unit, reference);
            String name = unit.model().declaration(reference).symbol().name();
            boolean resource = injection != null && injection.rootFqn().endsWith(".Resource");
            if (resource && (parameters.size() != 1 || !name.startsWith("set"))) return;
            for (var parameter : parameters) {
                if (annotations.first(parameter, NON_BEAN).isPresent()) continue;
                var qualifier =
                        annotations
                                .first(parameter, QUALIFIER)
                                .orElseGet(
                                        () -> annotations.first(reference, QUALIFIER).orElse(null));
                String site =
                        resource
                                ? "setter:" + SpringDetachedSupport.decapitalize(name.substring(3))
                                : "method:"
                                        + identities.declaration(reference).replace('#', '.')
                                        + ":"
                                        + unit.model().declaration(parameter).symbol().name();
                emitInjection(owner, parameter, reference, injection, qualifier, false, site);
            }
        }

        private void constructorInjection(JavaModel.DeclarationRef reference) {
            var owner = unit.model().parent(reference).orElse(null);
            if (owner == null) return;
            var injection = annotations.first(reference, INJECTION).orElse(null);
            boolean implicit =
                    injection == null
                            && annotations.first(owner, COMPONENTS).isPresent()
                            && unit.model().members(owner).stream()
                                            .filter(
                                                    member ->
                                                            unit.model()
                                                                    .declaration(member)
                                                                    .symbol()
                                                                    .kind()
                                                                    .equals("CONSTRUCTOR"))
                                            .count()
                                    == 1;
            if (injection == null && !implicit) return;
            for (var parameter : SpringDetachedSupport.parameters(unit, reference)) {
                if (annotations.first(parameter, NON_BEAN).isPresent()) continue;
                emitInjection(
                        owner,
                        parameter,
                        reference,
                        injection,
                        annotations.first(parameter, QUALIFIER).orElse(null),
                        implicit,
                        "constructor:"
                                + identities.declaration(reference).replace('#', '.')
                                + ":"
                                + unit.model().declaration(parameter).symbol().name());
            }
        }

        private void emitInjection(
                JavaModel.DeclarationRef owner,
                JavaModel.DeclarationRef reference,
                JavaModel.DeclarationRef injectionDeclaration,
                JavaModelAnnotations.Match injection,
                JavaModelAnnotations.Match qualifier,
                boolean implicit,
                String site) {
            var declaration = unit.model().declaration(reference);
            var types = unit.model().unit(reference.source()).types();
            int typeRef = declaration.symbol().declaredType();
            if (typeRef < 0 || types.get(typeRef).kind() == JavaType.Kind.ERROR) return;
            String raw = JavaTypeNames.erased(types, typeRef),
                    described = SpringDetachedSupport.display(types, typeRef);
            var lookup = unit.model().type(raw);
            if (lookup instanceof JavaModel.AmbiguousType) return;
            int line = SpringDetachedSupport.line(unit, reference);
            Edge edge =
                    SpringDetachedSupport.edge(
                            unit,
                            identities.declaration(owner),
                            null,
                            GraphConstants.Relation.INJECTS,
                            line);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("injectedType", described);
            metadata.put("injectionSite", site);
            metadata.putAll(SpringDetachedSupport.shape(types, typeRef));
            metadata.put("injectionPointName", site.substring(site.lastIndexOf(':') + 1));
            metadata.put("injectionPointNameAvailable", site.startsWith("field:"));
            if (injection != null) {
                metadata.put("annotation", injection.rootFqn());
                metadata.putAll(injection.evidence());
                String required = injection.string("required");
                if (required == null
                        && injection.attributes().get("required")
                                instanceof JavaBinaryType.Scalar scalar
                        && scalar.value() instanceof Boolean) required = scalar.value().toString();
                if (required != null) metadata.put("required", required);
            }
            if (implicit) metadata.put("implicitSingleConstructor", true);
            String selectedQualifier = qualifier == null ? null : qualifier.string("value");
            if (selectedQualifier == null && qualifier != null)
                selectedQualifier = qualifier.string("name");
            if (injection != null && injection.rootFqn().endsWith(".Resource")) {
                String name = injection.string("name");
                if (selectedQualifier == null)
                    selectedQualifier = name == null ? injection.string("value") : name;
                if (name != null && !name.isBlank()) metadata.put("resourceName", name);
                else metadata.put("resourceDefaultName", site.substring(site.indexOf(':') + 1));
                Map<String, Object> attributes =
                        annotations.raw(injectionDeclaration, injection.directFqn());
                metadata.put("resourceAttributes", attributes);
                if (attributes.keySet().stream()
                        .anyMatch(Set.of("lookup", "mappedName", "type")::contains))
                    metadata.put("matchingUnsupported", true);
            }
            if (selectedQualifier != null && !selectedQualifier.isBlank())
                metadata.put("qualifier", selectedQualifier);
            edge.metadata = Json.writeCompact(metadata);
            if (lookup instanceof JavaModel.FoundType found)
                edge.targetId = identities.declaration(found.declaration());
            else {
                edge.isExternal = true;
                edge.externalTargetFqn = raw.equals("<unresolved>") ? described : raw;
                edge.resolution = GraphConstants.Resolution.CLASSPATH;
            }
            result.edges.add(edge);
        }
    }
}
