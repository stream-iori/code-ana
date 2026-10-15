package com.anatomist.framework.spring;

import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import com.anatomist.model.*;

import java.util.*;

/** Static Spring MVC registration facts; conditions and runtime dispatch remain unevaluated. */
public final class SpringJavaModelMvc implements JavaModelAnalyzer {
    private static final Set<String> CONTROLLERS =
            Set.of(
                    "org.springframework.stereotype.Controller",
                    "org.springframework.web.bind.annotation.RestController");
    private static final Set<String> REQUEST =
            Set.of("org.springframework.web.bind.annotation.RequestMapping");
    private static final Set<String> MAPPINGS =
            Set.of(
                    "org.springframework.web.bind.annotation.RequestMapping",
                    "org.springframework.web.bind.annotation.GetMapping",
                    "org.springframework.web.bind.annotation.PostMapping",
                    "org.springframework.web.bind.annotation.PutMapping",
                    "org.springframework.web.bind.annotation.DeleteMapping",
                    "org.springframework.web.bind.annotation.PatchMapping");
    private static final Set<String> PARAMS =
            Set.of(
                    "org.springframework.web.bind.annotation.PathVariable",
                    "org.springframework.web.bind.annotation.RequestParam",
                    "org.springframework.web.bind.annotation.RequestBody");

    public String id() {
        return "spring-mvc";
    }

    public String version() {
        return "detached-2";
    }

    public void analyze(JavaUnitAnalysis unit, ExtractionResult result) {
        var annotations = new JavaModelAnnotations(unit);
        var identities = unit.identities();
        int emitted = 0;
        for (var owner : unit.model().declarations(unit.source())) {
            if (!Set.of("CLASS", "INTERFACE")
                            .contains(unit.model().declaration(owner).symbol().kind())
                    || annotations.first(owner, CONTROLLERS).isEmpty()) continue;
            var base =
                    annotations
                            .first(owner, REQUEST)
                            .map(SpringJavaModelMvc::paths)
                            .orElse(List.of(""));
            for (var method : unit.model().members(owner)) {
                if (!unit.model().declaration(method).symbol().kind().equals("METHOD")) continue;
                var match = annotations.first(method, MAPPINGS).orElse(null);
                if (match == null) continue;
                String target = identities.declaration(method);
                int line = SpringDetachedSupport.line(unit, method);
                for (String prefix : base)
                    for (String suffix : paths(match))
                        for (String http : httpMethods(match, annotations, method)) {
                            if (++emitted > 100_000)
                                throw new IllegalArgumentException(
                                        "Spring route expansion exceeds work budget");
                            String path = join(prefix, suffix),
                                    id = "route:" + http + " " + path + "|handler:" + target;
                            Map<String, Object> metadata = new LinkedHashMap<>(match.evidence());
                            metadata.put("mappingAnnotation", match.rootFqn());
                            for (String key : List.of("consumes", "produces", "params")) {
                                var values = match.strings(key);
                                if (!values.isEmpty()) metadata.put(key, values);
                            }
                            metadata.put("parameters", parameters(unit, annotations, method));
                            metadata.put("httpMethod", http);
                            metadata.put("path", path);
                            metadata.put("handler", target);
                            result.nodes.add(
                                    SpringDetachedSupport.node(
                                            unit,
                                            id,
                                            http + " " + path,
                                            GraphConstants.Kind.ROUTE,
                                            line,
                                            metadata));
                            var edge =
                                    SpringDetachedSupport.edge(
                                            unit,
                                            id,
                                            target,
                                            GraphConstants.Relation.HANDLES,
                                            line);
                            edge.beginLine = null;
                            result.edges.add(edge);
                        }
            }
        }
    }

    private static List<Map<String, Object>> parameters(
            JavaUnitAnalysis unit,
            JavaModelAnnotations annotations,
            JavaModel.DeclarationRef method) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var reference : SpringDetachedSupport.parameters(unit, method)) {
            var declaration = unit.model().declaration(reference);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", declaration.symbol().name());
            JavaNode syntax = unit.model().syntax(reference).orElse(null),
                    type = syntax == null ? null : syntax.tree("getType");
            row.put(
                    "type",
                    type != null && type.span().present()
                            ? unit.model().source(reference.source()).slice(type.span())
                            : SpringDetachedSupport.display(
                                    unit.model().unit(reference.source()).types(),
                                    declaration.symbol().declaredType()));
            annotations
                    .first(reference, PARAMS)
                    .ifPresent(
                            match -> {
                                row.put("binding", match.rootFqn());
                                row.put("bindingMetaPath", match.metaPath());
                                String value = match.string("value");
                                if (value != null) row.put("value", value);
                            });
            result.add(row);
        }
        return result;
    }

    private static List<String> paths(JavaModelAnnotations.Match match) {
        var values = match.strings("value");
        if (values.isEmpty()) values = match.strings("path");
        return values.isEmpty() ? List.of("") : List.copyOf(new LinkedHashSet<>(values));
    }

    private static List<String> httpMethods(
            JavaModelAnnotations.Match match,
            JavaModelAnnotations annotations,
            JavaModel.DeclarationRef method) {
        String fixed =
                switch (match.rootFqn()) {
                    case "org.springframework.web.bind.annotation.GetMapping" -> "GET";
                    case "org.springframework.web.bind.annotation.PostMapping" -> "POST";
                    case "org.springframework.web.bind.annotation.PutMapping" -> "PUT";
                    case "org.springframework.web.bind.annotation.DeleteMapping" -> "DELETE";
                    case "org.springframework.web.bind.annotation.PatchMapping" -> "PATCH";
                    default -> null;
                };
        if (fixed != null) return List.of(fixed);
        var value = match.attributes().get("method");
        List<JavaBinaryType.Value> values =
                value instanceof JavaBinaryType.ArrayValue array
                        ? array.values()
                        : value == null ? List.of() : List.of(value);
        Set<String> methods = new LinkedHashSet<>();
        for (var item : values) {
            if (item instanceof JavaBinaryType.EnumValue enumeration)
                methods.add(enumeration.name());
            else {
                String text = JavaModelAnnotations.text(item);
                if (text != null)
                    methods.add(text.substring(text.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
            }
        }
        if (methods.isEmpty())
            methods.addAll(
                    annotations.sourceEnumNames(
                            method,
                            match.directFqn(),
                            "method",
                            "org.springframework.web.bind.annotation.RequestMethod"));
        return methods.isEmpty() ? List.of("ANY") : List.copyOf(methods);
    }

    private static String join(String prefix, String suffix) {
        String left = normalize(prefix), right = normalize(suffix);
        if (left.isEmpty()) return right.isEmpty() ? "/" : right;
        if (right.isEmpty() || right.equals("/")) return left;
        return left.equals("/") ? right : left + right;
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) return "";
        String result = path.strip();
        if (!result.startsWith("/")) result = "/" + result;
        while (result.length() > 1 && result.endsWith("/"))
            result = result.substring(0, result.length() - 1);
        return result;
    }
}
