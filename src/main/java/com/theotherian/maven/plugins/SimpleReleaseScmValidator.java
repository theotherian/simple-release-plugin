package com.theotherian.maven.plugins;

import org.apache.maven.model.Scm;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.CommandParameter;
import org.apache.maven.scm.CommandParameters;
import org.apache.maven.scm.ScmFileSet;
import org.apache.maven.scm.command.info.InfoItem;
import org.apache.maven.scm.command.info.InfoScmResult;
import org.apache.maven.scm.command.remoteinfo.RemoteInfoScmResult;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.provider.ScmProviderRepository;
import org.apache.maven.scm.provider.ScmProviderRepositoryWithHost;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;

import java.io.File;

@Mojo(name = "validate-scm", aggregator = true)
public class SimpleReleaseScmValidator extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Component
  private ScmManager manager;

//  @Component
//  private ScmProvider scmProvider;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    Scm scm = project.getScm();
    getLog().info("hello!");
    getLog().info("scm was " + scm);
    ScmRepository repository = null;
    try {
//      getLog().info("SCM type: " + scmProvider.getScmType());
      repository = manager.makeScmRepository(scm.getDeveloperConnection());
      getLog().info("Provider: " + repository.getProvider());

//      ScmProviderRepositoryWithHost providerRepository = (ScmProviderRepositoryWithHost) repository.getProviderRepository();
//      ScmProviderRepository providerRepository = scmProvider.makeProviderScmRepository(new File("."));
      ScmProviderRepository providerRepository = repository.getProviderRepository();
      ScmProvider provider = manager.getProviderByRepository(repository);
      getLog().info("Provider thinks it was: " + provider.getScmType());

      ScmFileSet fileSet = new ScmFileSet(new File("."));
      CommandParameters parameters = new CommandParameters();
      InfoScmResult info = provider.info(providerRepository, fileSet, parameters);
      RemoteInfoScmResult remoteInfo = provider.remoteInfo(providerRepository, fileSet, parameters);
      provider.status(repository, fileSet);

      getLog().info("Provider message: " + info.getProviderMessage());
      for (InfoItem item : info.getInfoItems()) {
        getLog().info("Info item: " + item.getURL());
      }
    } catch (Exception e) {
      getLog().error(e);
    }

  }

}
