package io.github.koslowskyj.devprofile;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;

/**
 * The adapter's own keys. Declaring them is what stops Quarkus warning that it ignores them.
 */
@ConfigMapping(prefix = "quarkus.dev-profile")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface DevProfileAdapterConfig {

    /** FQCN of the {@code QuarkusTestProfile} to adopt; unset keeps the adapter inert. */
    @WithName("class")
    Optional<String> profileClass();

    /** Maven GAVs the profile's managers need at dev time but that are not on the dev classpath. */
    Optional<List<String>> dependencies();

    /** Opt-in persistent storage root; read by the resources themselves, not by the build step. */
    Optional<String> dataDir();
}
