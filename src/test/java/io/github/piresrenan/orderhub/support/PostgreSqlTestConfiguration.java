package io.github.piresrenan.orderhub.support;

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
}