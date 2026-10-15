package com.anatomist.framework.sofa;

import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import com.anatomist.model.ExtractionResult;

import java.util.*;

/** Detached SOFA source registrations, checked against the internal Runtime 4.19.1 API shape. */
public final class SofaJavaModelAnnotations implements JavaModelAnalyzer {
    private static final String API = "com.alipay.sofa.runtime.api.annotation.";

    public String id() {
        return "sofa-annotations";
    }

    public String version() {
        return "detached-1";
    }

    public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {
        var annotations = new JavaModelAnnotations(unit);
        var identities = unit.identities();
        for (var reference : unit.model().declarations(unit.source())) {
            var declaration = unit.model().declaration(reference);
            boolean provider = Set.of("CLASS", "INTERFACE").contains(declaration.symbol().kind());
            if (!provider && !declaration.symbol().kind().equals("FIELD")) continue;
            String annotationName = API + (provider ? "SofaService" : "SofaReference");
            var match = annotations.first(reference, Set.of(annotationName)).orElse(null);
            if (match == null) continue;
            var owner = provider ? reference : unit.model().parent(reference).orElse(null);
            if (owner == null) continue;
            var types = unit.model().unit(reference.source()).types();
            String contract =
                    JavaModelAnnotations.className(match.attributes().get("interfaceType"));
            if (contract == null || contract.equals("void")) {
                if (provider)
                    contract =
                            declaration.interfaces().size() == 1
                                    ? display(types, declaration.interfaces().getFirst())
                                    : null;
                else contract = display(types, declaration.symbol().declaredType());
            }
            String symbol = unit.model().declaration(owner).canonicalName();
            String site = provider ? symbol : symbol + "#" + declaration.symbol().name(),
                    role = provider ? "provider" : "requirement";
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("framework", "sofa");
            metadata.put("role", role);
            metadata.put("kind", "dependency");
            metadata.put("mechanism", "sofa.annotation." + role);
            metadata.put("annotation", match.directFqn());
            Map<String, Object> raw = annotations.raw(reference, match.directFqn());
            metadata.put("attributes", raw);
            metadata.put("injectionSite", site);
            metadata.put("contract", Objects.toString(contract, ""));
            String unique = match.string("uniqueId");
            metadata.put("uniqueId", Objects.toString(unique, ""));
            var members = annotations.members(match.directFqn());
            if (members.isPresent())
                metadata.put("apiMembers", members.get().stream().sorted().toList());
            else metadata.put("apiResolutionFailure", "annotation declaration unavailable");
            boolean supported =
                    members.isPresent()
                            && members.get().equals(Set.of("interfaceType", "uniqueId"))
                            && contract != null
                            && !contract.equals("void")
                            && !contract.equals("<unresolved>")
                            && unique != null
                            && !unique.contains("${")
                            && !unique.contains("#{")
                            && Set.of("interfaceType", "uniqueId").containsAll(raw.keySet());
            metadata.put("contractSupported", supported);
            metadata.put("transport", supported ? "jvm" : "unknown");
            metadata.put(
                    "contractBaseline",
                    supported ? "internal-runtime-4.19.1-api" : "unverified-api");
            if (provider) metadata.put("targetSymbol", symbol);
            var span = annotations.span(reference, match.directFqn());
            var source = unit.model().source(reference.source());
            int line = span.present() ? source.position(span.start()).line() : 1;
            int end =
                    span.present()
                            ? source.position(Math.max(span.start(), span.end() - 1)).line()
                            : line;
            var node =
                    FrameworkRegistration.add(
                            result,
                            identities.declaration(owner),
                            unit.sourceFile(),
                            line,
                            end,
                            site + ":" + role,
                            site,
                            metadata);
            node.module = unit.identity().module();
            node.scope = unit.identity().scope().name();
        }
    }

    private static String display(JavaTypes types, int reference) {
        if (reference < 0 || types.get(reference).kind() == JavaType.Kind.ERROR) return null;
        var type = types.get(reference);
        return type.display().isEmpty() ? JavaTypeNames.erased(types, reference) : type.display();
    }
}
