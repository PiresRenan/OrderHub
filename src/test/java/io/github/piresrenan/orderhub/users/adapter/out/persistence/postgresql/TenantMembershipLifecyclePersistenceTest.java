package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

@Testcontainers
class TenantMembershipLifecyclePersistenceTest {

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
  void persistsAndStrictlyRehydratesMembershipLifecycle() {
    // Why: lifecycle only protects Tenant trust if the value written to
    // PostgreSQL is the value read back, with no lenient mapping in between.
    // Covers: repository write of an explicitly ACTIVE membership and strict
    // rehydration of each admitted non-operational state from a real database.
    // Prevents: a suspended or terminated row round-tripping as operational
    // because the adapter defaulted or coerced an unexpected status.

    var dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(),
        POSTGRES.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();

    var jdbc = new JdbcTemplate(dataSource);
    var repository = new PostgreSqlTenantMembershipRepository(jdbc);
    var userId = UUID.randomUUID();
    var tenantId = UUID.randomUUID();

    jdbc.update(
        "INSERT INTO users.users (id) VALUES (?)",
        userId);

    repository.save(
        TenantMembership.create(
            userId,
            tenantId));

    var created = repository.find(
        userId,
        tenantId).orElseThrow();

    assertThat(created.status())
        .isEqualTo(TenantMembershipStatus.ACTIVE);

    jdbc.update(
        """
        UPDATE users.tenant_memberships
        SET status = 'SUSPENDED'
        WHERE user_id = ?
          AND tenant_id = ?
        """,
        userId,
        tenantId);

    var suspended = repository.find(
        userId,
        tenantId).orElseThrow();

    assertThat(suspended.status())
        .isEqualTo(TenantMembershipStatus.SUSPENDED);

    assertThat(suspended.isOperationallyActive())
        .isFalse();

    jdbc.update(
        """
        UPDATE users.tenant_memberships
        SET status = 'TERMINATED'
        WHERE user_id = ?
          AND tenant_id = ?
        """,
        userId,
        tenantId);

    var terminated = repository.find(
        userId,
        tenantId).orElseThrow();

    assertThat(terminated.status())
        .isEqualTo(TenantMembershipStatus.TERMINATED);

    assertThat(terminated.isOperationallyActive())
        .isFalse();
  }
}
