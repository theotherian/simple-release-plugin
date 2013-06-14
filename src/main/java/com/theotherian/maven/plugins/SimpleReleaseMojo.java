package com.theotherian.maven.plugins;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.manager.ScmManager;
import org.twdata.maven.mojoexecutor.MojoExecutor.*;

import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;


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

  @Parameter(defaultValue = "2.3.2")
  private String releasePluginVersion;

  @Parameter(defaultValue = "2.7")
  private String deployPluginVersion;

  @Parameter(defaultValue = "true")
  private boolean validateScm;

  @Component
  private ScmManager manager;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    if (validateScm) {
      // Validate SCM information before attempting a release at all
      SimpleReleaseScmValidator.buildAndExecute(mavenProject, manager);
    }

    String runtimeVersion = findRuntimeVersion();

    String rootBuildDirectory = mavenProject.getBuild().getDirectory() + "/simple-release-plugin";

    // we're still running release:prepare, but we're also running the 'preflight-check' mojo which will make sure
    // the version we're trying to release doesn't exist.
    // also, we're intervening at the end of the install phase to create manifests for the artifacts that need to be
    // uploaded
    executeMojo(
      plugin(
        groupId("org.apache.maven.plugins"),
        artifactId("maven-release-plugin"),
        version(releasePluginVersion)
      ),
      goal("prepare"),
      configuration(
        element(name("resume"), "false"),
        element(name("preparationGoals"),
            "com.theotherian.maven.plugins:simple-release-plugin:" + runtimeVersion + ":preflight-check " +
            "clean " +
            "com.theotherian.maven.plugins:simple-release-plugin:" + runtimeVersion + ":install"),
        element(name("arguments"), "-Dsimple-release.rootBuildDirectory=" + rootBuildDirectory)),
      executionEnvironment(mavenProject, mavenSession, pluginManager)
    );



    try {
      Map<String, File> buildFilesByArtifact = enumerateDeploymentDescriptors(rootBuildDirectory);

      Map<String, File> pomFilesByArtifact = enumerateReleasePoms(rootBuildDirectory);

      for (MavenProject project : reactorProjects) {
        // look up the pom and the descriptor for each project
        // the pom is the actual release pom maven builds for the project
        // the descriptor tracks all the artifacts created under the install phase
        String projectCoordinates = getNonversionedCoordinates(project.getId());
        File descriptor = buildFilesByArtifact.get(projectCoordinates);
        File pom = pomFilesByArtifact.get(projectCoordinates);


        try {
          List<String> lines = IOUtils.readLines(new FileReader(descriptor));
          getLog().info("Coordinates: " + projectCoordinates + ", " + "Artifact info:");
          for (String line : lines) {
            getLog().info(line);
          }
          // upload the pom and all the artifacts for the given project
          deployArtifacts(pom, lines);

        }
        catch (Exception e) {
          getLog().error(e);
        }
      }
    }
    catch (Exception e) {

    }

    // no matter what happens, run release:clean to remove the extra files the release:prepare goal creates
    executeMojo(
        plugin(
            groupId("org.apache.maven.plugins"),
            artifactId("maven-release-plugin"),
            version(releasePluginVersion)
        ),
        goal("clean"),
        configuration(),
        executionEnvironment(mavenProject, mavenSession, pluginManager)
    );

  }

  private Map<String, File> enumerateReleasePoms(String rootBuildDirectory) {
    return getMavenProjectFilesByExtension(rootBuildDirectory, SimpleReleaseInstallMojo.RELEASE_POM_EXTENSION);
  }

  private static final Set<String> STANDARD_ARTIFACTS = ImmutableSet.of("javadoc", "sources", "artifact");

  /**
   * Here's where the magic happens.  The pom and each artifact as described in 'lines' is uploaded via the deploy
   * plugin and release information is updated.
   * @param pom
   * @param lines
   * @throws MojoExecutionException
   */
  private void deployArtifacts(File pom, List<String> lines) throws MojoExecutionException {

    Map<String, ArtifactInfo> artifactsByClassifier = Maps.newHashMap();

    // parse each line, which is in the format 'classifier'='absolute file path'|'type'
    // an example of this is
    // for each line, create an ArtifactInfo instance that contains the path and type, and put that in a map with a
    // key of the classifier
    // the deploy plugin likes having both the classifier and the type
    for (String line : lines) {
      String[] artifactInfo = line.split("=");
      String[] artifactValues = artifactInfo[1].split("[|]");
      ArtifactInfo info = new ArtifactInfo();
      info.setFile(artifactValues[0]);
      info.setType(artifactValues[1]);
      artifactsByClassifier.put(artifactInfo[0], info);
    }

    // get the release repo details
    DeploymentRepository releaseRepository = mavenProject.getDistributionManagement().getRepository();
    String releaseRepoUrl = releaseRepository.getUrl();

    // build up the list of elements that we always need - the url of the repo, the repo id, the pom file, and
    // opt to update the release info with this upload
    List<Element> elements = Lists.newArrayList(
      element(name("url"), releaseRepoUrl),
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

    // sources are a special case and need to be named as sources in the plugin execution
    if (artifactsByClassifier.containsKey("sources")) {
      elements.add(element(name("sources"), artifactsByClassifier.get("sources").getFile()));
    }

    // javadocs are also a special case and need to be named as javadoc in the plugin execution
    if (artifactsByClassifier.containsKey("javadoc")) {
      elements.add(element(name("javadoc"), artifactsByClassifier.get("javadoc").getFile()));
    }


    List<String> additionalFiles = Lists.newArrayList();
    List<String> additionalClassifiers = Lists.newArrayList();
    List<String> additionalTypes = Lists.newArrayList();

    // for everything that isn't a standard artifact, which is the artifact itself along with sources and javadoc,
    // we need to build up lists for the data that are ordered with one another, i.e. you should have something
    // like:
    // classifiers=rpm,deb
    // files=foo.rpm,foo.deb
    // types=rpm-installer,deb-installer
    for (String key : artifactsByClassifier.keySet()) {
      if (!STANDARD_ARTIFACTS.contains(key)) {
        additionalClassifiers.add(key);
        additionalFiles.add(artifactsByClassifier.get(key).getFile());
        additionalTypes.add(artifactsByClassifier.get(key).getType());
      }
    }

    // if we have additional files, go through each list and join the data with commas so that it matches across
    // all three attributes
    if (!additionalFiles.isEmpty()) {
      elements.add(element(name("files"), StringUtils.join(additionalFiles, ',')));
      elements.add(element(name("classifiers"), StringUtils.join(additionalClassifiers, ',')));
      elements.add(element(name("types"), StringUtils.join(additionalTypes, ',')));
    }

    Element[] deployElements = new Element[elements.size()];
    elements.toArray(deployElements);

    // execute the deploy plugin with all of the elements we've amassed that track the artifacts we've built
    executeMojo(
      plugin(
        groupId("org.apache.maven.plugins"),
        artifactId("maven-deploy-plugin"),
        version(deployPluginVersion)
      ),
      goal("deploy-file"),
      configuration(deployElements),
      executionEnvironment(mavenProject, mavenSession, pluginManager)
    );
    // seems like a bad idea, but if you don't it'll try to upload previously uploaded artifacts
    mavenProject.getAttachedArtifacts().clear();
  }

  private Map<String, File> enumerateDeploymentDescriptors(String rootBuildDirectory) {
    return getMavenProjectFilesByExtension(rootBuildDirectory, SimpleReleaseInstallMojo.DEPLOY_DESCRIPTOR_EXTENSION);
  }

  /**
   * Builds a map matching a file to the maven group id and artifact id it represents
   * @param rootBuildDirectory
   * @param extension
   * @return a map of files based on group id and artifact id
   */
  private Map<String, File> getMavenProjectFilesByExtension(String rootBuildDirectory, String extension) {
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

  /**
   * Figures out the group id and artifact id string of the project
   * @param projectArtifactId
   * @return 'groupId':'artifactId'
   */
  String getNonversionedCoordinates(String projectArtifactId) {
    int colonCounter = 0;
    int place = 0;
    for (char c : projectArtifactId.toCharArray()) {
      place++;
      if (c == ':') {
        colonCounter++;
      }
      if (colonCounter == 2) {
        break;
      }
    }
    return projectArtifactId.substring(0, place - 1);
  }

  /**
   * Figures out what version of this plugin is executing at runtime.  The reason for this is because this plugin
   * executes other 'child' goals of itself, and wants to execute the same version of those to ensure compatibility
   * @return the runtime version of this plugin
   */
  private String findRuntimeVersion() {
    PluginDescriptor pluginDescriptor = new PluginDescriptor();
    pluginDescriptor.setGroupId("com.theotherian.maven.plugins");
    pluginDescriptor.setArtifactId("simple-release-plugin");
    Map<String,Object> pluginContext = mavenSession.getPluginContext(pluginDescriptor, mavenProject);
    PluginDescriptor descriptor = (PluginDescriptor) pluginContext.get("pluginDescriptor");
    getLog().info("Detected runtime version of '" + descriptor.getVersion() + "' for simple-release-plugin");
    return descriptor.getVersion();
  }

}
