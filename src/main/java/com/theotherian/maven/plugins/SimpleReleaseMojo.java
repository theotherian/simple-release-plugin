package com.theotherian.maven.plugins;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


// set aggregator to true to avoid traversing the aggregator, because, obviously... *facepalm*
@Mojo(name = "release", aggregator = true)
public class SimpleReleaseMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject mavenProject;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession mavenSession;

  @Component
  private BuildPluginManager pluginManager;

  @Parameter(defaultValue = "${reactorProjects}")
  private List<MavenProject> reactorProjects;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    String rootBuildDirectory = mavenProject.getBuild().getDirectory();

    getLog().info("doing something");

    executeMojo(
      plugin(
        groupId("org.apache.maven.plugins"),
        artifactId("maven-release-plugin"),
        // FIXME this should be configurable
        version("2.3.2")
      ),
      goal("prepare"),
      configuration(
        element(name("resume"), "false"),
        element(name("preparationGoals"),
//            "com.theotherian.maven.plugins:simple-release-plugin:preflight-check " +
            "clean " +
            "com.theotherian.maven.plugins:simple-release-plugin:1.0-SNAPSHOT:install"),
        element(name("arguments"), "-Dsimple-release.rootBuildDirectory=" + rootBuildDirectory)),
      executionEnvironment(mavenProject, mavenSession, pluginManager)
    );



    try {
      Map<String, File> buildFilesByArtifact = enumerateDeploymentDescriptors(rootBuildDirectory);

      Map<String, File> pomFilesByArtifact = enumerateReleasePoms(rootBuildDirectory);

      for (MavenProject project : reactorProjects) {
        String projectCoordinates = getNonversionedCoordinates(project.getId());
        File descriptor = buildFilesByArtifact.get(projectCoordinates);
        File pom = pomFilesByArtifact.get(projectCoordinates);
        try {

          List<String> lines = IOUtils.readLines(new FileReader(descriptor));
          getLog().info("Coordinates: " + projectCoordinates + ", " + "Artifact info:");
          for (String line : lines) {
            getLog().info(line);
          }
          deployArtifacts(pom, lines);

        }
        catch (Exception e) {
          getLog().error(e);
        }
      }
    }
    catch (Exception e) {

    }
    executeMojo(
        plugin(
            groupId("org.apache.maven.plugins"),
            artifactId("maven-release-plugin"),
            // FIXME this should be configurable
            version("2.3.2")
        ),
        goal("clean"),
        configuration(),
        executionEnvironment(mavenProject, mavenSession, pluginManager)
    );

  }

  private Map<String, File> enumerateReleasePoms(String rootBuildDirectory) {
    return getAllFilesByExtension(rootBuildDirectory, SimpleReleaseInstallMojo.RELEASE_POM_EXTENSION);
  }

  private static final Set<String> STANDARD_ARTIFACTS = ImmutableSet.of("javadoc", "sources", "artifact");

  private void deployArtifacts(File pom, List<String> lines) throws MojoExecutionException {
    Map<String, ArtifactInfo> artifactsByClassifier = Maps.newHashMap();
    for (String line : lines) {
      String[] artifactInfo = line.split("=");
      String[] artifactValues = artifactInfo[1].split("[|]");
      ArtifactInfo info = new ArtifactInfo();
      info.setFile(artifactValues[0]);
      info.setType(artifactValues[1]);
      artifactsByClassifier.put(artifactInfo[0], info);
    }
    DeploymentRepository releaseRepository = mavenProject.getDistributionManagement().getRepository();
    String releaseRepoUrl = releaseRepository.getUrl();

    List<Element> elements = Lists.newArrayList(
      element(name("url"), releaseRepoUrl),
      // FIXME this should be configurable
      element(name("repositoryId"), releaseRepository.getId()),
      element(name("pomFile"), pom.getAbsolutePath()),
      element(name("updateReleaseInfo"), "true")
    );

    // not true when artifact is only a pom
    if (artifactsByClassifier.containsKey("artifact")) {
      elements.add(element(name("file"), artifactsByClassifier.get("artifact").getFile()));
    }
    // otherwise attach the pom as the file since it's required
    else {
      elements.add(element(name("file"), pom.getAbsolutePath()));
    }

    if (artifactsByClassifier.containsKey("sources")) {
      elements.add(element(name("sources"), artifactsByClassifier.get("sources").getFile()));
    }

    if (artifactsByClassifier.containsKey("javadoc")) {
      elements.add(element(name("javadoc"), artifactsByClassifier.get("javadoc").getFile()));
    }

    List<String> additionalFiles = Lists.newArrayList();
    List<String> additionalClassifiers = Lists.newArrayList();
    List<String> additionalTypes = Lists.newArrayList();

    for (String key : artifactsByClassifier.keySet()) {
      if (!STANDARD_ARTIFACTS.contains(key)) {
        additionalClassifiers.add(key);
        additionalFiles.add(artifactsByClassifier.get(key).getFile());
        additionalTypes.add(artifactsByClassifier.get(key).getType());
      }
    }

    if (!additionalFiles.isEmpty()) {
      elements.add(element(name("files"), StringUtils.join(additionalFiles, ',')));
      elements.add(element(name("classifiers"), StringUtils.join(additionalClassifiers, ',')));
      elements.add(element(name("types"), StringUtils.join(additionalTypes, ',')));
    }

    Element[] deployElements = new Element[elements.size()];
    elements.toArray(deployElements);

    executeMojo(
      plugin(
        groupId("org.apache.maven.plugins"),
        artifactId("maven-deploy-plugin"),
        // FIXME this should be configurable
        version("2.7")
      ),
      goal("deploy-file"),
      configuration(deployElements),
      executionEnvironment(mavenProject, mavenSession, pluginManager)
    );
    // seems like a bad idea, but if you don't it'll try to upload previously uploaded artifacts
    mavenProject.getAttachedArtifacts().clear();
  }

  private Map<String, File> enumerateDeploymentDescriptors(String rootBuildDirectory) {
    return getAllFilesByExtension(rootBuildDirectory, SimpleReleaseInstallMojo.DEPLOY_DESCRIPTOR_EXTENSION);
  }

  private Map<String, File> getAllFilesByExtension(String rootBuildDirectory, String extension) {
    Map<String, File> buildFilesByArtifact = Maps.newHashMap();

    File targetDirectory = new File(rootBuildDirectory);
    File[] buildFiles = targetDirectory.listFiles();

    for (File buildFile : buildFiles) {
      if (buildFile.getName().endsWith(extension)) {
        buildFilesByArtifact.put(getNonversionedCoordinates(buildFile.getName()), buildFile);
      }
    }
    return buildFilesByArtifact;
  }

  private String getNonversionedCoordinates(String projectArtifactId) {
    StringBuilder nonversionedCoordinates = new StringBuilder();
    int colonCounter = 0;
    for (char c : projectArtifactId.toCharArray()) {
      if (c == ':') {
        colonCounter++;
      }
      if (colonCounter == 2) {
        break;
      }
      nonversionedCoordinates.append(c);
    }
    return nonversionedCoordinates.toString();
  }

}
