package com.anatomist.framework.spring;

import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.ProjectResource;
import com.anatomist.framework.ProjectResourceProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Discovers Spring beans XML without coupling orchestration to the resource kind. */
public final class SpringXmlResourceProvider implements ProjectResourceProvider {
    @Override
    public String id() {
        return "spring-xml-resources";
    }

    // Root-only discovery also inventories documents with a malformed body.
    @Override
    public String version() {
        return "3";
    }

    @Override
    public String producerId() {
        return "spring-xml-resources";
    }

    @Override
    public boolean enabled(JavaFrameworkContext context) {
        return context != null && context.springXml();
    }

    @Override
    public Set<String> kinds() {
        return Set.of("spring-xml");
    }

    @Override
    public boolean mayContain(Path path) {
        return path != null
                && path.getFileName() != null
                && path.getFileName().toString().endsWith(".xml");
    }

    @Override
    public List<ProjectResource> discover(JavaFrameworkContext context, ProjectScanner scanner) {
        var roots = context.config().resolvedResourceRoots();
        var files =
                roots == null
                        ? scanner.scanFiles(context.projectRoot(), Set.of(".xml"))
                        : scanner.scanSourceRoots(roots, Set.of(".xml"));
        return files.stream()
                .filter(SpringBeanParser::isSpringBeansFile)
                .map(
                        path ->
                                new ProjectResource(
                                        path, relative(context.projectRoot(), path), "spring-xml"))
                .toList();
    }

    private static String relative(Path root, Path path) {
        try {
            return root.toAbsolutePath()
                    .normalize()
                    .relativize(path.toAbsolutePath().normalize())
                    .toString();
        } catch (IllegalArgumentException ignored) {
            return path.toString();
        }
    }
}
