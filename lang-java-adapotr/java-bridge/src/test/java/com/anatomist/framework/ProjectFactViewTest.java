package com.anatomist.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.anatomist.model.SymbolFact;
import com.anatomist.model.SymbolRef;
import com.anatomist.model.SymbolResolution;
import com.anatomist.model.TypeRelationFact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

class ProjectFactViewTest {

    @Test
    void keepsTheResolverContractLanguageNeutralButOnlyClaimsInstalledJavaSupport() {
        ProjectFactView facts =
                new DefaultProjectFactView(
                        Set.of(),
                        Map.of(),
                        List.of(
                                new SymbolFact(
                                        "java-id",
                                        "p.Base#setName(java.lang.String)",
                                        "METHOD",
                                        ".",
                                        "MAIN",
                                        "src/main/java/p/Base.java",
                                        "{\"isStatic\":false}")),
                        List.of(new TypeRelationFact("p.Child", "p.Base", "INHERITS")));

        SymbolRef java =
                new SymbolRef(
                        "jvm",
                        "java",
                        "method",
                        "p.Child",
                        "setName",
                        1,
                        List.of("java.lang.String"),
                        false,
                        "setter",
                        "test",
                        "src/main/resources/a.xml");
        assertEquals(List.of("java-id"), facts.resolve(java).candidates());

        SymbolRef kotlin =
                new SymbolRef(
                        "jvm",
                        "kotlin",
                        "method",
                        "p.Child",
                        "setName",
                        1,
                        List.of("kotlin.String"),
                        false,
                        "setter",
                        "test",
                        "src/main/resources/a.xml");
        assertEquals(SymbolResolution.UNRESOLVED, facts.resolve(kotlin).status());
        assertEquals(List.of(), facts.resolve(kotlin).candidates());
    }
}
