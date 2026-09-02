package io.github.piresrenan.orderhub.authorization.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.authorization.application.observability.AuthorizationDecisionObservation;
import io.github.piresrenan.orderhub.authorization.application.observability.AuthorizationDecisionReason;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationDecisionObserver;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleAssignmentRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.RoleDefinitionRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.UserPermissionOverrideRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationPersona;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.authorization.domain.model.RoleAssignment;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationRequest;
import io.github.piresrenan.orderhub.authorization.domain.model.TenantAuthorizationScope;

class DurableTenantAuthorizationObservabilityTest {

    @Test
    void unsupportedCustomerStaffPathEmitsBoundedDenyObservation() {

        var observations =
                new ArrayList<AuthorizationDecisionObservation>();

        var service =
                service(
                        emptyAssignments(),
                        observations::add);

        var decision =
                service.authorize(
                        request(
                                AuthorizationPersona.CUSTOMER),
                        PermissionEnvelope.none());

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(observations)
                .containsExactly(
                        new AuthorizationDecisionObservation(
                                AuthorizationDecision.DENY,
                                AuthorizationPersona.CUSTOMER,
                                PermissionCode.ORDERS_VIEW,
                                AuthorizationDecisionReason.UNSUPPORTED_PERSONA));
    }

    @Test
    void persistenceFailureEmitsOnlyGenericBoundedFailureReason() {

        RoleAssignmentRepository failingAssignments =
                new RoleAssignmentRepository() {

                    @Override
                    public void save(
                            RoleAssignment assignment) {

                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<RoleAssignment> findByUserIdAndScope(
                            UUID userId,
                            TenantAuthorizationScope scope) {

                        throw new AuthorizationPersistenceException(
                                "synthetic persistence detail");
                    }
                };

        var observations =
                new ArrayList<AuthorizationDecisionObservation>();

        var service =
                service(
                        failingAssignments,
                        observations::add);

        var decision =
                service.authorize(
                        request(
                                AuthorizationPersona.STAFF),
                        PermissionEnvelope.none());

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);

        assertThat(observations)
                .containsExactly(
                        new AuthorizationDecisionObservation(
                                AuthorizationDecision.DENY,
                                AuthorizationPersona.STAFF,
                                PermissionCode.ORDERS_VIEW,
                                AuthorizationDecisionReason.PERSISTENCE_FAILURE));
    }

    @Test
    void observerFailureNeverChangesAuthorizationDecision() {

        AuthorizationDecisionObserver failingObserver =
                observation -> {
                    throw new IllegalStateException(
                            "synthetic metrics backend failure");
                };

        var service =
                service(
                        emptyAssignments(),
                        failingObserver);

        var decision =
                service.authorize(
                        request(
                                AuthorizationPersona.STAFF),
                        PermissionEnvelope.none());

        assertThat(decision)
                .isEqualTo(
                        AuthorizationDecision.DENY);
    }

    private static DurableTenantAuthorizationService service(
            RoleAssignmentRepository assignments,
            AuthorizationDecisionObserver observer) {

        RoleDefinitionRepository roles =
                (roleCode, scope) ->
                        Optional.empty();

        UserPermissionOverrideRepository overrides =
                (userId, scope) ->
                        List.of();

        return new DurableTenantAuthorizationService(
                assignments,
                roles,
                overrides,
                decision ->
                        decision.get(),
                observer);
    }

    private static RoleAssignmentRepository emptyAssignments() {

        return new RoleAssignmentRepository() {

            @Override
            public void save(
                    RoleAssignment assignment) {

                throw new UnsupportedOperationException();
            }

            @Override
            public List<RoleAssignment> findByUserIdAndScope(
                    UUID userId,
                    TenantAuthorizationScope scope) {

                return List.of();
            }
        };
    }

    private static TenantAuthorizationRequest request(
            AuthorizationPersona persona) {

        return new TenantAuthorizationRequest(
                UUID.randomUUID(),
                persona,
                new TenantAuthorizationScope(
                        UUID.randomUUID()),
                PermissionCode.ORDERS_VIEW);
    }
}
