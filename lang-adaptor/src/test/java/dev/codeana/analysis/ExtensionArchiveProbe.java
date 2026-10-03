package dev.codeana.analysis;

import dev.codeana.analysis.extension.BuiltinExtensions;
import dev.codeana.analysis.extension.ExtensionRunner;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.RecordArchive;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Framework queries must not keep the decoded declaration/type inventory resident. */
public final class ExtensionArchiveProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int count = 60000;
        Map<String, Object> source =
                Json.object("path", "Large.java", "world", "app", "publish", true);
        try (RecordArchive rows =
                RecordArchive.createFacts(root.resolve("core.bin"), 256L << 20, count)) {
            for (int i = 0; i < count; i++) {
                rows.add(
                        Json.object(
                                "id",
                                "d" + i,
                                "kind",
                                "declaration",
                                "signature",
                                "A#f" + i,
                                "parent",
                                "",
                                "name",
                                "f" + i,
                                "source",
                                "Large.java",
                                "owner",
                                Json.object("world", "app"),
                                "relation",
                                "",
                                "attributes",
                                Json.object(
                                        "element_kind",
                                        "FIELD",
                                        "annotations",
                                        List.of(),
                                        "generated",
                                        false,
                                        "payload",
                                        "汉".repeat(1100) + i,
                                        "type_model",
                                        Json.object(
                                                "version",
                                                2,
                                                "kind",
                                                "DECLARED",
                                                "complete",
                                                true,
                                                "element",
                                                "java.lang.String"))));
            }
            rows.seal();
            if (rows.bytes() < 100L << 20) throw new AssertionError("insufficient fixture");
            Map<String, Object> model =
                    Json.object(
                            "version",
                            dev.codeana.common.ModelArchive.VERSION,
                            "context",
                            "large@MAIN",
                            "sources",
                            List.of(source),
                            "declarations",
                            rows,
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
            Path modelPath = root.resolve("model.bin");
            ModelArchive.write(modelPath, model, 256L << 20);
            try (ModelArchive.OpenModel opened =
                    ModelArchive.open(modelPath, Json.sha(modelPath), 256L << 20, "large@MAIN")) {
                Map<String, Object> config =
                        Json.object(
                                "context",
                                "large@MAIN",
                                "world",
                                "app",
                                "sources",
                                List.of(source),
                                "resources",
                                List.of(),
                                "classpath",
                                List.of(),
                                "lombok",
                                "off",
                                "spring",
                                true,
                                "spring_xml",
                                true,
                                "sofa",
                                true,
                                "fact_limit",
                                100000);
                ExtensionRunner extensions =
                        new ExtensionRunner(config, BuiltinExtensions.REGISTRY);
                List<Object> javaFacts = extensions.analyzeJava(opened.model(), rows);
                if (javaFacts.size() != rows.size())
                    throw new AssertionError("unexpected Java evidence");
                List<Object> projectFacts = extensions.analyzeProject(opened.model(), javaFacts);
                if (!projectFacts.isEmpty())
                    throw new AssertionError("unexpected project evidence");
                if (!Json.list(extensions.report().get("failures")).isEmpty())
                    throw new AssertionError("framework callback failed: " + extensions.report());
            }
            System.out.println(
                    "extensions: "
                            + count
                            + " declarations / "
                            + rows.bytes()
                            + " bytes under 128 MiB heap; Java and project stages passed without"
                            + " truncation");
        }
    }
}
