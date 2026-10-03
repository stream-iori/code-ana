package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.RecordArchive;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class LookupArchiveProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int count = 20000, calls = 1000;
        Map<String, Object> source =
                Json.object("path", "Large.java", "world", "app", "publish", true);
        try (RecordArchive declarations =
                        RecordArchive.create(root.resolve("declarations.bin"), 128L << 20, count);
                RecordArchive core =
                        RecordArchive.create(root.resolve("core.bin"), 16L << 20, calls)) {
            for (int i = 0; i < count; i++)
                declarations.add(
                        Json.object(
                                "signature",
                                "C" + i,
                                "source",
                                "Large.java",
                                "parent",
                                "",
                                "name",
                                "C" + i,
                                "attributes",
                                Json.object(
                                        "element_kind",
                                        "CLASS",
                                        "annotations",
                                        i == count - 1
                                                ? List.of(Json.object("type", "lombok.Getter"))
                                                : List.of(),
                                        "payload",
                                        "汉".repeat(1000) + i)));
            declarations.seal();
            Map<String, Object> model =
                    Json.object(
                            "version",
                            dev.codeana.common.ModelArchive.VERSION,
                            "context",
                            "lookup@MAIN",
                            "sources",
                            List.of(source),
                            "declarations",
                            declarations,
                            "syntax",
                            List.of(),
                            "external",
                            List.of(),
                            "observations",
                            List.of(),
                            "annotation_types",
                            List.of(),
                            "diagnostics",
                            List.of());
            Path file = root.resolve("model.bin");
            ModelArchive.write(file, model, 128L << 20);
            for (int i = 0; i < calls; i++)
                core.add(
                        Json.object(
                                "id",
                                "c" + i,
                                "kind",
                                "call_site",
                                "source",
                                "Large.java",
                                "span",
                                null,
                                "name",
                                "getValue",
                                "signature",
                                "",
                                "parent",
                                "",
                                "target",
                                "",
                                "relation",
                                "",
                                "owner",
                                Json.object(
                                        "world",
                                        "app",
                                        "id",
                                        "Large.java",
                                        "kind",
                                        "source",
                                        "producer",
                                        "javac-java8-v1"),
                                "attributes",
                                Json.object("receiver_type", "C" + (count - 1))));
            core.seal();
            try (ModelArchive.OpenModel opened =
                    ModelArchive.open(file, Json.sha(file), 128L << 20, "lookup@MAIN")) {
                Map<String, Object> config =
                        Json.object(
                                "context",
                                "lookup@MAIN",
                                "world",
                                "app",
                                "sources",
                                List.of(source),
                                "resources",
                                List.of(),
                                "classpath",
                                List.of(),
                                "lombok",
                                "model",
                                "spring",
                                false,
                                "sofa",
                                false,
                                "fact_limit",
                                100000);
                ExtensionRunner extensions =
                        new ExtensionRunner(config, BuiltinExtensions.REGISTRY);
                List<Object> output = extensions.analyzeJava(opened.model(), core);
                if (output.size() != 2 * calls
                        || !Json.list(extensions.report().get("failures")).isEmpty())
                    throw new AssertionError(extensions.report().toString());
                ExtensionContext context =
                        new ExtensionContext(config, opened.model(), output, List.of());
                Map<String, Object> found = context.declaration("C" + (count - 1)).orElseThrow();
                try {
                    found.put("mutate", true);
                    throw new AssertionError("mutable lookup");
                } catch (UnsupportedOperationException expected) {
                }
                if (context.declaration("absent").isPresent())
                    throw new AssertionError("invented declaration");
                System.out.println(
                        "indexed lookup: "
                                + count
                                + " declarations / "
                                + declarations.bytes()
                                + " bytes / "
                                + calls
                                + " call callbacks under 128 MiB heap; complete immutable"
                                + " evidence");
            }
        }
    }
}
