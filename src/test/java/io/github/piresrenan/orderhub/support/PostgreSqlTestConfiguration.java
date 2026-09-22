package io.github.piresrenan.orderhub.support;

import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Supplies a real disposable PostgreSQL instance to Spring integration tests.
 *
 * <p>The configuration is intentionally test-only. Production connection
 * details remain externally configured and are never embedded in application
 * code.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgreSqlTestConfiguration {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres");

    /**
     * Provides PostgreSQL connection details to DataSource and Flyway
     * auto-configuration for full Spring application-context tests.
     *
     * @return isolated PostgreSQL container containing only synthetic test data
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("orderhub_test")
                .withUsername("orderhub_test")
                .withPassword("synthetic-test-password");
    }

    /**
     * Supplies synthetic Resource Server properties only to integration tests
     * that did not explicitly define their own JWT configuration.
     *
     * <p>This fixture remains test-only and does not provide production
     * fallbacks.
     *
     * @param environment current integration-test environment
     * @return registrar for missing synthetic JWT properties
     */
    @Bean
    DynamicPropertyRegistrar securityJwtTestProperties(
            Environment environment) {

        return registry -> {
            if (!environment.containsProperty(
                    "orderhub.security.jwt.issuer")) {
                registry.add(
                        "orderhub.security.jwt.issuer",
                        () -> "https://issuer.orderhub.test");
            }

            if (!environment.containsProperty(
                    "orderhub.security.jwt.audience")) {
                registry.add(
                        "orderhub.security.jwt.audience",
                        () -> "orderhub-api-test");
            }

            if (!environment.containsProperty(
                    "orderhub.security.jwt.jwk-set-uri")) {
                registry.add(
                        "orderhub.security.jwt.jwk-set-uri",
                        () -> "http://127.0.0.1:1/test-only-jwks");
            }
        };
    }
}