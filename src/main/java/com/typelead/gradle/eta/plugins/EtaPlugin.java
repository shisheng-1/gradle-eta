package com.typelead.gradle.eta.plugins;

import java.io.File;
import java.util.Map;
import java.util.HashMap;

import javax.inject.Inject;

import org.gradle.api.Describable;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTreeElement;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.internal.file.SourceDirectorySetFactory;
import org.gradle.api.internal.tasks.DefaultSourceSetOutput;

import com.typelead.gradle.utils.ExtensionHelper;
import com.typelead.gradle.eta.api.EtaConfiguration;
import com.typelead.gradle.eta.tasks.EtaResolveDependencies;
import com.typelead.gradle.eta.tasks.EtaInstallDependencies;
import com.typelead.gradle.eta.tasks.EtaFetchDependencies;
import com.typelead.gradle.eta.tasks.EtaCompile;
import com.typelead.gradle.eta.internal.ConfigurationUtils;
import com.typelead.gradle.eta.internal.DefaultEtaSourceSet;

/**
 * A {@link Plugin} which sets up an Eta project.
 */
@SuppressWarnings("WeakerAccess")
public class EtaPlugin extends EtaBasePlugin implements Plugin<Project> {

    private Project project;
    private SourceDirectorySetFactory sourceDirectorySetFactory;

    @Inject
    public EtaPlugin(SourceDirectorySetFactory sourceDirectorySetFactory) {
        this.sourceDirectorySetFactory = sourceDirectorySetFactory;
    }


    @Override
    public void apply(Project project) {
        this.project = project;

        project.getPlugins().apply(EtaBasePlugin.class);
        project.getPlugins().apply(JavaPlugin.class);

        configureSourceSetDefaults();
    }

    private void configureSourceSetDefaults() {
        project.getConvention().getPlugin(JavaPluginConvention.class)
               .getSourceSets().all(this::configureSourceSet);
    }

    private void configureSourceSet(SourceSet sourceSet) {

        final DefaultEtaSourceSet etaSourceSet =
            ExtensionHelper.createExtension
              (sourceSet, "eta", DefaultEtaSourceSet.class, sourceSet,
               sourceDirectorySetFactory);

        final SourceDirectorySet etaSourceDirectorySet = etaSourceSet.getEta();

        etaSourceDirectorySet
            .srcDir("src/" + sourceSet.getName() + "/eta");

        /* Ensure that you exclude any Eta source files from the
           resources set. */

        sourceSet.getResources().getFilter()
            .exclude(element -> etaSourceSet.getEta().contains(element.getFile()));

        sourceSet.getAllSource().source(etaSourceDirectorySet);

        final EtaResolveDependencies resolveDependenciesTask
            = (EtaResolveDependencies) project.getTasks()
            .getByPath(EtaBasePlugin.ETA_RESOLVE_DEPENDENCIES_TASK_NAME);

        final FileCollection freezeConfigFile =
            resolveDependenciesTask.getOutputs().getFiles();

        final Provider<String> targetConfiguration
            = project.provider(() -> sourceSet.getCompileClasspathConfigurationName());

        final Provider<Directory> destinationDir
            = project.getLayout().getBuildDirectory()
            .dir(etaSourceSet.getRelativeOutputDir());

        /* Create the install dependencies task. */

        EtaInstallDependencies installDependenciesTask =
            project.getTasks().create(etaSourceSet.getInstallDependenciesTaskName(),
                                      EtaInstallDependencies.class);

        installDependenciesTask.setTargetConfiguration(targetConfiguration);
        installDependenciesTask.setFreezeConfigFile(freezeConfigFile);
        installDependenciesTask.setDestinationDir(destinationDir);
        installDependenciesTask.setSourceDirs
            (etaSourceDirectorySet.getSourceDirectories());
        installDependenciesTask.setSource(etaSourceDirectorySet);
        installDependenciesTask.dependsOn(resolveDependenciesTask);
        installDependenciesTask.setDescription("Installs dependencies for the " + sourceSet.getName() + " Eta source.");

        /* Create the fetch dependencies task. */

        EtaFetchDependencies fetchDependenciesTask =
            project.getTasks().create(etaSourceSet.getFetchDependenciesTaskName(),
                                      EtaFetchDependencies.class);

        fetchDependenciesTask.setTargetConfiguration(targetConfiguration);
        fetchDependenciesTask.setDestinationDir(destinationDir);
        fetchDependenciesTask.dependsOn(installDependenciesTask);
        fetchDependenciesTask.setDescription("Fetches dependencies for the " + sourceSet.getName() + " Eta source.");

        EtaCompile compileTask =
            project.getTasks().create(etaSourceSet.getCompileTaskName(),
                                      EtaCompile.class);

        AbstractCompile javaCompileTask = (AbstractCompile)
            project.getTasks().getByName(sourceSet.getCompileJavaTaskName());

        Provider<Directory> classesDir = project.provider
            (() -> {
                final DirectoryProperty buildDir =
                  project.getLayout().getBuildDirectory();
                if (sourceSet.getOutput().isLegacyLayout()) {
                    return buildDir.dir(buildDir.getAsFile().get().toPath()
                                        .relativize(sourceSet.getOutput()
                                                    .getClassesDir().toPath())
                                        .toString()).get();
                }
                return buildDir.dir(etaSourceSet.getClassesDir()).get();
            });

        compileTask.setTargetConfiguration(targetConfiguration);
        compileTask.setDestinationDir(destinationDir);
        compileTask.addExtraClasspath(javaCompileTask.getDestinationDir());
        compileTask.setClassesDir(classesDir);
        compileTask.dependsOn(javaCompileTask);
        compileTask.setDescription("Compiles the " + sourceSet.getName() + " Eta source.");

        /* The fetch dependencies tasks injects into this configuration so we must
           ensure that it runs before the Java compilation. */

        javaCompileTask.dependsOn(fetchDependenciesTask);

        Map<String, Object> builtByOptions = new HashMap<String, Object>();
        builtByOptions.put("builtBy", compileTask);

        /* Register the Eta classes directory as an output so that the Jar task
           will pick it up nicely. */

        etaSourceDirectorySet.setOutputDir
            (project.provider(() -> classesDir.get().getAsFile()));

        /* TODO: Are both classesDir and the output registration below required? */
        ((DefaultSourceSetOutput) sourceSet.getOutput()).addClassesDir
            (() -> etaSourceDirectorySet.getOutputDir());

        sourceSet.getOutput().dir(builtByOptions, classesDir);

        /* Register the package databases as artifacts that will be collected
           upon dependency resolution of project dependencies. */

        addArtifacts(compileTask.getPackageDB(),
                     JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME,
                     JavaPlugin.RUNTIME_CONFIGURATION_NAME,
                     JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME);
    }

    public void addArtifacts(Provider<File> artifact, String... configurationNames) {
        for (String configurationName : configurationNames) {
            ConfigurationUtils.getEtaConfiguration(project, configurationName)
                .getArtifacts().add(artifact);
        }
    }
}
