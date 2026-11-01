package com.anatomist.application;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.JavaFrameworkPlugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class FrameworkPluginsTest {
    @Test
    void detachedBuiltInsComposeJavaAndSharedXmlAndOptionalLombok(@TempDir Path root) {
        var config = new com.anatomist.config.ProjectConfig();
        var context = new JavaFrameworkContext(root, List.of(), config, true);
        var registry = FrameworkPlugins.builtIns().prepareDetached(context);
        assertEquals(
                List.of("spring-components", "spring-mvc", "spring-handlers", "sofa-annotations"),
                registry.analyzers().stream()
                        .map(com.anatomist.framework.ExtensionPoint::id)
                        .toList());
        assertEquals(
                List.of("spring-xml-resources"),
                registry.resourceProviders().stream()
                        .map(com.anatomist.framework.ExtensionPoint::id)
                        .toList());
        assertEquals(
                List.of("spring-xml", "sofa-xml"),
                registry.resourceAnalyzers().stream()
                        .map(com.anatomist.framework.ExtensionPoint::id)
                        .toList());
        assertEquals(3, registry.pluginContracts().size());
        assertEquals(
                registry.fingerprint(),
                FrameworkPlugins.builtIns().prepareDetached(context).fingerprint());
        config.setLombokMode("ast");
        var augmented = FrameworkPlugins.builtIns().prepareDetached(context);
        assertEquals(
                List.of("lombok-ast"),
                augmented.declarations().stream()
                        .map(com.anatomist.framework.ExtensionPoint::id)
                        .toList());
        assertNotEquals(registry.fingerprint(), augmented.fingerprint());
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        new FrameworkPlugins(List.of(plugin("unported", List.of())))
                                .prepareDetached(context));
    }

    @Test
    void selectedLombokSurvivesAllOtherFrameworksBeingDisabled(@TempDir Path root) {
        var config = new com.anatomist.config.ProjectConfig();
        config.setLombokMode("ast");
        var context = new JavaFrameworkContext(root, List.of(), config, false);
        var registry = FrameworkPlugins.selected(null, false).prepareDetached(context, java.util.Map.of());
        assertEquals(List.of("lombok-ast"), registry.declarations().stream().map(com.anatomist.framework.ExtensionPoint::id).toList());
        assertEquals(List.of("lombok-call-site"), registry.callSiteEvidenceProviders().stream().map(com.anatomist.framework.ExtensionPoint::id).toList());
        assertTrue(registry.analyzers().isEmpty());
        assertTrue(registry.resourceProviders().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> FrameworkPlugins.selected(null, true));
    }

    private static JavaFrameworkPlugin plugin(String id, List<String> requires) {
        return new JavaFrameworkPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public List<String> requires() {
                return requires;
            }
        };
    }

    @Test
    void assemblyRejectsDuplicateAndMissingDependencies() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FrameworkPlugins(
                                List.of(plugin("spring", List.of()), plugin("spring", List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FrameworkPlugins(
                                List.of(
                                        plugin("sofa", List.of("spring")),
                                        plugin("spring", List.of()))));
    }

    @Test
    void builtInsExposeBothFrameworksAndStableFingerprint() {
        FrameworkPlugins plugins = FrameworkPlugins.builtIns();
        assertEquals(
                plugins.fingerprint(),
                plugins.prepareDetached(
                                new JavaFrameworkContext(
                                        Path.of("."),
                                        List.of(),
                                        new com.anatomist.config.ProjectConfig(),
                                        false))
                        .fingerprint());
        assertTrue(
                plugins
                        .prepareDetached(
                                new JavaFrameworkContext(
                                        Path.of("."),
                                        List.of(),
                                        new com.anatomist.config.ProjectConfig(),
                                        false))
                        .resourceProviders()
                        .stream()
                        .anyMatch(provider -> provider.id().equals("spring-xml-resources")));
        assertTrue(
                plugins
                        .prepareDetached(
                                new JavaFrameworkContext(
                                        Path.of("."),
                                        List.of(),
                                        new com.anatomist.config.ProjectConfig(),
                                        false))
                        .resourceAnalyzers()
                        .stream()
                        .anyMatch(analyzer -> analyzer.id().equals("sofa-xml")));
    }
}
