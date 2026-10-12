package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.framework.JavaFrameworkContext;
import com.anatomist.framework.spring.SpringXmlResourceProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pins Spring resource discovery: it discovers only Spring {@code <beans>}-root {@code .xml} files,
 * ignoring {@code pom.xml} / arbitrary XML, and honours the same excluded-directory rules as the
 * {@code .java} scan.
 */
class ProjectScannerSpringXmlTest {

    private static List<Path> discover(Path root) {
        return new SpringXmlResourceProvider()
                        .discover(
                                new JavaFrameworkContext(
                                        root,
                                        List.of(),
                                        new com.anatomist.config.ProjectConfig(),
                                        true),
                                new ProjectScanner())
                        .stream()
                        .map(com.anatomist.framework.ProjectResource::path)
                        .toList();
    }

    private static void write(Path p, String body) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    private static final String BEANS =
            "<beans xmlns='http://www.springframework.org/schema/beans'>"
                    + "<bean id='a' class='com.example.A'/></beans>";

    @Test
    void findsBeansXmlAndIgnoresOtherXml(@TempDir Path root) throws Exception {
        write(root.resolve("src/main/resources/applicationContext.xml"), BEANS);
        write(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        write(
                root.resolve("src/main/resources/logback.xml"),
                "<configuration><logger name='x'/></configuration>");

        List<Path> found = discover(root);
        assertEquals(1, found.size());
        assertEquals("applicationContext.xml", found.get(0).getFileName().toString());
    }

    @Test
    void respectsExcludedDirs(@TempDir Path root) throws Exception {
        write(root.resolve("src/main/resources/beans.xml"), BEANS);
        write(root.resolve("target/classes/beans.xml"), BEANS);

        List<Path> found = discover(root);
        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().contains("src/main/resources"), found.get(0).toString());
    }

    @Test
    void multiRootScanAggregates(@TempDir Path root) throws Exception {
        write(root.resolve("modA/src/main/resources/a.xml"), BEANS);
        write(root.resolve("modB/src/main/resources/b.xml"), BEANS);

        List<Path> found =
                java.util.stream.Stream.of(root.resolve("modA"), root.resolve("modB"))
                        .flatMap(module -> discover(module).stream())
                        .toList();
        assertEquals(2, found.size());
    }

    @Test
    void emptyWhenNoBeansXml(@TempDir Path root) throws Exception {
        write(root.resolve("pom.xml"), "<project/>");
        assertTrue(discover(root).isEmpty());
    }
}
