package com.anatomist.framework.lombok;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Stable, signature-level disclosure of detected and modeled Lombok capabilities. */
final class LombokCapabilitySummary {
    static final String GETTER = "getter";
    static final String SETTER = "setter";
    static final String NO_ARGS_CONSTRUCTOR = "no_args_constructor";
    static final String REQUIRED_CONSTRUCTOR = "required_constructor";
    static final String ALL_ARGS_CONSTRUCTOR = "all_args_constructor";
    static final String EQUALS_HASH_CODE = "equals_hash_code";
    static final String TO_STRING = "to_string";
    static final String LOGGER_FIELD = "logger_field";
    static final String VALUE_MODIFIERS = "value_modifiers";
    static final String WILDCARD = "*";

    private static final Map<String, List<String>> MODELED =
            Map.ofEntries(
                    Map.entry("UtilityClass", List.of("utility_class")),
                    Map.entry("Builder", List.of("builder")),
                    Map.entry("SuperBuilder", List.of("super_builder")),
                    Map.entry("Getter", List.of(GETTER)),
                    Map.entry("Setter", List.of(SETTER)),
                    Map.entry("ToString", List.of(TO_STRING)),
                    Map.entry("EqualsAndHashCode", List.of(EQUALS_HASH_CODE)),
                    Map.entry("NoArgsConstructor", List.of(NO_ARGS_CONSTRUCTOR)),
                    Map.entry("RequiredArgsConstructor", List.of(REQUIRED_CONSTRUCTOR)),
                    Map.entry("AllArgsConstructor", List.of(ALL_ARGS_CONSTRUCTOR)),
                    Map.entry(
                            "Data",
                            List.of(
                                    GETTER,
                                    SETTER,
                                    REQUIRED_CONSTRUCTOR,
                                    EQUALS_HASH_CODE,
                                    TO_STRING)),
                    Map.entry(
                            "Value",
                            List.of(GETTER, ALL_ARGS_CONSTRUCTOR, EQUALS_HASH_CODE, TO_STRING)),
                    Map.entry("Slf4j", List.of(LOGGER_FIELD)),
                    Map.entry("XSlf4j", List.of(LOGGER_FIELD)),
                    Map.entry("Log", List.of(LOGGER_FIELD)),
                    Map.entry("Log4j", List.of(LOGGER_FIELD)),
                    Map.entry("Log4j2", List.of(LOGGER_FIELD)),
                    Map.entry("CommonsLog", List.of(LOGGER_FIELD)),
                    Map.entry("JBossLog", List.of(LOGGER_FIELD)),
                    Map.entry("Flogger", List.of(LOGGER_FIELD)));

    private static final Map<String, String> UNMODELED =
            Map.ofEntries(
                    Map.entry("Accessors", "accessors"),
                    Map.entry("With", "with"),
                    Map.entry("CustomLog", "custom_log"),
                    Map.entry("Cleanup", "cleanup"),
                    Map.entry("SneakyThrows", "sneaky_throws"),
                    Map.entry("Synchronized", "synchronized"),
                    Map.entry("Locked", "locked"),
                    Map.entry("Delegate", "delegate"),
                    Map.entry("ExtensionMethod", "extension_method"));

    private final Set<String> detectedAnnotations = new TreeSet<>();
    private final Set<String> modeledCapabilities = new TreeSet<>();
    private final Set<String> partialCapabilities = new TreeSet<>();
    private final Set<String> unmodeledCapabilities = new TreeSet<>();
    private int generatedMemberCount = -1;

    void detect(String annotation) {
        if (annotation == null) return;
        detectedAnnotations.add(annotation);
        for (String capability : MODELED.getOrDefault(annotation, List.of())) model(capability);
        String unsupported = UNMODELED.get(annotation);
        if (unsupported != null) unmodeled(unsupported);
        if ("Value".equals(annotation)) partial(VALUE_MODIFIERS);
    }

    void merge(LombokCapabilitySummary other) {
        if (other == null) return;
        other.detectedAnnotations.forEach(detectedAnnotations::add);
        other.modeledCapabilities.forEach(this::model);
        other.unmodeledCapabilities.forEach(this::unmodeled);
        other.partialCapabilities.forEach(this::partial);
    }

    void model(String capability) {
        if (capability == null
                || partialCapabilities.contains(capability)
                || unmodeledCapabilities.contains(capability)) return;
        modeledCapabilities.add(capability);
    }

    void partial(String capability) {
        if (capability == null) return;
        modeledCapabilities.remove(capability);
        unmodeledCapabilities.remove(capability);
        partialCapabilities.add(capability);
    }

    void complete(String capability) {
        partialCapabilities.remove(capability);
        unmodeledCapabilities.remove(capability);
        modeledCapabilities.add(capability);
    }

    void unmodeled(String capability) {
        if (capability == null || partialCapabilities.contains(capability)) return;
        modeledCapabilities.remove(capability);
        unmodeledCapabilities.add(capability);
    }

    boolean isPartial(String capability) {
        return partialCapabilities.contains(capability);
    }

    void degrade(Set<String> uncertainCapabilities) {
        if (uncertainCapabilities == null || uncertainCapabilities.isEmpty()) return;
        if (uncertainCapabilities.contains(WILDCARD)) {
            List.copyOf(modeledCapabilities).forEach(this::partial);
            return;
        }
        uncertainCapabilities.forEach(
                capability -> {
                    if (modeledCapabilities.contains(capability)) partial(capability);
                });
    }

    void generatedMemberCount(int count) {
        generatedMemberCount = Math.max(0, count);
    }

    boolean hasEvidence() {
        return !detectedAnnotations.isEmpty();
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "ast");
        out.put("semantic_level", "signature-only");
        out.put("detected_annotations", List.copyOf(detectedAnnotations));
        out.put("modeled_capabilities", List.copyOf(modeledCapabilities));
        out.put("partial_capabilities", List.copyOf(partialCapabilities));
        out.put("unmodeled_capabilities", List.copyOf(unmodeledCapabilities));
        out.put("coverage", coverage());
        if (generatedMemberCount >= 0) out.put("generated_member_count", generatedMemberCount);
        out.put("inference_policy", "hypothesis_only");
        return out;
    }

    private String coverage() {
        if (!partialCapabilities.isEmpty()) return "partial";
        if (!unmodeledCapabilities.isEmpty()) {
            return modeledCapabilities.isEmpty() ? "none" : "partial";
        }
        return modeledCapabilities.isEmpty() ? "none" : "complete";
    }
}
