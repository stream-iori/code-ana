package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.util.*;

public record ProjectResource(String kind, Map<String, Object> captured) {
    public ProjectResource { captured = ExtensionContext.freezeMap(captured); }
    public String path() { return Json.string(Json.map(captured.get("unit")), "path"); }
    public String world() { return Json.string(Json.map(captured.get("unit")), "world"); }
}
