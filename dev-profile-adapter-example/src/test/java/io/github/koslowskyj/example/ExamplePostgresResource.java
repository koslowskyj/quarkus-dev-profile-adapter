package io.github.koslowskyj.example;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.eclipse.microprofile.config.ConfigProvider;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * A Postgres {@link QuarkusTestResourceLifecycleManager} used by both {@code @QuarkusTest}
 * and (via the dev-profile-adapter) {@code quarkus:dev}.
 * <p>
 * Demonstrates the optional persistent-data pattern: when {@code quarkus.dev-profile.data-dir}
 * is set, the database is bind-mounted under {@code <data-dir>/<dataId>} so it survives dev
 * restarts; when unset (the default in tests) it is ephemeral.
 */
public class ExamplePostgresResource implements QuarkusTestResourceLifecycleManager {

    private static final String DATA_DIR_KEY = "quarkus.dev-profile.data-dir";
    private static final String PGDATA = "/var/lib/postgresql/data";

    /** TestResourceEntry init-arg key carrying this resource's persistence subdirectory id. */
    public static final String DATA_ID_ARG = "dataId";

    /** This resource's persistence subdirectory under {@code quarkus.dev-profile.data-dir}. */
    public static final String DATA_ID = "postgres";

    private PostgreSQLContainer container;
    private String dataId;

    @Override
    public void init(Map<String, String> initArgs) {
        dataId = initArgs.get(DATA_ID_ARG);
    }

    @Override
    public Map<String, String> start() {
        container = new PostgreSQLContainer("postgres:17")
                .withDatabaseName("example")
                .withUsername("example")
                .withPassword("example");
        dataMount()
                .ifPresent(dir -> {
                    container.withFileSystemBind(dir.toString(), PGDATA, BindMode.READ_WRITE);
                    // A reused data dir skips initdb (one "ready" log, not two), so the
                    // default wait times out; wait on the listening port instead.
                    container.waitingFor(Wait.forListeningPort());
                });
        container.start();

        return Map
                .of(
                        "quarkus.datasource.db-kind",
                        "postgresql",
                        "quarkus.datasource.jdbc.url",
                        container.getJdbcUrl(),
                        "quarkus.datasource.username",
                        container.getUsername(),
                        "quarkus.datasource.password",
                        container.getPassword()
                );
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }

    private Optional<Path> dataMount() {
        if (dataId == null || dataId.isBlank()) {
            return Optional.empty();
        }
        return ConfigProvider
                .getConfig()
                .getOptionalValue(DATA_DIR_KEY, String.class)
                .filter(dir -> !dir.isBlank())
                .map(dir -> createDirectory(Path.of(dir, dataId).toAbsolutePath().normalize()));
    }

    private static Path createDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create data directory " + path, e);
        }
    }
}
