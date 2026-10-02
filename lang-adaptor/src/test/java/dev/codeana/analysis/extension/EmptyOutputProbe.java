package dev.codeana.analysis.extension;

import dev.codeana.analysis.SofaXmlAnalysis;
import dev.codeana.analysis.SpringXmlAnalysis;
import dev.codeana.common.Json;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;

/** Empty callbacks must not decode a large existing archive merely to validate zero links. */
public final class EmptyOutputProbe {
    public static void main(String[] args) throws Exception {
        List<Object> archive = new AbstractList<>() {
            @Override
            public int size() {
                return 200000;
            }

            @Override
            public Object get(int index) {
                throw new AssertionError("EXISTING_FACT_DECODED");
            }
        };
        Map<String, Object> config = Json.object(
                "world", "w", "context", "c", "sources", List.of(),
                "resources", List.of(), "fact_limit", 200000);
        ExtensionPoint producer = new ExtensionPoint() {
            public String id() {
                return "empty";
            }

            public String producer() {
                return "empty";
            }
        };
        ExtensionContext context = new ExtensionContext(config, Map.of(), archive, List.of());
        switch (args[0]) {
            case "spring" -> {
                if (!SpringXmlAnalysis.extract(config, archive).isEmpty())
                    throw new AssertionError("UNEXPECTED_SPRING_XML_FACTS");
            }
            case "sofa" -> {
                if (!SofaXmlAnalysis.extract(config, archive).isEmpty())
                    throw new AssertionError("UNEXPECTED_SOFA_XML_FACTS");
            }
            case "sink" -> {
                if (!new FactSink().validate(producer, context, archive).isEmpty())
                    throw new AssertionError("UNEXPECTED_EMPTY_SINK_FACTS");
                rejectLimit(config, archive, producer, 0, "JAVA_FACT_LIMIT_RANGE");
                rejectLimit(config, archive, producer, 5000001, "JAVA_FACT_LIMIT_RANGE");
                rejectLimit(config, archive, producer, 199999, "EXTENSION_FACT_BUDGET");
                config.put("fact_limit", 200001);
                FactSink sink = new FactSink();
                sink.add(Json.object("id", "new"));
                try {
                    sink.validate(producer, context, archive);
                    throw new AssertionError("NONEMPTY_OUTPUT_VALIDATION_SKIPPED");
                } catch (AssertionError expected) {
                    if (!"EXISTING_FACT_DECODED".equals(expected.getMessage())) throw expected;
                }
            }
            default -> throw new IllegalArgumentException("UNKNOWN_PROBE_MODE");
        }
        System.out.println("PASS " + args[0] + ": empty input/output with 200000 existing facts");
    }

    private static void rejectLimit(
            Map<String, Object> config, List<Object> archive, ExtensionPoint producer,
            int limit, String code) {
        config.put("fact_limit", limit);
        try {
            new FactSink().validate(
                    producer, new ExtensionContext(config, Map.of(), archive, List.of()), archive);
            throw new AssertionError("MISSING_BUDGET_REJECTION");
        } catch (IllegalArgumentException expected) {
            if (!code.equals(expected.getMessage())) throw expected;
        }
    }
}
