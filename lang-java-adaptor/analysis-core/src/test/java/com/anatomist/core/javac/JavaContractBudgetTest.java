package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

class JavaContractBudgetTest {
    @Test
    void sharedTypeDagCannotExpandWithoutATotalWorkBudget() {
        List<JavaType> types = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            types.add(
                    new JavaType(
                            JavaType.Kind.DECLARED,
                            "p.T" + index,
                            "",
                            -1,
                            index == 0 ? List.of() : List.of(index - 1, index - 1),
                            List.of(),
                            -1,
                            -1,
                            List.of(),
                            -1,
                            List.of()));
        }
        var declaration =
                new JavaDeclaration(
                        new JavaSymbol("CLASS", "", "Box", "", 24, List.of(), false),
                        "Box",
                        "Box",
                        "",
                        SourceSpan.ABSENT,
                        JavaDeclaration.Origin.SOURCE,
                        -1,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        List.of());
        var unit =
                new JavaUnit(
                        Path.of("Box.java"),
                        new JavaNode(0, "COMPILATION_UNIT", -1, -1, Map.of()),
                        com.anatomist.core.java.JavaProcessingState.NONE,
                        Map.of(),
                        new JavaTypes(types),
                        false,
                        List.of(declaration));
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> JavaContract.fingerprint(unit))
                        .getMessage()
                        .contains("work budget"));
    }

    @Test
    void packageAndModuleContractsExcludeCoordinatesAndSurviveCompaction() {
        JavaNode packageName = new JavaNode(1, "IDENTIFIER", 0, 1, Map.of("getName", "p"));
        JavaUnit before = header(Map.of("getPackageName", packageName));
        JavaUnit moved =
                header(
                        Map.of(
                                "getPackageName",
                                new JavaNode(100, "IDENTIFIER", 80, 81, Map.of("getName", "p"))));
        assertEquals(JavaContract.fingerprint(before), JavaContract.fingerprint(moved));
        assertEquals(
                JavaContract.fingerprint(before),
                JavaContract.fingerprint(JavaDeclarationView.of(before)));
        assertNotEquals(
                JavaContract.fingerprint(before),
                JavaContract.fingerprint(
                        header(
                                Map.of(
                                        "getPackageName",
                                        new JavaNode(
                                                2, "IDENTIFIER", 0, 1, Map.of("getName", "q"))))));
        var module =
                new JavaNode(
                        3,
                        "MODULE",
                        0,
                        20,
                        Map.of("getName", "sample", "getDirectives", List.of()));
        assertNotEquals(
                JavaContract.fingerprint(header(Map.of())),
                JavaContract.fingerprint(header(Map.of("getModule", module))));
    }

    private static JavaUnit header(Map<String, Object> fields) {
        return new JavaUnit(
                Path.of("package-info.java"),
                new JavaNode(0, "COMPILATION_UNIT", 0, 20, fields),
                com.anatomist.core.java.JavaProcessingState.NONE,
                Map.of(),
                JavaTypes.EMPTY,
                false,
                List.of());
    }
}
