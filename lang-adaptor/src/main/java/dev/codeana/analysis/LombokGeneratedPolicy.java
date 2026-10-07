package dev.codeana.analysis;

import java.io.IOException;
import java.util.Map;
import dev.codeana.common.Json;

/** Context-local classpath policy, including negative results; never shared between requests. */
public final class LombokGeneratedPolicy {
    private final Map<String,Object> context;
    private Boolean generatedDefault;
    private boolean checked;
    private int inspectedArchives;
    public LombokGeneratedPolicy(Map<String,Object> context) { this.context=context; }
    public int inspectedArchives() { return inspectedArchives; }
    public Boolean value() {
        if (!checked) {
            checked=true;
            for (Object item : (context.get("classpath") instanceof java.util.List ? Json.list(context.get("classpath")) : java.util.List.of())) {
                inspectedArchives++;
                try (FrozenArchive jar = new FrozenArchive(java.nio.file.Path.of(item instanceof String ? (String)item : ""))) {
                    if (jar.getEntry("lombok/Getter.class") == null) continue;
                    var entry = jar.getEntry("META-INF/MANIFEST.MF");
                    if (entry == null) break;
                    byte[] data;
                    try (var input = jar.getInputStream(entry)) {
                        data = input.readNBytes(65537);
                    }
                    if (data.length > 65536) break;
                    String version =
                            new java.util.jar.Manifest(new java.io.ByteArrayInputStream(data))
                                    .getMainAttributes()
                                    .getValue("Lombok-Version");
                    if (version != null) {
                        var matcher =
                                java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+).*")
                                        .matcher(version);
                        if (matcher.matches()) {
                            int major = Integer.parseInt(matcher.group(1)),
                                    minor = Integer.parseInt(matcher.group(2)),
                                    patch = Integer.parseInt(matcher.group(3));
                            generatedDefault =
                                    major > 1
                                            || major == 1
                                                    && (minor > 18 || minor == 18 && patch >= 34);
                        }
                    }
                    break;
                } catch (IOException | NumberFormatException failure) {
                    break;
                }
            }
        }
        return generatedDefault;
    }
}
