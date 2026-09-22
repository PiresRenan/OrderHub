package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.OrderHubApplication;

/**
 * Why: the seed must never become production behavior.
 * Covers: main build output, production sources/resources, migrations and the ordinary application under
 * every deployment-like profile, including a bare "dev" profile.
 * Prevents: a profile, scan or resource making fixture data reachable outside the explicit launcher.
 */
class DevelopmentSeedIsolationTest {
    private static final List<String> FIXTURE_MARKERS = List.of("synthetic-local-", DevelopmentIssuer.AUDIENCE,
            "DevelopmentSeedCatalog", "DevelopmentIssuer", "DevelopmentConfiguration", "LocalDevelopmentApplication",
            "orderhub.development", "orderhub/development");
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");
    private static PostgreSQLContainer database;

    @BeforeAll
    static void startDatabase() {
        database = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-trixie@sha256:"
                + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280").asCompatibleSubstituteFor("postgres"));
        database.withDatabaseName("orderhub_isolation").withUsername("orderhub_isolation")
                .withPassword("synthetic-isolation-password");
        database.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) database.stop();
    }

    @Test
    void mainBuildOutputContainsNoDevelopmentFixture() throws Exception {
        var mainOutput = Path.of(OrderHubApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(mainOutput.endsWith(Path.of("target", "classes"))).as("main output location %s", mainOutput).isTrue();
        try (var files = Files.walk(mainOutput)) {
            var fixture = files.map(path -> mainOutput.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> path.contains("orderhub/development/") || path.startsWith("development/")
                            || path.matches(".*/(DevelopmentIssuer|DevelopmentConfiguration|DevelopmentSeed[A-Za-z]*|LocalDevelopmentApplication)[^/]*"))
                    .toList();
            assertThat(fixture).isEmpty();
        }
    }

    @Test
    void productionSourcesResourcesAndMigrationsNeverReferenceTheFixture() throws Exception {
        for (var root : List.of(MAIN_JAVA, MAIN_RESOURCES)) {
            try (var files = Files.walk(root)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var text = read(file);
                    for (var marker : FIXTURE_MARKERS) {
                        assertThat(text).as("%s references %s", file, marker).doesNotContain(marker);
                    }
                }
            }
        }
        try (var files = Files.walk(MAIN_RESOURCES.resolve("db"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var text = read(file).toLowerCase(java.util.Locale.ROOT);
                // Migrations may register vocabulary; they must never create business fixture rows.
                for (var table : List.of("tenants.tenants", "organizations.organizations", "users.users",
                        "customers.customer_profiles", "catalog.products", "catalog.product_variants",
                        "catalog.categories", "inventory.movements", "orders.orders")) {
                    assertThat(text).as("%s seeds %s", file, table).doesNotContain("insert into " + table + " ");
                    assertThat(text).as("%s seeds %s", file, table).doesNotContain("insert into " + table + "(");
                }
            }
        }
        try (var names = Files.list(MAIN_RESOURCES)) {
            assertThat(names.map(path -> path.getFileName().toString()).toList())
                    .doesNotContain("data.sql", "import.sql", "schema.sql");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "production", "staging", "pre-release"})
    void ordinaryApplicationUnderAnyProfileCreatesNoFixture(String profile) throws Exception {
        var application = new SpringApplication(OrderHubApplication.class);
        application.setAdditionalProfiles(profile);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("isolation", Map.of(
                        "server.port", 0, "server.address", "127.0.0.1",
                        "spring.datasource.url", database.getJdbcUrl(),
                        "spring.datasource.username", database.getUsername(),
                        "spring.datasource.password", database.getPassword(),
                        "orderhub.security.jwt.issuer", "https://issuer.isolation.test",
                        "orderhub.security.jwt.audience", "orderhub-isolation",
                        "orderhub.security.jwt.token-profile", "GENERIC",
                        "orderhub.security.jwt.jwk-set-uri", "http://127.0.0.1:1/isolation-jwks"))));
        try (var context = application.run()) {
            assertThat(context.getBeanNamesForType(DevelopmentIssuer.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DevelopmentConfiguration.class)).isEmpty();
            assertThat(context.containsBean("developmentSeed")).isFalse();
            assertThat(context.containsBean("developmentDataSource")).isFalse();
            var jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            for (var table : List.of("tenants.tenants", "organizations.organizations", "users.users",
                    "customers.customer_profiles", "catalog.products", "orders.orders")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
            }
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
