package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class LexicalValuesTest {
    @Test
    void lexicalBindingsStayWithinTheirScopes(@TempDir Path directory) throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "A.java",
                        """
                class A { int x; void use(Object a) {} void run(Object o) {
                  use(x); { int x=1; use(x); } use(x);
                  try (java.io.InputStream resource=null) { use(resource); } catch(Exception caught) { use(caught); }
                  for (String item : new String[0]) use(item);
                  for (int index=0; index<1; index++) use(index);
                  if (o instanceof String text) use(text);
                  java.util.function.Consumer<String> f = parameter -> use(parameter);
                } }
                """);
        assertTrue(
                context.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.kind().equals("ERROR")));
        var unit = context.units().getFirst();
        var xs = uses(unit, "x");
        assertEquals(
                List.of("FIELD", "LOCAL_VARIABLE", "FIELD"),
                xs.stream().map(JavaBinding::kind).toList());
        for (var entry :
                Map.of(
                                "resource",
                                "java.io.InputStream",
                                "caught",
                                "java.lang.Exception",
                                "item",
                                "java.lang.String",
                                "index",
                                "int",
                                "text",
                                "java.lang.String",
                                "parameter",
                                "java.lang.String")
                        .entrySet()) {
            var bindings = uses(unit, entry.getKey());
            assertFalse(bindings.isEmpty(), entry.getKey());
            for (var binding : bindings) {
                assertTrue(binding.declarationResolved(), entry.getKey());
                assertEquals(
                        entry.getValue(),
                        JavaTypeNames.erased(unit.types(), binding.expressionType()),
                        entry.getKey());
            }
        }
    }

    private static List<JavaBinding> uses(JavaUnit unit, String name) {
        return new JavaSyntax(unit.tree())
                .nodes().stream()
                        .filter(
                                node ->
                                        node.kind().equals("IDENTIFIER")
                                                && node.text("getName").equals(name))
                        .map(node -> unit.bindings().get(node.id()))
                        .toList();
    }
}
