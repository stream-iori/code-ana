package dev.codeana.analysis.extension;

/** Emits facts from a read-only detached model and the complete staged fact view. */
public interface JavaModelAnalyzer extends ExtensionPoint {
    void analyze(ExtensionContext context, FactSink facts) throws Exception;
}
