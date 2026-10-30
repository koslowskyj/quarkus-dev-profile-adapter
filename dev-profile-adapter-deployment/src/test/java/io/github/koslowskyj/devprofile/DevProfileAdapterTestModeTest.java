package io.github.koslowskyj.devprofile;

import io.quarkus.test.QuarkusExtensionTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter's build step is gated by {@code IsDevelopment}, so it must stay dormant
 * in test mode even when a profile is configured. This pins that @QuarkusTest suites are
 * unaffected: no resource config is injected and the build does not fail. The extension
 * also declares its three keys, so Quarkus must not warn that it ignores them.
 */
class DevProfileAdapterTestModeTest {

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("quarkus.dev-profile.class", "io.github.koslowskyj.AllProfile")
            .overrideConfigKey("quarkus.dev-profile.dependencies", "org.wiremock:wiremock:3.13.1")
            .overrideConfigKey("quarkus.dev-profile.data-dir", ".local/dev")
            .setLogRecordPredicate(record -> true)
            .assertLogRecords(
                    records -> assertTrue(
                            records.stream().noneMatch(r -> ((ExtLogRecord) r).getFormattedMessage().contains("Unrecognized configuration key \"quarkus.dev-profile")),
                            "the extension must declare its quarkus.dev-profile.* keys"
                    )
            );

    @Test
    void adapter_is_dormant_in_test_mode() {
        assertFalse(
                ConfigProvider.getConfig().getOptionalValue("mockserver.url", String.class).isPresent(),
                "adapter must not inject resource config in test mode"
        );
    }
}
