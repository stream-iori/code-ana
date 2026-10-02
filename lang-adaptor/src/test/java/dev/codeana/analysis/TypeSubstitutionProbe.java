package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import java.nio.file.Path;
import java.util.*;

/** A recovered signature must preserve immutable compiler subtrees and candidate isolation. */
public final class TypeSubstitutionProbe {
    public static void main(String[] args) throws Exception {
        Map<String, Object> variable = Json.object("version", 2, "kind", "TYPEVAR", "name", "T", "owner", "Owner");
        Map<String, Object> leaf = Json.object("version", 2, "kind", "DECLARED", "element", "Leaf", "arguments", List.of());
        Object tree = leaf;
        for (int i = 0; i < 40; i++)
            tree = Json.object("version", 2, "kind", "DECLARED", "element", "Box", "arguments", List.of(tree));
        Map<String, Object> pair = Json.object("version", 2, "kind", "DECLARED", "element", "Pair", "arguments", List.of(tree, variable));
        Map<String, Object> model = Json.object("version", 6, "context", "sharing");
        for (String section : List.of("sources", "declarations", "observations", "syntax", "external", "annotation_types", "diagnostics"))
            model.put(section, new ArrayList<>());
        Json.list(model.get("declarations")).add(Json.object("type", pair));
        Path file = Path.of(args[0]).resolve("sharing.bin");
        ModelArchive.write(file, model, 1L << 20);
        try (ModelArchive.OpenModel opened = ModelArchive.open(file, Json.sha(file), 1L << 20, "sharing")) {
            Map<String, Object> input = Json.map(Json.map(Json.list(opened.model().get("declarations")).getFirst()).get("type"));
            Object unchanged = Json.list(input.get("arguments")).getFirst();
            String original = Json.write(input);
            TypeOperations operations = new TypeOperations(Map.of(), null);
            if (operations.substitute(input, Map.of()) != input)
                throw new AssertionError("unchanged signature copied the compiler type tree");
            operations.reset();
            Map<String, Object> first = operations.substitute(input, Map.of("Owner\0T", TypeOperations.named("First")));
            operations.reset();
            Map<String, Object> second = operations.substitute(input, Map.of("Owner\0T", TypeOperations.named("Second")));
            if (first == input || Json.list(first.get("arguments")).getFirst() != unchanged)
                throw new AssertionError("substitution copied an unrelated 40-level subtree");
            if (!Json.map(Json.list(first.get("arguments")).get(1)).get("element").equals("First")
                    || !Json.map(Json.list(second.get("arguments")).get(1)).get("element").equals("Second")
                    || !Json.write(input).equals(original))
                throw new AssertionError("substitution leaked between candidates or changed compiler evidence");
            if (ExtensionContext.freeze(first) != first)
                throw new AssertionError("callback copied a recovered immutable type");
            try {
                Json.list(first.get("arguments")).clear();
                throw new AssertionError("recovered signature was mutable");
            } catch (UnsupportedOperationException expected) { }
            Map<String, Object> editable = ExtensionContext.mutableMap(first);
            Json.map(Json.list(editable.get("arguments")).getFirst()).put("element", "Edited");
            if (!Json.map(unchanged).get("element").equals("Box"))
                throw new AssertionError("mutable copy changed a shared compiler node");
            Map<String, Object> capture = Json.object("kind", "CAPTURE", "capture", "c", "upper", variable);
            operations.reset();
            Map<String, Object> bound = operations.substitute(variable, Map.of("Owner\0T", capture));
            if (!Boolean.TRUE.equals(Json.map(bound.get("upper")).get("recursive")))
                throw new AssertionError("capture cycle lost its explicit identity");
            Object deep = leaf;
            for (int i = 0; i < 70; i++) deep = Json.object("kind", "ARRAY", "component", deep);
            operations.reset();
            if (operations.substitute(Json.map(deep), Map.of()) != deep)
                throw new AssertionError("empty substitutions copied an unchanged deep type");
            deep = variable;
            for (int i = 0; i < 70; i++) deep = Json.object("kind", "ARRAY", "component", deep);
            operations.reset();
            try {
                operations.substitute(Json.map(deep), Map.of("Owner\0T", leaf));
                throw new AssertionError("deep substitutions bypassed the type operation budget");
            } catch (TypeOperations.Budget expected) { }
        }
        System.out.println("type substitution: unchanged/shared paths, candidate isolation, immutable callbacks, capture cycles and budgets passed");
    }
}
