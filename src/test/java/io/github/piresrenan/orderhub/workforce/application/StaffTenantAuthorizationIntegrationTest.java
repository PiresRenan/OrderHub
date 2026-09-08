package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;
import io.github.piresrenan.orderhub.authorization.application.port.in.current.AuthorizeCurrentTenantActionUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.StaffAuthorizationUnavailableException;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.AuthorizeStaffTenantActionUseCase;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class StaffTenantAuthorizationIntegrationTest {

    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * Why: the isolated kernel cannot grant business authority without current Staff facts.
     * Covers: production composition over actual position permissions and durable role grants.
     * Prevents: missing beans, membership-only access and a permanently denying mock facade.
     */
    @Test
    void allowsOnlyThePermissionWithinBothCurrentCeilingAndDurableGrant() {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        seedStaff(userId, tenantId, PermissionCode.CATALOG_MANAGE);
        seedGrant(userId, tenantId, PermissionCode.CATALOG_MANAGE);

        var authorization = context.getBean(AuthorizeStaffTenantActionUseCase.class);
        assertThat(authorization.authorize(userId, tenantId, PermissionCode.CATALOG_MANAGE))
                .isEqualTo(AuthorizationDecision.ALLOW);
        assertThat(authorization.authorize(userId, tenantId, PermissionCode.INVENTORY_RECEIVE))
                .isEqualTo(AuthorizationDecision.DENY);
    }

    /**
     * Why: neither a position nor a role independently confers permission.
     * Covers: absent/inactive Staff, missing grant, unrelated Tenant and deny override.
     * Prevents: authority accumulation bypassing the current organizational ceiling.
     */
    @Test
    void rejectsIncompleteAndRestrictedAuthority() {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var authorization = context.getBean(AuthorizeStaffTenantActionUseCase.class);
        seedGrant(userId, tenantId, PermissionCode.CATALOG_MANAGE);
        assertThat(authorization.authorize(userId, tenantId, PermissionCode.CATALOG_MANAGE))
                .isEqualTo(AuthorizationDecision.DENY);
        seedStaff(userId, tenantId, PermissionCode.CATALOG_MANAGE);
        assertThat(authorization.authorize(userId, UUID.randomUUID(), PermissionCode.CATALOG_MANAGE))
                .isEqualTo(AuthorizationDecision.DENY);
        jdbc.update("UPDATE workforce.staff_profiles SET status='INACTIVE' WHERE tenant_id=? AND user_id=?", tenantId, userId);
        assertThat(authorization.authorize(userId, tenantId, PermissionCode.CATALOG_MANAGE))
                .isEqualTo(AuthorizationDecision.DENY);
        jdbc.update("UPDATE workforce.staff_profiles SET status='ACTIVE' WHERE tenant_id=? AND user_id=?", tenantId, userId);
        jdbc.update("""
                INSERT INTO access_control.user_permission_overrides(override_id,user_id,tenant_id,permission_code,effect)
                VALUES (?,?,?,'CATALOG_MANAGE','DENY')
                """, UUID.randomUUID(), userId, tenantId);
        assertThat(authorization.authorize(userId, tenantId, PermissionCode.CATALOG_MANAGE))
                .isEqualTo(AuthorizationDecision.DENY);
        var otherUser = UUID.randomUUID();
        var otherTenant = UUID.randomUUID();
        seedStaff(otherUser, otherTenant, PermissionCode.CATALOG_MANAGE);
        assertThat(authorization.authorize(otherUser, otherTenant, PermissionCode.CATALOG_MANAGE))
                .isEqualTo(AuthorizationDecision.DENY);
        jdbc.update("""
                INSERT INTO access_control.user_permission_overrides(override_id,user_id,tenant_id,permission_code,effect)
                VALUES (?,?,?,'INVENTORY_ADJUST','ALLOW')
                """, UUID.randomUUID(), otherUser, otherTenant);
        assertThat(authorization.authorize(otherUser, otherTenant, PermissionCode.INVENTORY_ADJUST))
                .isEqualTo(AuthorizationDecision.DENY);
    }

    /**
     * Why: corrupt/unavailable durable authority is not a policy decision.
     * Covers: an unknown owner permission fact passed through real JDBC and kernel composition.
     * Prevents: technical uncertainty becoming an ALLOW or a misleading business denial.
     */
    @Test
    void invalidDurableAuthorityRemainsATechnicalFailure() {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var positionId = seedStaff(userId, tenantId, PermissionCode.CATALOG_MANAGE);
        seedGrant(userId, tenantId, PermissionCode.CATALOG_MANAGE);
        jdbc.update("INSERT INTO workforce.job_position_permissions VALUES (?,?,'SYNTHETIC_UNKNOWN_PERMISSION')",
                tenantId, positionId);
        assertThatThrownBy(() -> context.getBean(AuthorizeStaffTenantActionUseCase.class)
                .authorize(userId, tenantId, PermissionCode.CATALOG_MANAGE))
                .isInstanceOf(StaffAuthorizationUnavailableException.class)
                .hasMessage("Staff authorization could not be established");
    }

    /**
     * Why: a ceiling from one state and grant from another can authorize impossible authority.
     * Covers: real independent committed writer between workforce and role reads in one snapshot.
     * Prevents: fractured READ_COMMITTED composition; neither complete state allows the action.
     */
    @Test
    void concurrentCeilingAndGrantChangeCannotProduceAnImpossibleAllow() throws Exception {
        var userId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var positionId = seedStaff(userId, tenantId, PermissionCode.CATALOG_MANAGE);
        var current = context.getBean(AuthorizeCurrentTenantActionUseCase.class);
        var envelopes = context.getBean(WorkforcePermissionEnvelopeRepository.class);
        try (var writer = Executors.newSingleThreadExecutor()) {
            var result = current.authorizeCurrent(new TenantAuthorizationRequest(userId, AuthorizationPersona.STAFF,
                    new TenantAuthorizationScope(tenantId), PermissionCode.CATALOG_MANAGE), (user, tenant) -> {
                var oldCeiling = envelopes.find(user, tenant);
                try {
                    writer.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbc.update("DELETE FROM workforce.job_position_permissions WHERE tenant_id=? AND position_id=?",
                                tenantId, positionId);
                        seedGrant(userId, tenantId, PermissionCode.CATALOG_MANAGE);
                    })).get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Independent authority change must commit before kernel role lookup", exception);
                }
                return oldCeiling;
            });
            assertThat(result).isEqualTo(AuthorizationDecision.DENY);
        }
        assertThat(context.getBean(AuthorizeStaffTenantActionUseCase.class)
                .authorize(userId, tenantId, PermissionCode.CATALOG_MANAGE)).isEqualTo(AuthorizationDecision.DENY);
    }

    /** Creates only synthetic workforce-owned state; no User or Tenant relationship grants access. */
    private UUID seedStaff(UUID userId, UUID tenantId, PermissionCode permission) {
        var staffId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();
        var positionId = UUID.randomUUID();
        jdbc.update("INSERT INTO workforce.staff_profiles VALUES (?, ?, ?, 'ACTIVE')",
                staffId, userId, tenantId);
        jdbc.update("INSERT INTO workforce.departments VALUES (?, ?, 'CATALOG', 'Catalog')",
                departmentId, tenantId);
        jdbc.update("INSERT INTO workforce.job_positions VALUES (?, ?, 'CATALOG', 'Catalog operator', 'OPERATIONAL')",
                positionId, tenantId);
        jdbc.update("INSERT INTO workforce.job_position_permissions VALUES (?, ?, ?)",
                tenantId, positionId, permission.name());
        jdbc.update("INSERT INTO workforce.staff_placements VALUES (?, ?, ?, ?)",
                tenantId, staffId, departmentId, positionId);
        return positionId;
    }

    /** Adds an explicit Tenant-owned Staff role; the position envelope remains independently required. */
    private void seedGrant(UUID userId, UUID tenantId, PermissionCode permission) {
        var roleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_control.role_definitions
                    (role_id, tenant_id, code, persona, authority_band, mutability)
                VALUES (?, ?, ?, 'STAFF', 'OPERATIONAL', 'TENANT_CUSTOM')
                """, roleId, tenantId, "TEST_" + roleId.toString().replace("-", "").toUpperCase());
        jdbc.update("INSERT INTO access_control.role_permissions VALUES (?, ?)", roleId, permission.name());
        jdbc.update("""
                INSERT INTO access_control.role_assignments
                    (assignment_id, user_id, tenant_id, persona, role_id)
                VALUES (?, ?, ?, 'STAFF', ?)
                """, UUID.randomUUID(), userId, tenantId, roleId);
    }
}
