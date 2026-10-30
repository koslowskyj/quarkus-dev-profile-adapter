package io.github.koslowskyj.example;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;
import java.util.Map;

/**
 * The profile adopted in {@code quarkus:dev} (via {@code quarkus.dev-profile.class}) and
 * used by {@code @QuarkusTest}. The {@code dataId} is the resource's subdirectory under
 * {@code quarkus.dev-profile.data-dir} when persistence is enabled.
 */
public class ExampleProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        return List
                .of(
                        new TestResourceEntry(
                                ExamplePostgresResource.class,
                                Map.of(ExamplePostgresResource.DATA_ID_ARG, ExamplePostgresResource.DATA_ID),
                                true
                        )
                );
    }
}
