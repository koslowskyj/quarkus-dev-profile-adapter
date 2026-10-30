package io.github.koslowskyj.devprofile;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code QuarkusExtensionTest} bootstraps with its own Maven context in the forked test JVM. That context
 * learns Maven's options from {@code MAVEN_CMD_LINE_ARGS}, which holds the command line but not
 * {@code .mvn/maven.config}, so left alone it reads {@code ~/.m2/settings.xml} and {@code ~/.m2/repository}
 * instead of the settings and local repository Maven itself runs with (a {@code -s} or {@code -Dmaven.repo.local}
 * from {@code .mvn/maven.config}, for instance). The surefire configuration of this module hands both over.
 */
class BootstrapMavenSettingsTest {

    private final BootstrapMavenContext bootstrap = bootstrapContext();

    @Test
    void the_bootstrap_resolver_uses_the_local_repository_maven_runs_with() throws BootstrapMavenException {
        assertEquals(System.getProperty("localRepository"), bootstrap.getLocalRepo());
    }

    private static BootstrapMavenContext bootstrapContext() {
        try {
            return new BootstrapMavenContext(BootstrapMavenContext.config().setWorkspaceDiscovery(false));
        } catch (BootstrapMavenException e) {
            throw new IllegalStateException(e);
        }
    }
}
