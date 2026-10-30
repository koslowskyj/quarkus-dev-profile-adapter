# Dev Profile Adapter for Quarkus

A Quarkus extension that lets **`quarkus:dev` adopt a `QuarkusTestProfile`**. Dev mode then starts
the *same* test resources (databases, mock servers, brokers, …) and gets the *same* configuration
as your `@QuarkusTest` suite. There's no second copy of that wiring in a `%dev` block.

```
                    ┌──────────────────────────── one QuarkusTestProfile ────────────────────────────┐
                    │  testResources():  PostgresResource, WireMockResource, …                       │
                    │  getConfigOverrides(): app.admin.password=…, …                                 │
                    └───────────────┬──────────────────────────────────────────────┬─────────────────┘
                                    │                                              │
                     @QuarkusTest (JUnit, unchanged)                  quarkus:dev (this extension)
```

- **Coordinates:** `io.github.koslowskyj:dev-profile-adapter`
- **License:** MIT
- **Status:** experimental

---

## Contents

- [The problem](#the-problem)
- [What the adapter does](#what-the-adapter-does)
- [Versioning and compatibility](#versioning-and-compatibility)
- [Quick start](#quick-start)
- [Configuration reference](#configuration-reference)
- [Writing resources that work in both test and dev](#writing-resources-that-work-in-both-test-and-dev)
- [Persistent dev data](#persistent-dev-data)
- [Resources that need test-only libraries](#resources-that-need-test-only-libraries)
- [Interaction with Quarkus Dev Services](#interaction-with-quarkus-dev-services)
- [Lifecycle: live reload and shutdown](#lifecycle-live-reload-and-shutdown)
- [Caveats and troubleshooting](#caveats-and-troubleshooting)
- [The example module](#the-example-module)
- [Building from source](#building-from-source)
- [Releasing](#releasing)

---

## The problem

Quarkus gives you two separate ways to supply backing services:

| | Tests (`@QuarkusTest`) | Dev mode (`quarkus:dev`) |
|---|---|---|
| Mechanism | `QuarkusTestProfile` + `QuarkusTestResourceLifecycleManager` | Built-in Dev Services, or a hand-written `%dev` config |
| What you control | Everything: image, version, init scripts, seed data, mock stubs, config keys | Only what Dev Services exposes, or whatever the `%dev` block points at |
| Reach | Test classpath only | Main classpath only |

Projects that outgrow Dev Services end up with **two definitions of the same environment**:

- **Test side:** a carefully built set of test resources, e.g. a Postgres container with extensions and a pinned version, a WireMock server with stubs for every external API, plus the config that points the app at them.
- **Dev side:** a `%dev` profile, a `docker-compose.yml` and a README section telling people what to start first.

The two drift. A stub gets added for tests but not for dev, a config key gets renamed in one place, or the dev database runs a different major version. "Works in the tests, breaks in dev" (and the reverse) becomes routine.

Quarkus has no built-in way for dev mode to reuse a test profile. Test resources live in
`src/test/java`, and the machinery that starts them belongs to the JUnit integration.

## What the adapter does

Point it at one of your existing `QuarkusTestProfile` classes. In dev mode, a build step gated by
`IsDevelopment` then:

1. **Loads the profile** named by `quarkus.dev-profile.class` from the application module's compiled test
   classes (`target/test-classes`). It uses a class loader that also sees the app's dependencies.
2. **Resolves extra libraries** listed in `quarkus.dev-profile.dependencies`, with their transitive
   dependencies. These are test-scope-only libraries a resource needs that aren't on the dev classpath.
3. **Starts every resource the profile lists:**
   - Calls `init(args)` and then `start()` on each `QuarkusTestResourceLifecycleManager`.
   - Collects the `Map<String, String>` that `start()` returns.
   - Adds the profile's `getConfigOverrides()` on top.
4. **Injects that configuration** into the dev application as system properties (ordinal 400). These
   win over `application.yaml`, including its `%dev` block (ordinal 250).
5. **Turns off the datasource Dev Service**, so Quarkus doesn't start a second database next to yours.
6. **Keeps the resources running across live reloads** and stops them when dev mode shuts down.

In **test** and **production** the build step never runs, so `@QuarkusTest` behaves exactly as before.
Declare the dependency `provided` and the extension never reaches your production artifact at all.

## Versioning and compatibility

The adapter's version follows the Quarkus version it is built for:

```
<quarkus major>.<quarkus minor>.<adapter fix>
```

| Adapter version | Built against | Works with Quarkus | What changed |
|---|---|---|---|
| `3.40.0` | 3.40.1 | 3.40.x | first release on the 3.40 line |
| `3.40.1`, `3.40.2`, … | 3.40.x | 3.40.x | adapter fixes and features on the same Quarkus line |
| `3.41.0` | 3.41.x | 3.41.x | first release on the 3.41 line (fix counter restarts at 0) |

- **Pick the adapter whose first two numbers match your Quarkus version.** On Quarkus 3.40.3, use the
  newest `3.40.*` adapter.
- **The third number is the adapter's own fix counter.** It isn't the Quarkus patch number. Adapter
  `3.40.2` works with every 3.40.x patch release.
- **Why one line per Quarkus minor:** the adapter uses Quarkus-internal APIs (the bootstrap application
  model, the Maven resolver, live-reload context). Those can change between minor releases.
- **The range is recorded in the extension descriptor** as `requires-quarkus-core: [3.40,3.41)`, so
  Quarkus tooling can flag a mismatch.

**Requirements:** Java 17+, Maven, and Docker if your resources use Testcontainers.

## Quick start

### 1. Add the extension with `provided` scope

```xml
<dependency>
    <groupId>io.github.koslowskyj</groupId>
    <artifactId>dev-profile-adapter</artifactId>
    <version>3.40.0</version> <!-- match your Quarkus major.minor -->
    <scope>provided</scope>
</dependency>
```

`provided` puts the extension on the dev and test classpath and keeps it out of the packaged app.

> Until a release is published to a repository you can reach, build it once with `mvn install`
> (see [Building from source](#building-from-source)) and use `3.40.0-SNAPSHOT`.

### 2. Have a `QuarkusTestProfile`

You most likely already have one. If not, it's a small class in `src/test/java`:

```java
package io.github.koslowskyj.example;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.List;
import java.util.Map;

public class AppProfile implements QuarkusTestProfile {

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(
                new TestResourceEntry(PostgresResource.class),
                new TestResourceEntry(PaymentApiMockResource.class));
    }

    // Optional: config the app needs to boot that no resource provides.
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("app.admin.initial-password", "admin");
    }
}
```

Your tests keep using it as usual:

```java
@QuarkusTest
@TestProfile(AppProfile.class)
class OrderResourceTest { … }
```

### 3. Select the profile in `application.yaml` (or `.properties`)

```yaml
quarkus:
  dev-profile:
    class: io.github.koslowskyj.example.AppProfile
```

```properties
quarkus.dev-profile.class=io.github.koslowskyj.example.AppProfile
```

### 4. Run dev mode

```bash
mvn quarkus:dev
```

The log shows what was adopted:

```
dev-profile-adapter: started io.github.koslowskyj.example.PostgresResource
dev-profile-adapter: started io.github.koslowskyj.example.PaymentApiMockResource
dev-profile-adapter: adopted io.github.koslowskyj.example.AppProfile (2 resources, 7 config keys)
```

The app now runs against exactly the services your tests use.

## Configuration reference

All keys are build-time keys under `quarkus.dev-profile`. If you leave them all unset, the extension does nothing.

| Key | Type | Default | Purpose |
|---|---|---|---|
| `quarkus.dev-profile.class` | string | none | Fully-qualified name of the `QuarkusTestProfile` to adopt. It's loaded from the app module's `target/test-classes`. **Unset means the adapter is inactive.** |
| `quarkus.dev-profile.dependencies` | list of Maven coordinates | none | Libraries the profile's resources need in dev that aren't on the dev classpath. Resolved with their transitive dependencies. Format `groupId:artifactId[:extension[:classifier]]:version`, comma-separated. See [below](#resources-that-need-test-only-libraries). |
| `quarkus.dev-profile.data-dir` | string | none | Base directory for [persistent dev data](#persistent-dev-data). **The adapter itself never reads it.** It exists so your resources can use one shared, declared key, and Quarkus won't warn about an unknown key. |

Environment variable forms: `QUARKUS_DEV_PROFILE_CLASS`, `QUARKUS_DEV_PROFILE_DEPENDENCIES` and
`QUARKUS_DEV_PROFILE_DATA_DIR`.

**Key precedence in dev mode**, from strongest to weakest:

1. Values from the adopted profile: resource `start()` maps, then `getConfigOverrides()`. These are injected as system properties.
2. Any other system properties or environment variables you set yourself.
3. `application.yaml` / `application.properties`, including the `%dev` profile.

So the adopted profile wins over your `%dev` block. Keep in `%dev` only what the profile doesn't provide.

## Writing resources that work in both test and dev

A resource is an ordinary `QuarkusTestResourceLifecycleManager`, and the adapter calls it the way
JUnit does. A few habits make one resource serve both modes well:

- **Return every key the app needs from `start()`.** That map is the single hand-off point in both
  modes. Return URLs, credentials and ports, not just the ones you happen to need in tests.
- **Use the ordinary test-resource API.** The adapter calls `init(Map<String,String>)` with the
  `TestResourceEntry` args, then `start()`, and `stop()` on shutdown. Anything that relies on
  JUnit-only hooks won't run in dev:
  - `inject(TestInjector)`
  - `setContext(Context)`
  - `@QuarkusTestResource` annotations on test classes
- **Put config the app needs to boot into `getConfigOverrides()`.** Examples: an admin password
  read by a startup observer, or a feature flag a Flyway callback checks. This keeps the profile
  self-sufficient, so dev doesn't depend on keys that only exist in `src/test/resources`.
- **Use a no-arg constructor.** Both the profile and the resources are created by reflection through
  their declared no-arg constructor.
- **Load extra classes from the test classpath.** Resources may use any class from your
  `src/test/java`; the adapter adds `target/test-classes` to the class loader.

A minimal Postgres resource (Testcontainers 2.x, the version the Quarkus 3.40 BOM manages):

```java
public class PostgresResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer container;

    @Override
    public Map<String, String> start() {
        container = new PostgreSQLContainer("postgres:17");
        container.start();
        return Map.of(
                "quarkus.datasource.jdbc.url", container.getJdbcUrl(),
                "quarkus.datasource.username", container.getUsername(),
                "quarkus.datasource.password", container.getPassword());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
```

## Persistent dev data

Out of the box the adopted resources are **ephemeral**, exactly as in tests: every `quarkus:dev`
start creates fresh containers. That's often what you want. If you'd rather keep your dev data
between runs, use this convention:

1. Set a base directory **only in `%dev`**, so tests stay ephemeral:

   ```yaml
   "%dev":
     quarkus:
       dev-profile:
         data-dir: .local/dev      # relative to where quarkus:dev runs (the module dir); gitignore it
   ```

2. Give each resource its own sub-directory id through the `TestResourceEntry` args:

   ```java
   new TestResourceEntry(PostgresResource.class, Map.of("dataId", "postgres"), true)
   ```

3. In the resource, bind-mount `<data-dir>/<dataId>` when the key is set:

   ```java
   private String dataId;

   @Override
   public void init(Map<String, String> args) {
       dataId = args.get("dataId");
   }

   @Override
   public Map<String, String> start() {
       container = new PostgreSQLContainer("postgres:17");
       ConfigProvider.getConfig()
               .getOptionalValue("quarkus.dev-profile.data-dir", String.class)
               .filter(dir -> dataId != null && !dir.isBlank())
               .map(dir -> Path.of(dir, dataId).toAbsolutePath().normalize())
               .ifPresent(dir -> {
                   createDirectories(dir);
                   container.withFileSystemBind(dir.toString(), "/var/lib/postgresql/data", BindMode.READ_WRITE);
                   // A reused data dir skips initdb, so Postgres logs "ready" once instead of twice
                   // and the default wait strategy times out. Waiting on the port works for both.
                   container.waitingFor(Wait.forListeningPort());
               });
       container.start();
       …
   }
   ```

The first run initialises the database under `.local/dev/postgres`. Later runs reuse it, so rows you
created survive restarts. Flyway sees an already-migrated schema and only applies new migrations.

**Resetting.** The directory belongs to the user *inside* the container (for Postgres, uid 999), so a
plain `rm -rf` may fail with `Permission denied`. Remove it through a container instead:

```bash
docker run --rm -v "$PWD/.local/dev:/data" alpine rm -rf /data/postgres
```

## Resources that need test-only libraries

The dev classpath already contains:

- everything your application depends on, with any scope Quarkus puts on the dev classpath;
- the Quarkus JUnit harness (`quarkus-junit`), which the adapter brings in for `QuarkusTestProfile`
  and `QuarkusTestResourceLifecycleManager`;
- your compiled test classes.

It **doesn't** contain your other `test`-scope dependencies. If a resource uses one of them directly
(an in-process WireMock server, an embedded broker, …), list it so the adapter can resolve it:

```yaml
quarkus:
  dev-profile:
    class: io.github.koslowskyj.example.AppProfile
    dependencies: org.wiremock:wiremock-standalone:3.13.1
```

- **Separate several coordinates with commas.** Transitive dependencies are resolved for you.
- **Resolution uses your Maven settings and local repository.**
- **You often need nothing here.** A Testcontainers-based database resource usually works as-is,
  because Quarkus datasource extensions already put Testcontainers core on the dev classpath. Add a
  coordinate only after dev mode reports a `ClassNotFoundException` / `NoClassDefFoundError` from a
  resource.

## Interaction with Quarkus Dev Services

The adapter sets `quarkus.datasource.devservices.enabled=false` once a profile is adopted, because a
database resource and the datasource Dev Service would compete for the same `quarkus.datasource.*`
keys. It deliberately **doesn't** set the global `quarkus.devservices.enabled=false`. That switch also
stops unrelated dev-time helpers, such as a frontend dev server some extensions run as a Dev Service.

If your profile also replaces another Dev Service (Kafka, Redis, Keycloak, …), turn that one off
yourself:

```yaml
quarkus:
  kafka:
    devservices:
      enabled: false
```

Dev Services your profile *doesn't* replace keep working side by side with the adopted resources.

## Lifecycle: live reload and shutdown

| Event | What happens |
|---|---|
| First dev-mode build | Resources start, config is injected, and the log says `adopted …` |
| Live reload (you edited a class or config) | **Resources keep running**, the same config is re-injected, and the log says `reusing adopted … across live reload`. No container restart and no lost state. |
| Dev mode stops (`q`, Ctrl+C) | Each resource's `stop()` is called from a shutdown hook, then the class loader is closed |

The adopted profile is fixed when dev mode starts. If you change the *profile or its resources*
(`src/test/java`), or change `quarkus.dev-profile.*`, restart dev mode to pick it up. Live reload
only reloads the application.

If adopting fails (a resource throws, or a class can't be found), the adapter logs
`dev-profile-adapter: failed to adopt …` with the cause and the app boots without the remaining
resources. Look for that line first when something seems unwired.

## Caveats and troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `application module has no test sources` warning; nothing adopted | Dev mode isn't running from the module that holds the profile, or the module has no `src/test/java` | Run `mvn quarkus:dev` in the application module, or use `-pl <module>` from the root |
| `ClassNotFoundException` for your profile | Test classes aren't compiled | `quarkus:dev` compiles them on start; if you launch differently, run `mvn test-compile` first |
| `ClassNotFoundException` / `NoClassDefFoundError` from inside a resource | The resource uses a test-scope library | Add it to `quarkus.dev-profile.dependencies` |
| A second database container appears | Another Dev Service is still on | Disable that Dev Service ([above](#interaction-with-quarkus-dev-services)) |
| A `%dev` value is ignored | The adopted profile sets the same key, and the profile wins | Change it in the profile, or remove it there |
| `Could not find artifact …dev-profile-adapter…` when running dev from a sub-directory | The extension isn't in your local repo yet | `mvn install` the adapter (or depend on a released version) |
| Persistent Postgres times out waiting on a reused data dir | Default log-message wait expects two "ready" lines | `waitingFor(Wait.forListeningPort())` ([above](#persistent-dev-data)) |

More things to know:

- **Dev-time trust boundary:** the adapter runs your test code (profile and resources) inside the
  dev-mode JVM. That's your own code, the same code your test suite already runs, but don't point
  `quarkus.dev-profile.class` at anything you wouldn't run in a test.
- **No effect on production or native builds.** The build step only exists in dev mode, and with
  `provided` scope the extension isn't packaged at all.

## The example module

[`dev-profile-adapter-example`](dev-profile-adapter-example) is a minimal REST + Postgres service
that shows every piece above:

| File | Shows |
|---|---|
| `src/main/java/io/github/koslowskyj/example/WidgetResource.java` | `GET /widgets` and `POST /widgets?name=…` on a plain datasource |
| `src/main/resources/db/migration/V1__init.sql` | Flyway schema with one seed row |
| `src/test/java/io/github/koslowskyj/example/ExampleProfile.java` | The profile shared by tests and dev |
| `src/test/java/io/github/koslowskyj/example/ExamplePostgresResource.java` | A Testcontainers Postgres resource with the optional persistent data directory |
| `src/test/java/io/github/koslowskyj/example/WidgetResourceTest.java` | A `@QuarkusTest` using the same profile |
| `src/main/resources/application.yaml` | `quarkus.dev-profile.class`, plus `data-dir` set only in `%dev` |

Try it (Docker required):

```bash
mvn install -DskipTests                 # once, from the repository root: puts the extension in ~/.m2
cd dev-profile-adapter-example
mvn quarkus:dev

curl localhost:8080/widgets                          # [{"id":1,"name":"first widget"}]
curl -X POST 'localhost:8080/widgets?name=mine'      # {"id":2,"name":"mine"}
# stop dev mode, start it again: "mine" is still there (persisted under .local/dev/postgres)
```

The example is a demo. It's never installed or deployed as an artifact.

## Building from source

```bash
mvn verify          # builds runtime + deployment, runs all tests (the example's tests need Docker)
mvn install         # additionally installs the extension into ~/.m2 for use in other projects
mvn verify -pl '!dev-profile-adapter-example'   # skip the Docker-based example
```

| Module | Contents |
|---|---|
| `dev-profile-adapter` | Runtime artifact, the one you depend on. It's empty apart from the extension descriptor. |
| `dev-profile-adapter-deployment` | The build step (`DevProfileAdapterProcessor`) and the config mapping (`DevProfileAdapterConfig`) |
| `dev-profile-adapter-example` | The demo service described above |

## Releasing

Each release keeps the version tied to its Quarkus line ([Versioning](#versioning-and-compatibility)).

**Fix or feature on the current Quarkus line**, e.g. `3.40.0` → `3.40.1`:

```bash
mvn versions:set -DnewVersion=3.40.1 -DgenerateBackupPoms=false
mvn verify
git commit -am "release: 3.40.1" && git tag v3.40.1
# deploy, then move to the next snapshot
mvn versions:set -DnewVersion=3.40.2-SNAPSHOT -DgenerateBackupPoms=false
```

**Moving to a new Quarkus minor**, e.g. 3.40 → 3.41. Change these three values together:

| Where | From | To |
|---|---|---|
| root `pom.xml`, `<quarkus.version>` | `3.40.x` | `3.41.x` |
| project version (`mvn versions:set`) | `3.40.n-SNAPSHOT` | `3.41.0-SNAPSHOT` |
| `dev-profile-adapter/pom.xml`, `<requiresQuarkusCore>` | `[3.40,3.41)` | `[3.41,3.42)` |

Also check `<testcontainers.version>` in the example POM against the Testcontainers version the new
Quarkus BOM manages. Then run `mvn verify` and fix whatever the Quarkus-internal API changes broke.

**A new Quarkus patch release** (3.40.1 → 3.40.2) only needs a `<quarkus.version>` bump. It doesn't
force a new adapter version.

## License

[MIT](LICENSE)
