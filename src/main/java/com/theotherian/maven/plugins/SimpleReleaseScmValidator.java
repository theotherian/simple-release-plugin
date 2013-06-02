package com.theotherian.maven.plugins;

import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmException;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.info.InfoScmResult;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.repository.ScmRepository;

import java.io.File;

@Mojo(name = "validate-scm", aggregator = true)
public class SimpleReleaseScmValidator extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Component
  private ScmManager manager;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {


    Scm scm = project.getScm();
    ScmRepository repository = null;
    try {
      repository = manager.makeScmRepository(scm.getDeveloperConnection());
      getLog().info("Provider: " + repository.getProvider());


//      ScmProviderRepositoryWithHost providerRepository = (ScmProviderRepositoryWithHost) repository.getProviderRepository();
//      ScmProviderRepository providerRepository = scmProvider.makeProviderScmRepository(new File("."));
      ScmProviderRepository providerRepository = repository.getProviderRepository();
      ScmProvider provider = manager.getProviderByRepository(repository);
//      getLog().info("Provider thinks it was: " + provider.getScmType());

      if ("svn".equals(repository.getProvider())) {
        validateSvnLocations(scm, provider, providerRepository);
      }

//      ScmFileSet fileSet = new ScmFileSet(new File("."));
//      CommandParameters parameters = new CommandParameters();
//      InfoScmResult info = provider.info(providerRepository, fileSet, parameters);
//      RemoteInfoScmResult remoteInfo = provider.remoteInfo(providerRepository, fileSet, parameters);
//      provider.status(repository, fileSet);
//
//
//      getLog().info("Provider message: " + info.getProviderMessage());
//      for (InfoItem item : info.getInfoItems()) {
//        getLog().info("Info item: " + item.getURL());
//      }
    } catch (Exception e) {
      getLog().error(e);
      throw new MojoFailureException("Validating SCM information failed", e);
    }

  }

  private void validateSvnLocations(Scm scm, ScmProvider provider, ScmProviderRepository providerRepository)
      throws ScmException, MojoExecutionException {

    getLog().info("Attempting to validate SCM information for SCM type: 'svn'...");

    ScmFileSet fileSet = new ScmFileSet(new File("."));
    CommandParameters parameters = new CommandParameters();
    InfoScmResult info = provider.info(providerRepository, fileSet, parameters);
    if (info.getInfoItems().size() > 1) {
      getLog().warn("Can't validate SCM info: unexpected number of results from 'svn info' command: "
          + info.getInfoItems().size());
      return;
    }
    else if (info.getInfoItems().isEmpty()) {
      getLog().warn("Can't validate SCM info: nothing returned from 'svn info' command.  " +
          "Your release may be unstable or have invalid information.");
      return;
    }
    String urlFromPom = scm.getDeveloperConnection().substring("scm:svn:".length());
    String urlFromInfoCommand = info.getInfoItems().get(0).getURL();
    getLog().info("SCM location from pom: '" + urlFromPom + "'");
    getLog().info("SCM location from 'svn info': '" + urlFromInfoCommand + "'");
    if (urlFromPom.equals(urlFromInfoCommand)) {
      getLog().info("SCM locations equal; tagging will ensure the correct source captured for the release");
    }
    else {
      String message = "SCM locations for workspace and pom SCM section are not the same.  " +
          "Your build is not configured correctly for a release as tagging during release prepare will tag " +
          "the incorrect location: expected '" + urlFromInfoCommand + "', but was '" + urlFromPom + "'";
      getLog().error(message);
      throw new MojoExecutionException(message);
    }
  }

  public static void buildAndExecute(MavenProject project, ScmManager manager) throws MojoFailureException, MojoExecutionException {
    SimpleReleaseScmValidator validator = new SimpleReleaseScmValidator();
    validator.manager = manager;
    validator.project = project;
    validator.execute();
  }

}
