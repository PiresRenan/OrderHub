package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.application.port.in.administration.MutateAdministrativeGrantUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.organizations.adapter.out.transaction.spring.SpringOrganizationTransactionExecutor;
import io.github.piresrenan.orderhub.organizations.application.service.PlatformOrganizationService;
import io.github.piresrenan.orderhub.organizations.application.service.OrganizationAdministrationService;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantLifecycleRepository;
import io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql.PostgreSqlTenantRepository;
import io.github.piresrenan.orderhub.tenants.adapter.out.transaction.spring.SpringTenantTransactionExecutor;
import io.github.piresrenan.orderhub.tenants.application.service.TenantAdministrationService;
import io.github.piresrenan.orderhub.tenants.application.service.FindTenantAdministrativeMetadataService;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

@Testcontainers
class AdministrativeControlAuditTransactionIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("orderhub_test")
            .withUsername("orderhub_test")
            .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @Test
    void organizationAuditFailureRollsBackAuthoritativeCreate() {
        var duplicateAuditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organizations.administrative_audit_events (
                    audit_event_id, actor_user_id, organization_id, action_type,
                    outcome, after_organization_status, correlation_id)
                VALUES (?, ?, ?, 'CREATE_ORGANIZATION', 'APPLIED', 'ACTIVE', ?)
                """, duplicateAuditId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var organizationId = UUID.randomUUID();
        var service = new PlatformOrganizationService(
                ignored -> AuthorizationDecision.ALLOW,
                new PostgreSqlOrganizationRepository(jdbc),
                new PostgreSqlOrganizationLifecycleRepository(jdbc, transactionManager),
                new SpringOrganizationTransactionExecutor(transactionManager),
                new PostgreSqlOrganizationAdministrativeAuditRepository(jdbc),
                () -> organizationId, () -> duplicateAuditId);

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), "Group", UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM organizations.organizations WHERE id = ?",
                Integer.class, organizationId)).isZero();
    }

    @Test
    void tenantAuditFailureRollsBackAuthoritativeCreate() {
        var duplicateAuditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants.administrative_audit_events (
                    audit_event_id, actor_user_id, tenant_id, action_type,
                    outcome, after_status, correlation_id)
                VALUES (?, ?, ?, 'CREATE_TENANT', 'APPLIED', 'ACTIVE', ?)
                """, duplicateAuditId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var tenantId = UUID.randomUUID();
        var service = new TenantAdministrationService(
                ignored -> AuthorizationDecision.ALLOW,
                new PostgreSqlTenantRepository(jdbc),
                new PostgreSqlTenantLifecycleRepository(jdbc, transactionManager),
                new SpringTenantTransactionExecutor(transactionManager),
                new PostgreSqlTenantAdministrativeAuditRepository(jdbc),
                () -> tenantId, () -> duplicateAuditId);

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), "Store", UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tenants.tenants WHERE id = ?",
                Integer.class, tenantId)).isZero();
    }

    @Test
    void organizationAuditFailureRollsBackPlacement() {
        var duplicateAuditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO organizations.administrative_audit_events (
                    audit_event_id, actor_user_id, organization_id, action_type,
                    outcome, after_organization_status, correlation_id)
                VALUES (?, ?, ?, 'CREATE_ORGANIZATION', 'APPLIED', 'ACTIVE', ?)
                """, duplicateAuditId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var organizationId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var organizations = new PostgreSqlOrganizationRepository(jdbc);
        var tenantRepository = new PostgreSqlTenantRepository(jdbc);
        organizations.save(Organization.create(organizationId, "Group"));
        tenantRepository.save(Tenant.create(tenantId, "Store"));
        var noGrantMutation = new MutateAdministrativeGrantUseCase() {
            @Override
            public void grant(UUID actor, AdministrativeGrant grant, UUID correlationId) {
            }

            @Override
            public void revoke(UUID actor, AdministrativeGrant grant, UUID correlationId) {
            }
        };
        var service = new OrganizationAdministrationService(
                ignored -> AuthorizationDecision.ALLOW,
                noGrantMutation,
                organizations,
                new PostgreSqlOrganizationTenantPlacementRepository(jdbc, transactionManager),
                new FindTenantAdministrativeMetadataService(tenantRepository),
                ignored -> false,
                new SpringOrganizationTransactionExecutor(transactionManager),
                new PostgreSqlOrganizationAdministrativeAuditRepository(jdbc),
                () -> duplicateAuditId);

        assertThatThrownBy(() -> service.attachTenant(
                UUID.randomUUID(), organizationId, tenantId, UUID.randomUUID()))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM organizations.tenant_placements WHERE tenant_id = ?",
                Integer.class, tenantId)).isZero();
    }

    @Test
    void ownerLocalAuditTablesRejectUpdateAndDelete() {
        jdbc.update("""
                INSERT INTO organizations.administrative_audit_events (
                    audit_event_id, actor_user_id, organization_id, action_type,
                    outcome, after_organization_status, correlation_id)
                VALUES (?, ?, ?, 'CREATE_ORGANIZATION', 'APPLIED', 'ACTIVE', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO tenants.administrative_audit_events (
                    audit_event_id, actor_user_id, tenant_id, action_type,
                    outcome, after_status, correlation_id)
                VALUES (?, ?, ?, 'CREATE_TENANT', 'APPLIED', 'ACTIVE', ?)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE organizations.administrative_audit_events SET outcome = 'NO_CHANGE'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM tenants.administrative_audit_events"))
                .isInstanceOf(DataAccessException.class);
    }
}
