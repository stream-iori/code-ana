package dev.codeana.analysis;

import dev.codeana.analysis.extension.BuiltinExtensions;
import dev.codeana.analysis.extension.ExtensionRunner;
import dev.codeana.common.Json;
import java.util.*;

/** Eligibility and repeatability of the signature-only AST overlay. */
public final class LombokAstSharingProbe {
    static Map<String,Object> declaration(String signature, String parent, String kind,
            Map<String,Object> attributes) {
        attributes.put("element_kind", kind);
        attributes.put("modifiers", List.of("public"));
        return Json.object("kind", "declaration", "signature", signature, "parent", parent,
                "name", signature.contains("#") ? signature.substring(signature.indexOf('#')+1) : signature,
                "source", "A.java", "start", 0, "end", 1, "generated", false,
                "attributes", attributes);
    }
    public static void main(String[] args) throws Exception {
        var type = Json.object("version", 2, "kind", "INT", "complete", true);
        var owner = declaration("A", "", "CLASS", Json.object("annotations", List.of(
                Json.object("type", "lombok.Getter", "complete", true,
                        "effective", Map.of(), "explicit", Map.of()))));
        var field = declaration("A#value", "A", "FIELD", Json.object("annotations", List.of(),
                "has_initializer", false, "type_model", type, "type", "int"));
        List<Object> declarations = new ArrayList<>(List.of(owner, field));
        List<Object> forbiddenSyntax = new AbstractList<>() {
            public int size() { return 1; }
            public Object get(int index) { throw new AssertionError("unrelated syntax scanned"); }
        };
        var model = Json.object("declarations", declarations, "syntax", forbiddenSyntax);
        var config = Json.object("lombok", "model", "sources", List.of(), "resources", List.of());
        LombokModel.augment(model, config);
        model.put("syntax", List.of());
        var runner = new ExtensionRunner(config, BuiltinExtensions.REGISTRY);
        if (declarations.stream().map(Json::map).noneMatch(d -> "A#getValue()".equals(d.get("signature"))))
            throw new AssertionError("getter missing");
        String before = Json.write(declarations);
        runner.augment(model);
        if (!before.equals(Json.write(declarations))) throw new AssertionError("overlay not idempotent");
        if (!LombokModel.pendingSources(model).isEmpty()) throw new AssertionError("processed source pending");
        System.out.println("explicit initializer metadata skips syntax; source overlay is idempotent");
    }
}
