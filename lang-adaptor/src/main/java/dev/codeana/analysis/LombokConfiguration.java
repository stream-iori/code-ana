package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionPoint;
import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Only daemon-captured controls are read. Directory walking never occurs in the worker. */
final class LombokConfiguration {
    record Layer(String path, String hash, Map<String, String> values, Set<String> uncertain, boolean stop) {}
    record Effective(boolean noIsPrefix, String logName, boolean logStatic, Boolean generated,
            boolean suppressWarnings, boolean javaxGenerated, Set<String> uncertain, List<Object> inputs) {}
    private final Map<String, Layer> local = new HashMap<>();
    private final SortedMap<Integer, Layer> ancestors = new TreeMap<>();
    LombokConfiguration(Map<String, Object> context) {
        int files = 0, bytes = 0;
        for (Object raw : Json.list(context.get("resources"))) {
            Map<String, Object> resource = Json.map(raw), unit = Json.map(resource.get("unit"));
            String path = Json.string(unit, "path");
            if (!path.equals("lombok.config") && !path.endsWith("/lombok.config")) continue;
            if (++files > 4096) throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_FILE_BUDGET");
            byte[] data;
            try (var input = Files.newInputStream(Path.of(Json.string(resource, "input")))) {
                data = input.readNBytes(1024 * 1024+1);
            } catch (java.io.IOException failure) { throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_INPUT_UNAVAILABLE"); }
            bytes += data.length;
            if (data.length > 1024 * 1024 || bytes > 8 * 1024 * 1024)
                throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_BYTE_BUDGET");
            if (!Json.sha(data).equals(unit.get("hash"))) throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_INPUT_DRIFT");
            Layer layer = parse(path, Json.sha(data), new String(data, java.nio.charset.StandardCharsets.UTF_8));
            if (path.startsWith(".code-ana/lombok-ancestors/")) {
                String[] parts = path.split("/");
                if (parts.length != 4 || !parts[2].matches("[0-9]{6}")) throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_ANCESTOR_ID");
                int depth = Integer.parseInt(parts[2]);
                if (depth < 1 || depth > 256 || ancestors.putIfAbsent(depth, layer) != null)
                    throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_ANCESTOR_ID");
            } else if (local.putIfAbsent(path, layer) != null) throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_DUPLICATE");
        }
    }
    Effective forSource(String source) {
        List<Layer> layers = new ArrayList<>();
        int separator = source.lastIndexOf('/');
        String directory = separator < 0 ? "" : source.substring(0, separator);
        boolean stop = false; int depth = 0;
        while (true) {
            if (++depth > 256) throw new ExtensionPoint.GateFailure("LOMBOK_CONFIG_DEPTH_BUDGET");
            Layer layer = local.get(directory.isEmpty() ? "lombok.config" : directory+"/lombok.config");
            if (layer != null) { layers.add(layer); if (layer.stop()) { stop = true; break; } }
            if (directory.isEmpty()) break;
            int parent = directory.lastIndexOf('/'); directory = parent < 0 ? "" : directory.substring(0, parent);
        }
        if (!stop) for (Layer layer : ancestors.values()) {
            layers.add(layer); if (layer.stop()) break;
        }
        Collections.reverse(layers);
        Map<String, String> values = new HashMap<>(); Set<String> uncertain = new TreeSet<>(); List<Object> inputs = new ArrayList<>();
        for (Layer layer : layers) {
            values.putAll(layer.values()); uncertain.addAll(layer.uncertain()); inputs.add(List.of(layer.path(), layer.hash()));
        }
        Boolean noIs = bool(values.get("lombok.getter.noIsPrefix")), stat = bool(values.get("lombok.log.fieldIsStatic"));
        if (values.containsKey("lombok.getter.noIsPrefix") && noIs == null) uncertain.add("getter");
        if (values.containsKey("lombok.log.fieldIsStatic") && stat == null) uncertain.add("logger_field");
        String name = values.getOrDefault("lombok.log.fieldName", "log");
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                || javax.lang.model.SourceVersion.isKeyword(name, javax.lang.model.SourceVersion.RELEASE_8)) {
            uncertain.add("logger_field"); name = "log";
        }
        return new Effective(Boolean.TRUE.equals(noIs), name, stat == null || stat,
                bool(values.get("lombok.addLombokGeneratedAnnotation")),
                !Boolean.FALSE.equals(bool(values.get("lombok.addSuppressWarnings"))),
                Boolean.TRUE.equals(bool(values.getOrDefault("lombok.addJavaxGeneratedAnnotation", values.get("lombok.addGeneratedAnnotation")))),
                Set.copyOf(uncertain), List.copyOf(inputs));
    }
    private static Layer parse(String path, String hash, String content) {
        Map<String, String> values = new HashMap<>(); Set<String> uncertain = new TreeSet<>(); boolean stop = false;
        for (String raw : content.split("\\R")) {
            String line = raw.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('=');
            if (split < 1) { uncertain.add("*"); continue; }
            String key = line.substring(0, split).strip(), value = line.substring(split+1).strip();
            if (key.equals("config.stopBubbling")) {
                Boolean valid = bool(value); if (valid == null) uncertain.add("*"); stop = Boolean.TRUE.equals(valid);
            } else if (Set.of("lombok.getter.noIsPrefix", "lombok.log.fieldName", "lombok.log.fieldIsStatic").contains(key)) {
                values.put(key, value);
            } else if (Set.of("lombok.addLombokGeneratedAnnotation", "lombok.addSuppressWarnings", "lombok.addGeneratedAnnotation",
                    "lombok.addJavaxGeneratedAnnotation").contains(key) && bool(value) != null) {
                values.put(key, value);
            } else uncertain.addAll(affected(key));
        }
        return new Layer(path, hash, Map.copyOf(values), Set.copyOf(uncertain), stop);
    }
    private static Boolean bool(String value) {
        if (value == null) return null;
        if (value.equalsIgnoreCase("true")) return true; if (value.equalsIgnoreCase("false")) return false; return null;
    }
    private static Set<String> affected(String key) {
        if (key.startsWith("lombok.getter.")) return Set.of("getter");
        if (key.startsWith("lombok.setter.")) return Set.of("setter");
        if (key.startsWith("lombok.accessors.")) return Set.of("getter", "setter", "accessors");
        if (key.startsWith("lombok.noArgsConstructor.")) return Set.of("no_args_constructor");
        if (key.startsWith("lombok.requiredArgsConstructor.")) return Set.of("required_constructor");
        if (key.startsWith("lombok.allArgsConstructor.")) return Set.of("all_args_constructor");
        if (key.startsWith("lombok.anyConstructor.")) return Set.of("no_args_constructor", "required_constructor", "all_args_constructor");
        if (key.startsWith("lombok.log.")) return Set.of("logger_field");
        if (key.startsWith("lombok.equalsAndHashCode.")) return Set.of("equals_hash_code");
        if (key.startsWith("lombok.toString.")) return Set.of("to_string");
        if (key.startsWith("lombok.builder.")) return Set.of("builder");
        return Set.of("*");
    }
}
