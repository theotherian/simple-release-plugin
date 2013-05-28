package com.theotherian.maven.plugins;

import java.io.File;
import java.io.FileWriter;

import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name = "install")
@Execute(phase = LifecyclePhase.INSTALL)
public class SimpleReleaseInstallMojo extends AbstractMojo {

  public static final String RELEASE_POM_EXTENSION = ".release-pom";

  public static final String DEPLOY_DESCRIPTOR_EXTENSION = ".deploy-descriptor";

  @Parameter(defaultValue = "${executedProject}", readonly = true, required = true)
  private MavenProject mavenProject;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession mavenSession;

  @Component
  private BuildPluginManager pluginManager;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    String rootBuildDirectory = (String) mavenSession.getSystemProperties().get("simple-release.rootBuildDirectory");

    getLog().info("Root build dir: " + rootBuildDirectory);

    try {
      String fileName = mavenProject.getId() + DEPLOY_DESCRIPTOR_EXTENSION;
      File file = new File(rootBuildDirectory + "/" + fileName);
      String pomFileName = mavenProject.getId() + RELEASE_POM_EXTENSION;
      File pomFile = new File(rootBuildDirectory + "/" + pomFileName);
      try (FileWriter writer = new FileWriter(file)) {
        // if we have a pom as the main artifact don't try to attach another nothing else can be attached
        if (!"pom".equals(mavenProject.getArtifact().getType())) {
          writer.write("artifact=" + mavenProject.getArtifact().getFile().getAbsolutePath() +
            "|" + mavenProject.getArtifact().getType() + "\n");
        }
        for (Artifact artifact : mavenProject.getAttachedArtifacts()) {
          writer.write(artifact.getClassifier() + "=" + artifact.getFile().getAbsolutePath() +
            "|" + artifact.getType() + "\n");
        }
      }
      FileUtils.copyFile(mavenProject.getFile(), pomFile);
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to write file", e);
    }

  }



}
