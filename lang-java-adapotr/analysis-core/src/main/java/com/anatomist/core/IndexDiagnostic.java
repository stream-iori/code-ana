package com.anatomist.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record IndexDiagnostic(
        String severity,
        String code,
        String phase,
        String sourceFile,
        String module,
        String scope,
        String symbol,
        long count,
        String sample,
        String language,
        String providerId,
        String providerReason,
        String reasonCode,
        String sampleDetails) {
    public IndexDiagnostic(
            String severity,
            String code,
            String phase,
            String sourceFile,
            String module,
            String scope,
            String symbol,
            long count,
            String sample,
            String language,
            String providerId,
            String providerReason) {
        this(
                severity,
                code,
                phase,
                sourceFile,
                module,
                scope,
                symbol,
                count,
                sample,
                language,
                providerId,
                providerReason,
                null,
                null);
    }

    public IndexDiagnostic(
            String severity,
            String code,
            String phase,
            String sourceFile,
            String module,
            String scope,
            String symbol,
            long count,
            String sample) {
        this(
                severity,
                code,
                phase,
                sourceFile,
                module,
                scope,
                symbol,
                count,
                sample,
                null,
                null,
                null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", severity);
        out.put("code", code);
        out.put("phase", phase);
        if (sourceFile != null) out.put("source_file", sourceFile);
        if (module != null) out.put("module", module);
        if (scope != null) out.put("scope", scope);
        if (symbol != null) out.put("symbol", symbol);
        if (language != null) out.put("language", language);
        if (providerId != null) out.put("provider_id", providerId);
        if (providerReason != null) out.put("provider_reason", providerReason);
        if (reasonCode != null) out.put("reason_code", reasonCode);
        if (sampleDetails != null)
            out.put("sample_details", com.anatomist.json.Json.parseTree(sampleDetails));
        out.put("count", count);
        if (sample != null)
            out.put("sample", sample.length() > 500 ? sample.substring(0, 500) : sample);
        return out;
    }
}
