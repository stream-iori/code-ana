package dev.codeana.adapter;

import dev.codeana.common.*;

import java.util.*;

/** Mutable wire maps are confined to the existing Native/JVM output boundary. */
record AnalysisResult(String mode, List<Map<String, Object>> replacements, GraphArchive facts) {
    Map<String, Object> wire() {
        return Json.object(
                "version", 10, "mode", mode, "replacements", replacements, "facts", facts);
    }
}
