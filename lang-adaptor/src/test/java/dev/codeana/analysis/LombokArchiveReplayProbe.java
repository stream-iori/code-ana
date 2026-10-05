package dev.codeana.analysis;

import dev.codeana.analysis.extension.BuiltinExtensions;
import dev.codeana.analysis.extension.ExtensionRunner;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import java.nio.file.Path;

/** Replays a captured detached model without compiling or loading target classes. */
public final class LombokArchiveReplayProbe {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        long start = System.nanoTime();
        try (ModelArchive.OpenModel opened = ModelArchive.open(
                path, Json.sha(path), java.nio.file.Files.size(path), args[1])) {
            System.err.println("archive.open " + (System.nanoTime() - start));
            var configuration = args.length > 2
                    ? Json.map(Json.parse(java.nio.file.Files.readAllBytes(Path.of(args[2]))))
                    : Json.object("lombok", "model", "resources", java.util.List.of());
            if (args.length > 2) {
                System.err.println("binary.enrich " + (System.nanoTime() - start));
                BinaryIndex.enrich(configuration, opened.model(),
                        Path.of(Json.string(configuration, "target_java_home")));
            }
            new ExtensionRunner(configuration, BuiltinExtensions.REGISTRY,
                    stage -> System.err.println(stage + " " + (System.nanoTime() - start)))
                    .augment(opened.model());
            System.err.println("finished " + (System.nanoTime() - start));
        }
    }
}
