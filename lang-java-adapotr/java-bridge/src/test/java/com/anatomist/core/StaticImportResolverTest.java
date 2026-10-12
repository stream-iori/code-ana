package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaBinding;
import com.anatomist.core.java.JavaSyntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class StaticImportResolverTest {
    @TempDir Path directory;

    @Test
    void jdkConstantsHaveRealOwnerAndType() throws Exception {
        var field =
                binding(
                        "import static java.time.LocalTime.MIN; class A { Object x = MIN; }",
                        "MIN",
                        List.of());
        assertNotNull(field);
        assertTrue(field.declarationResolved());
        assertEquals("java.time.LocalTime", field.owner());
        assertEquals("java.time.LocalTime", field.type());
    }

    @Test
    void ambiguousImportsAreNotGuessed() throws Exception {
        var field =
                binding(
                        "import static java.lang.Integer.MAX_VALUE; import static java.lang.Long.MAX_VALUE; class A { Object x = MAX_VALUE; }",
                        "MAX_VALUE",
                        List.of());
        assertTrue(field == null || !field.declarationResolved());
    }

    @Test
    void localAndMemberDeclarationsShadowImports() throws Exception {
        for (String body :
                List.of("int MIN; Object x = MIN;", "Object f(int MIN) { return MIN; }")) {
            var field =
                    binding(
                            "import static java.time.LocalTime.MIN; class A {" + body + "}",
                            "MIN",
                            List.of());
            assertNotNull(field);
            assertTrue(field.declarationResolved());
            assertTrue(field.kind().equals("PARAMETER") || field.owner().equals("A"));
            assertNotEquals("java.time.LocalTime", field.owner());
        }
    }

    @Test
    void explicitImportWinsOverWildcard() throws Exception {
        var field =
                binding(
                        "import static java.lang.Integer.MAX_VALUE; import static java.lang.Long.*; class A { Object x = MAX_VALUE; }",
                        "MAX_VALUE",
                        List.of());
        assertNotNull(field);
        assertTrue(field.declarationResolved());
        assertEquals("java.lang.Integer", field.owner());
    }

    @Test
    void asmInheritedFieldsInterfacesAndVisibility(@TempDir Path tmp) throws Exception {
        var src = tmp.resolve("Base.java");
        java.nio.file.Files.writeString(
                src,
                "package dep; public class Base { public static String VALUE; public static int HIDDEN; }");
        var child = tmp.resolve("Child.java");
        java.nio.file.Files.writeString(
                child,
                "package dep; public class Child extends Base { private static int HIDDEN; }");
        var constant = tmp.resolve("Constants.java");
        java.nio.file.Files.writeString(
                constant, "package dep; public interface Constants { String TOKEN = \"token\"; }");
        var derived = tmp.resolve("Derived.java");
        java.nio.file.Files.writeString(
                derived, "package dep; public interface Derived extends Constants {}");
        var classes = java.nio.file.Files.createDirectory(tmp.resolve("classes"));
        assertEquals(
                0,
                javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "8",
                                "-d",
                                classes.toString(),
                                src.toString(),
                                child.toString(),
                                constant.toString(),
                                derived.toString()));
        for (String member : List.of("Child.VALUE", "Derived.TOKEN")) {
            String name = member.substring(member.indexOf('.') + 1);
            var field =
                    binding(
                            "import static dep."
                                    + member
                                    + "; class A { Object x = "
                                    + name
                                    + "; }",
                            name,
                            List.of(classes));
            assertNotNull(field);
            assertTrue(field.declarationResolved());
            assertEquals(member.startsWith("Child") ? "dep.Base" : "dep.Constants", field.owner());
        }
        var hidden =
                binding(
                        "import static dep.Child.HIDDEN; class A { int x = HIDDEN; }",
                        "HIDDEN",
                        List.of(classes));
        assertTrue(hidden == null || !hidden.declarationResolved());
    }

    private JavaBinding binding(String source, String name, List<Path> classpath) throws Exception {
        var context = JavaFactsTestSupport.analyze(directory, "A.java", source, classpath);
        var unit = context.units().getFirst();
        return new JavaSyntax(unit.tree())
                .nodes().stream()
                        .filter(
                                node ->
                                        node.kind().equals("IDENTIFIER")
                                                && node.text("getName").equals(name))
                        .map(node -> unit.bindings().get(node.id()))
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
    }
}
