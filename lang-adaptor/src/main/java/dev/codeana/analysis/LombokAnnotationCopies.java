package dev.codeana.analysis;

import dev.codeana.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Conservative union of copy policies verified against the two processor fixture versions. */
final class LombokAnnotationCopies {
    private static final Set<String> CANDIDATES = load();

    private LombokAnnotationCopies() {}

    static boolean needsModel(Map<String, Object> annotation) {
        return willCopy(annotation) && !modeled(annotation);
    }

    static boolean willCopy(Map<String, Object> annotation) {
        return CANDIDATES.contains(descriptor(annotation))
                || descriptor(annotation).equals("Ljava/lang/Deprecated;");
    }

    static boolean modeled(Map<String, Object> annotation) {
        return descriptor(annotation).equals("Llombok/NonNull;")
                || descriptor(annotation).equals("Ljava/lang/Deprecated;")
                        && Json.map(annotation.get("explicit")).isEmpty();
    }

    static boolean willCopy(String name) {
        return CANDIDATES.contains("L" + name.replace('.', '/') + ";")
                || name.equals("java.lang.Deprecated");
    }

    private static String descriptor(Map<String, Object> annotation) {
        return "L" + Json.string(annotation, "type").replace('.', '/') + ";";
    }

    private static Set<String> load() {
        try (var input =
                LombokAnnotationCopies.class.getResourceAsStream(
                        "/lombok/annotation-copy-candidates.txt")) {
            if (input == null)
                throw new IllegalStateException("missing Lombok annotation copy policy");
            byte[] bytes = input.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024)
                throw new IllegalStateException("Lombok copy policy exceeds budget");
            Set<String> result = new HashSet<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                result.add("L" + line.replace('.', '/') + ";");
            }
            if (result.size() > 512)
                throw new IllegalStateException("Lombok copy policy exceeds count budget");
            return Set.copyOf(result);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load Lombok annotation copy policy", failure);
        }
    }
}
