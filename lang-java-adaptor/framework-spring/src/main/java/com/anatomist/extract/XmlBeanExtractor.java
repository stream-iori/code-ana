package com.anatomist.extract;

import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.framework.ProjectFactView;
import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.ExtractionResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compatibility facade for the Spring XML graph adapter. */
@Deprecated(forRemoval = false)
public final class XmlBeanExtractor {
    private final com.anatomist.framework.spring.XmlBeanGraphExtractor delegate;

    public XmlBeanExtractor() {
        this.delegate = new com.anatomist.framework.spring.XmlBeanGraphExtractor();
    }

    public XmlBeanExtractor(String scope) {
        this.delegate = new com.anatomist.framework.spring.XmlBeanGraphExtractor(scope);
    }

    public void extract(
            List<ParsedBean> beans,
            Set<String> knownIds,
            String sourceFile,
            ExtractionResult result) {
        delegate.extract(beans, knownIds, sourceFile, result);
    }

    public void extract(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, String> existingBeanClass,
            String sourceFile,
            ExtractionResult result) {
        delegate.extract(beans, knownIds, existingBeanClass, sourceFile, result);
    }

    public void extractWithResolvedBeans(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            String sourceFile,
            ExtractionResult result) {
        delegate.extractWithResolvedBeans(beans, knownIds, existingBeans, sourceFile, result);
    }

    public void extractWithResolvedBeans(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            String sourceFile,
            ArtifactRange artifactRange,
            ExtractionResult result) {
        delegate.extractWithResolvedBeans(
                beans, knownIds, existingBeans, sourceFile, artifactRange.delegate(), result);
    }

    public void extractWithResolvedBeans(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            String sourceFile,
            ArtifactRange artifactRange,
            ProjectFactView facts,
            ExtractionResult result) {
        delegate.extractWithResolvedBeans(
                beans,
                knownIds,
                existingBeans,
                sourceFile,
                artifactRange.delegate(),
                facts,
                result);
    }

    public record ArtifactRange(int endLine, int endColumn) {
        public ArtifactRange {
            if (endLine < 1 || endColumn < 1) {
                throw new IllegalArgumentException("artifact range must be positive");
            }
        }

        private com.anatomist.framework.spring.XmlBeanGraphExtractor.ArtifactRange delegate() {
            return new com.anatomist.framework.spring.XmlBeanGraphExtractor.ArtifactRange(
                    endLine, endColumn);
        }
    }

    public static String artifactNodeId(String sourceFile) {
        return com.anatomist.framework.spring.XmlBeanGraphExtractor.artifactNodeId(sourceFile);
    }

    public static String beanNodeId(String beanName, String sourceFile) {
        return com.anatomist.framework.spring.XmlBeanGraphExtractor.beanNodeId(
                beanName, sourceFile);
    }

    public static Map<String, String> resolveProductClasses(
            List<ParsedBean> beans,
            Map<String, BeanRefTarget> existingBeans,
            ProjectFactView facts,
            String sourceFile) {
        return com.anatomist.framework.spring.XmlBeanGraphExtractor.resolveProductClasses(
                beans, existingBeans, facts, sourceFile);
    }
}
