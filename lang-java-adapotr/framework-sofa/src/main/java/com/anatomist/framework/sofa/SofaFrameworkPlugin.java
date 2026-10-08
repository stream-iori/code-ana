package com.anatomist.framework.sofa;

import com.anatomist.framework.JavaFrameworkPlugin;

import java.util.List;

/** SOFA annotations and the SOFA dialect embedded in Spring bean XML. */
public final class SofaFrameworkPlugin implements JavaFrameworkPlugin {
    @Override
    public String id() {
        return "sofa";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<String> requires() {
        return List.of("spring");
    }

    @Override
    public com.anatomist.framework.JavaModelAnalyzerRegistry detachedAnalyzers(
            com.anatomist.framework.JavaFrameworkContext context) {
        return new com.anatomist.framework.JavaModelAnalyzerRegistry(
                List.of(),
                List.of(new SofaJavaModelAnnotations()),
                List.of(),
                List.of(new SofaXmlAnalyzer()));
    }
}
