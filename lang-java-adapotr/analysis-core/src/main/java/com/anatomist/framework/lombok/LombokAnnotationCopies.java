package com.anatomist.framework.lombok;

import com.anatomist.core.java.JavaAnnotation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Conservative union of copy policies verified against the two processor fixture versions. */
final class LombokAnnotationCopies {
    private static final Set<String> CANDIDATES = load();

    private LombokAnnotationCopies() {}

    static boolean needsModel(JavaAnnotation annotation) {
        return willCopy(annotation) && !modeled(annotation);
    }

    static boolean willCopy(JavaAnnotation annotation) {
        return CANDIDATES.contains(annotation.descriptor())
                || annotation.descriptor().equals("Ljava/lang/Deprecated;");
    }

    static boolean modeled(JavaAnnotation annotation) {
        return annotation.descriptor().equals("Llombok/NonNull;")
                || annotation.descriptor().equals("Ljava/lang/Deprecated;")
                        && annotation.values().isEmpty();
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
