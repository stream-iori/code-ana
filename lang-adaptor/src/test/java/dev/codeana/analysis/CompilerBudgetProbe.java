package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.Path;
import java.util.*;

/** Compact assertions over a large real Java 8 model; never prints/materializes its syntax. */
public final class CompilerBudgetProbe {
    public static void main(String[] args) throws Exception {
        Map<String,Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        try (CompilerSession session = new CompilerSession(request)) {
            Map<String,Object> context = Json.map(Json.list(request.get("contexts")).getFirst());
            Map<String,Object> model = session.analyze(context);
            try {
                Map<String,Map<String,Object>> declarations = new HashMap<>();
                for (Object raw : Json.list(model.get("declarations"))) {
                    Map<String,Object> declaration = Json.map(raw);
                    if (declarations.put(Json.string(declaration,"signature"),declaration)!=null)
                        throw new AssertionError("duplicate declaration identity");
                }
                int locals = 0, references = 0;
                for (Map<String,Object> declaration : declarations.values()) {
                    String signature = Json.string(declaration,"signature");
                    if (!signature.contains("#local:x@")) continue;
                    locals++;
                    String owner = Json.string(declaration,"parent");
                    if (!signature.startsWith(owner+"#local:x@") || !declarations.containsKey(owner))
                        throw new AssertionError("local path belongs to a different method");
                }
                for (Object raw : Json.list(model.get("observations"))) {
                    Map<String,Object> observation = Json.map(raw);
                    String target = Json.string(observation,"target");
                    if (target.contains("#local:x@")) {
                        references++;
                        if (!declarations.containsKey(target)) throw new AssertionError("reference used a non-declaration path");
                    }
                }
                System.out.println(Json.write(Json.object("declarations",declarations.size(),"locals",locals,
                        "references",references,"syntax",Json.list(model.get("syntax")).size())));
            } finally { session.releaseModel(model); }
        }
    }
}
