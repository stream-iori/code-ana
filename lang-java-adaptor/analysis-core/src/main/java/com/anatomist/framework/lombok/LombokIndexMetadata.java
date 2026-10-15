package com.anatomist.framework.lombok;

import com.anatomist.config.LombokMode;
import com.anatomist.config.ProjectConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable project metadata describing the effective Lombok structural model. */
public final class LombokIndexMetadata {
    private LombokIndexMetadata() {}

    public static Map<String, String> detachedSnapshot(
            ProjectConfig config,
            String fingerprint,
            long generatedMembers,
            List<Map<String, Object>> diagnosticCoverage) {
        LombokMode mode = config == null ? LombokMode.OFF : config.lombokMode();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lombok_mode", mode.optionValue());
        values.put(
                "lombok_extension_version",
                mode != LombokMode.OFF ? LombokJavaModelExtension.VERSION : "");
        values.put("lombok_fingerprint", fingerprint == null ? "" : fingerprint);
        values.put("lombok_generated_members", String.valueOf(generatedMembers));
        values.put("lombok_coverage", coverage(mode, diagnosticCoverage));
        return values;
    }

    public static String coverage(LombokMode mode, List<Map<String, Object>> diagnosticCoverage) {
        if (mode == LombokMode.OFF) return "off";
        boolean partial =
                diagnosticCoverage != null
                        && diagnosticCoverage.stream()
                                .filter(row -> "DIAGNOSTIC_DETAILS".equals(row.get("capability")))
                                .flatMap(row -> ((List<?>) row.get("codes")).stream())
                                .map(Object::toString)
                                .anyMatch(
                                        code ->
                                                code.startsWith("LOMBOK_")
                                                        && !"LOMBOK_BODY_SEMANTICS_OMITTED"
                                                                .equals(code));
        return partial ? "partial" : "signature-only";
    }
}
