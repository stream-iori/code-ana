package com.anatomist.framework.lombok;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaDeclaration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class LombokBuilderModelTest {
    @Test
    void genericBuilderHasOnlySignaturesAndOrigin(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve("lombok.config"),
                "config.stopBubbling = true\nlombok.addGeneratedAnnotation = true\n");
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Builder;
                @lombok.Data @Builder(toBuilder=true) class Box<T> {
                    T value;
                    static String ignored;
                    final String constant = "fixed";
                    @Builder.Default final int count = 1;
                }
                """,
                        true);
        var owner = fixture.type("Box");
        var builder = fixture.type("BoxBuilder");
        assertEquals(
                "Box.BoxBuilder", fixture.returns(fixture.methods(owner, "builder").getFirst()));
        assertEquals(
                "Box.BoxBuilder", fixture.returns(fixture.methods(owner, "toBuilder").getFirst()));
        assertEquals("Box<T>", fixture.returns(fixture.methods(builder, "build").getFirst()));
        assertEquals(
                List.of("T"),
                fixture.arguments(
                        fixture.returnType(fixture.methods(owner, "builder").getFirst())));
        assertEquals(
                List.of("T"),
                fixture.arguments(
                        fixture.returnType(fixture.methods(owner, "toBuilder").getFirst())));
        assertEquals(
                List.of("T"),
                fixture.arguments(
                        fixture.returnType(fixture.methods(builder, "build").getFirst())));
        assertEquals("T", fixture.parameter(fixture.methods(builder, "value").getFirst(), 0));
        assertTrue(fixture.methods(builder, "ignored").isEmpty());
        assertTrue(fixture.methods(builder, "constant").isEmpty());
        assertEquals(1, fixture.methods(builder, "count").size());
        assertNotNull(fixture.origin(builder));
        fixture.members(builder).stream()
                .filter(value -> value.symbol().kind().equals("METHOD"))
                .forEach(
                        method -> {
                            assertFalse(fixture.origin(method).bodyAvailable());
                            assertEquals(JavaDeclaration.Origin.MODEL, method.origin());
                        });
        assertEquals("complete", fixture.lombok(owner).get("coverage"));
        assertTrue(
                fixture.diagnostics().stream().allMatch(value -> value.severity().equals("info")));
        assertEquals(fixture.context(), fixture.repeat(), "augmentation must be idempotent");
    }

    @Test
    void constructorBuilderUsesParametersAndCustomEntry(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Builder;
                class Task {
                    String other;
                    @Builder(builderMethodName="newTask") Task(String id, int count) {}
                }
                """,
                        false);
        assertEquals(1, fixture.methods(fixture.type("Task"), "newTask").size());
        var builder = fixture.type("TaskBuilder");
        assertEquals(1, fixture.methods(builder, "id").size());
        assertEquals(1, fixture.methods(builder, "count").size());
        assertTrue(fixture.methods(builder, "other").isEmpty());
    }

    @Test
    void explicitEntryWinsAndCustomBuilderIsCompleted(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Builder;
                @Builder class A { String value; static ABuilder builder() { return null; } }
                @Builder class B { static class BBuilder {} }
                """,
                        false);
        var owner = fixture.type("A");
        assertEquals(1, fixture.methods(owner, "builder").size());
        assertNull(fixture.origin(fixture.methods(owner, "builder").getFirst()));
        assertEquals("complete", fixture.lombok(fixture.type("B")).get("coverage"));
    }

    @Test
    void customMembersArePreservedAndOnlyMissingNamesAreAdded(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Builder;
                @Builder class Item { String name; int count;
                    static class ItemBuilder { String name;
                        ItemBuilder script(String value) { name=value; return this; }
                        ItemBuilder count(String value) { return this; }
                    }
                }
                """,
                        false);
        var builder = fixture.type("ItemBuilder");
        var script = fixture.methods(builder, "script").getFirst();
        assertEquals(JavaDeclaration.Origin.SOURCE, script.origin());
        assertTrue(script.span().present());
        assertNull(fixture.origin(script));
        assertEquals(1, fixture.methods(builder, "count").size());
        assertEquals(
                "java.lang.String",
                fixture.parameter(fixture.methods(builder, "count").getFirst(), 0));
        assertEquals(1, fixture.methods(builder, "name").size());
        assertEquals(1, fixture.methods(builder, "build").size());
        assertEquals("complete", fixture.lombok(fixture.type("Item")).get("coverage"));
        assertEquals(fixture.context(), fixture.repeat());
        assertSame(script, fixture.methods(builder, "script").getFirst());
    }

    @Test
    void interfaceAndAnnotationMemberClassesAreEffectivelyStatic(@TempDir Path root)
            throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Builder;
                interface Api { @Builder class Response { String code; } }
                @interface Contract { @Builder class Payload { String value; } }
                class Outer { @Builder class Inner { String ignored; } }
                """,
                        false);
        for (String name : List.of("Response", "Payload")) {
            assertEquals("complete", fixture.lombok(fixture.type(name)).get("coverage"));
            assertEquals(1, fixture.methods(fixture.type(name), "builder").size());
            assertEquals(1, fixture.methods(fixture.type(name + "Builder"), "build").size());
        }
        var inner = fixture.type("Inner");
        assertNotEquals("complete", fixture.lombok(inner).get("coverage"));
        assertTrue(fixture.methods(inner, "builder").isEmpty());
    }
}
