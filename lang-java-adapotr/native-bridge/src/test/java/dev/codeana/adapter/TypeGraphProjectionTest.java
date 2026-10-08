package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;
import dev.codeana.common.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class TypeGraphProjectionTest {
    private static JavaType type(JavaType.Kind kind, String name, String display,
            List<Integer> arguments, List<JavaAnnotation> annotations) {
        return new JavaType(kind, name, display, -1, arguments, List.of(), -1, -1,
                List.of(), -1, List.of(), annotations, "");
    }

    private static Map<String, Object> graph(String first, String second) {
        var literal = new JavaAnnotation("Lp/Tag;", JavaAnnotation.Retention.SOURCE,
                Map.of("value", new JavaBinaryType.Scalar("capture#123")));
        var types = new JavaTypes(List.of(
                type(JavaType.Kind.DECLARED, "Map", "@Tag(\"capture#123\") Map<" + first + "," + second + "," + first + ">",
                        List.of(1, 2, 1), List.of(literal)),
                type(JavaType.Kind.TYPEVAR, first, first + " of ?", List.of(), List.of()),
                type(JavaType.Kind.TYPEVAR, second, second + " of ?", List.of(), List.of())));
        var projection = new TypeGraphProjection(types);
        Map<String, Object> attributes = Json.object("source_literal", "capture#123");
        attributes.put("type_model", projection.reference(0));
        projection.attach(attributes);
        // Projection must leave the independently observed compiler model intact.
        assertEquals(first, types.get(1).name());
        return attributes;
    }

    @Test
    void processNumbersChangeNeitherGraphNorCaptureAliases() {
        var first = graph("capture#564", "capture#311");
        assertEquals(first, graph("capture#82", "capture#932"));
        String encoded = Json.write(first);
        assertTrue(encoded.contains("Map<capture#0,capture#1,capture#0>"));
        assertTrue(encoded.contains("capture#123"), "annotation/source literal must survive");
        assertEquals("capture#123", first.get("source_literal"));
        assertNotEquals(first, graph("capture#82", "capture#82"), "distinct captures must not collapse");
    }

    @Test
    void quotedAnnotationTextAndEscapesDoNotAllocateCaptureIdentities() {
        var projection = new TypeGraphProjection(JavaTypes.EMPTY);
        assertEquals("@Tag(\"prefix \\\"capture#123\\\"\") List<capture#0>",
                projection.spelling("@Tag(\"prefix \\\"capture#123\\\"\") List<capture#934>"));
        assertEquals("Pair<capture#0,capture#1,capture#0>",
                projection.spelling("Pair<capture#934,capture#11,capture#934>"));
    }
}
