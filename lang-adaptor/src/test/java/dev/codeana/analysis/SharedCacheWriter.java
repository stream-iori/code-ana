package dev.codeana.analysis;

import dev.codeana.common.*;

import java.nio.file.*;
import java.util.*;

public final class SharedCacheWriter {
    public static void main(String[] args) throws Exception {
        Map<String, Object> request = Json.map(Json.parse(Json.read(Path.of(args[0]))));
        List<Object> contexts = Json.list(request.get("contexts"));
        long budget = Json.number(request, "pin_limit");
        try (CompilerSession session = new CompilerSession(request);
                ModuleSignatures modules =
                        new ModuleSignatures(request, contexts, session, budget, 256L << 20)) {
            List<Map<String, Object>> models = new ArrayList<>();
            for (int i = 0; i < 2; i++) models.add(session.analyze(Json.map(contexts.get(i))));
            SignatureCache files = new SignatureCache(request, budget);
            for (int i = 0; i < 16; i++) {
                int index = i % 2;
                Map<String, Object> context = Json.map(contexts.get(index)),
                        model = models.get(index);
                if (args[1].equals("files")) files.publish(context, model);
                else modules.publish(context, model);
            }
            if (args[1].equals("files") && files.writeFailures() != 0)
                throw new AssertionError("optional writer failures=" + files.writeFailures());
            if (args[1].equals("modules")
                    && !Json.map(modules.report().get("module_signature_fallbacks")).isEmpty())
                throw new AssertionError(Json.write(modules.report()));
            for (Map<String, Object> model : models) session.releaseModel(model);
        }
        System.out.println("accepted");
    }
}
