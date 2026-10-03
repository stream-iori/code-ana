package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.util.*;

/** Shared bounded annotation matching from detached source and binary declaration headers. */
public final class ModelAnnotations {
    private static final String ALIAS = "org.springframework.core.annotation.AliasFor";
    private static final int CACHE_LIMIT = 4096;
    private final Map<String, Map<String, Object>> types = new HashMap<>();
    private final Map<String, List<Object>> declarations = new HashMap<>();
    private final Map<String, String> declarationSources = new HashMap<>();
    private final Map<String, Integer> sourceWork = new HashMap<>();
    private final Map<PathKey, List<String>> paths = new HashMap<>();
    private final Set<String> limitedSources = new HashSet<>();
    private String currentSource = "";
    private int queryWork;
    private boolean queryLimited;
    private long firstCalls, metaPathVisits, cacheHits;

    private record PathKey(String annotation, Set<String> roots) {
        private PathKey { roots = Set.copyOf(roots); }
    }

    public record Counters(long firstCalls, long metaPathVisits, long cacheHits) {}

    public Counters counters() { return new Counters(firstCalls, metaPathVisits, cacheHits); }

    public record Match(
            String direct,
            String root,
            List<String> path,
            Map<String, Object> values,
            boolean complete) {
        public Map<String, Object> evidence() {
            return Json.object(
                    "direct_annotation",
                    direct,
                    "root_annotation",
                    root,
                    "meta_path",
                    path,
                    "attribute_status",
                    complete ? "static" : "unsupported");
        }
    }

    public ModelAnnotations(Map<String, Object> model) {
        for (Object raw : items(model.get("annotation_types"))) {
            Map<String, Object> header = Json.map(raw);
            types.put(Json.string(header, "name"), header);
        }
        for (String section : List.of("declarations", "external"))
            for (Object raw : items(model.get(section))) {
                Map<String, Object> declaration = Json.map(raw);
                List<Object> annotations =
                        declared(declaration);
                // Matching needs annotation metadata, not the declaration's potentially large
                // types.
                declarations.put(Json.string(declaration, "signature"), annotations);
                declarationSources.put(Json.string(declaration, "signature"),
                        declaration.get("source") instanceof String ? (String) declaration.get("source") : "");
            }
    }

    /** Extension view only. Compiler annotations and their unresolved spelling stay intact. */
    public static List<Object> declared(Map<String, Object> declaration) {
        var attributes = Json.map(declaration.get("attributes"));
        List<Object> result = new ArrayList<>(items(attributes.get("annotations")));
        if (attributes.get("prerequisite_annotations") instanceof Map) {
            for (Object raw : items(Json.map(attributes.get("prerequisite_annotations")).get("annotations"))) {
                var annotation=Json.map(raw);String name=Json.string(annotation,"type");
                String simple=name.substring(Math.max(name.lastIndexOf('.'),name.lastIndexOf('$'))+1);
                result.removeIf(old -> !Boolean.TRUE.equals(Json.map(old).get("complete"))
                        && simple.equals(Json.map(old).get("type")));
                result.add(annotation);
            }
        }
        for (Object raw : items(attributes.get("source_annotations"))) {
            var written = new LinkedHashMap<>(Json.map(raw));
            // This is completeness of the written option values in an extension view,
            // not compiler confirmation of the annotation declaration.
            written.put("complete", Boolean.TRUE.equals(written.get("source_options_complete")));
            written.put("declaration_resolved", false);
            String spelling = Json.string(written, "source_name");
            if (result.stream().map(Json::map).anyMatch(a -> Boolean.TRUE.equals(a.get("complete"))
                    && written.get("type").equals(a.get("type")))) continue;
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                var annotation = Json.map(result.get(i));
                if (!Boolean.TRUE.equals(annotation.get("complete"))
                        && (spelling.equals(annotation.get("type")) || written.get("type").equals(annotation.get("type"))))
                    matches.add(i);
            }
            if (matches.size() == 1) result.set(matches.getFirst(), written);
            else if (matches.isEmpty()) result.add(written);
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean matchesWritten(Map<String, Object> declaration, String qualified, String observed) {
        if (qualified.equals(observed)) return true;
        List<Object> evidence = items(Json.map(declaration.get("attributes")).get("source_annotations"));
        return evidence.stream().map(Json::map).filter(a -> observed.equals(a.get("source_name"))).count() == 1
                && evidence.stream().map(Json::map).anyMatch(a -> observed.equals(a.get("source_name"))
                        && qualified.equals(a.get("type")));
    }

    private static List<Object> items(Object value) {
        return value instanceof List ? Json.list(value) : List.of();
    }

    private boolean visit(int depth) {
        int total = sourceWork.getOrDefault(currentSource, 0);
        boolean allowed = depth <= 64 && ++queryWork <= 100000 && total < 1000000;
        if (allowed) sourceWork.put(currentSource, total + 1);
        else {
            queryLimited = true;
            limitedSources.add(currentSource);
        }
        return allowed;
    }

    public boolean limited() {
        return !limitedSources.isEmpty();
    }

    public Set<String> limitedSources() { return Set.copyOf(limitedSources); }

    public Optional<Set<String>> members(String annotation) {
        Map<String, Object> header = types.get(annotation);
        if (header == null || !Boolean.TRUE.equals(header.get("complete"))) return Optional.empty();
        Set<String> result = new TreeSet<>();
        for (Object raw : items(header.get("members")))
            result.add(Json.string(Json.map(raw), "name"));
        return Optional.of(Collections.unmodifiableSet(result));
    }

    public Optional<Match> first(String signature, Set<String> roots) {
        currentSource = declarationSources.getOrDefault(signature, "");
        List<Object> declaration = declarations.get(signature);
        if (declaration == null) return Optional.empty();
        return firstOn(declaration, roots);
    }

    public Optional<Match> firstOn(List<Object> annotations, Set<String> roots) {
        firstCalls++;
        queryWork = 0;
        queryLimited = false;
        for (Object raw : annotations) {
            Map<String, Object> annotation = Json.map(raw);
            String direct = Json.string(annotation, "type");
            PathKey key = new PathKey(direct, roots);
            List<String> path = paths.get(key);
            if (path == null) {
                path = path(direct, roots, new HashSet<>(), 0);
                if (!queryLimited) {
                    if (paths.size() >= CACHE_LIMIT) paths.clear();
                    paths.put(key, List.copyOf(path));
                }
            } else cacheHits++;
            if (path.isEmpty()) continue;
            Effective effective = effective(annotation, path, 0);
            return Optional.of(
                    new Match(
                            direct,
                            path.getLast(),
                            List.copyOf(path),
                            Collections.unmodifiableMap(effective.values),
                            effective.complete));
        }
        return Optional.empty();
    }

    private List<String> path(String current, Set<String> roots, Set<String> seen, int depth) {
        metaPathVisits++;
        if (!visit(depth) || !seen.add(current)) return List.of();
        try {
            if (roots.contains(current)) return List.of(current);
            if (depth >= 16) return List.of();
            Map<String, Object> header = types.get(current);
            if (header == null || !Boolean.TRUE.equals(header.get("complete"))) return List.of();
            List<Object> metadata = new ArrayList<>(items(header.get("annotations")));
            metadata.sort(Comparator.comparing(raw -> Json.string(Json.map(raw), "type")));
            for (Object raw : metadata) {
                List<String> child =
                        path(Json.string(Json.map(raw), "type"), roots, seen, depth + 1);
                if (!child.isEmpty()) {
                    List<String> result = new ArrayList<>();
                    result.add(current);
                    result.addAll(child);
                    return result;
                }
            }
            return List.of();
        } finally {
            seen.remove(current);
        }
    }

    private static final class Effective {
        final TreeMap<String, Object> values;
        boolean complete;

        Effective(Map<String, Object> values, boolean complete) {
            this.values = new TreeMap<>(values);
            this.complete = complete;
        }
    }

    private Effective effective(Map<String, Object> annotation, List<String> path, int index) {
        if (!visit(index)) return new Effective(Map.of(), false);
        Map<String, Object> values = new TreeMap<>(Json.map(annotation.get("effective")));
        Map<String, Object> explicit = Json.map(annotation.get("explicit"));
        String current = path.get(index);
        Map<String, Object> header = types.get(current);
        boolean complete = Boolean.TRUE.equals(annotation.get("complete"));
        List<Object> members = header == null ? List.of() : items(header.get("members"));
        // Same-annotation aliases are evaluated before forwarding to meta annotations.
        for (Object raw : members) {
            Map<String, Object> member = Json.map(raw);
            for (Object aliasRaw : items(member.get("annotations"))) {
                Map<String, Object> alias = Json.map(aliasRaw);
                if (!ALIAS.equals(alias.get("type"))) continue;
                if (!Boolean.TRUE.equals(alias.get("complete"))) {
                    complete = false;
                    continue;
                }
                Map<String, Object> a = Json.map(alias.get("effective"));
                String targetType = aliasType(a, current),
                        target = aliasAttribute(a, Json.string(member, "name"));
                if (!validAlias(a, targetType, target)) {
                    complete = false;
                    continue;
                }
                if (!current.equals(targetType)) continue;
                String source = Json.string(member, "name");
                if (explicit.containsKey(source)) {
                    if (explicit.containsKey(target)
                            && !Objects.equals(explicit.get(source), explicit.get(target)))
                        complete = false;
                    values.put(target, explicit.get(source));
                }
            }
        }
        if (index == path.size() - 1) return new Effective(values, complete);
        String next = path.get(index + 1);
        Map<String, Object> meta = null;
        for (Object raw : header == null ? List.of() : items(header.get("annotations")))
            if (next.equals(Json.map(raw).get("type"))) meta = new LinkedHashMap<>(Json.map(raw));
        if (meta == null) return new Effective(Map.of(), false);
        Map<String, Object> forwarded = new TreeMap<>(Json.map(meta.get("effective")));
        Map<String, Object> forwardedExplicit = new TreeMap<>(Json.map(meta.get("explicit")));
        Map<String, Object> rootOverrides = new TreeMap<>();
        for (Object raw : members) {
            Map<String, Object> member = Json.map(raw);
            String source = Json.string(member, "name");
            Object value = values.get(source);
            if (!explicit.containsKey(source) && empty(value)) continue;
            for (Object aliasRaw : items(member.get("annotations"))) {
                Map<String, Object> alias = Json.map(aliasRaw);
                if (!ALIAS.equals(alias.get("type"))) continue;
                if (!Boolean.TRUE.equals(alias.get("complete"))) {
                    complete = false;
                    continue;
                }
                Map<String, Object> a = Json.map(alias.get("effective"));
                String targetType = aliasType(a, current), target = aliasAttribute(a, source);
                if (!validAlias(a, targetType, target)) {
                    complete = false;
                    continue;
                }
                if (next.equals(targetType)) {
                    forwarded.put(target, value);
                    forwardedExplicit.put(target, value);
                } else if (path.getLast().equals(targetType)) rootOverrides.put(target, value);
                else if (!current.equals(targetType)) complete = false;
            }
        }
        meta.put("effective", forwarded);
        meta.put("explicit", forwardedExplicit);
        Effective result = effective(meta, path, index + 1);
        result.values.putAll(rootOverrides);
        result.complete &= complete;
        return result;
    }

    private static boolean empty(Object value) {
        return value == null
                || "".equals(value)
                || value instanceof Map
                        && "array".equals(Json.map(value).get("kind"))
                        && items(Json.map(value).get("values")).isEmpty();
    }

    private boolean validAlias(Map<String, Object> attributes, String type, String member) {
        Object value = attributes.get("value"), attribute = attributes.get("attribute");
        if (value instanceof String a
                && attribute instanceof String b
                && !a.isEmpty()
                && !b.isEmpty()
                && !a.equals(b)) return false;
        Map<String, Object> target = types.get(type);
        return target != null
                && items(target.get("members")).stream()
                        .anyMatch(raw -> member.equals(Json.map(raw).get("name")));
    }

    private static String aliasType(Map<String, Object> attributes, String current) {
        Object raw = attributes.get("annotation");
        String name = raw instanceof Map ? (String) Json.map(raw).get("name") : null;
        Object descriptor = raw instanceof Map ? Json.map(raw).get("descriptor") : null;
        if (descriptor instanceof String d && d.startsWith("L") && d.endsWith(";"))
            name = d.substring(1, d.length() - 1).replace('/', '.');
        return name == null || name.equals("java.lang.annotation.Annotation") ? current : name;
    }

    private static String aliasAttribute(Map<String, Object> attributes, String fallback) {
        Object name = attributes.get("attribute"), value = attributes.get("value");
        return name instanceof String && !name.equals("")
                ? (String) name
                : value instanceof String && !value.equals("") ? (String) value : fallback;
    }

    public static List<String> strings(Object value) {
        if (value instanceof String text) return List.of(text);
        if (value instanceof Map && "array".equals(Json.map(value).get("kind"))) {
            List<String> result = new ArrayList<>();
            for (Object item : items(Json.map(value).get("values"))) {
                if (!(item instanceof String)) return List.of();
                result.add((String) item);
            }
            return result;
        }
        return List.of();
    }

    public static List<Object> values(Object value) {
        return value == null
                ? List.of()
                : value instanceof Map && "array".equals(Json.map(value).get("kind"))
                        ? items(Json.map(value).get("values"))
                        : List.of(value);
    }

    public static String className(Object value) {
        if (!(value instanceof Map) || !"class".equals(Json.map(value).get("kind"))) return null;
        Map<String, Object> type = Json.map(value);
        if (Boolean.FALSE.equals(type.get("complete"))) return null;
        Object descriptor = type.get("descriptor");
        if (descriptor instanceof String d && d.startsWith("L") && d.endsWith(";"))
            return d.substring(1, d.length() - 1).replace('/', '.');
        return (String) type.get("name");
    }
}
