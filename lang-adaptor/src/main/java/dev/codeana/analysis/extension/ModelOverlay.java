package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.*;

/** A callback's private pending headers. Validation precedes the single commit. */
public final class ModelOverlay {
    private final List<Object> declarations = new ArrayList<>();
    private final Set<String> removedConstructors = new HashSet<>();
    private final Map<String, Map<String, Object>> descriptions = new TreeMap<>();
    /** Only a compiler-created constructor may be replaced by a static declaration model. */
    public void removeCompilerConstructor(String signature) { removedConstructors.add(signature); }
    /** Separate modeled metadata; never overwrites the compiler's modifiers or binding evidence. */
    public void describe(String signature, Map<String, Object> details) {
        if (descriptions.size() >= 100000) throw new IllegalArgumentException("EXTENSION_MODEL_BUDGET");
        descriptions.put(signature, ExtensionContext.pendingMap(details));
    }
    public void add(Map<String, Object> declaration) {
        if (declarations.size() >= 100000) throw new IllegalArgumentException("EXTENSION_MODEL_BUDGET");
        declarations.add(ExtensionContext.pendingMap(declaration));
    }
    void commit(Map<String, Object> model, String producer, long limit) {
        if (limit < 1 || limit > 5000000) throw new IllegalArgumentException("JAVA_MODEL_RECORD_LIMIT_RANGE");
        if (declarations.size() > limit - Json.list(model.get("declarations")).size())
            throw new IllegalArgumentException("EXTENSION_MODEL_BUDGET");
        Map<String, Map<String, Object>> known = new HashMap<>();
        for (Object raw : Json.list(model.get("declarations")))
            known.put(Json.string(Json.map(raw), "signature"), Json.map(raw));
        for (String signature : removedConstructors) {
            Map<String, Object> original = known.get(signature);
            if (original == null || !Boolean.TRUE.equals(original.get("generated"))
                    || !"CONSTRUCTOR".equals(Json.map(original.get("attributes")).get("element_kind")))
                throw new IllegalArgumentException("EXTENSION_SOURCE_DECLARATION_REMOVAL");
            known.remove(signature);
        }
        for (String signature : descriptions.keySet())
            if (!known.containsKey(signature)) throw new IllegalArgumentException("EXTENSION_MODEL_SUBJECT");
        for (Object raw : declarations) {
            Map<String, Object> d = Json.map(raw), a = Json.map(d.get("attributes"));
            Map<String, Object> origin = known.get(a.get("generated_from"));
            String parent = Json.string(d, "parent"), name = Json.string(d, "name"), signature = Json.string(d, "signature");
            String kind = Json.string(a, "element_kind");
            boolean member = Set.of("FIELD", "METHOD", "CONSTRUCTOR").contains(kind);
            boolean type = Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE").contains(kind);
            if (!"declaration".equals(d.get("kind")) || !Boolean.TRUE.equals(d.get("generated"))
                    || Json.number(d, "start") != -1 || Json.number(d, "end") != -1
                    || !Boolean.FALSE.equals(a.get("body_available")) || origin == null
                    || !Objects.equals(d.get("source"), origin.get("source"))
                    || !known.containsKey(parent) || name.isBlank() || signature.isBlank()
                    || !(member && (kind.equals("FIELD") ? signature.equals(parent + '#' + name)
                        : signature.startsWith(parent + '#' + name + '(') && signature.endsWith(")"))
                        || type && signature.equals(parent + '$' + name))
                    || known.putIfAbsent(Json.string(d, "signature"), d) != null)
                throw new IllegalArgumentException("EXTENSION_MODEL_OWNERSHIP_OR_EVIDENCE");
            a.put("extension_producer", producer);
            a.put("binding_origin", producer.equals("lombok-model-v1") ? "lombok_model" : producer);
        }
        List<Object> committed = Json.list(model.get("declarations"));
        committed.removeIf(raw -> removedConstructors.contains(Json.string(Json.map(raw), "signature")));
        committed.addAll(declarations);
        for (var entry : descriptions.entrySet()) {
            Map<String, Object> attrs = Json.map(known.get(entry.getKey()).get("attributes"));
            Map<String, Object> metadata = new TreeMap<>(attrs.get("model_extensions") instanceof Map
                    ? Json.map(attrs.get("model_extensions")) : Map.of());
            metadata.put(producer, entry.getValue()); attrs.put("model_extensions", metadata);
        }
    }
}
