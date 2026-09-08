package io.github.piresrenan.orderhub.authorization.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.port.in.current.TenantAuthorizationUnavailableException;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

class CurrentTenantAuthorizationTest {

    private final RoleAssignmentRepository assignments = mock(RoleAssignmentRepository.class);
    private final RoleDefinitionRepository definitions = mock(RoleDefinitionRepository.class);
    private final UserPermissionOverrideRepository overrides = mock(UserPermissionOverrideRepository.class);
    private final TenantAuthorizationRequest request = new TenantAuthorizationRequest(
            UUID.randomUUID(), AuthorizationPersona.STAFF,
            new TenantAuthorizationScope(UUID.randomUUID()), PermissionCode.CATALOG_MANAGE);

    /**
     * Why: a ceiling read before the role snapshot can authorize an impossible state.
     * Covers: source invocation inside the decision transaction with exact trusted IDs.
     * Prevents: split snapshots and permission grants based on caller-populated envelopes.
     */
    @Test
    void resolvesTheCeilingInsideTheKernelSnapshot() {
        var events = new ArrayList<String>();
        var service = new DurableTenantAuthorizationService(assignments, definitions, overrides,
                decision -> {
                    events.add("begin");
                    var result = decision.get();
                    events.add("end");
                    return result;
                }, observation -> { });
        when(assignments.findByUserIdAndScope(request.userId(), request.scope())).thenReturn(List.of());
        when(overrides.findByUserIdAndScope(request.userId(), request.scope())).thenReturn(List.of());

        var result = service.authorizeCurrent(request, (userId, tenantId) -> {
            assertThat(userId).isEqualTo(request.userId());
            assertThat(tenantId).isEqualTo(request.scope().tenantId());
            events.add("ceiling");
            return PermissionEnvelope.of(Set.of(PermissionCode.CATALOG_MANAGE));
        });

        assertThat(result).isEqualTo(AuthorizationDecision.DENY);
        assertThat(events).containsExactly("begin", "ceiling", "end");
    }

    /**
     * Why: infrastructure uncertainty cannot be returned to administrators as a false 403.
     * Covers: source and kernel persistence failure with a bounded public message.
     * Prevents: continuing mutation or leaking internal exception text.
     */
    @Test
    void propagatesTechnicalFailureDistinctlyFromDenial() {
        var service = new DurableTenantAuthorizationService(assignments, definitions, overrides);
        var cause = new AuthorizationPersistenceException("private synthetic SQL detail");

        assertThatThrownBy(() -> service.authorizeCurrent(request, (userId, tenantId) -> {
            throw cause;
        })).isInstanceOf(TenantAuthorizationUnavailableException.class)
                .hasMessage("Tenant authorization could not be established").hasCause(cause);
        verifyNoInteractions(assignments, definitions, overrides);
    }

    /**
     * Why: a Customer permission is not Staff authority even for an authenticated User.
     * Covers: persona denial before invoking the Staff ceiling or durable role policy.
     * Prevents: the new entry turning Customers into employees.
     */
    @Test
    void customerNeverReadsStaffAuthority() {
        var service = new DurableTenantAuthorizationService(assignments, definitions, overrides);
        var customer = new TenantAuthorizationRequest(request.userId(), AuthorizationPersona.CUSTOMER,
                request.scope(), PermissionCode.CUSTOMER_ORDERS_VIEW);

        assertThat(service.authorizeCurrent(customer, (userId, tenantId) -> {
            throw new AssertionError("Staff ceiling must not be read for Customer");
        })).isEqualTo(AuthorizationDecision.DENY);
        verifyNoInteractions(assignments, definitions, overrides);
    }
}
