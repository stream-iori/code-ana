package com.anatomist.framework.lombok;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class LombokSuperBuilderModelTest {
    @Test
    void genericInheritanceHasSelfAndProductTypes(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.experimental.SuperBuilder;
                @SuperBuilder abstract class Parent<T> { T value; }
                @SuperBuilder class Child<T> extends Parent<T> { String name; }
                """,
                        false);
        var child = fixture.type("Child");
        var builder = fixture.type("ChildBuilder");
        assertEquals(
                "Parent.ParentBuilder", fixture.unit().types().get(builder.superclass()).display());
        assertEquals(
                java.util.List.of("T", "C", "B"),
                fixture.arguments(fixture.unit().types().get(builder.superclass())));
        assertEquals(
                java.util.List.of("T", "?", "?"),
                fixture.arguments(
                        fixture.returnType(fixture.methods(child, "builder").getFirst())));
        assertEquals("B", fixture.returns(fixture.methods(builder, "name").getFirst()));
        assertEquals("C", fixture.returns(fixture.methods(builder, "build").getFirst()));
        assertEquals(
                "Child.ChildBuilder",
                fixture.returns(fixture.methods(child, "builder").getFirst()));
        assertTrue(fixture.methods(fixture.type("Parent"), "builder").isEmpty());
        assertEquals("complete", fixture.lombok(child).get("coverage"));
        assertEquals(fixture.context(), fixture.repeat());
    }

    @Test
    void customOptionsAndMissingParentKeepGap(@TempDir Path root) throws Exception {
        for (String declaration :
                new String[] {
                    "@SuperBuilder(toBuilder=true) class A {}",
                    "@SuperBuilder class A extends Missing {}"
                }) {
            var fixture =
                    LombokTestSupport.analyze(
                            root, "import lombok.experimental.SuperBuilder; " + declaration, false);
            var owner = fixture.type("A");
            assertNotEquals("complete", fixture.lombok(owner).get("coverage"));
            assertTrue(fixture.methods(owner, "builder").isEmpty());
        }
    }
}
