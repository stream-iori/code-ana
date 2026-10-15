package com.anatomist.framework.spring;

import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.ProjectFactView;
import com.anatomist.framework.ProjectResource;
import com.anatomist.framework.ProjectResourceAnalyzer;
import com.anatomist.framework.ResourceSelector;
import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.ExtractionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpringXmlAnalyzer implements ProjectResourceAnalyzer {

    @Override
    public String id() {
        return "spring-xml";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public boolean enabled(JavaFrameworkContext context) {
        return context.springXml();
    }

    @Override
    public ResourceSelector selector() {
        return ResourceSelector.kind("spring-xml");
    }

    @Override
    public void analyze(
            JavaFrameworkContext context,
            List<ProjectResource> resources,
            ProjectFactView facts,
            ExtractionResult result) {
        if (!enabled(context)) return;
        var xmlFiles = resources.stream().map(ProjectResource::path).toList();
        if (xmlFiles.isEmpty()) return;
        extractXmlBeans(context.projectRoot(), xmlFiles, facts, result);
    }

    public static void extractXmlBeans(
            Path projectRoot, List<Path> xmlFiles, ProjectFactView facts, ExtractionResult result) {
        extractXmlBeans(
                projectRoot, xmlFiles, facts.knownNodeIds(), facts.beanTargets(), facts, result);
    }

    public static void extractXmlBeans(
            Path projectRoot,
            List<Path> xmlFiles,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            ExtractionResult result) {
        ProjectFactView facts =
                new com.anatomist.framework.DefaultProjectFactView(knownIds, existingBeans);
        extractXmlBeans(projectRoot, xmlFiles, knownIds, existingBeans, facts, result);
    }

    private static void extractXmlBeans(
            Path projectRoot,
            List<Path> xmlFiles,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            ProjectFactView facts,
            ExtractionResult result) {
        List<XmlSource> parsed = new ArrayList<>();
        SpringBeanParser beanParser = new SpringBeanParser();
        List<Path> sorted = new ArrayList<>(xmlFiles);
        sorted.sort(
                Comparator.comparing(p -> relativize(projectRoot, p.toAbsolutePath().normalize())));
        for (Path xml : sorted) {
            String rel = relativize(projectRoot, xml.toAbsolutePath().normalize());
            parsed.add(new XmlSource(rel, beanParser.parse(xml), artifactRange(xml)));
        }

        Set<String> allKnownIds = new HashSet<>(knownIds);
        Map<String, BeanRefTarget> resolvedBeans = new HashMap<>(existingBeans);
        Set<String> ambiguous = new HashSet<>();
        for (XmlSource src : parsed) {
            for (ParsedBean b : src.beans()) {
                String beanId = XmlBeanGraphExtractor.beanNodeId(b.name(), src.sourceFile());
                allKnownIds.add(beanId);
                putUnique(
                        resolvedBeans,
                        ambiguous,
                        "bean:" + b.name(),
                        new BeanRefTarget(
                                beanId, b.factoryMethod() == null ? b.className() : null));
            }
        }
        List<ParsedBean> allBeans =
                parsed.stream().flatMap(source -> source.beans().stream()).toList();
        Map<String, String> products =
                XmlBeanGraphExtractor.resolveProductClasses(
                        allBeans, resolvedBeans, facts, "<spring-xml-set>");
        for (Map.Entry<String, String> product : products.entrySet()) {
            String key = "bean:" + product.getKey();
            BeanRefTarget target = resolvedBeans.get(key);
            if (target != null && !ambiguous.contains(key)) {
                resolvedBeans.put(key, new BeanRefTarget(target.beanId(), product.getValue()));
            }
        }

        XmlBeanGraphExtractor xmlExtractor = new XmlBeanGraphExtractor("MAIN");
        for (XmlSource src : parsed) {
            xmlExtractor.extractWithResolvedBeans(
                    src.beans(),
                    allKnownIds,
                    resolvedBeans,
                    src.sourceFile(),
                    src.range(),
                    facts,
                    result);
        }
    }

    public static Map<String, BeanRefTarget> fromBeanClassMap(Map<String, String> beanClassById) {
        Map<String, BeanRefTarget> out = new HashMap<>();
        for (Map.Entry<String, String> e : beanClassById.entrySet()) {
            out.put(e.getKey(), new BeanRefTarget(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static void putUnique(
            Map<String, BeanRefTarget> beans,
            Set<String> ambiguous,
            String key,
            BeanRefTarget target) {
        if (ambiguous.contains(key)) return;
        BeanRefTarget prior = beans.putIfAbsent(key, target);
        if (prior != null && !prior.equals(target)) {
            beans.remove(key);
            ambiguous.add(key);
        }
    }

    private record XmlSource(
            String sourceFile, List<ParsedBean> beans, XmlBeanGraphExtractor.ArtifactRange range) {}

    private static XmlBeanGraphExtractor.ArtifactRange artifactRange(Path file) {
        try {
            String content = Files.readString(file);
            int line = 1;
            int column = 1;
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new XmlBeanGraphExtractor.ArtifactRange(line, column);
        } catch (java.io.IOException e) {
            return new XmlBeanGraphExtractor.ArtifactRange(1, 1);
        }
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}
