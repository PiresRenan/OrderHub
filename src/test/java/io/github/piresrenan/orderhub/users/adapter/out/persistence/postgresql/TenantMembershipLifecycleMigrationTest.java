package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TenantMembershipLifecycleMigrationTest {

  private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
      "postgres:18.6-trixie@sha256:"
          + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
      .asCompatibleSubstituteFor("postgres");

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
      .withDatabaseName("orderhub_test")
      .withUsername("orderhub_test")
      .withPassword("synthetic-test-password");

  @Test
  void upgradesAcceptedV35MembershipsToActiveAndConstrainsLifecycleVocabulary() {
    // Why: V36 must upgrade a database that already holds accepted V35 rows,
    // which carry no lifecycle column at all, without losing them.
    // Covers: the real V35 -> V36 transition for a pre-existing membership and
    // the bounded persisted vocabulary afterwards.
    // Prevents: an existing membership becoming unreadable or non-operational
    // after upgrade, and an unadmitted status literal entering durable state.

    var dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("35")
        .load()
        .migrate();

    var jdbc = new JdbcTemplate(dataSource);
    var userId = UUID.randomUUID();
    var tenantId = UUID.randomUUID();

    jdbc.update(
        "INSERT INTO users.users (id) VALUES (?)",
        userId);

    jdbc.update(
        """
        INSERT INTO users.tenant_memberships (
            user_id,
            tenant_id
        )
        VALUES (?, ?)
        """,
        userId,
        tenantId);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    var migratedStatus = jdbc.queryForObject(
        """
        SELECT status
        FROM users.tenant_memberships
        WHERE user_id = ?
          AND tenant_id = ?
        """,
        String.class,
        userId,
        tenantId);

    assertThat(migratedStatus)
        .isEqualTo("ACTIVE");

    assertThatThrownBy(() ->
        jdbc.update(
            """
            UPDATE users.tenant_memberships
            SET status = 'UNKNOWN'
            WHERE user_id = ?
              AND tenant_id = ?
            """,
            userId,
            tenantId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
