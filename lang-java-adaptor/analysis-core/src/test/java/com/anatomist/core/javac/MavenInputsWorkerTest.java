package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.*;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

class MavenInputsWorkerTest {
    @Test
    void duplicateCoordinatesIdentifyBothPomsBeforeSelection(@TempDir Path tmp) throws Exception {
        Path worker = tmp.resolve("worker.jar");
        try (var input =
                JavacMavenInputs.class.getResourceAsStream(
                        "/META-INF/anatomist/maven-input-worker.jar")) {
            assertNotNull(input);
            Files.copy(input, worker);
        }
        var projects = new ArrayList<MavenProject>();
        for (String module : List.of("app", "duplicate")) {
            Path pom = Files.createDirectories(tmp.resolve(module)).resolve("pom.xml");
            Files.writeString(pom, "<project/>\n");
            var project = new MavenProject();
            project.setGroupId("fixture");
            project.setArtifactId("app");
            project.setVersion("1");
            project.setFile(pom.toFile());
            projects.add(project);
        }
        var request = new DefaultMavenExecutionRequest();
        var properties = new Properties();
        properties.setProperty("anatomist.inputs.output", tmp.toString());
        properties.setProperty("anatomist.inputs.nonce", UUID.randomUUID().toString());
        properties.setProperty("anatomist.inputs.interactive", "true");
        Path selected =
                Files.writeString(tmp.resolve("selected.txt"), tmp.resolve("app").toString());
        properties.setProperty("anatomist.inputs.modules", selected.toString());
        request.setUserProperties(properties);
        var session =
                new MavenSession(
                        null,
                        (org.eclipse.aether.RepositorySystemSession) null,
                        request,
                        new DefaultMavenExecutionResult());
        session.setAllProjects(projects);
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {worker.toUri().toURL()}, getClass().getClassLoader())) {
            var participant =
                    (AbstractMavenLifecycleParticipant)
                            loader.loadClass("com.anatomist.maven.MavenInputsWorker")
                                    .getConstructor()
                                    .newInstance();
            var failure =
                    assertThrows(
                            MavenExecutionException.class,
                            () -> participant.afterProjectsRead(session));
            String detail = failure.getCause().getMessage();
            assertTrue(detail.contains("duplicate reactor coordinate fixture:app:1"), detail);
            for (var project : projects)
                assertTrue(detail.contains(project.getFile().getCanonicalPath()), detail);
            assertFalse(Files.exists(tmp.resolve("metadata.xml")));
        }
    }
}
