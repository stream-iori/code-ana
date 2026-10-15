package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class AstTypeNamesTest {
    @TempDir Path directory;

    @Test
    void forwardedVarargsAreDescribedAsArrays() throws Exception {
        assertEquals(
                List.of("java.lang.String[]"),
                argumentTypes(
                        "class A { void use(Object value) {} void run(String... values) { use(values); } }"));
    }

    @Test
    void wildcardNamesAreVerifiedAndAmbiguitiesStayUnknown() throws Exception {
        for (String[] sample :
                new String[][] {
                    {"import java.util.*;", "List", "java.util.List"},
                    {"import java.util.*; import java.awt.*;", "List", "<unresolved>"},
                    {"import java.util.*; import java.awt.List;", "List", "java.awt.List"},
                    {"import java.util.*;", "Missing", "<unresolved>"},
                    {"import p.Other.String;", "String", "java.lang.String"},
                    {"", "java.util.List", "java.util.List"}
                }) {
            var context =
                    JavaFactsTestSupport.analyze(
                            directory,
                            "A.java",
                            sample[0] + " class A { " + sample[1] + " value; }");
            try (var model = new JavaModel(context)) {
                var unit = context.units().getFirst();
                var field =
                        model.declarations(unit.path()).stream()
                                .map(model::declaration)
                                .filter(value -> value.symbol().kind().equals("FIELD"))
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        sample[2],
                        JavaTypeNames.erased(unit.types(), field.symbol().declaredType()));
                if (sample[2].equals("<unresolved>"))
                    assertEquals(
                            JavaType.Kind.ERROR,
                            unit.types().get(field.symbol().declaredType()).kind());
            }
        }
    }

    @Test
    void outOfScopeLocalDoesNotDetermineOverload() throws Exception {
        assertEquals(
                List.of("int", "java.lang.String"),
                argumentTypes(
                        "class A { String x; void use(Object x) {} void f() { { int x=1; use(x); } use(x); } }"));
    }

    private List<String> argumentTypes(String source) throws Exception {
        var context = JavaFactsTestSupport.analyze(directory, "A.java", source);
        try (var model = new JavaModel(context)) {
            var unit = context.units().getFirst();
            return model.syntax(unit.path()).nodes().stream()
                    .filter(
                            node ->
                                    node.kind().equals("METHOD_INVOCATION")
                                            && !node.trees("getArguments").isEmpty())
                    .map(node -> unit.bindings().get(node.trees("getArguments").getFirst().id()))
                    .filter(java.util.Objects::nonNull)
                    .map(binding -> JavaTypeNames.erased(unit.types(), binding.expressionType()))
                    .toList();
        }
    }
}
