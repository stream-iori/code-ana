package com.anatomist.skill;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Information ownership and distribution checks; query behavior is exercised by CLI tests. */
class SkillMdContractTest {
    private static final Set<String> GUIDES =
            Set.of(
                    "core",
                    "topics",
                    "explore",
                    "source",
                    "trace",
                    "relations",
                    "spring",
                    "versions",
                    "maintenance",
                    "output");

    @Test
    void installedSkillIsACompactDiscoveryEntry() throws Exception {
        assertFalse(Files.exists(repo().resolve("SKILL.md")));
        assertTrue(
                Files.size(guidePath("core")) <= 2048,
                "core must remain an inexpensive entrypoint");
    }

    @Test
    void distributedGuidesMatchThePublicScenesAndStayBounded() throws Exception {
        try (var paths =
                java.util.stream.Stream.concat(
                        Files.list(sceneDir()), Files.list(springSceneDir()))) {
            assertEquals(
                    GUIDES,
                    paths.filter(p -> p.toString().endsWith(".md"))
                            .map(p -> p.getFileName().toString().replace(".md", ""))
                            .collect(Collectors.toSet()));
        }
        for (String guide : GUIDES) {
            Path path = guidePath(guide);
            byte[] expected = Files.readAllBytes(path);
            assertTrue(expected.length <= 3 * 1024, guide + " exceeds 3 KiB");
            try (var embedded =
                    getClass().getResourceAsStream("/META-INF/anatomist/skills/" + guide + ".md")) {
                assertNotNull(embedded, guide);
                assertArrayEquals(expected, embedded.readAllBytes(), guide);
            }
            String text = new String(expected, StandardCharsets.UTF_8);
            assertFalse(text.contains("Usage:"), guide + " duplicates CLI help");
            assertFalse(
                    text.contains("anatomist --skill"), guide + " references a removed entrypoint");
        }
        for (String removed : List.of("branch", "flow")) {
            assertNull(getClass().getResource("/META-INF/anatomist/skills/" + removed + ".md"));
        }
    }

    @Test
    void conditionalMaintenanceDetailsHaveOneOwner() throws Exception {
        String core = Files.readString(sceneDir().resolve("core.md"));
        String maintenance = Files.readString(sceneDir().resolve("maintenance.md"));
        for (String field :
                List.of(
                        "config_source",
                        "config_path",
                        "scan_policy_hash",
                        "--changed-files-from")) {
            assertFalse(core.contains(field), field + " leaked into common rules");
            assertTrue(maintenance.contains(field), field + " missing from maintenance");
        }
        for (String guide : GUIDES) {
            String text = Files.readString(guidePath(guide));
            assertFalse(text.contains("copied_pages"), guide + " embeds backup protocol internals");
            assertFalse(
                    text.contains("flow-materialize"),
                    guide + " advertises removed data-flow command");
        }
    }

    @Test
    void springGuideExplainsXmlReferenceDirection() throws Exception {
        String spring = Files.readString(guidePath("spring"));
        assertTrue(spring.contains("references --relation config --direction outgoing"));
        assertTrue(spring.contains("resolve --endpoint callee"));
        assertTrue(spring.contains("DEFINED_BY"));
    }

    private static Path repo() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static Path sceneDir() {
        return repo().resolve("anatomist-core/src/main/resources/META-INF/anatomist/skills");
    }

    private static Path springSceneDir() {
        return repo().resolve(
                        "anatomist-framework-spring/src/main/resources/META-INF/anatomist/skills");
    }

    private static Path guidePath(String guide) {
        return (guide.equals("spring") ? springSceneDir() : sceneDir()).resolve(guide + ".md");
    }
}
