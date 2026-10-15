package dev.codeana.adapter;

import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.JavaFrameworkPlugin;
import com.anatomist.framework.JavaModelAnalyzerRegistry;
import com.anatomist.framework.spring.SpringFrameworkPlugin;
import java.util.List;

/** Selects the copied plugin's Java/XML extensions using independent captured switches. */
final class CapturedSpringPlugin implements JavaFrameworkPlugin {
    private final SpringFrameworkPlugin original = new SpringFrameworkPlugin();
    private final boolean javaEnabled;
    private final boolean xmlEnabled;

    CapturedSpringPlugin(boolean javaEnabled, boolean xmlEnabled) {
        this.javaEnabled = javaEnabled;
        this.xmlEnabled = xmlEnabled;
    }

    public String id() {
        return original.id();
    }

    public String version() {
        return original.version() + ":java=" + javaEnabled + ":xml=" + xmlEnabled;
    }

    public List<String> requires() {
        return original.requires();
    }

    public JavaModelAnalyzerRegistry detachedAnalyzers(JavaFrameworkContext context) {
        var registry = original.detachedAnalyzers(context);
        return new JavaModelAnalyzerRegistry(
                javaEnabled ? registry.declarations() : List.of(),
                javaEnabled ? registry.analyzers() : List.of(),
                xmlEnabled ? registry.resourceProviders() : List.of(),
                xmlEnabled ? registry.resourceAnalyzers() : List.of(),
                registry.pluginContracts(),
                javaEnabled ? registry.callSiteEvidenceProviders() : List.of());
    }
}
