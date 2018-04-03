package com.github.rmannibucau.gradle.plugin;

import static java.util.Collections.list;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;

import org.gradle.BuildAdapter;
import org.gradle.BuildResult;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import lombok.Data;

public class ListArtifactsPlugin implements Plugin<Project> {

    @Override
    public void apply(final Project project) {
        final ClassLoader buildScriptLoader = Thread.currentThread().getContextClassLoader();
        project.getGradle().addBuildListener(new BuildAdapter() {

            @Override
            public void buildFinished(final BuildResult result) {
                if (result.getFailure() != null) {
                    project.getLogger().warn("Build failed, skipping artifacts dump");
                    return;
                }
                final Thread thread = Thread.currentThread();
                final ClassLoader oldLoader = thread.getContextClassLoader();
                thread.setContextClassLoader(buildScriptLoader);
                try (final Jsonb jsonb = JsonbBuilder.create(new JsonbConfig().withFormatting(true))) {
                    final Set<String> alreadyAdded = new HashSet<>();
                    final Deployments deployments = new Deployments();
                    deployments.deployments = new TreeSet<>(Comparator.comparing(e -> e.artifact));

                    project.getAllprojects().forEach(
                            project -> project.getConfigurations().forEach(c -> c.getAllArtifacts().getFiles().forEach(f -> {
                                // todo: convert the path to a maven gav
                                onDeployment(alreadyAdded, deployments, project.getRootDir().toPath().relativize(f.toPath()).toString(), f);
                            })));

                    if (!deployments.deployments.isEmpty()) {
                        final String json = jsonb.toJson(deployments);
                        final Object output = project.getProperties().get("listArtifactsOutput");
                        if (output == null) {
                            project.getLogger().info("Deployments:\n{}", json);
                        } else {
                            try (final Writer writer = new BufferedWriter(new FileWriter(output.toString()))) {
                                writer.write(json);
                            }
                        }
                    }
                } catch (final Exception e) {
                    project.getLogger().error(e.getMessage(), e);
                } finally {
                    thread.setContextClassLoader(oldLoader);
                }
            }
        });
    }

    private void onDeployment(final Set<String> alreadyAdded, final Deployments deployments, final String artifact,
            final File file) {
        if (!alreadyAdded.add(artifact)) { // todo: check it is the same, it was for maven but todo: check for gradle
            return;
        }

        final String name = file.getName();
        final Deployment deployment = new Deployment();
        deployment.artifact = artifact;
        synchronized (deployments) {
            deployments.deployments.add(deployment);
        }
        if (name.endsWith(".jar")) {
            deployment.content = listJarContent(file);
        } else if (name.endsWith(".pom")) {
            // add the actual content to be able to diff it
            try {
                deployment.content = singletonMap("content",
                        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream().collect(joining("\n")));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        } else {
            deployment.content = null; // for now
        }
    }

    private Map<String, String> listJarContent(final File file) {
        try (final JarFile jar = new JarFile(file)) {
            return new TreeMap<>(list(jar.entries()).stream().collect(toMap(ZipEntry::getName, e -> Long.toString(e.getSize()))));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    public static class Deployment {

        private String artifact;

        private Map<String, String> content;
    }

    @Data
    public static class Deployments {

        private Collection<Deployment> deployments;
    }
}
