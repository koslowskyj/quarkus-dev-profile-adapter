package io.github.koslowskyj.devprofile;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.resolver.maven.MavenArtifactResolver;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DevProfileAdapterProcessor {

    private static final Logger log = LoggerFactory.getLogger(DevProfileAdapterProcessor.class);
    private static final String FEATURE = "dev-profile-adapter";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    void adoptProfile(
            CurateOutcomeBuildItem curate,
            LiveReloadBuildItem liveReload,
            DevProfileAdapterConfig adapterConfig,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> config) {
        final String profileClass = adapterConfig.profileClass().orElse(null);
        if (profileClass == null) {
            return;
        }
        disableCompetingDevServices();

        AdoptedProfile adopted = liveReload.getContextObject(AdoptedProfile.class);
        if (adopted == null) {
            adopted = start(curate.getApplicationModel(), profileClass, adapterConfig.dependencies().orElse(List.of()));
            liveReload.setContextObject(AdoptedProfile.class, adopted);
            Runtime.getRuntime().addShutdownHook(new Thread(adopted::stop, "dev-profile-adapter-stop"));
            log
                    .info(
                            "dev-profile-adapter: adopted {} ({} resources, {} config keys)",
                            profileClass,
                            adopted.managers.size(),
                            adopted.config.size()
                    );
        } else {
            log.info("dev-profile-adapter: reusing adopted {} across live reload", profileClass);
        }

        injectConfig(adopted.config, config);
    }

    private static AdoptedProfile start(ApplicationModel model, String profileClass, List<String> dependencies) {
        final List<URL> classpath = new ArrayList<>();
        addTestClasses(model, classpath);
        addModelDependencies(model, classpath);
        addConfiguredDependencies(dependencies, classpath);

        final URLClassLoader loader = new URLClassLoader(
                classpath.toArray(new URL[0]), Thread.currentThread().getContextClassLoader()
        );
        final List<QuarkusTestResourceLifecycleManager> managers = new ArrayList<>();
        final Map<String, String> collected = new LinkedHashMap<>();
        try {
            final QuarkusTestProfile profile = (QuarkusTestProfile) loader
                    .loadClass(profileClass)
                    .getDeclaredConstructor()
                    .newInstance();
            for (TestResourceEntry entry : profile.testResources()) {
                final QuarkusTestResourceLifecycleManager manager = entry
                        .getClazz()
                        .getDeclaredConstructor()
                        .newInstance();
                manager.init(entry.getArgs());
                collected.putAll(manager.start());
                managers.add(manager);
                log.info("dev-profile-adapter: started {}", entry.getClazz().getName());
            }
            collected.putAll(profile.getConfigOverrides());
        } catch (Throwable t) {
            log.error("dev-profile-adapter: failed to adopt {}", profileClass, t);
        }
        return new AdoptedProfile(managers, collected, loader);
    }

    private static void disableCompetingDevServices() {
        // The adopted profile's resources provide the backing services, so Quarkus' own
        // built-in dev services must not also start. We disable the datasource dev
        // service specifically (the built-in that competes with a DB resource) rather
        // than the global quarkus.devservices.enabled flag, which would also stop
        // Quinoa's frontend live-coding.
        System.setProperty("quarkus.datasource.devservices.enabled", "false");
    }

    private static void injectConfig(
            Map<String, String> values,
            BuildProducer<RunTimeConfigurationDefaultBuildItem> config) {
        values.forEach((key, value) -> {
            // System properties (ordinal 400) override application.yaml's %dev block (250);
            // the build-time default keeps the step from being pruned and covers non-conflicting keys.
            System.setProperty(key, value);
            config.produce(new RunTimeConfigurationDefaultBuildItem(key, value));
        });
    }

    private static void addTestClasses(ApplicationModel model, List<URL> classpath) {
        final WorkspaceModule appModule = model.getApplicationModule();
        if (appModule == null || !appModule.hasTestSources()) {
            log.warn("dev-profile-adapter: application module has no test sources: {}", appModule);
            return;
        }
        for (SourceDir dir : appModule.getTestSources().getSourceDirs()) {
            addUrl(classpath, dir.getOutputDir());
        }
    }

    private static void addModelDependencies(ApplicationModel model, List<URL> classpath) {
        for (ResolvedDependency dep : model.getDependencies()) {
            for (Path p : dep.getResolvedPaths()) {
                addUrl(classpath, p);
            }
        }
    }

    private static void addConfiguredDependencies(List<String> coords, List<URL> classpath) {
        if (coords.isEmpty()) {
            return;
        }
        try {
            final MavenArtifactResolver resolver = MavenArtifactResolver.builder().build();
            for (String coord : coords) {
                resolveWithTransitives(resolver, coord, classpath);
            }
        } catch (Exception e) {
            log.error("dev-profile-adapter: failed to build the Maven resolver for {}", coords, e);
        }
    }

    private static void resolveWithTransitives(MavenArtifactResolver resolver, String coord, List<URL> classpath) {
        try {
            final Artifact artifact = new DefaultArtifact(coord);
            final List<ArtifactResult> results = resolver.resolveDependencies(artifact, List.of()).getArtifactResults();
            for (ArtifactResult result : results) {
                if (result.getArtifact() != null && result.getArtifact().getFile() != null) {
                    addUrl(classpath, result.getArtifact().getFile().toPath());
                }
            }
        } catch (Exception e) {
            log.error("dev-profile-adapter: failed to resolve configured dependency {}", coord, e);
        }
    }

    private static void addUrl(List<URL> classpath, Path path) {
        if (path == null) {
            return;
        }
        try {
            classpath.add(path.toUri().toURL());
        } catch (Exception e) {
            log.warn("dev-profile-adapter: cannot convert path to URL: {}", path, e);
        }
    }

    private static final class AdoptedProfile {

        private final List<QuarkusTestResourceLifecycleManager> managers;
        private final Map<String, String> config;
        private final URLClassLoader loader;

        private AdoptedProfile(
                List<QuarkusTestResourceLifecycleManager> managers,
                Map<String, String> config,
                URLClassLoader loader) {
            this.managers = managers;
            this.config = config;
            this.loader = loader;
        }

        private void stop() {
            for (QuarkusTestResourceLifecycleManager manager : managers) {
                try {
                    manager.stop();
                } catch (Exception e) {
                    log.warn("dev-profile-adapter: error stopping {}", manager.getClass().getName(), e);
                }
            }
            try {
                loader.close();
            } catch (Exception e) {
                log.warn("dev-profile-adapter: error closing classloader", e);
            }
        }
    }
}
