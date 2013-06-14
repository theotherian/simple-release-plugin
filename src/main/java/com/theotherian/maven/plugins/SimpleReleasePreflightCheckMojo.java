package com.theotherian.maven.plugins;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

@Mojo(name = "preflight-check", aggregator = true)
public class SimpleReleasePreflightCheckMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${reactorProjects}")
  private List<MavenProject> reactorProjects;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    ArtifactRepository repository = project.getDistributionManagementArtifactRepository();

    if (reactorProjects.isEmpty()) {
      checkForArtifact(repository, project);
    }
    else {
      for (MavenProject reactorProject : reactorProjects) {
        checkForArtifact(repository, reactorProject);
      }
    }

  }

  private void checkForArtifact(ArtifactRepository repository, MavenProject project) throws MojoExecutionException {
    String groupId = project.getGroupId().replaceAll("[.]", "/");
    String artifactId = project.getArtifactId();
    String version = project.getVersion();

    String uri = "/" + groupId + "/" + artifactId + "/" + version;

    String target = repository.getUrl() + uri;

    getLog().info("Checking to see if something already exists at " + target);

    int responseCode = 0;
    try {
      URL url = new URL(target);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      responseCode = connection.getResponseCode();
      getLog().debug("Response code for " + target + " was " + responseCode);

    }
    catch (Exception e) {
      getLog().error("Couldn't check preconditions for build", e);
      return;
    }

    if (responseCode != 404) {
      MojoExecutionException e = new MojoExecutionException("The release version specified is already present in the repository");
      getLog().error("This release either already exists or an unexpected response code was returned, failing build since it won't " +
          "complete due to duplicate artifact or other errors. (response code was " + responseCode + ")", e);
      throw e;
    }
    else {
      getLog().info("Release for " + project.getId() + " doesn't exist, therefore this build can proceed");
    }
  }

}
