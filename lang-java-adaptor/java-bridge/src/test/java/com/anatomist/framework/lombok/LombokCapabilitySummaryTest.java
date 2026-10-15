package com.anatomist.framework.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class LombokCapabilitySummaryTest {

    @Test
    void dataAndBuilderAreDisclosedWithoutConflatingDetectionAndModeling() {
        LombokCapabilitySummary summary = new LombokCapabilitySummary();
        summary.detect("Data");
        summary.detect("Builder");
        summary.generatedMemberCount(7);

        Map<String, Object> value = summary.toMap();
        assertEquals(List.of("Builder", "Data"), value.get("detected_annotations"));
        assertEquals(
                List.of(
                        "builder",
                        "equals_hash_code",
                        "getter",
                        "required_constructor",
                        "setter",
                        "to_string"),
                value.get("modeled_capabilities"));
        assertEquals(List.of(), value.get("unmodeled_capabilities"));
        assertEquals("complete", value.get("coverage"));
        assertEquals(7, value.get("generated_member_count"));
    }

    @Test
    void partialStatusWinsAndConfigurationCanDegradeOnlyAffectedCapabilities() {
        LombokCapabilitySummary summary = new LombokCapabilitySummary();
        summary.detect("Data");
        summary.degrade(Set.of(LombokCapabilitySummary.GETTER));
        summary.model(LombokCapabilitySummary.GETTER);

        Map<String, Object> value = summary.toMap();
        assertEquals(List.of("getter"), value.get("partial_capabilities"));
        assertEquals(
                List.of("equals_hash_code", "required_constructor", "setter", "to_string"),
                value.get("modeled_capabilities"));
        assertEquals("partial", value.get("coverage"));
    }

    @Test
    void unsupportedOnlyHasNoModeledCoverage() {
        LombokCapabilitySummary summary = new LombokCapabilitySummary();
        summary.detect("Accessors");

        assertEquals("none", summary.toMap().get("coverage"));
        assertEquals(List.of("accessors"), summary.toMap().get("unmodeled_capabilities"));
    }
}
