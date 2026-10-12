package com.anatomist.framework.lombok;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class LombokConfigurationScopeTest {
    @Test
    void sourceOverridesAndStopBubblingControlTheEffectiveConfiguration(@TempDir Path parent)
            throws Exception {
        Files.writeString(
                parent.resolve("lombok.config"),
                "config.stopBubbling = true\nlombok.getter.noIsPrefix = true\nlombok.log.fieldIsStatic = false\nlombok.addLombokGeneratedAnnotation = true\nlombok.addSuppressWarnings = false\n");
        Path project = Files.createDirectory(parent.resolve("project"));
        Files.writeString(
                project.resolve("lombok.config"),
                "config.stopBubbling = false\nlombok.log.fieldName = rootLog\n");
        Path nested = Files.createDirectory(project.resolve("nested"));
        Files.writeString(
                nested.resolve("lombok.config"),
                "config.stopBubbling = true\nlombok.log.fieldName = nestedLog\nlombok.addLombokGeneratedAnnotation = false\n");
        var scope = new LombokConfigurationScope(project);
        var root = scope.forSource(project.resolve("Example.java"));
        assertTrue(root.noIsPrefix());
        assertFalse(root.logFieldStatic());
        assertEquals("rootLog", root.logFieldName());
        assertFalse(root.partial());
        assertEquals(true, root.addLombokGeneratedAnnotation());
        assertFalse(root.addSuppressWarnings());
        var child = scope.forSource(nested.resolve("Example.java"));
        assertFalse(child.noIsPrefix());
        assertTrue(child.logFieldStatic());
        assertEquals("nestedLog", child.logFieldName());
        assertFalse(child.partial());
        assertEquals(false, child.addLombokGeneratedAnnotation());
        assertTrue(child.addSuppressWarnings());
        assertNull(LombokConfiguration.fromContent("").addLombokGeneratedAnnotation());
        var legacy = LombokConfiguration.fromContent("lombok.addGeneratedAnnotation = true");
        assertFalse(legacy.partial());
        assertTrue(legacy.addJavaxGeneratedAnnotation());
        assertFalse(
                LombokConfiguration.fromContent(
                                "lombok.addGeneratedAnnotation = true\nlombok.addJavaxGeneratedAnnotation = false")
                        .addJavaxGeneratedAnnotation());
        assertFalse(
                LombokConfiguration.fromContent(
                                "lombok.addJavaxGeneratedAnnotation = false\nlombok.addGeneratedAnnotation = true")
                        .addJavaxGeneratedAnnotation());
        scope.verify();
    }

    @Test
    void configurationContentAdditionRemovalAndAncestorChangesInvalidateTheSnapshot(
            @TempDir Path parent) throws Exception {
        Path project = Files.createDirectory(parent.resolve("project"));
        var original = new LombokConfigurationScope(project);
        Files.writeString(project.resolve("Example.java"), "class Example {}");
        original.verify();
        Path nested = Files.createDirectory(project.resolve("nested"));
        Path file =
                Files.writeString(
                        nested.resolve("lombok.config"), "lombok.getter.noIsPrefix = true");
        assertThrows(IllegalStateException.class, original::verify);
        var added = new LombokConfigurationScope(project);
        assertNotEquals(original.fingerprint(), added.fingerprint());
        Files.writeString(file, "lombok.getter.noIsPrefix = false");
        assertThrows(IllegalStateException.class, added::verify);
        var changed = new LombokConfigurationScope(project);
        Files.delete(file);
        assertThrows(IllegalStateException.class, changed::verify);
        assertEquals(original.fingerprint(), new LombokConfigurationScope(project).fingerprint());
        Files.writeString(parent.resolve("lombok.config"), "config.stopBubbling = true");
        assertThrows(IllegalStateException.class, original::verify);
        assertThrows(
                IllegalArgumentException.class,
                () -> original.forSource(parent.resolve("Outside.java")));
    }

    @Test
    void unsupportedImportsAndOversizedConfigurationNeverClaimCompleteCoverage(
            @TempDir Path project) throws Exception {
        Files.writeString(project.resolve("lombok.config"), "import ../shared.config\n");
        assertTrue(
                new LombokConfigurationScope(project)
                        .forSource(project.resolve("Example.java"))
                        .partial());
        Files.writeString(project.resolve("lombok.config"), "x".repeat(1024 * 1024 + 1));
        assertThrows(IllegalArgumentException.class, () -> new LombokConfigurationScope(project));
    }
}
