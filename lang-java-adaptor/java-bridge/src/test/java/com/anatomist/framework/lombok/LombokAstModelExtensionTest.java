package com.anatomist.framework.lombok;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaDeclaration;
import com.anatomist.core.java.JavaGeneratedOrigin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class LombokAstModelExtensionTest {

    @Test
    void dataGeneratesSignaturesAndRespectsExplicitAndFinalMembers(@TempDir Path root)
            throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                package p;
                import lombok.Data;
                import lombok.NonNull;
                @Data class User {
                  private final String id;
                  @NonNull private String name;
                  private boolean active;
                  public String getName() { return name; }
                }
                """,
                        false);

        JavaDeclaration type = fixture.type("User");
        assertEquals(1, fixture.methods(type, "getName").size(), "explicit member wins");
        assertEquals(1, fixture.methods(type, "getId").size());
        assertEquals(1, fixture.methods(type, "isActive").size());
        assertEquals(1, fixture.methods(type, "setName").size());
        assertEquals(1, fixture.methods(type, "setActive").size());
        assertTrue(fixture.methods(type, "setId").isEmpty(), "final field has no setter");
        assertEquals(1, fixture.constructors(type).size());
        assertEquals(
                2, fixture.executable(fixture.constructors(type).getFirst()).parameters().size());
        assertEquals(1, fixture.methods(type, "equals").size());
        assertEquals(1, fixture.methods(type, "hashCode").size());
        assertEquals(1, fixture.methods(type, "toString").size());

        JavaDeclaration generated = fixture.methods(type, "getId").getFirst();
        JavaGeneratedOrigin origin = fixture.origin(generated);
        assertNotNull(origin);
        assertEquals("lombok-ast", origin.producerId());
        assertFalse(origin.bodyAvailable());
        Map<String, Object> lombok = fixture.lombok(type);
        assertEquals("complete", lombok.get("coverage"));
        assertEquals(
                List.of(
                        "equals_hash_code",
                        "getter",
                        "required_constructor",
                        "setter",
                        "to_string"),
                lombok.get("modeled_capabilities"));
        assertEquals(9L, ((Number) lombok.get("generated_member_count")).longValue());
        assertEquals(1, fixture.methods(type, "canEqual").size());
        assertTrue(
                fixture.diagnostics().stream()
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.code().equals("LOMBOK_BODY_SEMANTICS_OMITTED")));
    }

    @Test
    void booleanPrefixesMatchLombokAndRespectExplicitMethods(@TempDir Path root) throws Exception {
        for (boolean noIsPrefix : new boolean[] {false, true}) {
            Files.writeString(
                    root.resolve("lombok.config"), "lombok.getter.noIsPrefix = " + noIsPrefix);
            var fixture =
                    LombokTestSupport.analyze(
                            root,
                            "@lombok.Data class Flag { boolean isReady; Boolean isBoxed; boolean active; public boolean isReady() { return isReady; } }",
                            false);
            var type = fixture.type("Flag");
            assertEquals(1, fixture.methods(type, "isReady").size());
            assertNull(fixture.origin(fixture.methods(type, "isReady").getFirst()));
            assertEquals(1, fixture.methods(type, noIsPrefix ? "setIsReady" : "setReady").size());
            assertEquals(noIsPrefix ? 1 : 0, fixture.methods(type, "getIsReady").size());
            assertEquals(1, fixture.methods(type, "getIsBoxed").size());
            assertEquals(1, fixture.methods(type, "setIsBoxed").size());
            assertEquals(1, fixture.methods(type, noIsPrefix ? "getActive" : "isActive").size());
        }
    }

    @Test
    void accessNoneSuppressesGenerationAndLoggerIsSynthetic(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                package p;
                import lombok.AccessLevel;
                import lombok.Getter;
                import lombok.extern.slf4j.Slf4j;
                @Slf4j class Service {
                  @Getter(AccessLevel.NONE) private String secret;
                }
                """,
                        false);

        JavaDeclaration type = fixture.type("Service");
        assertTrue(fixture.methods(type, "getSecret").isEmpty());
        var log = fixture.field(type, "log").orElseThrow();
        assertTrue(log.symbol().modifiers().contains("private"));
        assertTrue(log.symbol().modifiers().contains("static"));
        assertTrue(log.symbol().modifiers().contains("final"));
        assertNotNull(fixture.origin(log));
    }

    @Test
    void supportedRootConfigChangesNamesAndReportsPartial(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve("lombok.config"),
                """
                lombok.getter.noIsPrefix = true
                lombok.log.fieldName = LOGGER
                """);
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Getter;
                import lombok.extern.slf4j.Slf4j;
                @Getter @Slf4j class Flag { private boolean ready; }
                """,
                        false);

        JavaDeclaration type = fixture.type("Flag");
        assertEquals(1, fixture.methods(type, "getReady").size());
        assertTrue(fixture.methods(type, "isReady").isEmpty());
        assertTrue(fixture.field(type, "LOGGER").isPresent());
        assertFalse(
                fixture.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals("LOMBOK_CONFIG_PARTIAL")));
    }

    @Test
    void unknownConfigSuppressesOnlyUncertainFacts(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve("lombok.config"), "lombok.unknown.signature.option = true\n");
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Getter;
                import lombok.extern.slf4j.Slf4j;
                @Getter @Slf4j class Flag { private boolean ready; }
                """,
                        false);

        JavaDeclaration type = fixture.type("Flag");
        assertTrue(fixture.methods(type, "isReady").isEmpty());
        assertTrue(fixture.field(type, "log").isEmpty());
        Map<String, Object> lombok = fixture.lombok(type);
        assertEquals(List.of("getter", "logger_field"), lombok.get("partial_capabilities"));
        assertTrue(
                fixture.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.code().equals("LOMBOK_CONFIG_PARTIAL")));
    }

    @Test
    void accessorsMakesGetterSetterPartialWithoutBlockingOtherDataMembers(@TempDir Path root)
            throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Data;
                import lombok.experimental.Accessors;
                @Data @Accessors(fluent = true) class Fluent { private String name; }
                """,
                        false);

        JavaDeclaration type = fixture.type("Fluent");
        assertTrue(fixture.methods(type, "getName").isEmpty());
        assertTrue(fixture.methods(type, "setName").isEmpty());
        assertEquals(1, fixture.constructors(type).size());
        assertEquals(1, fixture.methods(type, "equals").size());
        Map<String, Object> lombok = fixture.lombok(type);
        assertEquals(List.of("getter", "setter"), lombok.get("partial_capabilities"));
        assertEquals(List.of("accessors"), lombok.get("unmodeled_capabilities"));
        assertEquals("partial", lombok.get("coverage"));
    }

    @Test
    void fieldAccessorsOnlySuppressesThatFieldsAccessors(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Data;
                import lombok.experimental.Accessors;
                @Data class Mixed {
                  @Accessors(fluent = true) private String special;
                  private String normal;
                }
                """,
                        false);

        JavaDeclaration type = fixture.type("Mixed");
        assertTrue(fixture.methods(type, "getSpecial").isEmpty());
        assertTrue(fixture.methods(type, "setSpecial").isEmpty());
        assertEquals(1, fixture.methods(type, "getNormal").size());
        assertEquals(1, fixture.methods(type, "setNormal").size());
        var field = fixture.field(type, "special").orElseThrow();
        Map<String, Object> fieldLombok = fixture.lombok(field);
        assertEquals(List.of("getter", "setter"), fieldLombok.get("partial_capabilities"));
    }

    @Test
    void unsupportedStaticConstructorDoesNotInventConstructorContract(@TempDir Path root)
            throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Data;
                @Data(staticConstructor = "of") class Item { private final String name; }
                """,
                        false);

        JavaDeclaration type = fixture.type("Item");
        assertTrue(fixture.constructors(type).isEmpty());
        assertEquals(1, fixture.methods(type, "getName").size());
        Map<String, Object> lombok = fixture.lombok(type);
        assertEquals(List.of("required_constructor"), lombok.get("partial_capabilities"));
        assertTrue(
                fixture.diagnostics().stream()
                        .anyMatch(
                                diagnostic ->
                                        diagnostic.code().equals("LOMBOK_FEATURE_UNSUPPORTED")));
    }

    @Test
    void unsupportedFeatureIsVisibleAndStrictPromotesSeverity(@TempDir Path root) throws Exception {
        var fixture =
                LombokTestSupport.analyze(
                        root,
                        """
                import lombok.Builder;
                @Builder(builderClassName="*Builder") class Item { String name; }
                """,
                        true);

        var diagnostic =
                fixture.diagnostics().stream()
                        .filter(value -> value.code().equals("LOMBOK_FEATURE_UNSUPPORTED"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("error", diagnostic.severity());
        assertEquals("Item", diagnostic.symbol());
        assertTrue(diagnostic.sample().contains("Builder"));
        var type = fixture.type("Item");
        Map<String, Object> lombok = fixture.lombok(type);
        assertEquals(List.of("builder"), lombok.get("partial_capabilities"));
        assertTrue(fixture.methods(type, "builder").isEmpty());
        assertEquals("partial", lombok.get("coverage"));
    }
}
