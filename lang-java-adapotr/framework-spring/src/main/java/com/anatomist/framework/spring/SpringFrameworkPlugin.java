package com.anatomist.framework.spring;

import com.anatomist.framework.JavaFrameworkPlugin;

/** Spring's Java and XML graph analysis. */
public final class SpringFrameworkPlugin implements JavaFrameworkPlugin {
    @Override
    public String id() {
        return "spring";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public com.anatomist.framework.JavaModelAnalyzerRegistry detachedAnalyzers(
            com.anatomist.framework.JavaFrameworkContext context) {
        return new com.anatomist.framework.JavaModelAnalyzerRegistry(
                java.util.List.of(),
                java.util.List.of(
                        new SpringJavaModelComponents(),
                        new SpringJavaModelMvc(),
                        new SpringJavaModelHandlers()),
                java.util.List.of(new SpringXmlResourceProvider()),
                java.util.List.of(new SpringXmlAnalyzer()));
    }
}
